package com.gaetteok.backend.realtime

import com.fasterxml.jackson.databind.JsonNode
import com.gaetteok.backend.api.dto.RoomSnapshotDto

data class ClientRealtimeMessage(
    val type: String,
    val roomCode: String? = null,
    val sessionId: String? = null,
    val commandId: String? = null,
    val payload: JsonNode? = null,
)

data class ServerRealtimeMessage(
    val type: String,
    val commandId: String? = null,
    val room: RoomSnapshotDto? = null,
    val payload: Any? = null,
    val error: String? = null,
)
