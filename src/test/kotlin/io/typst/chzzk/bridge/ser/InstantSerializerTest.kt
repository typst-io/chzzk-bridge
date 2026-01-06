package io.typst.chzzk.bridge.ser

import io.typst.chzzk.bridge.nowInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class InstantSerializerTest {
    @Serializable
    data class Obj(val instant: InstantAsLong)

    @Test
    fun test() {
        val instant = nowInstant()
        val obj = Obj(instant)
        val json = Json.encodeToString(obj)
        val deserialized = Json.decodeFromString<Obj>(json)
        assertEquals(obj, deserialized)
    }
}