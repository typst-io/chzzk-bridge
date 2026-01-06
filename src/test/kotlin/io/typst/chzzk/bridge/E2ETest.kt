package io.typst.chzzk.bridge

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.typst.chzzk.bridge.api.ApiSseChatMessage
import io.typst.chzzk.bridge.api.ApiSubscribeResponseBody
import io.typst.chzzk.bridge.mock.TestChzzkGateway
import io.typst.chzzk.bridge.persis.UserToken
import io.typst.chzzk.bridge.repository.SQLiteBridgeRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.*
import kotlin.test.assertEquals

class E2ETest {
    lateinit var rootJob: Job
    lateinit var scope: CoroutineScope
    lateinit var service: ChzzkService
    lateinit var testChzzkGateway: TestChzzkGateway
    lateinit var oAuthClient: HttpClient
    lateinit var apiClient: HttpClient
    val channelId = "a66e45a95d3d01325deaf5f72c19c2c5"

    @BeforeEach
    fun setup(@TempDir dir: File) {
        val dbFile = Files.createTempFile(dir.toPath(), "chzzk_bridge-", ".db").toFile()
        rootJob = Job()
        scope = CoroutineScope(rootJob + Dispatchers.Default)
        testChzzkGateway = TestChzzkGateway()
        service = createChzzkService(
            scope,
            bridgeRepository = SQLiteBridgeRepository(dir, dbFile),
            chzzkGateway = testChzzkGateway
        )
        runBlocking {
            startApp(scope, service)
        }
        oAuthClient = HttpClient(CIO) {
            install(Resources)
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            defaultRequest {
                host = "0.0.0.0"
                port = oAuthServerPort
                url { protocol = URLProtocol.HTTP }
            }
        }
        apiClient = HttpClient(CIO) {
            install(Resources)
            install(ContentNegotiation) {
                json()
            }
            install(SSE)
            defaultRequest {
                host = "127.0.0.1"
                port = apiServerPort
                url { protocol = URLProtocol.HTTP }
            }
        }
    }

    @AfterEach
    fun tearDown() {
        oAuthClient.close()
        apiClient.close()
        scope.cancel()
        runBlocking {
            rootJob.join()
            println("exit")
        }
    }

    private suspend fun testFetch(uuid: UUID) {
        // poll messages
        val msg = ApiSseChatMessage(
            1, channelId, "senderId", "senderName", "msg",
            nowInstant()
        )
        val msgB = msg.copy(id = 2, payAmount = 10)
        service.bridgeRepository.addMessage(msg)
        service.bridgeRepository.addMessage(msgB)

        val sseMessages = mutableListOf<ApiSseChatMessage>()
        apiClient.sse({
            url("api/v1/sse?uuid=$uuid")
        }, deserialize = { typeInfo, jsonString ->
            val serializer = Json.serializersModule.serializer(typeInfo.kotlinType!!)
            Json.decodeFromString(serializer, jsonString)!!
        }) {
            // receive individual messages (no longer a list)
            val events = incoming.filter { it.event != "heartbeat" }.take(2).toList()
            val receivedMessages = events
                .map { event ->
                    val data = Json.decodeFromString<ApiSseChatMessage>(event.data!!)
                    println("Received: $data (SSE id: ${event.id})")
                    data
                }
            sseMessages.addAll(receivedMessages)
            cancel()
        }
        assertEquals(listOf(msg, msgB), sseMessages)
    }

    @Test
    fun `e2e create token`(): Unit = scope.runBlockingWithContext {
        // try subscribe
        val uuid = UUID.randomUUID()
        val response = apiClient.post(ApiSubscribePathParameters(uuid))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val body = response.body<ApiSubscribeResponseBody>()
        assertNotNull(body.state)

        // ----
        // sending the interlock url to the player
        // ----

        // oAuth
        val channelId = "a66e45a95d3d01325deaf5f72c19c2c5"
        val code = "testCode"
        testChzzkGateway.registerAuthCode(code, body.state, channelId)
        val oAuthResponse = oAuthClient.get(OAuthCallbackPathParameters(code, body.state))
        assertEquals(HttpStatusCode.OK, oAuthResponse.status)

        testFetch(uuid)
    }

    @Test
    fun `e2e subscribe with token`() = scope.runBlockingWithContext {
        // subscribe
        val uuid = UUID.randomUUID()
        val token = UserToken(channelId, uuid, "testAT", "testRT", nowInstant().plusSeconds(3600))
        service.bridgeRepository.setToken(token)
        testChzzkGateway.registerToken(token)
        val response = apiClient.post(ApiSubscribePathParameters(uuid))
        assertEquals(HttpStatusCode.OK, response.status)

        testFetch(uuid)
    }
}