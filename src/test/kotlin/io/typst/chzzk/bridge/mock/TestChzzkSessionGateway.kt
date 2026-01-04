package io.typst.chzzk.bridge.mock

import io.typst.chzzk.bridge.chzzk.ChzzkSessionGateway

/**
 * Test implementation of [ChzzkSessionGateway] that simulates a CHZZK session connection.
 *
 * This is a minimal stub implementation for testing purposes.
 * In production, the session handles real-time chat and donation events via WebSocket.
 */
class TestChzzkSessionGateway : ChzzkSessionGateway {

    @Volatile
    private var closed = false

    /**
     * Returns whether this session has been closed.
     */
    fun isClosed(): Boolean = closed

    override suspend fun close() {
        closed = true
    }
}
