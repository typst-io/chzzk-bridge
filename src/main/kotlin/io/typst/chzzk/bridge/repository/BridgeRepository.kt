package io.typst.chzzk.bridge.repository

import io.typst.chzzk.bridge.api.ApiSseChatMessage
import io.typst.chzzk.bridge.persis.UserToken
import java.util.*

interface BridgeRepository {
    suspend fun getToken(id: UUID): UserToken?

    suspend fun setToken(token: UserToken)

    suspend fun deleteToken(id: UUID)

    suspend fun addMessage(x: ApiSseChatMessage)

    suspend fun getMessages(mcUuid: UUID, fromId: Int): List<ApiSseChatMessage>

    suspend fun updateLastSentEventId(mcUuid: UUID, eventId: Int)

    fun close()
}