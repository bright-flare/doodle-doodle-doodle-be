package com.gaetteok.backend.infrastructure.persistence.repository

import com.gaetteok.backend.infrastructure.persistence.entity.GameSessionSnapshotEntity
import org.springframework.data.jpa.repository.JpaRepository

interface GameSessionJpaRepository : JpaRepository<GameSessionSnapshotEntity, String>
