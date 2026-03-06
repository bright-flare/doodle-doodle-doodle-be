package com.gaetteok.backend.api.controller

import com.gaetteok.backend.api.dto.ChatRequest
import com.gaetteok.backend.api.dto.CreateRoomRequest
import com.gaetteok.backend.api.dto.JoinRequestCreateRequest
import com.gaetteok.backend.api.dto.RoomCommandRequest
import com.gaetteok.backend.api.dto.RoomResponse
import com.gaetteok.backend.api.dto.StrokeRequest
import com.gaetteok.backend.api.dto.VoteRequest
import com.gaetteok.backend.game.service.GameFacade
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
) {
    @PostMapping
    fun createOrJoinRoom(@Valid @RequestBody request: CreateRoomRequest): ResponseEntity<RoomResponse> {
        return when (request.action) {
            "create" -> ResponseEntity.ok(RoomResponse(gameFacade.createRoom(request)))
            "join" -> ResponseEntity.ok(RoomResponse(gameFacade.joinRoom(request)))
            else -> ResponseEntity.badRequest().build()
        }
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
    ): ResponseEntity<RoomResponse> = ResponseEntity.ok(RoomResponse(gameFacade.startGame(code, request)))

    @PostMapping("/{code}/chat")
    fun sendChat(
        @PathVariable code: String,
        @Valid @RequestBody request: ChatRequest,
    ): ResponseEntity<RoomResponse> = ResponseEntity.ok(RoomResponse(gameFacade.sendChat(code, request)))

    @PostMapping("/{code}/stroke")
    fun sendStroke(
        @PathVariable code: String,
        @Valid @RequestBody request: StrokeRequest,
    ): ResponseEntity<RoomResponse> = ResponseEntity.ok(RoomResponse(gameFacade.sendStroke(code, request)))

    @PostMapping("/{code}/join-request")
    fun createJoinRequest(
        @PathVariable code: String,
        @Valid @RequestBody request: JoinRequestCreateRequest,
    ): ResponseEntity<RoomResponse> = ResponseEntity.ok(RoomResponse(gameFacade.createJoinRequest(code, request)))

    @PostMapping("/{code}/vote")
    fun voteJoinRequest(
        @PathVariable code: String,
        @Valid @RequestBody request: VoteRequest,
    ): ResponseEntity<RoomResponse> = ResponseEntity.ok(RoomResponse(gameFacade.voteJoinRequest(code, request)))
}
