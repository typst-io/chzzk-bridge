package io.typst.chzzk.bridge.endpoint

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.typst.chzzk.bridge.ChzzkService
import io.typst.chzzk.bridge.CreateSessionResult
import io.typst.chzzk.bridge.OAuthCallbackPathParameters
import io.typst.chzzk.bridge.auth.LoginMethod
import io.typst.chzzk.bridge.auth.UserLoginMethod
import kotlinx.coroutines.future.await

object OAuthEndpoints {
    private val successHtmlTemplate: String by lazy {
        loadResource("static/oauth-success.html")
    }

    private val errorHtmlTemplate: String by lazy {
        loadResource("static/oauth-error.html")
    }

    private fun loadResource(path: String): String =
        OAuthEndpoints::class.java.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: error("Resource not found: $path")

    private fun renderPermissionsHtml(permissions: Map<String, String>): String =
        permissions.toList().joinToString("\n") { (name, desc) ->
            """
            <li class="permission-item">
                <svg class="permission-icon" viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/>
                    <polyline points="22 4 12 14.01 9 11.01"/>
                </svg>
                $name
                <span class="permission-desc">$desc</span>
            </li>
            """.trimIndent()
        }

    private fun renderSuccessHtml(permissions: Map<String, String>): String =
        successHtmlTemplate.replace("{{PERMISSIONS}}", renderPermissionsHtml(permissions))

    suspend fun onRequest(
        ctx: RoutingContext,
        x: OAuthCallbackPathParameters,
        service: ChzzkService,
    ) {
        val bridgeRepo = service.bridgeRepository
        val call = ctx.call
        val code = x.code.takeIf { it.isNotEmpty() }
        val state = x.state
        if (code == null) {
            call.respondErrorHtml(
                HttpStatusCode.BadRequest,
                "잘못된 요청",
                "인증 코드가 없습니다.<br>연동을 다시 시도해주세요."
            )
            return
        }
        val uuid = service.removeState(state)
        if (uuid == null) {
            call.respondErrorHtml(
                HttpStatusCode.BadRequest,
                "잘못된 요청",
                "요청이 유효하지 않거나 만료되었습니다.<br>연동을 다시 시도해주세요."
            )
            return
        }

        if (bridgeRepo.getToken(uuid) != null) {
            call.respondErrorHtml(
                HttpStatusCode.NotAcceptable,
                "이미 연동됨",
                "이 계정은 이미 치지직과 연동되어 있습니다."
            )
            return
        }

        val loginMethod = UserLoginMethod(uuid, LoginMethod.CreateToken(code, state))
        when (val result = service.createSession(loginMethod)) {
            is CreateSessionResult.Success -> {
                if (result.created) {
                    result.session.await()
                    call.respondText(
                        renderSuccessHtml(service.config.scopes.associate { it.label to it.description }),
                        ContentType.Text.Html,
                        HttpStatusCode.OK
                    )
                } else {
                    call.respondErrorHtml(
                        HttpStatusCode.NoContent,
                        "세션 충돌",
                        "이 계정에 이미 세션이 존재합니다.<br>잠시 후 다시 시도해주세요."
                    )
                }
            }

            is CreateSessionResult.LoginFailed -> {
                call.respondErrorHtml(
                    HttpStatusCode.InternalServerError,
                    "서버 오류",
                    "예기치 않은 오류가 발생했습니다.<br>잠시 후 다시 시도해주세요."
                )
            }

            is CreateSessionResult.RefreshTokenExpired -> {
                call.respondErrorHtml(
                    HttpStatusCode.InternalServerError,
                    "서버 오류",
                    "예기치 않은 오류가 발생했습니다.<br>잠시 후 다시 시도해주세요."
                )
            }
        }
    }

    private suspend fun RoutingCall.respondErrorHtml(
        status: HttpStatusCode,
        title: String,
        message: String,
    ) {
        val html = errorHtmlTemplate
            .replace("{{TITLE}}", title)
            .replace("{{MESSAGE}}", "$message<br>${status.value} ${status.description}")
        respondText(html, ContentType.Text.Html, status)
    }
}