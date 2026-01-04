package io.typst.chzzk.bridge.persis

import io.typst.chzzk.bridge.ser.InstantAsLong
import io.typst.chzzk.bridge.ser.UUIDAsString
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class UserToken(
    val channelId: String,
    val mcUuid: UUIDAsString,
    val accessToken: String,
    val refreshToken: String,
    val expireTime: InstantAsLong,
) {
    fun isExpired(now: Instant = Instant.now()): Boolean =
        now >= expireTime
}
