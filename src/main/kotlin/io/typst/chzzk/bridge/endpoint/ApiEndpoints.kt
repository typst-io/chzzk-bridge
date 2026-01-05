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
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import java.util.*
import kotlin.time.Duration.Companion.seconds

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
            val path = getUriChzzkAccountInterlock(service.config.clientId, state, service.config.hostname)
            call.respond(HttpStatusCode.Unauthorized, ApiSubscribeResponseBody(state, path))
            return
        }

        // acquire session
        when (val result = service.createSession(loginMethod)) {
            is CreateSessionResult.Success -> {
                if (!result.created) {
                    call.respondText("{}", status = HttpStatusCode.NoContent)
                    return
                }
                // success
                result.session.await()
                call.respondText("{}", status = HttpStatusCode.OK)
            }

            is CreateSessionResult.LoginFailed -> {
                call.respondText("{}", status = HttpStatusCode.InternalServerError)
            }

            is CreateSessionResult.RefreshTokenExpired -> {
                // treat as if no token exists - require re-authentication
                val state = service.issueState(req.uuid)
                val path = getUriChzzkAccountInterlock(service.config.clientId, state)
                call.respond(HttpStatusCode.Unauthorized, ApiSubscribeResponseBody(state, path))
            }
        }
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

    private val POLL_INTERVAL = 1.seconds

    suspend fun onSseFetch(
        ctx: ServerSSESessionWithSerialization,
        bridgeRepo: BridgeRepository,
    ): Unit {
        val call = ctx.call
        val uuid = runCatching {
            UUID.fromString(call.parameters["uuid"])
        }.getOrNull()
        if (uuid == null) {
            ctx.send(ApiFetchResponseBody(emptyList(), error = "Requires uuid query parameter"))
            return
        }

        // Priority: Last-Event-ID header > server cursor > 0
        val lastEventIdHeader = call.request.headers["Last-Event-ID"]?.toIntOrNull()
        val serverCursor = bridgeRepo.getToken(uuid)?.lastSentEventId ?: 0
        var cursor = lastEventIdHeader ?: serverCursor

        // Send initial buffered messages
        val initialMessages = bridgeRepo.getMessages(uuid, cursor + 1)
        for (msg in initialMessages) {
            ctx.send(msg, id = msg.id.toString())
            bridgeRepo.updateLastSentEventId(uuid, msg.id)
            cursor = msg.id
        }

        // Continuous polling for new messages (heartbeat handled by Ktor SSE)
        while (true) {
            delay(POLL_INTERVAL)
            val newMessages = bridgeRepo.getMessages(uuid, cursor + 1)
            for (msg in newMessages) {
                ctx.send(msg, id = msg.id.toString())
                bridgeRepo.updateLastSentEventId(uuid, msg.id)
                cursor = msg.id
            }
        }
    }
}