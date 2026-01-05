package io.typst.chzzk.bridge

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.typst.chzzk.bridge.auth.LoginMethod
import io.typst.chzzk.bridge.auth.UserLoginMethod
import io.typst.chzzk.bridge.mock.TestChzzkGateway
import io.typst.chzzk.bridge.persis.UserToken
import io.typst.chzzk.bridge.repository.SQLiteBridgeRepository
import kotlinx.coroutines.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.*
import kotlin.test.assertEquals
import kotlin.time.measureTime

// TODO: !! expires token
class OAuthEndpointTest {
    var chzzkCode = "testCode"
    val uuid: UUID = UUID.randomUUID()
    lateinit var token: UserToken
    lateinit var state: String
    lateinit var testChzzkGateway: TestChzzkGateway
    lateinit var service: ChzzkService

    @BeforeEach
    fun setup(@TempDir tempDir: File): Unit = runBlocking {
        val testScope = createTestScope()
        val dbFile = Files.createTempFile(tempDir.toPath(), "test-", ".db").toFile()
        val bridgeRepo = SQLiteBridgeRepository(tempDir, dbFile)
        val sessionRepo = SessionStore(random)

        // register fake token
        val gateway = TestChzzkGateway()
        state = sessionRepo.issueState(uuid)
        val channelId = "a66e45a95d3d01325deaf5f72c19c2c5"
        gateway.registerAuthCode(chzzkCode, state, channelId)
        val userInfo = gateway.login(UserLoginMethod(uuid, LoginMethod.CreateToken(chzzkCode, state)))
        assertNotNull(userInfo)
        gateway.registerToken(userInfo.accessToken, userInfo.refreshToken, userInfo.userId, uuid, userInfo.expireTime)
        gateway.registerAuthCode(chzzkCode, state, channelId)
        token = userInfo.toToken(uuid)

        testChzzkGateway = gateway
        service = createChzzkService(testScope, bridgeRepository = bridgeRepo, sessionStore = sessionRepo, chzzkGateway = gateway)
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        service.close()
    }

    @Test
    fun simple(): Unit = createTestApplication(service) {
        val param = OAuthCallbackPathParameters(chzzkCode, state)
        val first = client.get(param.copy(code = "", state = ""))
        assertEquals(HttpStatusCode.BadRequest, first.status)
        assertEquals(HttpStatusCode.BadRequest, client.get(param.copy(state = "invalidState")).status)

        val second = client.get(param.copy(code = "invalidCode", state = state))
        assertEquals(HttpStatusCode.InternalServerError, second.status)

        state = service.sessionStore.issueState(uuid)
        testChzzkGateway.registerAuthCode(chzzkCode, state)
        val third = client.get(param.copy(state = state))
        assertEquals(HttpStatusCode.OK, third.status)

        val fourth = client.get(param)
        assertEquals(HttpStatusCode.BadRequest, fourth.status)
    }

    @Test
    fun `already has a token`(): Unit = createTestApplication(service) {
        val bridgeRepo = service.bridgeRepository
        val state = service.issueState(uuid)
        val param = OAuthCallbackPathParameters(chzzkCode, state)
        bridgeRepo.setToken(token)
        val first = client.get(param)
        assertEquals(HttpStatusCode.NotAcceptable, first.status)
    }

    @Test
    @Disabled
    suspend fun e2eStressTest(): Unit = coroutineScope {
        startApp(this, createChzzkService(this, chzzkGateway = TestChzzkGateway()))
        val count = 1_000
        val client = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
            engine {
                maxConnectionsCount = count * 2
                endpoint {
                    maxConnectionsPerRoute = count * 2
                }
            }
        }
        val list = (0 until count).map {
            async {
                client.get("http://127.0.0.1:${oAuthServerPort}/oauth_callback") {
                    parameter("code", chzzkCode)
                    parameter("state", uuid.toString())
                }
            }
        }
        val duration = withTimeout(5000L) {
            measureTime {
                list.joinAll()
            }
        }
        println("1,000 requests: $duration")
    }
}