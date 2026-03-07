package com.gaetteok.backend.domain.game.repository

import com.gaetteok.backend.domain.game.model.RoomState

interface GameRoomRepository {
    fun save(room: RoomState): RoomState
    fun findByCode(code: String): RoomState?
    fun existsByCode(code: String): Boolean
}
