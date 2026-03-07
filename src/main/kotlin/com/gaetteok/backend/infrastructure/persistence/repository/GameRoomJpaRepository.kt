package com.gaetteok.backend.infrastructure.persistence.repository

import com.gaetteok.backend.infrastructure.persistence.entity.GameRoomSnapshotEntity
import org.springframework.data.jpa.repository.JpaRepository

interface GameRoomJpaRepository : JpaRepository<GameRoomSnapshotEntity, String>
