package io.typst.chzzk.bridge

import io.typst.chzzk.bridge.api.ApiFetchChzzkMessage
import io.typst.chzzk.bridge.persis.UserToken
import io.typst.chzzk.bridge.repository.SQLiteBridgeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals

class SQLiteBridgeRepositoryTest {
    lateinit var testScope: CoroutineScope
    lateinit var dbDir: File
    lateinit var repo: SQLiteBridgeRepository

    @BeforeEach
    fun setup(@TempDir dir: File) {
        testScope = CoroutineScope(Job() + Dispatchers.Default)
        dbDir = dir
        repo = SQLiteBridgeRepository(dir)
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        repo.close()
    }

    @Test
    fun `set token`(): Unit = runBlocking {
        val targetUuid = UUID.randomUUID()
        val nonTargetUuid = UUID.randomUUID()
        assertEquals(null, repo.getToken(targetUuid))
        assertEquals(null, repo.getToken(nonTargetUuid))
        val token = UserToken("testChannel", targetUuid, "test", "test", nowInstant())
        repo.setToken(token)
        assertEquals(token, repo.getToken(targetUuid))
        assertEquals(null, repo.getToken(nonTargetUuid))
    }

    @Test
    fun `add messages`(): Unit = runBlocking {
        val targetUuid = UUID.randomUUID()
        val nonTargetUuid = UUID.randomUUID()
        assertEquals(emptyList(), repo.getMessages(targetUuid, 1))
        assertEquals(emptyList(), repo.getMessages(nonTargetUuid, 1))
        val msg = ApiFetchChzzkMessage(1, targetUuid.toString(),  "sender", "sender", "msg", nowInstant(), 10)
        val msgB = msg.copy(id = 2, payAmount = 0, message = "msg2")
        repo.setToken(UserToken(targetUuid.toString(), targetUuid, "AT", "RT", nowInstant()))
        val jobA = launch {
            repo.addMessage(msg)
        }
        val jobB = launch {
            repo.addMessage(msgB)
        }
        jobA.join()
        jobB.join()
        assertEquals(setOf(msg, msgB), repo.getMessages(targetUuid, 1).toSet())
        assertEquals(emptyList(), repo.getMessages(nonTargetUuid, 1))

        // add again
        repo.addMessage(msg)
        assertEquals(listOf(msg.copy(id = 3)), repo.getMessages(targetUuid, 3))
    }
}