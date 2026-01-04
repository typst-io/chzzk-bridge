package io.typst.chzzk.bridge

import io.typst.chzzk.bridge.chzzk.ChzzkSessionGateway
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicReference
import java.util.random.RandomGenerator

data class UUIDState(
    val uuid: UUID,
) {
    val expireTime: Instant = Instant.now().plusSeconds(30)

    fun isValid(now: Instant = Instant.now()): Boolean =
        now < expireTime
}

class SessionStore(val random: RandomGenerator) {
    private val sessions: ConcurrentMap<UUID, CompletableFuture<ChzzkSessionGateway>> = ConcurrentHashMap()
    private val uuidBySessionF: AtomicReference<PersistentMap<String, UUIDState>> = AtomicReference(persistentMapOf())
    private val base64Encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    // private http api
    fun issueState(uuid: UUID): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val string = base64Encoder.encodeToString(bytes)
        uuidBySessionF.updateAndGet { map ->
            val old = map.entries.firstOrNull { it.value == uuid }
            map.put(string, UUIDState(uuid)).remove(old?.key ?: "")
        }
        return string
    }

    // public http api
    fun removeState(state: String): UUID? {
        val uuidBySession = uuidBySessionF.getAndUpdate {
            it.remove(state)
        }
        return uuidBySession[state]?.takeIf { it.isValid() }?.uuid
    }

    fun acquireSession(
        uuid: UUID,
    ): Pair<CompletableFuture<ChzzkSessionGateway>, Boolean> {
        val future = CompletableFuture<ChzzkSessionGateway>()
        val old = sessions.putIfAbsent(uuid, future)
        return if (old != null) {
            old to false
        } else future to true
    }

    fun removeSession(uuid: UUID): CompletableFuture<ChzzkSessionGateway>? {
        return sessions.remove(uuid)
    }
}