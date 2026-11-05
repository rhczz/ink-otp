package io.github.rhczz.inkotp.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object SpecVectors {

    val json: Json = Json { ignoreUnknownKeys = true }

    val root by lazy {
        val text = checkNotNull(javaClass.getResource("/vectors.json")) {
            "spec/vectors.json is missing from the test classpath"
        }.readText()
        json.parseToJsonElement(text).jsonObject
    }

    fun array(name: String) = root.getValue(name).jsonArray
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        out[i] = ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte()
    }
    return out
}

internal fun ByteArray.toHex(): String {
    val chars = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(chars[value ushr 4]).append(chars[value and 0x0F])
    }
    return out.toString()
}

internal fun JsonObjectInt(obj: kotlinx.serialization.json.JsonObject, name: String, default: Int): Int =
    obj[name]?.jsonPrimitive?.content?.toIntOrNull() ?: default
