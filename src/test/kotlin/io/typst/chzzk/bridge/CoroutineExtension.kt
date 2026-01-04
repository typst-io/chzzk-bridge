package io.typst.chzzk.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun createTestScope(): CoroutineScope =  CoroutineScope(Job() + Dispatchers.Default)

fun CoroutineScope.launchAndJoin(block: suspend CoroutineScope.() -> Unit) {
    val job = launch {
        block()
    }
    runBlocking {
        job.join()
    }
}