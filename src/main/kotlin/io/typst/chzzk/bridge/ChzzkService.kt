package io.typst.chzzk.bridge

import io.typst.chzzk.bridge.auth.LoginMethod
import io.typst.chzzk.bridge.auth.UserLoginMethod
import io.typst.chzzk.bridge.chzzk.ChzzkGateway
import io.typst.chzzk.bridge.chzzk.ChzzkSessionGateway
import io.typst.chzzk.bridge.persis.UserToken
import io.typst.chzzk.bridge.repository.BridgeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletionStage

sealed class CreateSessionResult {
    data class Success(val session: CompletionStage<ChzzkSessionGateway>, val created: Boolean) : CreateSessionResult()
    data object LoginFailed : CreateSessionResult()
    data object RefreshTokenExpired : CreateSessionResult()
}

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

    suspend fun createSession(userLoginMethod: UserLoginMethod): CreateSessionResult {
        val (future, created) = sessionStore.acquireSession(userLoginMethod.uuid)
        if (!created) {
            return CreateSessionResult.Success(future, created = false)
        }

        var newUserLoginMethod = userLoginMethod
        // if access token expired, try refresh
        val theToken = if (newUserLoginMethod.method is LoginMethod.UseToken) {
            bridgeRepository.getToken(newUserLoginMethod.uuid)
        } else null
        if (theToken?.isExpired() == true) {
            val refreshed = runCatching { chzzkGateway.refreshToken(theToken.refreshToken) }
            if (refreshed.isFailure) {
                // refresh token expired - delete token and require re-authentication
                future.cancel(true)
                sessionStore.removeSession(newUserLoginMethod.uuid)
                bridgeRepository.deleteToken(newUserLoginMethod.uuid)
                return CreateSessionResult.RefreshTokenExpired
            }
            newUserLoginMethod = newUserLoginMethod.copy(method = refreshed.getOrThrow())
        }

        val chzzkUser = chzzkGateway.login(newUserLoginMethod)
        if (chzzkUser == null) {
            // badToken or expired
            future.cancel(true)
            sessionStore.removeSession(newUserLoginMethod.uuid)
            return CreateSessionResult.LoginFailed
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
        return CreateSessionResult.Success(future, created = true)
    }

    suspend fun removeSession(mcUuid: UUID): CompletionStage<ChzzkSessionGateway>? {
        val removed = sessionStore.removeSession(mcUuid)
        if (removed != null) {
            val session = removed.await()
            session.close()
        }
        return removed
    }

    fun close() {
        coroutineScope.cancel()
        bridgeRepository.close()
    }
}