package com.wichat.crypto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Where encrypted chunks are kept between sender and recipient (the relay's blob store). */
interface BlobStore {
    suspend fun createBlob(size: Long, chunkCount: Int): String
    suspend fun putChunk(blobId: String, index: Int, data: ByteArray)
    suspend fun getChunk(blobId: String, index: Int): ByteArray
}

class IntegrityException(message: String) : IOException(message)

/**
 * Moves a file through [BlobStore] as independently encrypted chunks (SYSTEM_DESIGN.md 4.4).
 * The store only ever holds AES-256-GCM ciphertext; the key travels inside the E2EE message.
 */
object AttachmentCodec {

    fun chunkCount(size: Long): Int = maxOf(1, ((size + FileCipher.CHUNK_SIZE - 1) / FileCipher.CHUNK_SIZE).toInt())

    fun sha256(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest()
    }

    /**
     * Encrypts and uploads chunks [startAt] until [chunkCount] (earlier ones were uploaded
     * before an interruption). [onChunkDone] receives the number of chunks now stored.
     */
    suspend fun upload(
        input: InputStream,
        keys: FileCipher.FileKeys,
        store: BlobStore,
        blobId: String,
        chunkCount: Int,
        startAt: Int = 0,
        onChunkDone: suspend (done: Int) -> Unit = {},
    ) {
        val buffer = ByteArray(FileCipher.CHUNK_SIZE)
        for (index in 0 until chunkCount) {
            val read = readFully(input, buffer)
            if (index < startAt) continue
            val encrypted = FileCipher.encryptChunk(keys, index, buffer.copyOf(read))
            store.putChunk(blobId, index, encrypted)
            onChunkDone(index + 1)
        }
    }

    /**
     * Downloads, decrypts and writes every chunk to [output], then checks the whole-file SHA-256.
     * Throws [javax.crypto.AEADBadTagException] for a tampered chunk, [IntegrityException] for a
     * swapped, truncated or padded file.
     */
    suspend fun download(
        store: BlobStore,
        blobId: String,
        keys: FileCipher.FileKeys,
        chunkCount: Int,
        expectedSize: Long,
        expectedSha256: ByteArray,
        output: OutputStream,
        onChunkDone: suspend (done: Int) -> Unit = {},
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        for (index in 0 until chunkCount) {
            val plain = FileCipher.decryptChunk(keys, index, store.getChunk(blobId, index))
            total += plain.size
            if (total > expectedSize) throw IntegrityException("File larger than announced")
            digest.update(plain)
            output.write(plain)
            onChunkDone(index + 1)
        }
        if (total != expectedSize) throw IntegrityException("File size mismatch")
        if (!MessageDigest.isEqual(digest.digest(), expectedSha256)) throw IntegrityException("File hash mismatch")
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) break
            offset += n
        }
        return offset
    }
}
