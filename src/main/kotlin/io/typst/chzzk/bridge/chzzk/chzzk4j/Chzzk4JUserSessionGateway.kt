package io.typst.chzzk.bridge.chzzk.chzzk4j

import io.typst.chzzk.bridge.chzzk.ChzzkSessionGateway
import kotlinx.coroutines.future.await
import xyz.r2turntrue.chzzk4j.session.ChzzkUserSession

class Chzzk4JUserSessionGateway(private val session: ChzzkUserSession) : ChzzkSessionGateway {
    override suspend fun close() {
        session.disconnectAsync().await()
    }
}