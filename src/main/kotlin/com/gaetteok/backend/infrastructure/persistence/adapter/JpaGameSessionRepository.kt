package com.gaetteok.backend.infrastructure.persistence.adapter

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gaetteok.backend.domain.game.model.UserSession
import com.gaetteok.backend.domain.game.repository.GameSessionRepository
import com.gaetteok.backend.infrastructure.persistence.entity.GameSessionSnapshotEntity
import com.gaetteok.backend.infrastructure.persistence.repository.GameSessionJpaRepository
import org.springframework.stereotype.Repository

@Repository
class JpaGameSessionRepository(
    private val jpaRepository: GameSessionJpaRepository,
) : GameSessionRepository {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    override fun save(session: UserSession): UserSession {
        jpaRepository.save(
            GameSessionSnapshotEntity(
                id = session.id,
                payload = objectMapper.writeValueAsString(session),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return session
    }

    override fun findById(sessionId: String): UserSession? {
        return jpaRepository.findById(sessionId)
            .map { objectMapper.readValue(it.payload, UserSession::class.java) }
            .orElse(null)
    }
}
