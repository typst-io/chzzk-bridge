package io.typst.chzzk.bridge.endpoint

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.typst.chzzk.bridge.*
import io.typst.chzzk.bridge.api.ApiFetchResponseBody
import io.typst.chzzk.bridge.api.ApiSubscribeResponseBody
import io.typst.chzzk.bridge.auth.LoginMethod
import io.typst.chzzk.bridge.auth.UserLoginMethod
import io.typst.chzzk.bridge.repository.BridgeRepository
import kotlinx.coroutines.future.await
import java.util.*

object ApiEndpoints {
    suspend fun onPostSubscribe(
        ctx: RoutingContext,
        req: ApiSubscribePathParameters,
        service: ChzzkService,
    ) {
        val bridgeRepo = service.bridgeRepository
        val call = ctx.call
        val token = bridgeRepo.getToken(req.uuid)
        val loginMethod = if (token != null) {
            UserLoginMethod(token.mcUuid, LoginMethod.UseToken(token.accessToken, token.refreshToken))
        } else null
        // no token
        if (loginMethod == null) {
            val state = service.issueState(req.uuid)
            val path = getUriChzzkAccountInterlock(clientId, state)
            call.respond(HttpStatusCode.Unauthorized, ApiSubscribeResponseBody(state, path))
            return
        }

        // failed acquire session
        val result = service.createSession(loginMethod)
        if (result == null) {
            call.respondText("{}", status = HttpStatusCode.InternalServerError)
            return
        }
        val (session, created) = result
        if (!created) {
            call.respondText("{}", status = HttpStatusCode.NoContent)
            return
        }

        // success
        session.await()
        call.respondText("{}", status = HttpStatusCode.OK)
    }

    suspend fun onPostUnsubscribe(
        ctx: RoutingContext,
        req: ApiUnsubscribePathParameters,
        service: ChzzkService,
    ) {
        val call = ctx.call
        val removed = service.removeSession(req.uuid)
        if (removed != null) {
            call.respondText("OK!", status = HttpStatusCode.OK)
        } else {
            call.respondText("No session!", status = HttpStatusCode.NoContent)
        }
    }

    suspend fun onSseFetch(
        ctx: ServerSSESessionWithSerialization,
        bridgeRepo: BridgeRepository,
    ): Unit {
        val call = ctx.call
        val uuid = runCatching {
            UUID.fromString(call.parameters["uuid"])
        }.getOrNull()
        val fromId = call.parameters["fromId"]?.toInt() ?: 1
        if (uuid == null) {
            // SSE session already started, send error event instead of respondText
            ctx.send(ApiFetchResponseBody(emptyList(), error = "Requires uuid query parameter"))
            return
        }
        val messages = bridgeRepo.getMessages(uuid, fromId)
        val body = ApiFetchResponseBody(messages)
        ctx.send(body)
    }
}