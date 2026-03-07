package com.gaetteok.backend.realtime

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gaetteok.backend.api.dto.ChatRequest
import com.gaetteok.backend.api.dto.JoinRequestCreateRequest
import com.gaetteok.backend.api.dto.RoomCommandRequest
import com.gaetteok.backend.api.dto.StrokeDto
import com.gaetteok.backend.api.dto.StrokeRequest
import com.gaetteok.backend.api.dto.VoteRequest
import com.gaetteok.backend.game.service.GameFacade
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import java.util.UUID

@Component
class RoomWebSocketHandler(
    private val gameFacade: GameFacade,
    private val realtimeGateway: RealtimeGateway,
    @param:Value("\${gaetteok.realtime.websocket-path:/ws/rooms}")
    private val websocketPath: String,
) : WebSocketHandler {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    override fun handle(session: WebSocketSession): Mono<Void> {
        val connectionId = UUID.randomUUID().toString()
        val outbound = Sinks.many().unicast().onBackpressureBuffer<String>()
        realtimeGateway.registerConnection(connectionId, outbound)

        val input = session.receive()
            .map { it.payloadAsText }
            .concatMap { text -> processMessage(connectionId, text) }
            .onErrorResume { error ->
                realtimeGateway.publishErrorToConnection(connectionId, error.message ?: "실시간 동기화 오류")
                Mono.empty<Void>()
            }
            .then()

        val output = session.send(outbound.asFlux().map(session::textMessage))

        return input.and(output)
            .doFinally {
                val binding = realtimeGateway.unregisterConnection(connectionId)
                if (binding != null) {
                    try {
                        gameFacade.setPresence(binding.roomCode, binding.sessionId, false)
                        realtimeGateway.publishRoomSnapshot(binding.roomCode)
                    } catch (_: Exception) {
                        // ignore disconnect cleanup failures
                    }
                }
            }
    }

    private fun processMessage(connectionId: String, text: String): Mono<Void> {
        val message = objectMapper.readValue(text, ClientRealtimeMessage::class.java)
        return Mono.fromCallable<Unit> {
            val roomCode = message.roomCode?.uppercase()
            val sessionId = message.sessionId
            when (message.type) {
                "room.join" -> {
                    val normalizedCode = requireRoomCode(roomCode)
                    val normalizedSessionId = requireSessionId(sessionId)
                    realtimeGateway.bindConnection(connectionId, normalizedCode, normalizedSessionId)
                    gameFacade.setPresence(normalizedCode, normalizedSessionId, true)
                    val room = gameFacade.getRoom(normalizedCode, normalizedSessionId)
                        ?: throw IllegalArgumentException("room not found")
                    realtimeGateway.publishRoomSnapshot(normalizedCode, normalizedSessionId, room)
                    realtimeGateway.publishRoomSnapshot(normalizedCode)
                }

                "room.pull" -> {
                    val normalizedCode = requireRoomCode(roomCode)
                    val normalizedSessionId = requireSessionId(sessionId)
                    val room = gameFacade.getRoom(normalizedCode, normalizedSessionId)
                        ?: throw IllegalArgumentException("room not found")
                    realtimeGateway.publishRoomSnapshot(normalizedCode, normalizedSessionId, room)
                    message.commandId?.let { realtimeGateway.publishCommandOk(connectionId, it) }
                }

                "game.start" -> {
                    val normalizedCode = requireRoomCode(roomCode)
                    val normalizedSessionId = requireSessionId(sessionId)
                    gameFacade.startGame(normalizedCode, RoomCommandRequest(normalizedSessionId, message.commandId))
                    message.commandId?.let { realtimeGateway.publishCommandOk(connectionId, it) }
                    realtimeGateway.publishRoomSnapshot(normalizedCode)
                }

                "chat.send" -> {
                    val normalizedCode = requireRoomCode(roomCode)
                    val normalizedSessionId = requireSessionId(sessionId)
                    val textPayload = message.payload?.path("text")?.asText()?.trim().orEmpty()
                    gameFacade.sendChat(normalizedCode, ChatRequest(normalizedSessionId, textPayload, message.commandId))
                    message.commandId?.let { realtimeGateway.publishCommandOk(connectionId, it) }
                    realtimeGateway.publishRoomSnapshot(normalizedCode)
                }

                "draw.batch" -> {
                    val normalizedCode = requireRoomCode(roomCode)
                    val normalizedSessionId = requireSessionId(sessionId)
                    val strokes = message.payload.readStrokes()
                    gameFacade.sendStroke(
                        normalizedCode,
                        StrokeRequest(
                            sessionId = normalizedSessionId,
                            strokes = strokes,
                            commandId = message.commandId,
                        )
                    )
                    message.commandId?.let { realtimeGateway.publishCommandOk(connectionId, it) }
                    realtimeGateway.publishDrawBatch(normalizedCode, strokes, excludedSessionId = normalizedSessionId)
                    realtimeGateway.publishRoomSnapshot(normalizedCode)
                }

                "join-request.create" -> {
                    val normalizedCode = requireRoomCode(roomCode)
                    val normalizedSessionId = requireSessionId(sessionId)
                    gameFacade.createJoinRequest(normalizedCode, JoinRequestCreateRequest(normalizedSessionId, message.commandId))
                    message.commandId?.let { realtimeGateway.publishCommandOk(connectionId, it) }
                    realtimeGateway.publishRoomSnapshot(normalizedCode)
                }

                "join-request.vote" -> {
                    val normalizedCode = requireRoomCode(roomCode)
                    val normalizedSessionId = requireSessionId(sessionId)
                    val requestId = message.payload?.path("requestId")?.asText()?.trim().orEmpty()
                    val approve = message.payload?.path("approve")?.asBoolean() ?: false
                    gameFacade.voteJoinRequest(
                        normalizedCode,
                        VoteRequest(
                            sessionId = normalizedSessionId,
                            requestId = requestId,
                            approve = approve,
                            commandId = message.commandId,
                        )
                    )
                    message.commandId?.let { realtimeGateway.publishCommandOk(connectionId, it) }
                    realtimeGateway.publishRoomSnapshot(normalizedCode)
                }

                else -> throw IllegalArgumentException("unsupported realtime event: ${message.type} on $websocketPath")
            }
        }.subscribeOn(Schedulers.boundedElastic()).then().onErrorResume { error ->
            realtimeGateway.publishErrorToConnection(connectionId, error.message ?: "실시간 동기화 오류", message.commandId)
            Mono.empty<Void>()
        }
    }

    private fun requireRoomCode(roomCode: String?): String {
        return roomCode?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("roomCode required")
    }

    private fun requireSessionId(sessionId: String?): String {
        return sessionId?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("sessionId required")
    }

    private fun com.fasterxml.jackson.databind.JsonNode?.readStrokes(): List<StrokeDto> {
        val node = this?.path("strokes") ?: throw IllegalArgumentException("strokes required")
        if (!node.isArray) throw IllegalArgumentException("strokes required")
        return objectMapper.readValue(
            objectMapper.treeAsTokens(node),
            object : TypeReference<List<StrokeDto>>() {},
        )
    }
}
