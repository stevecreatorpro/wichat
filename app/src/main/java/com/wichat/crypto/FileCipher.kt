package com.wichat.crypto

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chunked AES-256-GCM for documents, images and audio.
 * Each chunk gets nonce = nonceBase XOR chunkIndex, so chunks can be uploaded,
 * downloaded and verified independently (resumable transfers).
 */
object FileCipher {
    const val CHUNK_SIZE = 256 * 1024
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    class FileKeys(val fileKey: ByteArray, val nonceBase: ByteArray)

    class Result(val sha256: ByteArray, val sizeBytes: Long, val chunkCount: Int)

    fun newKeys(): FileKeys = FileKeys(
        fileKey = ByteArray(32).also(random::nextBytes),
        nonceBase = ByteArray(12).also(random::nextBytes),
    )

    fun encryptChunk(keys: FileKeys, index: Int, plain: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, keys, index).doFinal(plain)

    /** Throws [javax.crypto.AEADBadTagException] if the chunk was tampered with or reordered. */
    fun decryptChunk(keys: FileKeys, index: Int, encrypted: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, keys, index).doFinal(encrypted)

    /** Encrypts [input] chunk by chunk, handing each encrypted chunk to [onChunk]. */
    fun encryptStream(keys: FileKeys, input: InputStream, onChunk: (index: Int, data: ByteArray) -> Unit): Result {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_SIZE)
        var index = 0
        var total = 0L
        while (true) {
            val read = readFully(input, buffer)
            if (read <= 0) break
            val plain = buffer.copyOf(read)
            digest.update(plain)
            total += read
            onChunk(index, encryptChunk(keys, index, plain))
            index++
        }
        return Result(digest.digest(), total, index)
    }

    /** Decrypts chunks in order into [output] and returns the plaintext SHA-256 for verification. */
    fun decryptStream(keys: FileKeys, chunks: Sequence<ByteArray>, output: OutputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        chunks.forEachIndexed { index, chunk ->
            val plain = decryptChunk(keys, index, chunk)
            digest.update(plain)
            output.write(plain)
        }
        return digest.digest()
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

    private fun cipher(mode: Int, keys: FileKeys, index: Int): Cipher {
        val nonce = keys.nonceBase.copyOf()
        for (i in 0 until 4) {
            val pos = nonce.size - 1 - i
            nonce[pos] = (nonce[pos].toInt() xor (index ushr (8 * i))).toByte()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(keys.fileKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        }
    }
}
