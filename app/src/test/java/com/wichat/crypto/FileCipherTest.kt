package com.wichat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import kotlin.random.Random

class FileCipherTest {

    @Test
    fun roundTripsMultiChunkFile() {
        val plain = Random.nextBytes(FileCipher.CHUNK_SIZE * 3 + 123)
        val keys = FileCipher.newKeys()
        val chunks = mutableListOf<ByteArray>()

        val result = FileCipher.encryptStream(keys, plain.inputStream()) { index, data ->
            assertEquals(chunks.size, index)
            chunks += data
        }

        assertEquals(4, result.chunkCount)
        assertEquals(plain.size.toLong(), result.sizeBytes)
        val out = ByteArrayOutputStream()
        val digest = FileCipher.decryptStream(keys, chunks.asSequence(), out)
        assertArrayEquals(plain, out.toByteArray())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(plain), digest)
        assertArrayEquals(result.sha256, digest)
    }

    @Test(expected = AEADBadTagException::class)
    fun rejectsTamperedChunk() {
        val keys = FileCipher.newKeys()
        val encrypted = FileCipher.encryptChunk(keys, 0, "hello".encodeToByteArray())
        encrypted[0] = (encrypted[0].toInt() xor 1).toByte()
        FileCipher.decryptChunk(keys, 0, encrypted)
    }

    @Test(expected = AEADBadTagException::class)
    fun rejectsReorderedChunks() {
        val keys = FileCipher.newKeys()
        val encrypted = FileCipher.encryptChunk(keys, 0, "first".encodeToByteArray())
        FileCipher.decryptChunk(keys, 1, encrypted)
    }
}
