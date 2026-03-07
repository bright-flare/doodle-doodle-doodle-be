package com.gaetteok.backend.domain.game.repository

import com.gaetteok.backend.domain.game.model.UserSession

interface GameSessionRepository {
    fun save(session: UserSession): UserSession
    fun findById(sessionId: String): UserSession?
}
