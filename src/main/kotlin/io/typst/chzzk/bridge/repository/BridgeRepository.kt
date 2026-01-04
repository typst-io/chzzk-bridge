package io.typst.chzzk.bridge.repository

import io.typst.chzzk.bridge.api.ApiFetchChzzkMessage
import io.typst.chzzk.bridge.persis.UserToken
import java.io.Closeable
import java.util.*

interface BridgeRepository {
    suspend fun getToken(id: UUID): UserToken?

    suspend fun setToken(token: UserToken)

    suspend fun addMessage(x: ApiFetchChzzkMessage)

    suspend fun getMessages(mcUuid: UUID, fromId: Int): List<ApiFetchChzzkMessage>

    suspend fun close()
}