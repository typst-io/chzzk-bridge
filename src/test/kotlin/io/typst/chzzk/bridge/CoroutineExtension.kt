package io.typst.chzzk.bridge

import kotlinx.coroutines.*

fun createTestScope(): CoroutineScope = CoroutineScope(Job() + Dispatchers.Default)

fun CoroutineScope.runBlockingWithContext(block: suspend CoroutineScope.() -> Unit) {
    val ctx = coroutineContext
    runBlocking {
        withContext(ctx.minusKey(Job)) {
            block()
        }
    }
}