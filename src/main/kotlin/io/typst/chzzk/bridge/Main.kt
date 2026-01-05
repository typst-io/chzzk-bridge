@file:Suppress("OPT_IN_USAGE")

package io.typst.chzzk.bridge

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.typst.chzzk.bridge.chzzk.ChzzkGateway
import io.typst.chzzk.bridge.chzzk.chzzk4j.Chzzk4jGateway
import io.typst.chzzk.bridge.config.BridgeConfig
import io.typst.chzzk.bridge.repository.BridgeRepository
import io.typst.chzzk.bridge.repository.SQLiteBridgeRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import java.util.random.RandomGenerator

val logger: Logger = LoggerFactory.getLogger("io.typst.chzzk.bridge.main")
val oAuthServerPort: Int = 39680
val apiServerPort: Int = 39681
val random = RandomGenerator.getDefault()
val configJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
}

fun getEnvOrThrow(key: String): String =
    System.getenv(key) ?: throw IllegalStateException("Please set environment variable: `$key`")

suspend fun main(args: Array<String>): Unit = supervisorScope {
    System.setProperty("org.jooq.no-tips", "true")
    System.setProperty("org.jooq.no-logo", "true")
    val configFile = File("config.json")
    val bridgeConfig = if (configFile.isFile) {
        configJson.decodeFromString<BridgeConfig>(configFile.readText())
    } else {
        BridgeConfig().apply {
            configFile.writeText(configJson.encodeToString(this))
        }
    }
    val service = createChzzkService(this, config = bridgeConfig)
    val uuid = UUID.fromString("e0a34904-d71e-4052-b6e2-59cb1865fbf1")
    val url = getUriChzzkAccountInterlock(bridgeConfig.clientId, service.issueState(uuid), bridgeConfig.hostname)
    logger.info(url)
    startApp(this, service, bridgeConfig).join()
}

fun createChzzkService(
    scope: CoroutineScope,
    config: BridgeConfig = BridgeConfig(),
    bridgeRepository: BridgeRepository = SQLiteBridgeRepository(File("./")),
    sessionStore: SessionStore = SessionStore(random),
    chzzkGateway: ChzzkGateway = Chzzk4jGateway(scope, bridgeRepository, config),
): ChzzkService = ChzzkService(scope, bridgeRepository, sessionStore, chzzkGateway, config)

suspend fun startApp(scope: CoroutineScope, service: ChzzkService, config: BridgeConfig = BridgeConfig()): Job {
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
            host = config.hostname
            port = oAuthServerPort
        })
    }) {
        serverConfig {
            parentCoroutineContext = scope.coroutineContext
        }
        commonModule()
        oAuthModule(service)
    }.startSuspend(false)

    return scope.launch(Dispatchers.Default) {
        try {
            awaitCancellation()
        } finally {
            apiServer.stop()
            oAuthServer.stop()
            service.close()
            logger.info("Server closed.")
        }
    }
}
