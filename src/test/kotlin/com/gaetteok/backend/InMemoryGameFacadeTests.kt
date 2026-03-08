package com.gaetteok.backend

import com.gaetteok.backend.api.dto.ChatRequest
import com.gaetteok.backend.api.dto.CreateRoomRequest
import com.gaetteok.backend.api.dto.CreateSessionRequest
import com.gaetteok.backend.api.dto.CustomKeywordRequest
import com.gaetteok.backend.api.dto.JoinRequestCreateRequest
import com.gaetteok.backend.api.dto.RoomCommandRequest
import com.gaetteok.backend.api.dto.StrokeDto
import com.gaetteok.backend.api.dto.StrokeRequest
import com.gaetteok.backend.api.dto.VoteRequest
import com.gaetteok.backend.game.service.InMemoryGameFacade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InMemoryGameFacadeTests {
    @Test
    fun `room create and join works`() {
        val facade = InMemoryGameFacade()
        val host = facade.createSession(CreateSessionRequest("호스트"))
        val guest = facade.createSession(CreateSessionRequest("게스트"))

        val room = facade.createRoom(CreateRoomRequest(action = "create", sessionId = host.id))
        val joined = facade.joinRoom(CreateRoomRequest(action = "join", sessionId = guest.id, code = room.code, asSpectator = false))

        assertEquals(6, room.code.length)
        assertEquals(2, joined.players.size)
        assertTrue(joined.players.any { it.sessionId == host.id })
        assertTrue(joined.players.any { it.sessionId == guest.id })
    }

    @Test
    fun `start game returns active room state`() {
        val facade = InMemoryGameFacade()
        val host = facade.createSession(CreateSessionRequest("호스트"))
        val guest = facade.createSession(CreateSessionRequest("게스트"))

        val room = facade.createRoom(CreateRoomRequest(action = "create", sessionId = host.id))
        facade.joinRoom(CreateRoomRequest(action = "join", sessionId = guest.id, code = room.code, asSpectator = false))
        val started = facade.startGame(room.code, RoomCommandRequest(sessionId = host.id))

        assertTrue(started.status == "WORD_PICK" || started.status == "ROUND_START" || started.status == "DRAWING")
        assertNotNull(started.drawerSessionId)
    }

    @Test
    fun `host can restart with new round config`() {
        val facade = InMemoryGameFacade()
        val host = facade.createSession(CreateSessionRequest("호스트"))
        val guest = facade.createSession(CreateSessionRequest("게스트"))

        val room = facade.createRoom(CreateRoomRequest(action = "create", sessionId = host.id))
        facade.joinRoom(CreateRoomRequest(action = "join", sessionId = guest.id, code = room.code, asSpectator = false))
        val started = facade.startGame(
            room.code,
            RoomCommandRequest(
                sessionId = host.id,
                roundTimeSec = 90,
                totalRounds = 5,
            )
        )

        assertEquals(90, started.config.roundTimeSec)
        assertEquals(5, started.config.totalRounds)
    }

    @Test
    fun `approved spectator becomes pending player`() {
        val facade = InMemoryGameFacade()
        val host = facade.createSession(CreateSessionRequest("호스트"))
        val guest = facade.createSession(CreateSessionRequest("게스트"))
        val spectator = facade.createSession(CreateSessionRequest("관전자"))

        val room = facade.createRoom(CreateRoomRequest(action = "create", sessionId = host.id))
        facade.joinRoom(CreateRoomRequest(action = "join", sessionId = guest.id, code = room.code, asSpectator = false))
        facade.joinRoom(CreateRoomRequest(action = "join", sessionId = spectator.id, code = room.code, asSpectator = true))
        val requested = facade.createJoinRequest(room.code, JoinRequestCreateRequest(sessionId = spectator.id))
        val requestId = requested.joinRequests.first { !it.resolved }.requestId

        facade.voteJoinRequest(room.code, VoteRequest(sessionId = host.id, requestId = requestId, approve = true))
        val voted = facade.voteJoinRequest(room.code, VoteRequest(sessionId = guest.id, requestId = requestId, approve = true))

        assertTrue(voted.pendingPlayers.any { it.sessionId == spectator.id })
    }

    @Test
    fun `drawer receives more points than guesser and can clear canvas`() {
        val facade = InMemoryGameFacade()
        val host = facade.createSession(CreateSessionRequest("호스트"))
        val guest = facade.createSession(CreateSessionRequest("게스트"))

        val room = facade.createRoom(CreateRoomRequest(action = "create", sessionId = host.id))
        facade.joinRoom(CreateRoomRequest(action = "join", sessionId = guest.id, code = room.code, asSpectator = false))

        val started = facade.startGame(room.code, RoomCommandRequest(sessionId = host.id))
        if (started.status == "WORD_PICK") {
            facade.setCustomKeyword(room.code, CustomKeywordRequest(sessionId = host.id, keyword = "붕어빵"))
        } else {
            Thread.sleep(2300)
        }

        val drawingRoom = facade.getRoom(room.code, host.id)!!
        facade.sendStroke(
            room.code,
            StrokeRequest(
                sessionId = host.id,
                stroke = StrokeDto(10.0, 10.0, 120.0, 120.0, "#111827", 8),
            )
        )
        val cleared = facade.clearCanvas(room.code, RoomCommandRequest(sessionId = host.id))
        assertTrue(cleared.strokes.isEmpty())

        val answer = drawingRoom.keyword ?: facade.getRoom(room.code, host.id)?.keyword
        requireNotNull(answer)
        val finished = facade.sendChat(room.code, ChatRequest(sessionId = guest.id, text = answer))
        val drawerDelta = finished.roundResult?.scoreDeltas?.firstOrNull { it.sessionId == host.id }?.delta ?: 0
        val guesserDelta = finished.roundResult?.scoreDeltas?.firstOrNull { it.sessionId == guest.id }?.delta ?: 0

        assertTrue(drawerDelta > guesserDelta)
    }
}
