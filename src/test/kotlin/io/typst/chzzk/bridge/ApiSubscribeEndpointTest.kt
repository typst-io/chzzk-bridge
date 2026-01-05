package io.typst.chzzk.bridge

import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.typst.chzzk.bridge.api.ApiSseChatMessage
import io.typst.chzzk.bridge.api.ApiSubscribeResponseBody
import io.typst.chzzk.bridge.auth.LoginMethod
import io.typst.chzzk.bridge.auth.UserLoginMethod
import io.typst.chzzk.bridge.mock.TestChzzkGateway
import io.typst.chzzk.bridge.persis.UserToken
import io.typst.chzzk.bridge.repository.SQLiteBridgeRepository
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

class ApiSubscribeEndpointTest {
    val uuid: UUID = UUID.randomUUID()
    val code: String = "testCode"
    lateinit var token: UserToken
    lateinit var service: ChzzkService
    lateinit var testChzzkGateway: TestChzzkGateway

    @BeforeEach
    fun setup(@TempDir tempDir: File): Unit = runBlocking {
        val testScope = createTestScope()
        val dbPath = Files.createTempFile(tempDir.toPath(), "test-", ".db")
        val bridgeRepo = SQLiteBridgeRepository(tempDir, dbPath.toFile())
        val sessionRepo = SessionStore(random)

        // register fake token
        val gateway = TestChzzkGateway()
        val state = sessionRepo.issueState(uuid)
        val channelId = "a66e45a95d3d01325deaf5f72c19c2c5"
        gateway.registerAuthCode(code, state, channelId)
        val userInfo = gateway.login(UserLoginMethod(uuid, LoginMethod.CreateToken(code, state)))
        assertNotNull(userInfo)
        gateway.registerToken(userInfo.toToken(uuid))
        token = userInfo.toToken(uuid)

        service = createChzzkService(testScope, bridgeRepository = bridgeRepo, sessionStore = sessionRepo, chzzkGateway = gateway)
        testChzzkGateway = gateway
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        service.close()
    }

    @Test
    fun subscribe(): Unit = createTestApplication(service) {
        // no token
        val bridgeRepo = service.bridgeRepository
        val param = ApiSubscribePathParameters(uuid)
        val responseA = client.post(param)
        assertEquals(HttpStatusCode.Unauthorized, responseA.status)

        // success
        bridgeRepo.setToken(token)
        val responseB = client.post(param)
        assertEquals(HttpStatusCode.OK, responseB.status)

        // duplicate session
        val responseC = client.post(param)
        assertEquals(HttpStatusCode.NoContent, responseC.status)
    }

    @Test
    fun `subscribe with invalid token`(): Unit = createTestApplication(service) {
        val bridgeRepo = service.bridgeRepository
        // fail with invalid token
        val param = ApiSubscribePathParameters(uuid)
        bridgeRepo.setToken(token.copy(accessToken = "invalidAT", refreshToken = "invalidRT"))
        val responseD = client.post(param)
        assertEquals(HttpStatusCode.InternalServerError, responseD.status)
    }

    @Test
    fun `subscribe with expired access token`(): Unit = createTestApplication(service) {
        // Create a token that is already expired (expireTime in the past)
        val expiredToken = token.copy(expireTime = nowInstant().minusSeconds(60))
        service.bridgeRepository.setToken(expiredToken)
        // Register token with valid refresh token - this enables token refresh
        testChzzkGateway.registerToken(expiredToken)

        val response = client.post(ApiSubscribePathParameters(uuid))

        // Should succeed because refresh token is valid and can obtain new access token
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify that a new token was saved (refresh rotates the token)
        val savedToken = service.bridgeRepository.getToken(uuid)
        assertNotNull(savedToken)
        // New access token should be different from the expired one
        assert(savedToken!!.accessToken != expiredToken.accessToken) {
            "Access token should be rotated after refresh"
        }
    }

    @Test
    fun `subscribe with expired refresh token`(): Unit = createTestApplication(service) {
        // Create a token that is already expired
        val expiredToken = token.copy(expireTime = nowInstant().minusSeconds(60))
        service.bridgeRepository.setToken(expiredToken)
        // Register token but then invalidate the refresh token
        testChzzkGateway.registerToken(expiredToken)
        // Remove refresh token from valid tokens - simulates expired/revoked refresh token
        testChzzkGateway.validRefreshTokens.remove(expiredToken.refreshToken)

        val response = client.post(ApiSubscribePathParameters(uuid))

        // Should return Unauthorized with state for re-authentication (like no token case)
        assertEquals(HttpStatusCode.Unauthorized, response.status)

        // Verify response contains interlock URL
        val body = response.body<ApiSubscribeResponseBody>()
        assertNotNull(body.state)
        assertNotNull(body.path)

        // Verify token was deleted from repository
        val deletedToken = service.bridgeRepository.getToken(uuid)
        assertEquals(null, deletedToken)
    }

    @Test
    fun unsubscribe(): Unit = createTestApplication(service) {
        val bridgeRepo = service.bridgeRepository
        val nonTarget = UUID.randomUUID()

        // unsubscribe without session
        val param = ApiSubscribePathParameters(uuid)
        val unsubscribeWithoutSessionResponse = client.post(param)
        assertEquals(HttpStatusCode.Unauthorized, unsubscribeWithoutSessionResponse.status)

        // subscribe
        bridgeRepo.setToken(token)
        val subscribeResponse = client.post(param)
        assertEquals(HttpStatusCode.OK, subscribeResponse.status)

        // non-target invariant
        val invariantResponse = client.post(ApiUnsubscribePathParameters(nonTarget))
        assertEquals(HttpStatusCode.NoContent, invariantResponse.status)

        // unsubscribe
        val unParam = ApiUnsubscribePathParameters(uuid)
        val unsubscribeResponse = client.post(unParam)
        assertEquals(HttpStatusCode.OK, unsubscribeResponse.status)
    }

    @Test
    fun `unsubscribe and subscribe immediately`(): Unit {
        createTestApplication(service) {
            val bridgeRepo = service.bridgeRepository
            // subscribe
            val param = ApiSubscribePathParameters(uuid)
            bridgeRepo.setToken(token)
            val subscribeResponse = client.post(param)
            assertEquals(HttpStatusCode.OK, subscribeResponse.status)

            // unsubscribe
            val a = client.post(ApiUnsubscribePathParameters(uuid))
            // subscribe again
            val b = client.post(param)
            assertEquals(HttpStatusCode.OK, a.status)
            assertEquals(HttpStatusCode.OK, b.status)
        }
    }

    // https://ktor.io/docs/client-server-sent-events.html#deserialization
    @Test
    fun fetch(): Unit = createTestApplication(service) {
        val bridgeRepo = service.bridgeRepository
        bridgeRepo.setToken(token)
        // add data first (no need to subscribe for fetch test)
        val donationMsg =
            ApiSseChatMessage(1, token.channelId, "EntryPoint", "EntryPoint", "test donation", nowInstant(), 10)
        bridgeRepo.addMessage(donationMsg)
        val msgMsg =
            ApiSseChatMessage(2, token.channelId, "EntryPoint", "EntryPoint", "test message", nowInstant())
        bridgeRepo.addMessage(msgMsg)

        // fetch via SSE with uuid query parameter
        client.sse({
            url("api/v1/sse?uuid=$uuid")
        }, deserialize = { typeInfo, jsonString ->
            val serializer = Json.serializersModule.serializer(typeInfo.kotlinType!!)
            Json.decodeFromString(serializer, jsonString)!!
        }) {
            // receive individual messages
            val events = incoming.take(2).toList()
            val receivedMessages = events.map { event ->
                Json.decodeFromString<ApiSseChatMessage>(event.data!!)
            }
            assertEquals(listOf(donationMsg, msgMsg), receivedMessages)
        }
    }
}