package io.typst.chzzk.bridge

import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.plugins.sse.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.coroutines.test.TestResult

fun createTestApplication(
    service: ChzzkService,
    f: suspend ApplicationTestBuilder.() -> Unit,
): TestResult = testApplication(service.coroutineScope.coroutineContext) {
    environment {
        config = MapApplicationConfig(
            "ktor.development" to "true",
        )
    }
    application {
        businessModule(service)
    }
    client = createClient {
        install(Resources)
        install(ContentNegotiation) {
            json()
        }
        install(SSE)
    }
    f()
}