package io.typst.chzzk.bridge.persis

import io.typst.chzzk.bridge.api.ApiFetchChzzkMessage
import io.typst.chzzk.bridge.ser.UUIDAsString
import kotlinx.serialization.Serializable
import java.util.*

/**
 * @param tokens token by channelId
 */
@Serializable
data class ChzzkPersistence(
    val tokens: Map<UUIDAsString, UserToken> = emptyMap(),
    val messagesByUuid: Map<UUIDAsString, List<ApiFetchChzzkMessage>> = emptyMap(),
) {
    fun withToken(x: UserToken): ChzzkPersistence =
        copy(tokens = tokens + (x.mcUuid to x))

    fun withMessage(id: UUID, x: ApiFetchChzzkMessage): ChzzkPersistence {
        val messages = messagesByUuid[id] ?: emptyList()
        return copy(messagesByUuid = messagesByUuid + (id to (messages + x)))
    }

    fun pollMessages(id: UUID): Pair<ChzzkPersistence, List<ApiFetchChzzkMessage>> {
        val messages = messagesByUuid[id] ?: emptyList()
        val newPersis = copy(messagesByUuid = messagesByUuid - id)
        return newPersis to messages
    }
}
