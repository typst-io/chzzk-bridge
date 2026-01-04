package io.typst.chzzk.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

fun createTestScope(): CoroutineScope =  CoroutineScope(Job() + Dispatchers.Default)