package com.wichat.crypto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException
import kotlin.random.Random

class AttachmentCodecTest {

    /** Stands in for the relay's blob store and lets tests inspect or tamper with what it holds. */
    private class MemoryBlobStore : BlobStore {
        val chunks = mutableMapOf<Pair<String, Int>, ByteArray>()
        var puts = 0
        override suspend fun createBlob(size: Long, chunkCount: Int) = "blob-${chunks.size}"
        override suspend fun putChunk(blobId: String, index: Int, data: ByteArray) {
            puts++
            chunks[blobId to index] = data
        }
        override suspend fun getChunk(blobId: String, index: Int) = chunks.getValue(blobId to index)
    }

    private val size = FileCipher.CHUNK_SIZE * 2L + 777
    private val plain = Random.nextBytes(size.toInt())
    private val keys = FileCipher.newKeys()
    private val sha = AttachmentCodec.sha256(plain.inputStream())
    private val chunkCount = AttachmentCodec.chunkCount(size)

    private suspend fun uploadAll(store: MemoryBlobStore, blobId: String = "b") =
        AttachmentCodec.upload(plain.inputStream(), keys, store, blobId, chunkCount)

    private suspend fun downloadAll(store: BlobStore, blobId: String = "b", expectedSize: Long = size): ByteArray {
        val out = ByteArrayOutputStream()
        AttachmentCodec.download(store, blobId, keys, chunkCount, expectedSize, sha, out)
        return out.toByteArray()
    }

    @Test
    fun roundTripsThroughStoreWhichOnlySeesCiphertext() = runTest {
        val store = MemoryBlobStore()
        uploadAll(store)
        assertEquals(3, store.chunks.size)
        // No 32-byte window of the plaintext appears in what the relay stores.
        val window = plain.copyOfRange(1000, 1032).toList()
        store.chunks.values.forEach { assertFalse(it.toList().windowed(32).any { w -> w == window }) }
        assertArrayEquals(plain, downloadAll(store))
    }

    @Test
    fun resumesInterruptedUploadWithoutResending() = runTest {
        val store = MemoryBlobStore()
        try {
            AttachmentCodec.upload(plain.inputStream(), keys, store, "b", chunkCount, startAt = 0) { done ->
                if (done == 1) throw java.io.IOException("connection lost")
            }
            fail("Upload should have been interrupted")
        } catch (expected: java.io.IOException) {
        }
        val firstAttemptPuts = store.puts
        AttachmentCodec.upload(plain.inputStream(), keys, store, "b", chunkCount, startAt = 1)
        assertEquals(1, firstAttemptPuts)
        assertEquals(3, store.puts)
        assertArrayEquals(plain, downloadAll(store))
    }

    @Test
    fun emptyFileStillWorks() = runTest {
        val store = MemoryBlobStore()
        val empty = ByteArray(0)
        AttachmentCodec.upload(empty.inputStream(), keys, store, "e", AttachmentCodec.chunkCount(0))
        val out = ByteArrayOutputStream()
        AttachmentCodec.download(store, "e", keys, 1, 0, AttachmentCodec.sha256(empty.inputStream()), out)
        assertEquals(0, out.size())
    }

    @Test
    fun tamperedChunkIsRejected() = runTest {
        val store = MemoryBlobStore()
        uploadAll(store)
        store.chunks.getValue("b" to 1)[10] = (store.chunks.getValue("b" to 1)[10] + 1).toByte()
        expectFailure<AEADBadTagException> { downloadAll(store) }
    }

    @Test
    fun swappedChunksAreRejected() = runTest {
        val store = MemoryBlobStore()
        uploadAll(store)
        val first = store.chunks.getValue("b" to 0)
        store.chunks["b" to 0] = store.chunks.getValue("b" to 1)
        store.chunks["b" to 1] = first
        expectFailure<AEADBadTagException> { downloadAll(store) }
    }

    @Test
    fun truncatedFileIsRejected() = runTest {
        val store = MemoryBlobStore()
        uploadAll(store)
        // Relay drops the last chunk and the attacker claims a shorter chunk count.
        val out = ByteArrayOutputStream()
        expectFailure<IntegrityException> {
            AttachmentCodec.download(store, "b", keys, chunkCount - 1, size, sha, out)
        }
    }

    @Test
    fun wrongKeyIsRejected() = runTest {
        val store = MemoryBlobStore()
        uploadAll(store)
        val out = ByteArrayOutputStream()
        expectFailure<AEADBadTagException> {
            AttachmentCodec.download(store, "b", FileCipher.newKeys(), chunkCount, size, sha, out)
        }
    }

    @Test
    fun chunkCountMatchesChunkSize() {
        assertEquals(1, AttachmentCodec.chunkCount(0))
        assertEquals(1, AttachmentCodec.chunkCount(FileCipher.CHUNK_SIZE.toLong()))
        assertEquals(2, AttachmentCodec.chunkCount(FileCipher.CHUNK_SIZE + 1L))
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }
}
