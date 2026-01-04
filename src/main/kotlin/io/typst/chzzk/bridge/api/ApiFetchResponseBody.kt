package io.typst.chzzk.bridge.api

import kotlinx.serialization.Serializable

@Serializable
data class ApiFetchResponseBody(
    val elements: List<ApiFetchChzzkMessage>,
    val error: String? = null,
)
