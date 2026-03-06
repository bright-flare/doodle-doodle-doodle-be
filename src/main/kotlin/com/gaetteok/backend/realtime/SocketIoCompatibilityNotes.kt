package com.gaetteok.backend.realtime

/**
 * This module intentionally does not implement Spring WebSocket/STOMP yet.
 *
 * The current frontend uses socket.io-client and depends on:
 * - named events
 * - callback-style acknowledgements
 * - room snapshot broadcast semantics
 *
 * For that reason, the recommended migration path is:
 * 1. keep the frontend contract from docs/realtime-contract.md
 * 2. implement the authoritative game domain in Spring Boot
 * 3. add a Socket.IO-compatible gateway or adapter in front of Spring Boot
 */
object SocketIoCompatibilityNotes
