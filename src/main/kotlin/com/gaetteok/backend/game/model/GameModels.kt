package com.gaetteok.backend.game.model

enum class RoomStatus {
    LOBBY,
    ROUND_START,
    DRAWING,
    ROUND_END,
    GAME_FINISHED,
}

data class UserSession(
    var id: String,
    var nickname: String,
    var createdAt: Long,
    var lastSeenAt: Long,
)

data class RoomConfig(
    var roundTimeSec: Int,
    var totalRounds: Int,
    var maxPlayers: Int,
)

data class PlayerState(
    var sessionId: String,
    var nickname: String,
    var score: Int,
    var connected: Boolean,
    var joinedAt: Long,
    var lastSeenAt: Long,
)

data class PendingPlayer(
    var sessionId: String,
    var nickname: String,
    var requestedAt: Long,
    var approvedAt: Long,
)

data class Stroke(
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double,
    val color: String,
    val size: Int,
)

data class RoomMessage(
    val messageId: String,
    val sessionId: String,
    val nickname: String,
    val text: String,
    val ts: Long,
    val system: Boolean = false,
    val kind: String? = null,
)

data class RoomReaction(
    val reactionId: String,
    val sessionId: String,
    val nickname: String,
    val emoji: String,
    val ts: Long,
)

data class JoinRequest(
    var requestId: String,
    var sessionId: String,
    var nickname: String,
    var createdAt: Long,
    var votes: MutableMap<String, Boolean>,
    var resolved: Boolean,
    var approved: Boolean?,
    var resolvedAt: Long?,
)

data class ScoreDelta(
    val sessionId: String,
    val delta: Int,
)

data class RoundResult(
    val reason: String,
    val answer: String,
    val drawerSessionId: String?,
    val drawerNickname: String?,
    val guesserSessionId: String?,
    val guesserNickname: String?,
    val scoreDeltas: List<ScoreDelta>,
)

data class RoomState(
    var code: String,
    var hostSessionId: String,
    var config: RoomConfig,
    var version: Long,
    var turnId: String?,
    var players: MutableList<PlayerState>,
    var spectators: MutableList<String>,
    var spectatorNicknames: MutableMap<String, String>,
    var spectatorLastSeenAt: MutableMap<String, Long>,
    var pendingPlayers: MutableList<PendingPlayer>,
    var status: RoomStatus,
    var roundNo: Int,
    var turnIndex: Int,
    var drawerSessionId: String?,
    var keyword: String?,
    var maskedKeyword: String,
    var hintRevealed: Boolean,
    var turnStartedAt: Long?,
    var turnEndsAt: Long?,
    var phaseEndsAt: Long?,
    var strokes: MutableList<Stroke>,
    var messages: MutableList<RoomMessage>,
    var joinRequests: MutableList<JoinRequest>,
    var roundResult: RoundResult?,
    var winnerSessionId: String?,
    var processedCommandIds: MutableList<String>,
    var keywordPool: MutableList<String> = mutableListOf(),
    var lastKeyword: String? = null,
    var reactions: MutableList<RoomReaction> = mutableListOf(),
)
