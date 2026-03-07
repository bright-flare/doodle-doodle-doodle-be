package com.gaetteok.backend.api.controller

import com.gaetteok.backend.api.dto.ChatRequest
import com.gaetteok.backend.api.dto.CreateRoomRequest
import com.gaetteok.backend.api.dto.JoinRequestCreateRequest
import com.gaetteok.backend.api.dto.ReactionRequest
import com.gaetteok.backend.api.dto.RoomCommandRequest
import com.gaetteok.backend.api.dto.RoomResponse
import com.gaetteok.backend.api.dto.StrokeRequest
import com.gaetteok.backend.api.dto.VoteRequest
import com.gaetteok.backend.game.service.GameFacade
import com.gaetteok.backend.realtime.RealtimeGateway
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/rooms")
class RoomController(
    private val gameFacade: GameFacade,
    private val realtimeGateway: RealtimeGateway,
) {
    @PostMapping
    fun createOrJoinRoom(@Valid @RequestBody request: CreateRoomRequest): ResponseEntity<RoomResponse> {
        if (request.action !in setOf("create", "join")) {
            return ResponseEntity.badRequest().build()
        }
        val room = if (request.action == "create") gameFacade.createRoom(request) else gameFacade.joinRoom(request)
        realtimeGateway.publishRoomSnapshot(room.code)
        return ResponseEntity.ok(RoomResponse(room))
    }

    @GetMapping("/{code}")
    fun getRoom(
        @PathVariable code: String,
        @RequestParam(required = false) sessionId: String?,
    ): ResponseEntity<RoomResponse> {
        val room = gameFacade.getRoom(code, sessionId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(RoomResponse(room))
    }

    @PostMapping("/{code}/start")
    fun startGame(
        @PathVariable code: String,
        @Valid @RequestBody request: RoomCommandRequest,
    ): ResponseEntity<RoomResponse> {
        val room = gameFacade.startGame(code, request)
        realtimeGateway.publishRoomSnapshot(room.code)
        return ResponseEntity.ok(RoomResponse(room))
    }

    @PostMapping("/{code}/chat")
    fun sendChat(
        @PathVariable code: String,
        @Valid @RequestBody request: ChatRequest,
    ): ResponseEntity<RoomResponse> {
        val room = gameFacade.sendChat(code, request)
        realtimeGateway.publishRoomSnapshot(room.code)
        return ResponseEntity.ok(RoomResponse(room))
    }

    @PostMapping("/{code}/reaction")
    fun sendReaction(
        @PathVariable code: String,
        @Valid @RequestBody request: ReactionRequest,
    ): ResponseEntity<RoomResponse> {
        val room = gameFacade.sendReaction(code, request)
        realtimeGateway.publishRoomSnapshot(room.code)
        return ResponseEntity.ok(RoomResponse(room))
    }

    @PostMapping("/{code}/stroke")
    fun sendStroke(
        @PathVariable code: String,
        @Valid @RequestBody request: StrokeRequest,
    ): ResponseEntity<RoomResponse> {
        val room = gameFacade.sendStroke(code, request)
        val strokes = request.strokes ?: request.stroke?.let { listOf(it) }.orEmpty()
        realtimeGateway.publishDrawBatch(room.code, strokes, excludedSessionId = request.sessionId)
        realtimeGateway.publishRoomSnapshot(room.code)
        return ResponseEntity.ok(RoomResponse(room))
    }

    @PostMapping("/{code}/join-request")
    fun createJoinRequest(
        @PathVariable code: String,
        @Valid @RequestBody request: JoinRequestCreateRequest,
    ): ResponseEntity<RoomResponse> {
        val room = gameFacade.createJoinRequest(code, request)
        realtimeGateway.publishRoomSnapshot(room.code)
        return ResponseEntity.ok(RoomResponse(room))
    }

    @PostMapping("/{code}/vote")
    fun voteJoinRequest(
        @PathVariable code: String,
        @Valid @RequestBody request: VoteRequest,
    ): ResponseEntity<RoomResponse> {
        val room = gameFacade.voteJoinRequest(code, request)
        realtimeGateway.publishRoomSnapshot(room.code)
        return ResponseEntity.ok(RoomResponse(room))
    }
}
