package io.typst.chzzk.bridge.endpoint

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.typst.chzzk.bridge.ChzzkService
import io.typst.chzzk.bridge.CreateSessionResult
import io.typst.chzzk.bridge.OAuthCallbackPathParameters
import io.typst.chzzk.bridge.auth.LoginMethod
import io.typst.chzzk.bridge.auth.UserLoginMethod
import kotlinx.coroutines.future.await

object OAuthEndpoints {
    suspend fun onRequest(
        ctx: RoutingContext,
        x: OAuthCallbackPathParameters,
        service: ChzzkService,
    ) {
        val bridgeRepo = service.bridgeRepository
        val call = ctx.call
        val code = x.code.takeIf { it.isNotEmpty() }
        val state = x.state
        if (code == null) {
            call.respondText("No `code` has presented, requires uri params `code`, `state(UUID)`.")
            call.response.status(HttpStatusCode.BadRequest)
            return
        }
        val uuid = service.removeState(state)
        if (uuid == null) {
            call.response.status(HttpStatusCode.BadRequest)
            return
        }

        if (bridgeRepo.getToken(uuid) != null) {
            call.respondText("Already has a token!")
            call.response.status(HttpStatusCode.NotAcceptable)
            return
        }

        val loginMethod = UserLoginMethod(uuid, LoginMethod.CreateToken(code, state))
        when (val result = service.createSession(loginMethod)) {
            is CreateSessionResult.Success -> {
                if (result.created) {
                    result.session.await()
                    call.respondText("OK!")
                    call.response.status(HttpStatusCode.OK)
                } else {
                    call.respondText("Duplicate session!")
                    call.response.status(HttpStatusCode.NoContent)
                }
            }
            is CreateSessionResult.LoginFailed -> {
                call.respondText("Internal error!")
                call.response.status(HttpStatusCode.InternalServerError)
            }
            is CreateSessionResult.RefreshTokenExpired -> {
                // Should not happen for CreateToken flow, but handle gracefully
                call.respondText("Internal error!")
                call.response.status(HttpStatusCode.InternalServerError)
            }
        }
    }
}