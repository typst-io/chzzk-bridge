package io.typst.chzzk.bridge.chzzk

import io.typst.chzzk.bridge.auth.LoginMethod
import io.typst.chzzk.bridge.auth.UserLoginMethod

interface ChzzkGateway {
    suspend fun login(loginMethod: UserLoginMethod): ChzzkUserData?

    suspend fun refreshToken(refreshToken: String): LoginMethod.UseToken

    suspend fun connectSession(): ChzzkSessionGateway
}