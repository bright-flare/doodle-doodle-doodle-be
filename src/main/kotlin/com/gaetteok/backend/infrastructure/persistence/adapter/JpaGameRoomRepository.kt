package com.gaetteok.backend.infrastructure.persistence.adapter

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gaetteok.backend.domain.game.model.RoomState
import com.gaetteok.backend.domain.game.repository.GameRoomRepository
import com.gaetteok.backend.infrastructure.persistence.entity.GameRoomSnapshotEntity
import com.gaetteok.backend.infrastructure.persistence.repository.GameRoomJpaRepository
import org.springframework.stereotype.Repository

@Repository
class JpaGameRoomRepository(
    private val jpaRepository: GameRoomJpaRepository,
) : GameRoomRepository {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    override fun save(room: RoomState): RoomState {
        jpaRepository.save(
            GameRoomSnapshotEntity(
                code = room.code.uppercase(),
                payload = objectMapper.writeValueAsString(room),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return room
    }

    override fun findByCode(code: String): RoomState? {
        return jpaRepository.findById(code.uppercase())
            .map { objectMapper.readValue(it.payload, RoomState::class.java) }
            .orElse(null)
    }

    override fun existsByCode(code: String): Boolean {
        return jpaRepository.existsById(code.uppercase())
    }
}
