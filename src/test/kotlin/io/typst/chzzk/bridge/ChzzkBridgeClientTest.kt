package io.typst.chzzk.bridge

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.typst.chzzk.bridge.api.ApiSseChatMessage
import io.typst.chzzk.bridge.client.ChzzkBridgeClient
import io.typst.chzzk.bridge.client.ChzzkBridgeClient.Status
import io.typst.chzzk.bridge.mock.TestChzzkGateway
import io.typst.chzzk.bridge.persis.UserToken
import io.typst.chzzk.bridge.repository.SQLiteBridgeRepository
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E tests using [ChzzkBridgeClient] (Java HttpClient-based client).
 */
class ChzzkBridgeClientTest {
    private lateinit var rootJob: Job
    private lateinit var scope: CoroutineScope
    private lateinit var service: ChzzkService
    private lateinit var testChzzkGateway: TestChzzkGateway
    private lateinit var oAuthClient: HttpClient
    private lateinit var bridgeClient: ChzzkBridgeClient

    private val channelId = "a66e45a95d3d01325deaf5f72c19c2c5"

    @BeforeEach
    fun setup(@TempDir dir: File) {
        val dbFile = Files.createTempFile(dir.toPath(), "chzzk_bridge-", ".db").toFile()
        rootJob = Job()
        scope = CoroutineScope(rootJob + Dispatchers.Default)
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
            defaultRequest {
                host = "0.0.0.0"
                port = oAuthServerPort
                url { protocol = URLProtocol.HTTP }
            }
        }
        bridgeClient = ChzzkBridgeClient("http://127.0.0.1:$apiServerPort")
    }

    @AfterEach
    fun tearDown() {
        bridgeClient.close()
        oAuthClient.close()
        scope.cancel()
        runBlocking {
            rootJob.join()
        }
    }

    @Test
    fun `client subscribe without token returns AUTH_REQUIRED`() {
        val uuid = UUID.randomUUID()
        val result = bridgeClient.subscribe(uuid)

        assertEquals(Status.AUTH_REQUIRED, result.status)
        assertTrue(result.requiresAuth())
        assertNotNull(result.state)
        assertNotNull(result.authUrl)
    }

    @Test
    fun `client subscribe with token returns SUCCESS`() = scope.launchAndJoin {
        val uuid = UUID.randomUUID()
        val token = UserToken(channelId, uuid, "testAT", "testRT", nowInstant().plusSeconds(3600))
        service.bridgeRepository.setToken(token)
        testChzzkGateway.registerToken(token)

        val result = bridgeClient.subscribe(uuid)

        assertEquals(Status.SUCCESS, result.status)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `client full OAuth flow and subscribe`() = scope.launchAndJoin {
        val uuid = UUID.randomUUID()

        // Step 1: subscribe without token -> AUTH_REQUIRED
        val authResult = bridgeClient.subscribe(uuid)
        assertEquals(Status.AUTH_REQUIRED, authResult.status)
        assertNotNull(authResult.state)

        // Step 2: simulate OAuth callback
        val code = "testCode"
        testChzzkGateway.registerAuthCode(code, authResult.state, channelId)
        val oAuthResponse = oAuthClient.get(OAuthCallbackPathParameters(code, authResult.state))
        assertEquals(HttpStatusCode.OK, oAuthResponse.status)

        // Step 3: subscribe again -> SUCCESS or ALREADY_SUBSCRIBED (both are valid)
        val successResult = bridgeClient.subscribe(uuid)
        assertTrue(successResult.isSuccess, "Expected SUCCESS or ALREADY_SUBSCRIBED but got ${successResult.status}")
    }

    @Test
    fun `client SSE streaming receives messages`() = scope.launchAndJoin {
        val uuid = UUID.randomUUID()
        val token = UserToken(channelId, uuid, "testAT", "testRT", nowInstant().plusSeconds(3600))
        service.bridgeRepository.setToken(token)
        testChzzkGateway.registerToken(token)

        // subscribe first
        val subscribeResult = bridgeClient.subscribe(uuid)
        assertEquals(Status.SUCCESS, subscribeResult.status)

        // add messages to the repository
        val msg1 = ApiSseChatMessage(
            1, channelId, "senderId", "senderName", "Hello",
            nowInstant()
        )
        val msg2 = ApiSseChatMessage(
            2, channelId, "senderId", "senderName", "World",
            nowInstant(), payAmount = 1000
        )
        service.bridgeRepository.addMessage(msg1)
        service.bridgeRepository.addMessage(msg2)

        // collect messages via SSE
        val receivedMessages = CopyOnWriteArrayList<ChzzkBridgeClient.ChatMessage>()
        val latch = CountDownLatch(2)

        val handle = bridgeClient.streamEventsAsync(uuid, { msg ->
            receivedMessages.add(msg)
            latch.countDown()
        }, { error ->
            error.printStackTrace()
        })

        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive 2 messages")
            assertEquals(2, receivedMessages.size)

            // verify first message
            val first = receivedMessages[0]
            assertEquals(1, first.id)
            assertEquals("Hello", first.message)
            assertEquals("senderName", first.senderName)
            assertEquals(0, first.payAmount)

            // verify second message (donation)
            val second = receivedMessages[1]
            assertEquals(2, second.id)
            assertEquals("World", second.message)
            assertEquals(1000, second.payAmount)
            assertTrue(second.isDonation)
        } finally {
            handle.close()
        }
    }

    @Test
    fun `client SSE with lastEventId resumes from position`() = scope.launchAndJoin {
        val uuid = UUID.randomUUID()
        val token = UserToken(channelId, uuid, "testAT", "testRT", nowInstant().plusSeconds(3600))
        service.bridgeRepository.setToken(token)
        testChzzkGateway.registerToken(token)

        bridgeClient.subscribe(uuid)

        // add 3 messages
        repeat(3) { i ->
            service.bridgeRepository.addMessage(
                ApiSseChatMessage(
                    i + 1, channelId, "sender", "name", "msg$i", nowInstant()
                )
            )
        }

        // connect with lastEventId=1 (should skip first message)
        val receivedMessages = CopyOnWriteArrayList<ChzzkBridgeClient.ChatMessage>()
        val latch = CountDownLatch(2)

        val handle = bridgeClient.streamEventsAsync(uuid, 1, { msg ->
            receivedMessages.add(msg)
            latch.countDown()
        }, null)

        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Should receive 2 messages")
            assertEquals(2, receivedMessages.size)
            assertEquals(2, receivedMessages[0].id)
            assertEquals(3, receivedMessages[1].id)
        } finally {
            handle.close()
        }
    }

    @Test
    fun `client unsubscribe removes session`() = scope.launchAndJoin {
        val uuid = UUID.randomUUID()
        val token = UserToken(channelId, uuid, "testAT", "testRT", nowInstant().plusSeconds(3600))
        service.bridgeRepository.setToken(token)
        testChzzkGateway.registerToken(token)

        // subscribe
        val subscribeResult = bridgeClient.subscribe(uuid)
        assertEquals(Status.SUCCESS, subscribeResult.status)

        // unsubscribe
        val unsubscribeResult = bridgeClient.unsubscribe(uuid)
        assertTrue(unsubscribeResult)
    }

    @Test
    fun `client subscribeAsync works correctly`() = scope.launchAndJoin {
        val uuid = UUID.randomUUID()
        val token = UserToken(channelId, uuid, "testAT", "testRT", nowInstant().plusSeconds(3600))
        service.bridgeRepository.setToken(token)
        testChzzkGateway.registerToken(token)

        val future = bridgeClient.subscribeAsync(uuid)
        val result = future.get(5, TimeUnit.SECONDS)

        assertEquals(Status.SUCCESS, result.status)
        assertTrue(result.isSuccess)
    }
}
