package io.typst.chzzk.bridge.chzzk.chzzk4j

import io.typst.chzzk.bridge.auth.LoginMethod
import io.typst.chzzk.bridge.auth.UserLoginMethod
import io.typst.chzzk.bridge.config.ChzzkApiScope
import xyz.r2turntrue.chzzk4j.ChzzkClient
import xyz.r2turntrue.chzzk4j.auth.ChzzkLoginAdapter
import xyz.r2turntrue.chzzk4j.auth.ChzzkOauthCodeLoginAdapter
import xyz.r2turntrue.chzzk4j.auth.ChzzkSimpleUserLoginAdapter
import xyz.r2turntrue.chzzk4j.session.ChzzkSessionBuilder
import xyz.r2turntrue.chzzk4j.session.ChzzkSessionSubscriptionType
import xyz.r2turntrue.chzzk4j.session.ChzzkUserSession
import xyz.r2turntrue.chzzk4j.session.event.SessionConnectedEvent
import xyz.r2turntrue.chzzk4j.session.event.SessionDisconnectedEvent

fun UserLoginMethod.toChzzkLoginAdapter(): ChzzkLoginAdapter {
    return when (method) {
        is LoginMethod.CreateToken -> ChzzkOauthCodeLoginAdapter(method.code, method.state)
        is LoginMethod.UseToken -> ChzzkSimpleUserLoginAdapter(method.accessToken, method.refreshToken)
    }
}

fun ChzzkApiScope.toChzzk4jSessionScope(): ChzzkSessionSubscriptionType? =
    when (this) {
        ChzzkApiScope.CHAT -> ChzzkSessionSubscriptionType.CHAT
        ChzzkApiScope.DONATION -> ChzzkSessionSubscriptionType.DONATION
        else -> null
    }

//fun createChzzkClient(adapter: ChzzkLoginAdapter, clientId: String, clientSecret: String): ChzzkClient =
//    ChzzkClientBuilder(clientId, clientSecret)
//        .withLoginAdapter(adapter)
//        .build()
//
//fun createChzzkCodeLoginClient(code: String, state: String): ChzzkClient =
//    createChzzkClient(ChzzkOauthCodeLoginAdapter(code, state))
//
//fun createChzzkSimpleClient(accessToken: String, refreshToken: String?): ChzzkClient =
//    createChzzkClient(ChzzkSimpleUserLoginAdapter(accessToken, refreshToken))

fun createChzzkUserSession(client: ChzzkClient): ChzzkUserSession {
    val session = ChzzkSessionBuilder(client)
        .buildUserSession()
    session.on(SessionConnectedEvent::class.java) { e ->
        println("Connected!")
    }
    session.on(SessionDisconnectedEvent::class.java) { e ->
        println("Disconnected.")
    }
    return session
}