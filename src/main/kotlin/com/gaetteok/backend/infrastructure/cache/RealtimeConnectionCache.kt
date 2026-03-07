package com.gaetteok.backend.infrastructure.cache

import com.gaetteok.backend.realtime.RealtimeConnectionBinding
import org.springframework.stereotype.Component
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

data class CachedRealtimeConnection(
    val outbound: Sinks.Many<String>,
    val roomCode: String? = null,
    val sessionId: String? = null,
)

interface RealtimeConnectionCache {
    fun register(connectionId: String, outbound: Sinks.Many<String>)
    fun bind(connectionId: String, roomCode: String, sessionId: String)
    fun find(connectionId: String): CachedRealtimeConnection?
    fun findByRoom(roomCode: String): Map<String, CachedRealtimeConnection>
    fun findByRoomAndSession(roomCode: String, sessionId: String): List<CachedRealtimeConnection>
    fun remove(connectionId: String): RealtimeConnectionBinding?
}

@Component
class LocalRealtimeConnectionCache : RealtimeConnectionCache {
    private val connections = ConcurrentHashMap<String, CachedRealtimeConnection>()

    override fun register(connectionId: String, outbound: Sinks.Many<String>) {
        connections[connectionId] = CachedRealtimeConnection(outbound = outbound)
    }

    override fun bind(connectionId: String, roomCode: String, sessionId: String) {
        val existing = connections[connectionId] ?: return
        connections[connectionId] = existing.copy(roomCode = roomCode.uppercase(), sessionId = sessionId)
    }

    override fun find(connectionId: String): CachedRealtimeConnection? {
        return connections[connectionId]
    }

    override fun findByRoom(roomCode: String): Map<String, CachedRealtimeConnection> {
        return connections.filterValues { it.roomCode == roomCode.uppercase() }
    }

    override fun findByRoomAndSession(roomCode: String, sessionId: String): List<CachedRealtimeConnection> {
        val normalizedCode = roomCode.uppercase()
        return connections.values.filter { it.roomCode == normalizedCode && it.sessionId == sessionId }
    }

    override fun remove(connectionId: String): RealtimeConnectionBinding? {
        val removed = connections.remove(connectionId) ?: return null
        removed.outbound.tryEmitComplete()
        val roomCode = removed.roomCode
        val sessionId = removed.sessionId
        if (roomCode == null || sessionId == null) return null
        return RealtimeConnectionBinding(roomCode = roomCode, sessionId = sessionId)
    }
}
