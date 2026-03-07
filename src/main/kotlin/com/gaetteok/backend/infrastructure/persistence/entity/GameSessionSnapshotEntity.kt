package com.gaetteok.backend.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table

@Entity
@Table(name = "game_sessions")
class GameSessionSnapshotEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    var payload: String = "",

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = 0,
)
