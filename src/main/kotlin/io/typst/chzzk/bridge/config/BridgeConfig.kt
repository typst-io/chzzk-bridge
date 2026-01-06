package io.typst.chzzk.bridge.config

import io.typst.chzzk.bridge.getEnvOrThrow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class BridgeConfig(
    val clientId: String = System.getenv("CHZZK_CLIENT_ID") ?: "",
    val scopes: Set<ChzzkApiScope> = setOf(ChzzkApiScope.USER_QUERY, ChzzkApiScope.DONATION),
    val hostname: String = "0.0.0.0",
) {
    @Transient
    val clientSecret: String = getEnvOrThrow("CHZZK_CLIENT_SECRET")
}
