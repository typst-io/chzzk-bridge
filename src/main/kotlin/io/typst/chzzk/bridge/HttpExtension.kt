package io.typst.chzzk.bridge

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.MessageFormat

fun uenc(xs: String): String =
    URLEncoder.encode(xs, StandardCharsets.UTF_8)

fun getUriChzzkAccountInterlock(clientId: String, state: String, hostname: String = "{0}"): String {
    val redirectUri = "${uenc("http://")}$hostname:${oAuthServerPort}/oauth_callback"
    return "https://chzzk.naver.com/account-interlock?clientId=${uenc(clientId)}&redirectUri=${redirectUri}&state=${
        uenc(state)
    }"
}