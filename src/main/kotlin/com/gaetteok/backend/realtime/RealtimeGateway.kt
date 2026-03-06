package com.gaetteok.backend.realtime

import com.gaetteok.backend.api.dto.RoomSnapshotDto

interface RealtimeGateway {
    fun publishRoomSnapshot(room: RoomSnapshotDto)
    fun publishRoomSnapshot(roomCode: String, sessionId: String, room: RoomSnapshotDto)
    fun publishDrawBatch(roomCode: String, strokes: List<Map<String, Any?>>)
    fun publishError(roomCode: String, sessionId: String, message: String)
}
