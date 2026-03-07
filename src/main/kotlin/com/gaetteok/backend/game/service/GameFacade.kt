package com.gaetteok.backend.game.service

import com.gaetteok.backend.api.dto.ChatRequest
import com.gaetteok.backend.api.dto.CreateRoomRequest
import com.gaetteok.backend.api.dto.CreateSessionRequest
import com.gaetteok.backend.api.dto.JoinRequestCreateRequest
import com.gaetteok.backend.api.dto.RoomCommandRequest
import com.gaetteok.backend.api.dto.RoomSnapshotDto
import com.gaetteok.backend.api.dto.SessionDto
import com.gaetteok.backend.api.dto.StrokeRequest
import com.gaetteok.backend.api.dto.VoteRequest

interface GameFacade {
    fun createSession(request: CreateSessionRequest): SessionDto
    fun getSession(sessionId: String): SessionDto?
    fun createRoom(request: CreateRoomRequest): RoomSnapshotDto
    fun joinRoom(request: CreateRoomRequest): RoomSnapshotDto
    fun getRoom(code: String, sessionId: String?): RoomSnapshotDto?
    fun setPresence(code: String, sessionId: String, connected: Boolean): RoomSnapshotDto
    fun startGame(code: String, request: RoomCommandRequest): RoomSnapshotDto
    fun sendChat(code: String, request: ChatRequest): RoomSnapshotDto
    fun sendStroke(code: String, request: StrokeRequest): RoomSnapshotDto
    fun createJoinRequest(code: String, request: JoinRequestCreateRequest): RoomSnapshotDto
    fun voteJoinRequest(code: String, request: VoteRequest): RoomSnapshotDto
}
