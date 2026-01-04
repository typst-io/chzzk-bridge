package io.typst.chzzk.bridge.api

import io.typst.chzzk.bridge.ser.InstantAsLong
import kotlinx.serialization.Serializable

@Serializable
data class ApiFetchChzzkMessage(
    val id: Int = -1,
    val channelId: String,
    val senderId: String,
    val senderName: String,
    val message: String,
    val messageTime: InstantAsLong,
    val payAmount: Int = 0,
)
