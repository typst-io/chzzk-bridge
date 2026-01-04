package io.typst.chzzk.bridge

import io.ktor.resources.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.resources.*
import io.ktor.server.resources.Resources
import io.ktor.server.resources.post
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.typst.chzzk.bridge.endpoint.ApiEndpoints
import io.typst.chzzk.bridge.endpoint.OAuthEndpoints
import io.typst.chzzk.bridge.ser.UUIDAsString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

@Resource("/oauth_callback")
data class OAuthCallbackPathParameters(
    val code: String,
    val state: String,
)

@Resource("/api/v1/subscribe")
data class ApiSubscribePathParameters(val uuid: UUIDAsString)

@Resource("/api/v1/unsubscribe")
data class ApiUnsubscribePathParameters(val uuid: UUIDAsString)

@Resource("/api/v1/sse")
data class ApiFetchParameters(val uuid: UUIDAsString)

fun Application.commonModule() {
    install(Resources)
    install(ContentNegotiation) { // json content
        json()
    }
}

fun Application.oAuthModule(service: ChzzkService) {
    routing {
        get<OAuthCallbackPathParameters> { OAuthEndpoints.onRequest(this, it, service) }
    }
}

fun Application.apiModule(service: ChzzkService) {
    install(SSE)
    routing {
        post<ApiSubscribePathParameters> { ApiEndpoints.onPostSubscribe(this, it, service) }
        post<ApiUnsubscribePathParameters> { ApiEndpoints.onPostUnsubscribe(this, it, service) }
        sse("/api/v1/sse", serialize = { typeInfo, it ->
            val serializer = Json.serializersModule.serializer(typeInfo.kotlinType!!)
            Json.encodeToString(serializer, it)
        }) {
            ApiEndpoints.onSseFetch(this, service.bridgeRepository)
        }
    }
}

fun Application.businessModule(service: ChzzkService) {
    commonModule()
    oAuthModule(service)
    apiModule(service)
}