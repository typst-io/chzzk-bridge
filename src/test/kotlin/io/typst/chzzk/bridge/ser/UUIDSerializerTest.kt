package io.typst.chzzk.bridge.ser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertEquals

class UUIDSerializerTest {
    @Serializable
    data class Obj(val uuid: UUIDAsString)

    @Test
    fun test() {
        val uuid = UUID.randomUUID()
        val obj = Obj(uuid)
        val json = Json.encodeToString(obj)
        val deserialized = Json.decodeFromString<Obj>(json)
        assertEquals(obj, deserialized)
    }
}