package io.typst.chzzk.bridge.chzzk

import io.typst.chzzk.bridge.persis.UserToken
import java.time.Instant
import java.util.*

data class ChzzkUserData(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val expireTime: Instant,
) {
    fun toToken(id: UUID): UserToken =
        UserToken(userId, id, accessToken, refreshToken, expireTime)
}
