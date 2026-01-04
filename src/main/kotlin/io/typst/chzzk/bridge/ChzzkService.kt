package io.typst.chzzk.bridge

import io.typst.chzzk.bridge.auth.LoginMethod
import io.typst.chzzk.bridge.auth.UserLoginMethod
import io.typst.chzzk.bridge.chzzk.ChzzkGateway
import io.typst.chzzk.bridge.chzzk.ChzzkSessionGateway
import io.typst.chzzk.bridge.persis.UserToken
import io.typst.chzzk.bridge.repository.BridgeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletionStage

data class ChzzkService(
    val coroutineScope: CoroutineScope,
    val bridgeRepository: BridgeRepository,
    val sessionStore: SessionStore,
    val chzzkGateway: ChzzkGateway,
) {
    fun issueState(uuid: UUID): String {
        return sessionStore.issueState(uuid)
    }

    fun removeState(state: String): UUID? {
        return sessionStore.removeState(state)
    }

    suspend fun createSession(userLoginMethod: UserLoginMethod): Pair<CompletionStage<ChzzkSessionGateway>, Boolean>? {
        val (future, created) = sessionStore.acquireSession(userLoginMethod.uuid)
        if (!created) {
            return future to false
        }

        var newUserLoginMethod = userLoginMethod
        // if expire
        val theToken = if (newUserLoginMethod.method is LoginMethod.UseToken) {
            bridgeRepository.getToken(newUserLoginMethod.uuid)
        } else null
        if (theToken?.isExpired() == true) {
            val newToken = chzzkGateway.refreshToken(theToken.refreshToken)
            newUserLoginMethod = newUserLoginMethod.copy(method = newToken)
        }

        val chzzkUser = chzzkGateway.login(newUserLoginMethod)
        if (chzzkUser == null) {
            // badToken or expired
            future.cancel(true)
            sessionStore.removeSession(newUserLoginMethod.uuid)
            return null
        }
        val newToken =
            if (newUserLoginMethod.method is LoginMethod.CreateToken || userLoginMethod.method != newUserLoginMethod.method) {
                val skew = Duration.ofSeconds(60)
                UserToken(
                    chzzkUser.userId,
                    newUserLoginMethod.uuid,
                    chzzkUser.accessToken,
                    chzzkUser.refreshToken,
                    chzzkUser.expireTime.minus(skew),
                )
            } else null

        // save token
        if (newToken != null) {
            bridgeRepository.setToken(newToken)
        }

        // new session, background
        coroutineScope.launch(Dispatchers.Default) {
            val session = chzzkGateway.connectSession()
            future.complete(session)
        }
        return future to true
    }

    suspend fun removeSession(mcUuid: UUID): CompletionStage<ChzzkSessionGateway>? {
        val removed = sessionStore.removeSession(mcUuid)
        if (removed != null) {
            val session = removed.await()
            session.close()
        }
        return removed
    }

    suspend fun close() {
        bridgeRepository.close()
    }
}