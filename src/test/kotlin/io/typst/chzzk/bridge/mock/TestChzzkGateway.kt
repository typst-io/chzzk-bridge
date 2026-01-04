package io.typst.chzzk.bridge.mock

import io.typst.chzzk.bridge.auth.LoginMethod
import io.typst.chzzk.bridge.auth.UserLoginMethod
import io.typst.chzzk.bridge.chzzk.ChzzkGateway
import io.typst.chzzk.bridge.chzzk.ChzzkSessionGateway
import io.typst.chzzk.bridge.chzzk.ChzzkUserData
import io.typst.chzzk.bridge.persis.UserToken
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Test implementation of [ChzzkGateway] that simulates Naver CHZZK OAuth behavior.
 *
 * Token format follows Naver CHZZK API patterns:
 * - Channel ID: 32-character lowercase hex string (e.g., `6e06f5e1907f17eff543abd06cb62891`)
 * - Access Token: Base64-like alphanumeric string (e.g., `FFok65zQFvH2eJ7SS7SBFlTXt0EZ10L5...`)
 * - Refresh Token: Base64-like alphanumeric string (e.g., `NWG05CKHAsz4k4d3PB0wQUV9ugGlp0Yu...`)
 */
class TestChzzkGateway(
    private val tokenExpirationDuration: Duration = Duration.ofSeconds(86400),
) : ChzzkGateway {

    private val random = SecureRandom()
    private val base64Encoder = Base64.getUrlEncoder().withoutPadding()

    /** Storage for valid authorization codes mapped to (channelId, state) pairs */
    val validAuthCodes: ConcurrentHashMap<String, AuthCodeData> = ConcurrentHashMap()

    data class AuthCodeData(val channelId: String, val state: String)

    /** Storage for issued tokens mapped by access token */
    val issuedTokens: ConcurrentHashMap<String, UserToken> = ConcurrentHashMap()

    override suspend fun refreshToken(refreshToken: String): LoginMethod.UseToken {
        // Validate refresh token exists
        val oldAccessToken = validRefreshTokens.remove(refreshToken)
            ?: throw IllegalArgumentException("Invalid or expired refresh token")

        // Invalidate old access token
        issuedTokens.remove(oldAccessToken)

        // Issue new token pair (token rotation per OAuth 2.0 best practices)
        val newAccessToken = generateAccessToken()
        val newRefreshToken = generateRefreshToken()

        // Register new refresh token mapping
        validRefreshTokens[newRefreshToken] = newAccessToken

        return LoginMethod.UseToken(newAccessToken, newRefreshToken)
    }

    /** Storage for valid refresh tokens mapped to access tokens */
    val validRefreshTokens: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    /**
     * Generates a channel ID in Naver CHZZK format.
     * Format: 32-character lowercase hexadecimal string.
     */
    fun generateChannelId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generates an access token in Naver CHZZK format.
     * Format: Base64-like alphanumeric string (~40 characters).
     */
    private fun generateAccessToken(): String {
        val bytes = ByteArray(30)
        random.nextBytes(bytes)
        return base64Encoder.encodeToString(bytes)
    }

    /**
     * Generates a refresh token in Naver CHZZK format.
     * Format: Base64-like alphanumeric string (~48 characters).
     */
    private fun generateRefreshToken(): String {
        val bytes = ByteArray(36)
        random.nextBytes(bytes)
        return base64Encoder.encodeToString(bytes)
    }

    /**
     * Registers an authorization code for testing purposes.
     *
     * @param code The authorization code to register.
     * @param state The state parameter for CSRF protection (typically mcUuid).
     * @param channelId The channel ID associated with the code. If null, a new channel ID is generated.
     * @return The channel ID associated with the code.
     */
    fun registerAuthCode(code: String, state: String, channelId: String? = null): String {
        val resolvedChannelId = channelId ?: generateChannelId()
        validAuthCodes[code] = AuthCodeData(resolvedChannelId, state)
        return resolvedChannelId
    }

    /**
     * Pre-registers a token for testing the UseToken login method.
     *
     * @param accessToken The access token to register.
     * @param refreshToken The refresh token to register.
     * @param channelId The channel ID associated with the token. If null, a new channel ID is generated.
     * @param mcUuid The Minecraft UUID to associate with the token.
     * @param expireTime The expiration time for the token. If null, uses the default expiration.
     * @return The created [UserToken].
     */
    fun registerToken(
        accessToken: String,
        refreshToken: String,
        channelId: String? = null,
        mcUuid: UUID = UUID.randomUUID(),
        expireTime: Instant? = null,
    ): UserToken {
        val resolvedChannelId = channelId ?: generateChannelId()
        val resolvedExpireTime = expireTime ?: Instant.now().plus(tokenExpirationDuration)

        val token = UserToken(
            channelId = resolvedChannelId,
            mcUuid = mcUuid,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expireTime = resolvedExpireTime,
        )

        issuedTokens[accessToken] = token
        validRefreshTokens[refreshToken] = accessToken
        return token
    }

    fun registerToken(token: UserToken) {
        registerToken(token.accessToken, token.refreshToken, token.channelId, token.mcUuid, token.expireTime)
    }

    override suspend fun login(loginMethod: UserLoginMethod): ChzzkUserData? {
        return when (val method = loginMethod.method) {
            is LoginMethod.CreateToken -> handleCreateToken(method.code, method.state)
            is LoginMethod.UseToken -> handleUseToken(method.accessToken, method.refreshToken)
        }
    }

    private fun handleCreateToken(code: String, state: String): ChzzkUserData? {
        val authCodeData = validAuthCodes.remove(code) ?: return null

        // Validate state parameter (CSRF protection per OAuth 2.0)
        if (authCodeData.state != state) {
            return null
        }

        val accessToken = generateAccessToken()
        val refreshToken = generateRefreshToken()
        val expireTime = Instant.now().plus(tokenExpirationDuration)

        validRefreshTokens[refreshToken] = accessToken

        return ChzzkUserData(
            userId = authCodeData.channelId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expireTime = expireTime,
        )
    }

    private fun handleUseToken(accessToken: String, refreshToken: String?): ChzzkUserData? {
        val existingToken = issuedTokens[accessToken]

        // Per OAuth 2.0 standard: only return valid non-expired tokens.
        // If token is expired or not found, return null.
        // Client must explicitly request token refresh via separate refresh grant.
        if (existingToken != null && Instant.now().isBefore(existingToken.expireTime)) {
            return ChzzkUserData(
                userId = existingToken.channelId,
                accessToken = existingToken.accessToken,
                refreshToken = existingToken.refreshToken,
                expireTime = existingToken.expireTime,
            )
        }

        return null
    }

    override suspend fun connectSession(): ChzzkSessionGateway {
        return TestChzzkSessionGateway()
    }

    /**
     * Clears all stored state (tokens, auth codes, etc.).
     */
    fun reset() {
        validAuthCodes.clear()
        issuedTokens.clear()
        validRefreshTokens.clear()
    }
}
