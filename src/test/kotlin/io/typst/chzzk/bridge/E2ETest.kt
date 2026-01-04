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
import io.typst.chzzk.bridge.api.ApiFetchChzzkMessage
import io.typst.chzzk.bridge.api.ApiFetchResponseBody
import io.typst.chzzk.bridge.api.ApiSubscribeResponseBody
import io.typst.chzzk.bridge.mock.TestChzzkGateway
import io.typst.chzzk.bridge.persis.UserToken
import io.typst.chzzk.bridge.repository.SQLiteBridgeRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.time.Instant
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
        scope = CoroutineScope(rootJob + Dispatchers.Main)
        testChzzkGateway = TestChzzkGateway()
        service = createChzzkService(
            scope,
            SQLiteBridgeRepository(dir, dbFile),
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
    fun tearDown() = runBlocking {
        service.close()
        scope.cancel()
        rootJob.join()
    }

    private suspend fun testFetch(uuid: UUID) {
        // poll messages
        val msg = ApiFetchChzzkMessage(
            1, channelId, "senderId", "senderName", "msg",
            nowInstant()
        )
        val msgB = msg.copy(id = 2, payAmount = 10)
        service.bridgeRepository.addMessage(msg)
        service.bridgeRepository.addMessage(msgB)

        apiClient.sse({
            url("api/v1/sse?fromId=1&uuid=$uuid")
        }, deserialize = { typeInfo, jsonString ->
            val serializer = Json.serializersModule.serializer(typeInfo.kotlinType!!)
            Json.decodeFromString(serializer, jsonString)!!
        }) {
            // receive first event and verify
            val event = incoming.first()
            val data = Json.decodeFromString<ApiFetchResponseBody>(event.data!!)
            println(data)
            assertEquals(
                ApiFetchResponseBody(listOf(msg, msgB)),
                data
            )
        }
    }

    @Test
    suspend fun `e2e create token`(): Unit {
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
    suspend fun `e2e subscribe with token`() {
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