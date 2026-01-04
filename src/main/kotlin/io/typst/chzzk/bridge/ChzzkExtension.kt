package io.typst.chzzk.bridge

import xyz.r2turntrue.chzzk4j.ChzzkClient
import xyz.r2turntrue.chzzk4j.ChzzkClientBuilder
import xyz.r2turntrue.chzzk4j.auth.ChzzkLoginAdapter
import xyz.r2turntrue.chzzk4j.auth.ChzzkOauthCodeLoginAdapter
import xyz.r2turntrue.chzzk4j.auth.ChzzkSimpleUserLoginAdapter
import xyz.r2turntrue.chzzk4j.session.ChzzkSessionBuilder
import xyz.r2turntrue.chzzk4j.session.ChzzkUserSession
import xyz.r2turntrue.chzzk4j.session.event.SessionChatMessageEvent
import xyz.r2turntrue.chzzk4j.session.event.SessionConnectedEvent
import xyz.r2turntrue.chzzk4j.session.event.SessionDisconnectedEvent
import xyz.r2turntrue.chzzk4j.session.event.SessionDonationEvent

fun createChzzkClient(adapter: ChzzkLoginAdapter): ChzzkClient =
    ChzzkClientBuilder(clientId, clientSecret)
        .withLoginAdapter(adapter)
        .build()

fun createChzzkCodeLoginClient(code: String, state: String): ChzzkClient =
    createChzzkClient(ChzzkOauthCodeLoginAdapter(code, state))

fun createChzzkSimpleClient(accessToken: String, refreshToken: String?): ChzzkClient =
    createChzzkClient(ChzzkSimpleUserLoginAdapter(accessToken, refreshToken))

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