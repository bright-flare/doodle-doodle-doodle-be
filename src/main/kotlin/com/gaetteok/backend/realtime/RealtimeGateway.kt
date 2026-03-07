package com.gaetteok.backend.realtime

import com.gaetteok.backend.api.dto.RoomSnapshotDto
import com.gaetteok.backend.api.dto.StrokeDto
import reactor.core.publisher.Sinks

data class RealtimeConnectionBinding(
    val roomCode: String,
    val sessionId: String,
)

interface RealtimeGateway {
    fun registerConnection(connectionId: String, outbound: Sinks.Many<String>)
    fun bindConnection(connectionId: String, roomCode: String, sessionId: String)
    fun unregisterConnection(connectionId: String): RealtimeConnectionBinding?
    fun publishRoomSnapshot(roomCode: String)
    fun publishRoomSnapshot(roomCode: String, sessionId: String, room: RoomSnapshotDto)
    fun publishDrawBatch(roomCode: String, strokes: List<StrokeDto>, excludedSessionId: String? = null)
    fun publishCommandOk(connectionId: String, commandId: String)
    fun publishErrorToConnection(connectionId: String, message: String, commandId: String? = null)
}
