package io.typst.chzzk.bridge.api

import kotlinx.serialization.Serializable

@Serializable
data class ApiSubscribeResponseBody(
    val state: String,
    val path: String,
)
