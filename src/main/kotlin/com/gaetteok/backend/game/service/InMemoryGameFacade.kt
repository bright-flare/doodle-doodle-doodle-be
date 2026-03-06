package com.gaetteok.backend.game.service

import com.gaetteok.backend.api.dto.ChatRequest
import com.gaetteok.backend.api.dto.CreateRoomRequest
import com.gaetteok.backend.api.dto.CreateSessionRequest
import com.gaetteok.backend.api.dto.JoinRequestCreateRequest
import com.gaetteok.backend.api.dto.JoinRequestDto
import com.gaetteok.backend.api.dto.PendingPlayerDto
import com.gaetteok.backend.api.dto.PlayerDto
import com.gaetteok.backend.api.dto.RoomCommandRequest
import com.gaetteok.backend.api.dto.RoomConfigDto
import com.gaetteok.backend.api.dto.RoomMessageDto
import com.gaetteok.backend.api.dto.RoomSnapshotDto
import com.gaetteok.backend.api.dto.RoundResultDto
import com.gaetteok.backend.api.dto.ScoreDeltaDto
import com.gaetteok.backend.api.dto.SessionDto
import com.gaetteok.backend.api.dto.StrokeDto
import com.gaetteok.backend.api.dto.StrokeRequest
import com.gaetteok.backend.api.dto.VoteRequest
import com.gaetteok.backend.game.model.JoinRequest
import com.gaetteok.backend.game.model.PendingPlayer
import com.gaetteok.backend.game.model.PlayerState
import com.gaetteok.backend.game.model.RoomConfig
import com.gaetteok.backend.game.model.RoomMessage
import com.gaetteok.backend.game.model.RoomState
import com.gaetteok.backend.game.model.RoomStatus
import com.gaetteok.backend.game.model.RoundResult
import com.gaetteok.backend.game.model.ScoreDelta
import com.gaetteok.backend.game.model.Stroke
import com.gaetteok.backend.game.model.UserSession
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import kotlin.math.floor
import kotlin.random.Random

@Service
class InMemoryGameFacade : GameFacade {
    private val sessions = LinkedHashMap<String, UserSession>()
    private val rooms = LinkedHashMap<String, RoomState>()
    private val keywords = listOf("사자", "바나나", "우주선", "떡볶이", "기린", "컴퓨터", "해바라기", "피자", "고양이", "자전거")

    companion object {
        private const val ROUND_START_DELAY_MS = 2200L
        private const val ROUND_END_DELAY_MS = 2600L
        private const val MAX_MESSAGES = 120
        private const val MAX_PROCESSED_COMMANDS = 300
    }

    @Synchronized
    override fun createSession(request: CreateSessionRequest): SessionDto {
        val session = UserSession(
            id = shortId(),
            nickname = trimmedNickname(request.nickname),
            createdAt = now(),
            lastSeenAt = now(),
        )
        sessions[session.id] = session
        return session.toDto()
    }

    @Synchronized
    override fun getSession(sessionId: String): SessionDto? {
        val session = sessions[sessionId] ?: return null
        session.lastSeenAt = now()
        return session.toDto()
    }

    @Synchronized
    override fun createRoom(request: CreateRoomRequest): RoomSnapshotDto {
        val host = sessions[request.sessionId] ?: badRequest("invalid session")
        val createdAt = now()
        val room = RoomState(
            code = roomCode(),
            hostSessionId = host.id,
            config = RoomConfig(
                roundTimeSec = request.roundTimeSec?.coerceIn(20, 180) ?: 60,
                totalRounds = request.totalRounds?.coerceIn(1, 5) ?: 3,
                maxPlayers = 10,
            ),
            version = 1,
            turnId = null,
            players = mutableListOf(
                PlayerState(
                    sessionId = host.id,
                    nickname = trimmedNickname(host.nickname),
                    score = 0,
                    connected = true,
                    joinedAt = createdAt,
                    lastSeenAt = createdAt,
                )
            ),
            spectators = mutableListOf(),
            spectatorNicknames = linkedMapOf(),
            spectatorLastSeenAt = linkedMapOf(),
            pendingPlayers = mutableListOf(),
            status = RoomStatus.LOBBY,
            roundNo = 1,
            turnIndex = 0,
            drawerSessionId = null,
            keyword = null,
            maskedKeyword = "",
            hintRevealed = false,
            turnStartedAt = null,
            turnEndsAt = null,
            phaseEndsAt = null,
            strokes = mutableListOf(),
            messages = mutableListOf(),
            joinRequests = mutableListOf(),
            roundResult = null,
            winnerSessionId = null,
            processedCommandIds = mutableListOf(),
        )
        rooms[room.code] = room
        return sanitizeRoom(room, host.id)
    }

    @Synchronized
    override fun joinRoom(request: CreateRoomRequest): RoomSnapshotDto {
        val code = request.code?.uppercase() ?: badRequest("code required")
        val room = rooms[code] ?: badRequest("invalid room/session")
        val session = sessions[request.sessionId] ?: badRequest("invalid room/session")
        val currentTime = now()

        playerBySessionId(room, request.sessionId)?.let { player ->
            player.connected = true
            player.lastSeenAt = currentTime
            return sanitizeRoom(room, request.sessionId)
        }

        if (room.spectators.contains(request.sessionId)) {
            room.spectatorLastSeenAt[request.sessionId] = currentTime
            return sanitizeRoom(room, request.sessionId)
        }

        val nickname = uniqueNickname(room, request.sessionId, session.nickname)
        val shouldSpectate = (request.asSpectator ?: false) || room.status != RoomStatus.LOBBY || room.players.size >= room.config.maxPlayers

        if (shouldSpectate) {
            room.spectators.add(request.sessionId)
            room.spectatorNicknames[request.sessionId] = nickname
            room.spectatorLastSeenAt[request.sessionId] = currentTime
        } else {
            room.players.add(
                PlayerState(
                    sessionId = request.sessionId,
                    nickname = nickname,
                    score = 0,
                    connected = true,
                    joinedAt = currentTime,
                    lastSeenAt = currentTime,
                )
            )
        }

        bumpVersion(room)
        return sanitizeRoom(room, request.sessionId)
    }

    @Synchronized
    override fun getRoom(code: String, sessionId: String?): RoomSnapshotDto? {
        val room = rooms[code.uppercase()] ?: return null
        if (sessionId != null) {
            setPresence(room, sessionId, true)
        }
        return sanitizeRoom(room, sessionId)
    }

    @Synchronized
    override fun startGame(code: String, request: RoomCommandRequest): RoomSnapshotDto {
        val room = rooms[code.uppercase()] ?: badRequest("invalid room")
        if (hasProcessedCommand(room, request.commandId)) return sanitizeRoom(room, request.sessionId)
        if (room.hostSessionId != request.sessionId) badRequest("only host can start")
        if (room.players.size < 2) badRequest("방장 포함 최소 2명의 플레이어가 필요해요")

        val currentTime = now()
        room.roundNo = 1
        room.turnIndex = 0
        room.winnerSessionId = null
        room.roundResult = null
        room.players.forEach {
            it.score = 0
            it.lastSeenAt = currentTime
        }
        room.joinRequests.forEach {
            it.resolved = true
            if (it.resolvedAt == null) it.resolvedAt = currentTime
        }

        startRoundCountdown(room)
        registerCommand(room, request.commandId)
        bumpVersion(room)
        return sanitizeRoom(room, request.sessionId)
    }

    @Synchronized
    override fun sendChat(code: String, request: ChatRequest): RoomSnapshotDto {
        val room = rooms[code.uppercase()] ?: badRequest("invalid room/session")
        val session = sessions[request.sessionId] ?: badRequest("invalid room/session")
        if (hasProcessedCommand(room, request.commandId)) return sanitizeRoom(room, request.sessionId)

        tickRoom(room)
        val clean = request.text.trim()
        if (clean.isBlank()) return sanitizeRoom(room, request.sessionId)

        appendMessage(
            room,
            RoomMessage(
                messageId = shortId(),
                sessionId = request.sessionId,
                nickname = nicknameFor(room, request.sessionId, session.nickname),
                text = clean,
                ts = now(),
                system = false,
                kind = if (isReaction(clean)) "reaction" else "chat",
            )
        )

        if (room.status == RoomStatus.DRAWING && room.keyword != null && room.drawerSessionId != request.sessionId) {
            if (clean.replace(" ", "") == room.keyword!!.replace(" ", "")) {
                val guesser = playerBySessionId(room, request.sessionId)
                val drawer = room.drawerSessionId?.let { playerBySessionId(room, it) }
                val scoreDeltas = mutableListOf<ScoreDelta>()
                if (guesser != null) {
                    guesser.score += 10
                    scoreDeltas.add(ScoreDelta(guesser.sessionId, 10))
                }
                if (drawer != null) {
                    drawer.score += 20
                    scoreDeltas.add(ScoreDelta(drawer.sessionId, 20))
                }
                finishTurn(
                    room,
                    RoundResult(
                        reason = "correct",
                        answer = room.keyword!!,
                        drawerSessionId = room.drawerSessionId,
                        drawerNickname = drawer?.nickname,
                        guesserSessionId = guesser?.sessionId,
                        guesserNickname = guesser?.nickname ?: nicknameFor(room, request.sessionId, session.nickname),
                        scoreDeltas = scoreDeltas,
                    )
                )
            }
        }

        registerCommand(room, request.commandId)
        bumpVersion(room)
        return sanitizeRoom(room, request.sessionId)
    }

    @Synchronized
    override fun sendStroke(code: String, request: StrokeRequest): RoomSnapshotDto {
        val room = rooms[code.uppercase()] ?: badRequest("invalid room")
        if (hasProcessedCommand(room, request.commandId)) return sanitizeRoom(room, request.sessionId)
        tickRoom(room)
        if (room.status != RoomStatus.DRAWING) return sanitizeRoom(room, request.sessionId)
        if (room.drawerSessionId != request.sessionId) return sanitizeRoom(room, request.sessionId)

        val strokes = when {
            request.strokes != null -> request.strokes.map { it.toModel() }
            request.stroke != null -> listOf(request.stroke.toModel())
            else -> badRequest("stroke required")
        }

        room.strokes.addAll(strokes)
        registerCommand(room, request.commandId)
        bumpVersion(room)
        return sanitizeRoom(room, request.sessionId)
    }

    @Synchronized
    override fun createJoinRequest(code: String, request: JoinRequestCreateRequest): RoomSnapshotDto {
        val room = rooms[code.uppercase()] ?: badRequest("invalid room/session")
        val session = sessions[request.sessionId] ?: badRequest("invalid room/session")
        if (hasProcessedCommand(room, request.commandId)) return sanitizeRoom(room, request.sessionId)
        if (playerBySessionId(room, request.sessionId) != null) badRequest("이미 플레이어예요")

        if (!room.spectators.contains(request.sessionId)) {
            room.spectators.add(request.sessionId)
            room.spectatorNicknames[request.sessionId] = uniqueNickname(room, request.sessionId, session.nickname)
        }
        room.spectatorLastSeenAt[request.sessionId] = now()

        if (room.pendingPlayers.any { it.sessionId == request.sessionId }) {
            return sanitizeRoom(room, request.sessionId)
        }
        if (room.joinRequests.any { it.sessionId == request.sessionId && !it.resolved }) {
            return sanitizeRoom(room, request.sessionId)
        }

        val joinRequest = JoinRequest(
            requestId = shortId(),
            sessionId = request.sessionId,
            nickname = nicknameFor(room, request.sessionId, session.nickname),
            createdAt = now(),
            votes = linkedMapOf(),
            resolved = false,
            approved = null,
            resolvedAt = null,
        )
        room.joinRequests.add(joinRequest)
        registerCommand(room, request.commandId)
        systemMessage(room, "${joinRequest.nickname} 님이 플레이어 입장을 요청했습니다.")
        bumpVersion(room)
        return sanitizeRoom(room, request.sessionId)
    }

    @Synchronized
    override fun voteJoinRequest(code: String, request: VoteRequest): RoomSnapshotDto {
        val room = rooms[code.uppercase()] ?: badRequest("invalid room")
        if (hasProcessedCommand(room, request.commandId)) return sanitizeRoom(room, request.sessionId)
        val voter = playerBySessionId(room, request.sessionId) ?: badRequest("only players can vote")
        val joinRequest = room.joinRequests.firstOrNull { it.requestId == request.requestId && !it.resolved } ?: badRequest("request not found")

        joinRequest.votes[voter.sessionId] = request.approve
        val totalPlayers = room.players.size
        val majority = floor(totalPlayers / 2.0).toInt() + 1
        val yesVotes = joinRequest.votes.values.count { it }
        val noVotes = joinRequest.votes.values.count { !it }
        val remainingVotes = totalPlayers - joinRequest.votes.size

        if (yesVotes >= majority) {
            joinRequest.resolved = true
            joinRequest.approved = true
            joinRequest.resolvedAt = now()
            if (room.pendingPlayers.none { it.sessionId == joinRequest.sessionId }) {
                room.pendingPlayers.add(
                    PendingPlayer(
                        sessionId = joinRequest.sessionId,
                        nickname = joinRequest.nickname,
                        requestedAt = joinRequest.createdAt,
                        approvedAt = now(),
                    )
                )
            }
            systemMessage(room, "${joinRequest.nickname} 님이 승인됐어요. 다음 턴부터 플레이어로 합류합니다.")
        } else if (noVotes >= majority || yesVotes + remainingVotes < majority) {
            joinRequest.resolved = true
            joinRequest.approved = false
            joinRequest.resolvedAt = now()
            systemMessage(room, "${joinRequest.nickname} 님의 플레이어 입장 요청이 거절됐어요.")
        }

        registerCommand(room, request.commandId)
        bumpVersion(room)
        return sanitizeRoom(room, request.sessionId)
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun shortId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    private fun roomCode(): String = UUID.randomUUID().toString().replace("-", "").take(4).uppercase()

    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    private fun trimmedNickname(value: String): String {
        val normalized = value.trim().replace(Regex("\\s+"), " ").take(16)
        return normalized.ifBlank { "플레이어" }
    }

    private fun maskWord(word: String, revealIndex: Int? = null): String {
        return word.mapIndexed { index, char -> if (revealIndex == index) char.toString() else "◻" }.joinToString(" ")
    }

    private fun isReaction(text: String): Boolean = text in listOf("👏", "😂", "🔥", "😮")

    private fun playerBySessionId(room: RoomState, sessionId: String): PlayerState? = room.players.firstOrNull { it.sessionId == sessionId }

    private fun nicknameFor(room: RoomState, sessionId: String, fallback: String = "플레이어"): String {
        return playerBySessionId(room, sessionId)?.nickname
            ?: room.spectatorNicknames[sessionId]
            ?: room.pendingPlayers.firstOrNull { it.sessionId == sessionId }?.nickname
            ?: trimmedNickname(fallback)
    }

    private fun uniqueNickname(room: RoomState, sessionId: String, requested: String): String {
        val base = trimmedNickname(requested)
        val taken = linkedSetOf<String>()
        room.players.filter { it.sessionId != sessionId }.forEach { taken += it.nickname.lowercase() }
        room.spectatorNicknames.filterKeys { it != sessionId }.values.forEach { taken += it.lowercase() }
        room.pendingPlayers.filter { it.sessionId != sessionId }.forEach { taken += it.nickname.lowercase() }
        if (!taken.contains(base.lowercase())) return base

        var suffix = 2
        while (taken.contains("$base $suffix".lowercase())) {
            suffix += 1
        }
        return "$base $suffix"
    }

    private fun bumpVersion(room: RoomState) {
        room.version += 1
    }

    private fun registerCommand(room: RoomState, commandId: String?) {
        if (commandId.isNullOrBlank()) return
        if (room.processedCommandIds.contains(commandId)) return
        room.processedCommandIds.add(commandId)
        if (room.processedCommandIds.size > MAX_PROCESSED_COMMANDS) {
            room.processedCommandIds = room.processedCommandIds.takeLast(MAX_PROCESSED_COMMANDS).toMutableList()
        }
    }

    private fun hasProcessedCommand(room: RoomState, commandId: String?): Boolean {
        return !commandId.isNullOrBlank() && room.processedCommandIds.contains(commandId)
    }

    private fun appendMessage(room: RoomState, message: RoomMessage) {
        room.messages.add(message)
        if (room.messages.size > MAX_MESSAGES) {
            room.messages = room.messages.takeLast(MAX_MESSAGES).toMutableList()
        }
    }

    private fun systemMessage(room: RoomState, text: String, kind: String = "system") {
        appendMessage(
            room,
            RoomMessage(
                messageId = shortId(),
                sessionId = "system",
                nickname = "시스템",
                text = text,
                ts = now(),
                system = true,
                kind = kind,
            )
        )
    }

    private fun setPresence(room: RoomState, sessionId: String, connected: Boolean) {
        val player = playerBySessionId(room, sessionId)
        if (player != null) {
            player.connected = connected
            player.lastSeenAt = now()
            return
        }
        if (room.spectators.contains(sessionId)) {
            room.spectatorLastSeenAt[sessionId] = now()
            return
        }
        if (room.pendingPlayers.any { it.sessionId == sessionId }) {
            room.spectatorLastSeenAt[sessionId] = now()
        }
    }

    private fun revealHintIfNeeded(room: RoomState): Boolean {
        if (room.status != RoomStatus.DRAWING || room.keyword == null || room.hintRevealed || room.turnStartedAt == null || room.turnEndsAt == null) {
            return false
        }
        val total = room.turnEndsAt!! - room.turnStartedAt!!
        val elapsed = now() - room.turnStartedAt!!
        if (elapsed < total * 0.9) return false
        val index = Random.nextInt(room.keyword!!.length)
        room.maskedKeyword = maskWord(room.keyword!!, index)
        room.hintRevealed = true
        systemMessage(room, "힌트 공개! 글자 1개가 열렸어요.")
        return true
    }

    private fun applyPendingPlayers(room: RoomState): Boolean {
        if (room.pendingPlayers.isEmpty()) return false
        val approved = room.pendingPlayers.toList()
        room.pendingPlayers.clear()
        var changed = false
        approved.forEach { pending ->
            if (room.players.size >= room.config.maxPlayers) {
                room.pendingPlayers.add(pending)
                return@forEach
            }
            if (playerBySessionId(room, pending.sessionId) != null) return@forEach
            room.spectators.remove(pending.sessionId)
            room.spectatorNicknames.remove(pending.sessionId)
            room.spectatorLastSeenAt.remove(pending.sessionId)
            room.players.add(
                PlayerState(
                    sessionId = pending.sessionId,
                    nickname = pending.nickname,
                    score = 0,
                    connected = true,
                    joinedAt = now(),
                    lastSeenAt = now(),
                )
            )
            systemMessage(room, "${pending.nickname} 님이 플레이어로 합류했어요.")
            changed = true
        }
        return changed
    }

    private fun startRoundCountdown(room: RoomState) {
        val drawer = room.players.getOrNull(room.turnIndex)
        room.drawerSessionId = drawer?.sessionId
        room.turnId = shortId()
        room.keyword = keywords.random()
        room.maskedKeyword = room.keyword?.let { maskWord(it) } ?: ""
        room.hintRevealed = false
        room.strokes.clear()
        room.roundResult = null
        room.turnStartedAt = null
        room.turnEndsAt = null
        room.phaseEndsAt = now() + ROUND_START_DELAY_MS
        room.status = RoomStatus.ROUND_START
        systemMessage(room, "라운드 ${room.roundNo} · ${drawer?.nickname ?: "플레이어"} 님 차례 준비!")
    }

    private fun beginDrawing(room: RoomState) {
        val startedAt = now()
        room.status = RoomStatus.DRAWING
        room.turnStartedAt = startedAt
        room.turnEndsAt = startedAt + room.config.roundTimeSec * 1000L
        room.phaseEndsAt = room.turnEndsAt
        systemMessage(room, "${room.players.getOrNull(room.turnIndex)?.nickname ?: "플레이어"} 님 그리기 시작!")
    }

    private fun finishTurn(room: RoomState, result: RoundResult) {
        room.status = RoomStatus.ROUND_END
        room.roundResult = result
        room.maskedKeyword = result.answer.map { it.toString() }.joinToString(" ")
        room.phaseEndsAt = now() + ROUND_END_DELAY_MS
        room.turnEndsAt = room.phaseEndsAt
        if (result.reason == "correct") {
            systemMessage(room, "${result.guesserNickname ?: "플레이어"} 정답! (${result.answer})", "correct")
        } else {
            systemMessage(room, "시간 종료! 정답은 ${result.answer} 였어요.")
        }
    }

    private fun finishGame(room: RoomState) {
        room.status = RoomStatus.GAME_FINISHED
        room.drawerSessionId = null
        room.turnId = null
        room.turnStartedAt = null
        room.turnEndsAt = null
        room.phaseEndsAt = null
        room.maskedKeyword = room.keyword?.map { it.toString() }?.joinToString(" ") ?: room.maskedKeyword
        val sorted = room.players.sortedByDescending { it.score }
        room.winnerSessionId = sorted.firstOrNull()?.sessionId
        systemMessage(room, "게임 종료! 우승: ${sorted.firstOrNull()?.nickname ?: "-"}")
    }

    private fun advanceTurn(room: RoomState) {
        applyPendingPlayers(room)
        room.turnIndex += 1
        if (room.turnIndex >= room.players.size) {
            room.turnIndex = 0
            room.roundNo += 1
        }
        if (room.roundNo > room.config.totalRounds || room.players.size < 2) {
            finishGame(room)
            return
        }
        startRoundCountdown(room)
    }

    private fun tickRoom(room: RoomState) {
        var changed = false
        if (room.status == RoomStatus.ROUND_START && room.phaseEndsAt != null && now() >= room.phaseEndsAt!!) {
            beginDrawing(room)
            changed = true
        }
        if (room.status == RoomStatus.DRAWING) {
            if (revealHintIfNeeded(room)) changed = true
            if (room.turnEndsAt != null && now() >= room.turnEndsAt!! && room.keyword != null) {
                finishTurn(
                    room,
                    RoundResult(
                        reason = "timeout",
                        answer = room.keyword!!,
                        drawerSessionId = room.drawerSessionId,
                        drawerNickname = room.drawerSessionId?.let { nicknameFor(room, it) },
                        guesserSessionId = null,
                        guesserNickname = null,
                        scoreDeltas = emptyList(),
                    )
                )
                changed = true
            }
        }
        if (room.status == RoomStatus.ROUND_END && room.phaseEndsAt != null && now() >= room.phaseEndsAt!!) {
            advanceTurn(room)
            changed = true
        }
        if (changed) {
            bumpVersion(room)
        }
    }

    private fun sanitizeRoom(room: RoomState, viewerSessionId: String?): RoomSnapshotDto {
        tickRoom(room)
        val canSeeKeyword = room.status == RoomStatus.ROUND_END ||
            room.status == RoomStatus.GAME_FINISHED ||
            (viewerSessionId != null && room.drawerSessionId == viewerSessionId && (room.status == RoomStatus.ROUND_START || room.status == RoomStatus.DRAWING))

        return RoomSnapshotDto(
            code = room.code,
            hostSessionId = room.hostSessionId,
            config = RoomConfigDto(
                roundTimeSec = room.config.roundTimeSec,
                totalRounds = room.config.totalRounds,
                maxPlayers = room.config.maxPlayers,
            ),
            version = room.version,
            turnId = room.turnId,
            players = room.players.map { PlayerDto(it.sessionId, it.nickname, it.score, it.connected) },
            spectators = room.spectators.toList(),
            spectatorNicknames = room.spectatorNicknames.toMap(),
            pendingPlayers = room.pendingPlayers.map { PendingPlayerDto(it.sessionId, it.nickname) },
            status = room.status.name,
            roundNo = room.roundNo,
            turnIndex = room.turnIndex,
            drawerSessionId = room.drawerSessionId,
            maskedKeyword = room.maskedKeyword,
            keyword = if (canSeeKeyword) room.keyword else null,
            turnEndsAt = room.turnEndsAt,
            phaseEndsAt = room.phaseEndsAt,
            strokes = room.strokes.map { StrokeDto(it.x0, it.y0, it.x1, it.y1, it.color, it.size) },
            messages = room.messages.map {
                RoomMessageDto(
                    messageId = it.messageId,
                    sessionId = it.sessionId,
                    nickname = it.nickname,
                    text = it.text,
                    ts = it.ts,
                    system = it.system,
                    kind = it.kind,
                )
            },
            joinRequests = room.joinRequests.map {
                JoinRequestDto(
                    requestId = it.requestId,
                    sessionId = it.sessionId,
                    nickname = it.nickname,
                    votes = it.votes.toMap(),
                    resolved = it.resolved,
                    approved = it.approved,
                )
            },
            roundResult = room.roundResult?.let {
                RoundResultDto(
                    reason = it.reason,
                    answer = it.answer,
                    drawerSessionId = it.drawerSessionId,
                    drawerNickname = it.drawerNickname,
                    guesserSessionId = it.guesserSessionId,
                    guesserNickname = it.guesserNickname,
                    scoreDeltas = it.scoreDeltas.map { delta -> ScoreDeltaDto(delta.sessionId, delta.delta) },
                )
            },
            winnerSessionId = room.winnerSessionId,
        )
    }

    private fun UserSession.toDto(): SessionDto {
        return SessionDto(
            id = id,
            nickname = nickname,
            createdAt = createdAt,
            lastSeenAt = lastSeenAt,
        )
    }

    private fun StrokeDto.toModel(): Stroke {
        return Stroke(
            x0 = x0,
            y0 = y0,
            x1 = x1,
            y1 = y1,
            color = color,
            size = size,
        )
    }
}
