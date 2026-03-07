package com.gaetteok.backend.realtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gaetteok.backend.api.dto.RoomSnapshotDto
import com.gaetteok.backend.api.dto.StrokeDto
import com.gaetteok.backend.game.service.GameFacade
import com.gaetteok.backend.infrastructure.cache.RealtimeConnectionCache
import org.springframework.stereotype.Component
import reactor.core.publisher.Sinks

@Component
class RoomRealtimeGateway(
    private val gameFacade: GameFacade,
    private val connectionCache: RealtimeConnectionCache,
) : RealtimeGateway {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    override fun registerConnection(connectionId: String, outbound: Sinks.Many<String>) {
        connectionCache.register(connectionId, outbound)
    }

    override fun bindConnection(connectionId: String, roomCode: String, sessionId: String) {
        connectionCache.bind(connectionId, roomCode, sessionId)
    }

    override fun unregisterConnection(connectionId: String): RealtimeConnectionBinding? {
        return connectionCache.remove(connectionId)
    }

    override fun publishRoomSnapshot(roomCode: String) {
        val normalizedCode = roomCode.uppercase()
        roomConnections(normalizedCode).forEach { (_, connection) ->
            val sessionId = connection.sessionId ?: return@forEach
            val room = gameFacade.getRoom(normalizedCode, sessionId) ?: return@forEach
            send(connection, ServerRealtimeMessage(type = "room.snapshot", room = room))
        }
    }

    override fun publishRoomSnapshot(roomCode: String, sessionId: String, room: RoomSnapshotDto) {
        val normalizedCode = roomCode.uppercase()
        connectionCache.findByRoomAndSession(normalizedCode, sessionId)
            .forEach { send(it, ServerRealtimeMessage(type = "room.snapshot", room = room)) }
    }

    override fun publishDrawBatch(roomCode: String, strokes: List<StrokeDto>, excludedSessionId: String?) {
        val normalizedCode = roomCode.uppercase()
        roomConnections(normalizedCode)
            .filter { (_, connection) -> connection.sessionId != excludedSessionId }
            .forEach { (_, connection) ->
                send(
                    connection,
                    ServerRealtimeMessage(
                        type = "draw.batch",
                        payload = mapOf("strokes" to strokes),
                    )
                )
            }
    }

    override fun publishCommandOk(connectionId: String, commandId: String) {
        val connection = connectionCache.find(connectionId) ?: return
        send(connection, ServerRealtimeMessage(type = "command.ok", commandId = commandId))
    }

    override fun publishErrorToConnection(connectionId: String, message: String, commandId: String?) {
        val connection = connectionCache.find(connectionId) ?: return
        send(
            connection,
            ServerRealtimeMessage(
                type = if (commandId.isNullOrBlank()) "room.error" else "command.error",
                commandId = commandId,
                error = message,
            )
        )
    }

    private fun roomConnections(roomCode: String) = connectionCache.findByRoom(roomCode)

    private fun send(connection: com.gaetteok.backend.infrastructure.cache.CachedRealtimeConnection, message: ServerRealtimeMessage) {
        val payload = objectMapper.writeValueAsString(message)
        connection.outbound.tryEmitNext(payload)
    }
}
