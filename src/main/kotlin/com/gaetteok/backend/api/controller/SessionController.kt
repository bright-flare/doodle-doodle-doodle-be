package com.gaetteok.backend.api.controller

import com.gaetteok.backend.api.dto.CreateSessionRequest
import com.gaetteok.backend.api.dto.SessionResponse
import com.gaetteok.backend.game.service.GameFacade
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/session")
class SessionController(
    private val gameFacade: GameFacade,
) {
    @PostMapping
    fun createSession(@Valid @RequestBody request: CreateSessionRequest): ResponseEntity<SessionResponse> {
        return ResponseEntity.ok(SessionResponse(gameFacade.createSession(request)))
    }

    @GetMapping
    fun getSession(@RequestParam sessionId: String): ResponseEntity<SessionResponse> {
        val session = gameFacade.getSession(sessionId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(SessionResponse(session))
    }
}
