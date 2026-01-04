package io.typst.chzzk.bridge.chzzk

interface ChzzkSessionGateway {
    suspend fun close()
}