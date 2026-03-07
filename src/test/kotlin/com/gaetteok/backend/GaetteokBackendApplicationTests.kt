package com.gaetteok.backend

import com.gaetteok.backend.api.dto.CreateRoomRequest
import com.gaetteok.backend.api.dto.CreateSessionRequest
import com.gaetteok.backend.game.service.GameFacade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.Test

@SpringBootTest
class GaetteokBackendApplicationTests(
    @param:Autowired private val gameFacade: GameFacade,
) {
    @Test
    fun `persistent facade stores room snapshots in database`() {
        val host = gameFacade.createSession(CreateSessionRequest("호스트DB"))
        val guest = gameFacade.createSession(CreateSessionRequest("게스트DB"))

        val room = gameFacade.createRoom(CreateRoomRequest(action = "create", sessionId = host.id))
        gameFacade.joinRoom(CreateRoomRequest(action = "join", sessionId = guest.id, code = room.code, asSpectator = false))

        val fetched = gameFacade.getRoom(room.code, guest.id)
        val persistedRoom = checkNotNull(fetched)

        assertNotNull(persistedRoom)
        assertEquals(room.code, persistedRoom.code)
        assertEquals(2, persistedRoom.players.size)
    }
}
