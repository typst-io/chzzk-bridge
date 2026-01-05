package io.typst.chzzk.bridge.chzzk.chzzk4j

import io.typst.chzzk.bridge.api.ApiSseChatMessage
import io.typst.chzzk.bridge.auth.LoginMethod
import io.typst.chzzk.bridge.auth.UserLoginMethod
import io.typst.chzzk.bridge.chzzk.ChzzkGateway
import io.typst.chzzk.bridge.chzzk.ChzzkSessionGateway
import io.typst.chzzk.bridge.chzzk.ChzzkUserData
import io.typst.chzzk.bridge.config.BridgeConfig
import io.typst.chzzk.bridge.logger
import io.typst.chzzk.bridge.repository.BridgeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import xyz.r2turntrue.chzzk4j.ChzzkClient
import xyz.r2turntrue.chzzk4j.ChzzkClientBuilder
import xyz.r2turntrue.chzzk4j.auth.ChzzkLoginAdapter
import xyz.r2turntrue.chzzk4j.auth.ChzzkOauthCodeLoginAdapter
import xyz.r2turntrue.chzzk4j.auth.ChzzkSimpleUserLoginAdapter
import xyz.r2turntrue.chzzk4j.session.event.SessionChatMessageEvent
import xyz.r2turntrue.chzzk4j.session.event.SessionDonationEvent
import java.time.Instant
import java.util.*

fun UserLoginMethod.toChzzkLoginAdapter(): ChzzkLoginAdapter {
    return when (method) {
        is LoginMethod.CreateToken -> ChzzkOauthCodeLoginAdapter(method.code, method.state)
        is LoginMethod.UseToken -> ChzzkSimpleUserLoginAdapter(method.accessToken, method.refreshToken)
    }
}

class Chzzk4jGateway(
    val applicationScope: CoroutineScope,
    val bridgeRepository: BridgeRepository,
    val config: BridgeConfig,
) : ChzzkGateway {
    lateinit var client: ChzzkClient

    private fun getClient(loginMethod: UserLoginMethod): ChzzkClient {
        if (!this::client.isInitialized) {
            client = ChzzkClientBuilder(config.clientId, config.clientSecret)
                .withLoginAdapter(loginMethod.toChzzkLoginAdapter())
                .build()
        }
        return client
    }

    override suspend fun refreshToken(refreshToken: String): LoginMethod.UseToken {
        val mockMethod = UserLoginMethod(UUID.randomUUID(), LoginMethod.UseToken("", refreshToken))
        val client = getClient(mockMethod)
        client.refreshTokenAsync().await()
        return LoginMethod.UseToken(client.loginResult.accessToken(), client.loginResult.refreshToken())
    }

    override suspend fun login(loginMethod: UserLoginMethod): ChzzkUserData? {
        val client = getClient(loginMethod)
        val chzzkUser = try {
            client.loginAsync().await()
            client.fetchLoggedUser().await()
        } catch (ex: Exception) {
            logger.warn("Error while login", ex)
            null
        }
        return if (chzzkUser != null) {
            val lr = client.loginResult
            val expireTime = Instant.now().plusSeconds(lr.tokenExpiresIn().toLong())
            ChzzkUserData(
                chzzkUser.userId,
                lr.accessToken(),
                lr.refreshToken(),
                expireTime,
            )
        } else null
    }

    override suspend fun connectSession(): ChzzkSessionGateway {
        val session = createChzzkUserSession(client)
        for (scope in config.scopes) {
            session.subscribeAsync(scope.toChzzk4jScope()).await()
        }
        session.createAndConnectAsync().await()

        session.on(SessionChatMessageEvent::class.java) { e ->
            println("Message: ${e.message}")
            val msg = e.message
            // chzzk4j 에서 ZoneId 고정을 안시키므로 시간은 임의로 설정
            val data = ApiSseChatMessage(
                -1,
                e.message.receivedChannelId,
                msg.senderChannelId,
                msg.profile.nickname,
                msg.content,
                Instant.now()
            )
            applicationScope.launch(Dispatchers.IO) {
                bridgeRepository.addMessage(data)
            }
        }
        session.on(SessionDonationEvent::class.java) { e ->
            val msg = e.message
            val data =
                ApiSseChatMessage(
                    -1,
                    msg.receivedChannelId,
                    msg.donatorChannelId,
                    msg.donatorNickname,
                    msg.donationText,
                    Instant.now(),
                    msg.payAmount
                )
            applicationScope.launch(Dispatchers.IO) {
                bridgeRepository.addMessage(data)
            }
            println("Donation: ${e.message}")
        }
        return Chzzk4JUserSessionGateway(session)
    }
}