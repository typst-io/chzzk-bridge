package io.typst.chzzk.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionStoreTest {
    val uuid: UUID = UUID.randomUUID()
    lateinit var repo: SessionStore

    @BeforeEach
    fun setup() {
        repo = SessionStore(random)
    }

    @Test
    fun acquire(): Unit = runBlocking {
        // init
        val nonTargetUuid = UUID.randomUUID()
        assertNull(repo.removeSession(uuid))
        assertNull(repo.removeSession(nonTargetUuid))

        // add session
        val resultA = repo.acquireSession(uuid)
        assertNotNull(resultA)
        assertTrue(resultA.second)

        // duplicate session
        val resultB = repo.acquireSession(uuid)
        assertNotNull(resultB)
        assertFalse(resultB.second)

        // non-target invariant
        assertNull(repo.removeSession(nonTargetUuid))

        // remove session
        assertNotNull(repo.removeSession(uuid))
    }

    @Test
    fun `concurrent acquire`(): Unit = runBlocking(Dispatchers.Default) {
        // add session concurrently
        val count = 10
        val uuidB = UUID.randomUUID()
        val results = (0 until count)
            .flatMap {
                listOf(async {
                    repo.acquireSession(uuid)
                }, async {
                    repo.acquireSession(uuidB)
                })
            }
            .awaitAll()
            .sumOf {
                assertNotNull(it)
                if (it.second) 1 else 0
            }
        assertEquals(2, results)

        // remove concurrently
        val removedCount = (0 until count)
            .flatMap {
                listOf(
                    async {
                        repo.removeSession(uuid)
                    },
                    async {
                        repo.removeSession(uuidB)
                    }
                )
            }
            .awaitAll()
            .sumOf {
                if (it != null) {
                    1
                } else 0
            }
        assertEquals(2, removedCount)
    }
}