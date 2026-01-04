@file:Suppress("OPT_IN_USAGE")

package io.typst.chzzk.bridge

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.typst.chzzk.bridge.chzzk.ChzzkGateway
import io.typst.chzzk.bridge.chzzk.chzzk4j.Chzzk4jGateway
import io.typst.chzzk.bridge.repository.BridgeRepository
import io.typst.chzzk.bridge.repository.SQLiteBridgeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import xyz.r2turntrue.chzzk4j.auth.ChzzkOauthLoginAdapter
import java.io.File
import java.net.URI
import java.util.*
import java.util.logging.Logger
import java.util.random.RandomGenerator

val logger: Logger = Logger.getLogger("ChzzkBridge")
val clientId = getEnvOrThrow("CHZZK_CLIENT_ID")
val clientSecret = getEnvOrThrow("CHZZK_CLIENT_SECRET")
val oAuthServerPort: Int = 39680
val apiServerPort: Int = 39681
val oAuthRedirectUri: URI = URI.create("http://localhost:${oAuthServerPort}/oauth_callback")
val random = RandomGenerator.getDefault()

fun getEnvOrThrow(key: String): String =
    System.getenv(key) ?: throw IllegalStateException("Please set environment variable: `$key`")

suspend fun main(args: Array<String>): Unit = supervisorScope {
    val service = createChzzkService(this)
    startApp(this, service).join()
}

fun createChzzkService(
    scope: CoroutineScope,
    bridgeRepository: BridgeRepository = SQLiteBridgeRepository(File("./")),
    sessionStore: SessionStore = SessionStore(random),
    chzzkGateway: ChzzkGateway = Chzzk4jGateway(scope, bridgeRepository),
): ChzzkService = ChzzkService(scope, bridgeRepository, sessionStore, chzzkGateway)

suspend fun startApp(scope: CoroutineScope, service: ChzzkService): Job {
    // api server (internal)
    val apiServer = embeddedServer(Netty, configure = {
        connectors.add(EngineConnectorBuilder().apply {
            host = "127.0.0.1"
            port = apiServerPort
        })
    }) {
        serverConfig {
            parentCoroutineContext = scope.coroutineContext
        }
        commonModule()
        apiModule(service)
    }.startSuspend(false)

    // oAuth server (external)
    val oAuthServer = embeddedServer(Netty, configure = {
        connectors.add(EngineConnectorBuilder().apply {
            port = oAuthServerPort
        })
    }) {
        serverConfig {
            parentCoroutineContext = scope.coroutineContext
        }
        commonModule()
        oAuthModule(service)
    }.startSuspend(false)

    val uuid = UUID.fromString("e0a34904-d71e-4052-b6e2-59cb1865fbf1")
    val authAdapter = ChzzkOauthLoginAdapter(oAuthServerPort)
    val url = authAdapter.getAccountInterlockUrl(clientId, false, service.issueState(uuid))
    println(url)

    return scope.launch(Dispatchers.Default) {
        try {
            awaitCancellation()
        } finally {
            apiServer.stop()
            oAuthServer.stop()
        }
    }
}
