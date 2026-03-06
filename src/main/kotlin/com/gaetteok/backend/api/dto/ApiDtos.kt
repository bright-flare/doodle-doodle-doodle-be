package com.gaetteok.backend.api.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateSessionRequest(
    @field:NotBlank
    @field:Size(min = 2, max = 16)
    val nickname: String,
)

data class CreateRoomRequest(
    val action: String,
    @field:NotBlank
    val sessionId: String,
    @field:Min(20)
    @field:Max(180)
    val roundTimeSec: Int? = null,
    @field:Min(1)
    @field:Max(5)
    val totalRounds: Int? = null,
    val code: String? = null,
    val asSpectator: Boolean? = null,
)

data class RoomCommandRequest(
    @field:NotBlank
    val sessionId: String,
    val commandId: String? = null,
)

data class ChatRequest(
    @field:NotBlank
    val sessionId: String,
    @field:NotBlank
    val text: String,
    val commandId: String? = null,
)

data class VoteRequest(
    @field:NotBlank
    val sessionId: String,
    @field:NotBlank
    val requestId: String,
    val approve: Boolean,
    val commandId: String? = null,
)

data class JoinRequestCreateRequest(
    @field:NotBlank
    val sessionId: String,
    val commandId: String? = null,
)

data class StrokeDto(
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double,
    val color: String,
    val size: Int,
)

data class StrokeRequest(
    @field:NotBlank
    val sessionId: String,
    val stroke: StrokeDto? = null,
    val strokes: List<StrokeDto>? = null,
    val commandId: String? = null,
)

data class SessionResponse(
    val session: SessionDto,
)

data class SessionDto(
    val id: String,
    val nickname: String,
    val createdAt: Long,
    val lastSeenAt: Long,
)

data class RoomResponse(
    val room: RoomSnapshotDto,
)

data class RoomSnapshotDto(
    val code: String,
    val hostSessionId: String,
    val config: RoomConfigDto,
    val version: Long,
    val turnId: String?,
    val players: List<PlayerDto>,
    val spectators: List<String>,
    val spectatorNicknames: Map<String, String>,
    val pendingPlayers: List<PendingPlayerDto>,
    val status: String,
    val roundNo: Int,
    val turnIndex: Int,
    val drawerSessionId: String?,
    val maskedKeyword: String,
    val keyword: String?,
    val turnEndsAt: Long?,
    val phaseEndsAt: Long?,
    val strokes: List<StrokeDto>,
    val messages: List<RoomMessageDto>,
    val joinRequests: List<JoinRequestDto>,
    val roundResult: RoundResultDto?,
    val winnerSessionId: String?,
)

data class RoomConfigDto(
    val roundTimeSec: Int,
    val totalRounds: Int,
    val maxPlayers: Int,
)

data class PlayerDto(
    val sessionId: String,
    val nickname: String,
    val score: Int,
    val connected: Boolean,
)

data class PendingPlayerDto(
    val sessionId: String,
    val nickname: String,
)

data class RoomMessageDto(
    val messageId: String,
    val sessionId: String,
    val nickname: String,
    val text: String,
    val ts: Long,
    val system: Boolean = false,
    val kind: String? = null,
)

data class JoinRequestDto(
    val requestId: String,
    val sessionId: String,
    val nickname: String,
    val votes: Map<String, Boolean>,
    val resolved: Boolean,
    val approved: Boolean?,
)

data class RoundResultDto(
    val reason: String,
    val answer: String,
    val drawerSessionId: String?,
    val drawerNickname: String?,
    val guesserSessionId: String?,
    val guesserNickname: String?,
    val scoreDeltas: List<ScoreDeltaDto>,
)

data class ScoreDeltaDto(
    val sessionId: String,
    val delta: Int,
)
