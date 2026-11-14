package com.wichat.model

import java.security.SecureRandom
import java.util.UUID

private val random = SecureRandom()

/** UUIDv7: time-ordered, so envelope ids sort by creation time. */
fun uuidV7(now: Long = System.currentTimeMillis()): String {
    val bytes = ByteArray(16).also(random::nextBytes)
    for (i in 0 until 6) bytes[i] = (now ushr (8 * (5 - i))).toByte()
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    var msb = 0L
    var lsb = 0L
    for (i in 0 until 8) msb = (msb shl 8) or (bytes[i].toLong() and 0xFF)
    for (i in 8 until 16) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFF)
    return UUID(msb, lsb).toString()
}

/** Usernames are case-insensitive identifiers on the relay. */
fun normalizeUserId(raw: String): String = raw.trim().lowercase()
