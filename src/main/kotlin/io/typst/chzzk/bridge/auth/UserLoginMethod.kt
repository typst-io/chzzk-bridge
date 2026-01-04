package io.typst.chzzk.bridge.auth

import java.util.*

data class UserLoginMethod(
    val uuid: UUID,
    val method: LoginMethod,
)

sealed interface LoginMethod {
    data class CreateToken(val code: String, val state: String) : LoginMethod
    data class UseToken(val accessToken: String, val refreshToken: String) : LoginMethod
}
