package io.typst.chzzk.bridge.config

import kotlinx.serialization.Serializable

@Serializable
data class BridgeConfig(
    val clientId: String = System.getenv("CHZZK_CLIENT_ID") ?: "",
    val clientSecret: String = System.getenv("CHZZK_CLIENT_SECRET") ?: "",
    val scopes: Set<ChzzkApiScope> = setOf(ChzzkApiScope.DONATION),
    val hostname: String = "0.0.0.0"
)
