package com.gaetteok.backend.application.game

import com.gaetteok.backend.api.dto.ChatRequest
import com.gaetteok.backend.api.dto.CreateRoomRequest
import com.gaetteok.backend.api.dto.CreateSessionRequest
import com.gaetteok.backend.api.dto.CustomKeywordRequest
import com.gaetteok.backend.api.dto.JoinRequestCreateRequest
import com.gaetteok.backend.api.dto.JoinRequestDto
import com.gaetteok.backend.api.dto.PendingPlayerDto
import com.gaetteok.backend.api.dto.PlayerDto
import com.gaetteok.backend.api.dto.ReactionDto
import com.gaetteok.backend.api.dto.ReactionRequest
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
import com.gaetteok.backend.domain.game.model.JoinRequest
import com.gaetteok.backend.domain.game.model.PendingPlayer
import com.gaetteok.backend.domain.game.model.PlayerState
import com.gaetteok.backend.domain.game.model.RoomReaction
import com.gaetteok.backend.domain.game.model.RoomConfig
import com.gaetteok.backend.domain.game.model.RoomMessage
import com.gaetteok.backend.domain.game.model.RoomState
import com.gaetteok.backend.domain.game.model.RoomStatus
import com.gaetteok.backend.domain.game.model.RoundResult
import com.gaetteok.backend.domain.game.model.ScoreDelta
import com.gaetteok.backend.domain.game.model.Stroke
import com.gaetteok.backend.domain.game.model.UserSession
import com.gaetteok.backend.domain.game.port.KeywordCatalog
import com.gaetteok.backend.domain.game.repository.GameRoomRepository
import com.gaetteok.backend.domain.game.repository.GameSessionRepository
import com.gaetteok.backend.game.service.GameFacade
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import kotlin.math.floor
import kotlin.random.Random

@Service
@Transactional
class GameApplicationService(
    private val sessionRepository: GameSessionRepository,
    private val roomRepository: GameRoomRepository,
    private val keywordCatalog: KeywordCatalog,
    @param:Value("\${gaetteok.game.custom-keyword-chance-percent:5}")
    private val customKeywordChancePercent: Int,
    @param:Value("\${gaetteok.game.custom-keyword-pick-ms:15000}")
    private val customKeywordPickMs: Long,
) : GameFacade {
    private val roomCodeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray()

    companion object {
        private const val ROUND_START_DELAY_MS = 2200L
        private const val ROUND_END_DELAY_MS = 2600L
        private const val MAX_MESSAGES = 120
        private const val MAX_REACTIONS = 32
        private const val MAX_PROCESSED_COMMANDS = 300
        private const val CORRECT_GUESS_SCORE = 10
        private const val CORRECT_DRAWER_SCORE = 20
    }

    @Synchronized
    override fun createSession(request: CreateSessionRequest): SessionDto {
        val session = UserSession(
            id = shortId(),
            nickname = trimmedNickname(request.nickname),
            createdAt = now(),
            lastSeenAt = now(),
        )
        sessionRepository.save(session)
        return session.toDto()
    }

    @Synchronized
    override fun getSession(sessionId: String): SessionDto? {
        val session = sessionRepository.findById(sessionId) ?: return null
        session.lastSeenAt = now()
        sessionRepository.save(session)
        return session.toDto()
    }

    @Synchronized
    override fun createRoom(request: CreateRoomRequest): RoomSnapshotDto {
        val host = sessionRepository.findById(request.sessionId) ?: badRequest("invalid session")
        val createdAt = now()
        val room = RoomState(
            code = uniqueRoomCode(),
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
        roomRepository.save(room)
        return sanitizeRoom(room, host.id)
    }

    @Synchronized
    override fun joinRoom(request: CreateRoomRequest): RoomSnapshotDto {
        val code = request.code?.uppercase() ?: badRequest("code required")
        val room = roomRepository.findByCode(code) ?: badRequest("invalid room/session")
        val session = sessionRepository.findById(request.sessionId) ?: badRequest("invalid room/session")
        val currentTime = now()

        playerBySessionId(room, request.sessionId)?.let { player ->
            player.connected = true
            player.lastSeenAt = currentTime
            roomRepository.save(room)
            return sanitizeRoom(room, request.sessionId)
        }

        if (room.spectators.contains(request.sessionId)) {
            room.spectatorLastSeenAt[request.sessionId] = currentTime
            roomRepository.save(room)
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
        roomRepository.save(room)
        return sanitizeRoom(room, request.sessionId)
    }

    @Synchronized
    override fun getRoom(code: String, sessionId: String?): RoomSnapshotDto? {
        val room = roomRepository.findByCode(code.uppercase()) ?: return null
        var shouldPersist = false
        if (sessionId != null) {
            setPresenceOnRead(room, sessionId, true)
            shouldPersist = true
        }
        return sanitizeRoom(room, sessionId, persist = shouldPersist)
    }

    @Synchronized
    override fun setPresence(code: String, sessionId: String, connected: Boolean): RoomSnapshotDto {
        val room = roomRepository.findByCode(code.uppercase()) ?: badRequest("invalid room")
        setPresenceOnRead(room, sessionId, connected)
        bumpVersion(room)
        roomRepository.save(room)
        return sanitizeRoom(room, sessionId)
    }

    @Synchronized
    override fun startGame(code: String, request: RoomCommandRequest): RoomSnapshotDto {
        val room = roomRepository.findByCode(code.uppercase()) ?: badRequest("invalid room")
        if (hasProcessedCommand(room, request.commandId)) return sanitizeRoom(room, request.sessionId)
        if (room.hostSessionId != request.sessionId) badRequest("only host can start")
        if (room.players.size < 2) badRequest("방장 포함 최소 2명의 플레이어가 필요해요")

        val currentTime = now()
        room.roundNo = 1
        room.turnIndex = 0
        room.config.roundTimeSec = request.roundTimeSec?.coerceIn(20, 180) ?: room.config.roundTimeSec
        room.config.totalRounds = request.totalRounds?.coerceIn(1, 5) ?: room.config.totalRounds
        room.winnerSessionId = null
        room.roundResult = null
        room.keywordPool.clear()
        room.lastKeyword = null
        room.reactions.clear()
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
        roomRepository.save(room)
        return sanitizeRoom(room, request.sessionId)
    }

    @Synchronized
    override fun sendChat(code: String, request: ChatRequest): RoomSnapshotDto {
        val room = roomRepository.findByCode(code.uppercase()) ?: badRequest("invalid room/session")
        val session = sessionRepository.findById(request.sessionId) ?: badRequest("invalid room/session")
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
                kind = "chat",
            )
        )

        if (room.status == RoomStatus.DRAWING && room.keyword != null && room.drawerSessionId != request.sessionId) {
            if (clean.replace(" ", "") == room.keyword!!.replace(" ", "")) {
                val guesser = playerBySessionId(room, request.sessionId)
                val drawer = room.drawerSessionId?.let { playerBySessionId(room, it) }
                val scoreDeltas = mutableListOf<ScoreDelta>()
                if (guesser != null) {
                    guesser.score += CORRECT_GUESS_SCORE
                    scoreDeltas.add(ScoreDelta(guesser.sessionId, CORRECT_GUESS_SCORE))
                }
                if (drawer != null) {
                    drawer.score += CORRECT_DRAWER_SCORE
                    scoreDeltas.add(ScoreDelta(drawer.sessionId, CORRECT_DRAWER_SCORE))
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
        roomRepository.save(room)
        return sanitizeRoom(room, request.sessionId)
    }

    @Synchronized
    override fun sendReaction(code: String, request: ReactionRequest): RoomSnapshotDto {
        val room = roomRepository.findByCode(code.uppercase()) ?: badRequest("invalid room")
        val session = sessionRepository.findById(request.sessionId) ?: badRequest("invalid room/session")
        if (hasProcessedCommand(room, request.commandId)) return sanitizeRoom(room, request.sessionId)
        val emoji = request.emoji.trim()
        if (!isReaction(emoji)) badRequest("지원하지 않는 리액션이에요")

        appendReaction(
            room,
            RoomReaction(
                reactionId = shortId(),
                sessionId = request.sessionId,
                nickname = nicknameFor(room, request.sessionId, session.nickname),
                emoji = emoji,
                ts = now(),
            )
        )

        registerCommand(room, request.commandId)
        bumpVersion(room)
        roomRepository.save(room)
        return sanitizeRoom(room, request.sessionId)
    }

    @Synchronized
    override fun clearCanvas(code: String, request: RoomCommandRequest): RoomSnapshotDto {
        val room = roomRepository.findByCode(code.uppercase()) ?: badRequest("invalid room")
        if (hasProcessedCommand(room, request.commandId)) return sanitizeRoom(room, request.sessionId)
        tickRoom(room)
        if (room.status != RoomStatus.DRAWING) return sanitizeRoom(room, request.sessionId)
        if (room.drawerSessionId != request.sessionId) return sanitizeRoom(room, request.sessionId)

        room.strokes.clear()
        registerCommand(room, request.commandId)
        bumpVersion(room)
        roomRepository.save(room)
        return sanitizeRoom(room, request.sessionId)
    }

    @Synchronized
    override fun setCustomKeyword(code: String, request: CustomKeywordRequest): RoomSnapshotDto {
        val room = roomRepository.findByCode(code.uppercase()) ?: badRequest("invalid room")
        if (hasProcessedCommand(room, request.commandId)) return sanitizeRoom(room, request.sessionId)
        tickRoom(room)
        if (room.status != RoomStatus.WORD_PICK) badRequest("지금은 직접 정답을 정할 수 없어요")
        if (room.drawerSessionId != request.sessionId) badRequest("이번 턴 출제자만 정답을 정할 수 있어요")

        val keyword = normalizeCustomKeyword(request.keyword)
        if (!isValidCustomKeyword(keyword)) {
            badRequest("정답은 2~10자의 한글/영문/숫자로 입력해 주세요")
        }

        room.keyword = keyword
        room.maskedKeyword = maskWord(keyword)
        beginDrawing(room, "drawer")
        registerCommand(room, request.commandId)
        bumpVersion(room)
        roomRepository.save(room)
        return sanitizeRoom(room, request.sessionId)
    }

    @Synchronized
    override fun sendStroke(code: String, request: StrokeRequest): RoomSnapshotDto {
        val room = roomRepository.findByCode(code.uppercase()) ?: badRequest("invalid room")
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
        roomRepository.save(room)
        return sanitizeRoom(room, request.sessionId)
    }

    @Synchronized
    override fun createJoinRequest(code: String, request: JoinRequestCreateRequest): RoomSnapshotDto {
        val room = roomRepository.findByCode(code.uppercase()) ?: badRequest("invalid room/session")
        val session = sessionRepository.findById(request.sessionId) ?: badRequest("invalid room/session")
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
        roomRepository.save(room)
        return sanitizeRoom(room, request.sessionId)
    }

    @Synchronized
    override fun voteJoinRequest(code: String, request: VoteRequest): RoomSnapshotDto {
        val room = roomRepository.findByCode(code.uppercase()) ?: badRequest("invalid room")
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
        roomRepository.save(room)
        return sanitizeRoom(room, request.sessionId)
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun shortId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    private fun roomCode(): String = buildString {
        repeat(6) {
            append(roomCodeAlphabet[Random.nextInt(roomCodeAlphabet.size)])
        }
    }

    private fun uniqueRoomCode(): String {
        repeat(32) {
            val code = roomCode()
            if (!roomRepository.existsByCode(code)) return code
        }
        throw ResponseStatusException(HttpStatus.CONFLICT, "room code generation failed")
    }

    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    private fun trimmedNickname(value: String): String {
        val normalized = value.trim().replace(Regex("\\s+"), " ").take(16)
        return normalized.ifBlank { "플레이어" }
    }

    private fun maskWord(word: String, revealIndex: Int? = null): String {
        return word.mapIndexed { index, char -> if (revealIndex == index) char.toString() else "◻" }.joinToString(" ")
    }

    private fun isReaction(text: String): Boolean = text in listOf("👏", "😂", "🔥", "😮")

    private fun normalizeCustomKeyword(value: String): String {
        return value.trim().replace(Regex("\\s+"), "")
    }

    private fun isValidCustomKeyword(value: String): Boolean {
        return value.length in 2..10 && value.matches(Regex("[0-9A-Za-z가-힣]+"))
    }

    private fun shouldPromptDrawerForKeyword(): Boolean {
        val normalizedChance = customKeywordChancePercent.coerceIn(0, 100)
        return Random.nextInt(100) < normalizedChance
    }

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

    private fun appendReaction(room: RoomState, reaction: RoomReaction) {
        room.reactions.add(reaction)
        if (room.reactions.size > MAX_REACTIONS) {
            room.reactions = room.reactions.takeLast(MAX_REACTIONS).toMutableList()
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

    private fun setPresenceOnRead(room: RoomState, sessionId: String, connected: Boolean) {
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

    private fun nextKeyword(room: RoomState): String {
        val keywords = keywordCatalog.keywords()
        if (room.keywordPool.isEmpty()) {
            room.keywordPool = keywords.shuffled().toMutableList()
        }

        if (room.keywordPool.size > 1 && room.lastKeyword != null && room.keywordPool.first() == room.lastKeyword) {
            val swapIndex = room.keywordPool.indexOfFirst { keyword -> keyword != room.lastKeyword }
            if (swapIndex > 0) {
                val currentFirst = room.keywordPool.first()
                room.keywordPool[0] = room.keywordPool[swapIndex]
                room.keywordPool[swapIndex] = currentFirst
            }
        }

        val keyword = room.keywordPool.firstOrNull() ?: keywords.randomOrNull() ?: "고양이"
        if (room.keywordPool.isNotEmpty()) {
            room.keywordPool.removeAt(0)
        }
        room.lastKeyword = keyword
        return keyword
    }

    private fun startRoundCountdown(room: RoomState) {
        val drawer = room.players.getOrNull(room.turnIndex)
        room.drawerSessionId = drawer?.sessionId
        room.turnId = shortId()
        room.hintRevealed = false
        room.strokes.clear()
        room.reactions.clear()
        room.roundResult = null
        room.turnStartedAt = null
        room.turnEndsAt = null

        if (drawer != null && shouldPromptDrawerForKeyword()) {
            room.keyword = null
            room.maskedKeyword = ""
            room.phaseEndsAt = now() + customKeywordPickMs
            room.status = RoomStatus.WORD_PICK
            systemMessage(room, "개떡 찬스! ${drawer.nickname} 님이 직접 정답을 정하는 중이에요.")
            return
        }

        room.keyword = nextKeyword(room)
        room.maskedKeyword = room.keyword?.let { maskWord(it) } ?: ""
        room.phaseEndsAt = now() + ROUND_START_DELAY_MS
        room.status = RoomStatus.ROUND_START
        systemMessage(room, "라운드 ${room.roundNo} · ${drawer?.nickname ?: "플레이어"} 님 차례 준비!")
    }

    private fun beginDrawing(room: RoomState, keywordSource: String = "random") {
        val startedAt = now()
        room.status = RoomStatus.DRAWING
        room.turnStartedAt = startedAt
        room.turnEndsAt = startedAt + room.config.roundTimeSec * 1000L
        room.phaseEndsAt = room.turnEndsAt
        if (keywordSource == "drawer") {
            systemMessage(room, "개떡 찬스! 직접 정한 정답으로 바로 시작해요.")
        }
        if (keywordSource == "fallback") {
            systemMessage(room, "정답 입력 시간이 지나 자동 제시어로 시작해요.")
        }
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

    private fun tickRoom(room: RoomState): Boolean {
        var changed = false
        if (room.status == RoomStatus.WORD_PICK && room.phaseEndsAt != null && now() >= room.phaseEndsAt!!) {
            room.keyword = nextKeyword(room)
            room.maskedKeyword = room.keyword?.let { maskWord(it) } ?: ""
            beginDrawing(room, "fallback")
            changed = true
        }
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
        return changed
    }

    private fun sanitizeRoom(room: RoomState, viewerSessionId: String?, persist: Boolean = false): RoomSnapshotDto {
        val changed = tickRoom(room)
        if (changed || persist) {
            roomRepository.save(room)
        }
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
            reactions = room.reactions.map {
                ReactionDto(
                    reactionId = it.reactionId,
                    sessionId = it.sessionId,
                    nickname = it.nickname,
                    emoji = it.emoji,
                    ts = it.ts,
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
