package com.wichat.data.remote

import com.wichat.crypto.BlobStore
import com.wichat.data.prefs.AccountStore
import com.wichat.model.WireJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class RelayException(val code: Int, message: String) : IOException(message)

/**
 * REST side of the relay: registration, prekey distribution and the encrypted blob store.
 * Messages go over the WebSocket.
 */
class RelayApi(
    private val http: OkHttpClient,
    private val accounts: AccountStore,
) : BlobStore {

    override suspend fun createBlob(size: Long, chunkCount: Int): String =
        call(
            authed("/v1/blobs").post(json(CreateBlobRequest.serializer(), CreateBlobRequest(size, chunkCount))).build(),
            CreateBlobResponse.serializer(),
        ).blobId

    override suspend fun putChunk(blobId: String, index: Int, data: ByteArray) {
        call<Unit>(authed("/v1/blobs/$blobId/chunks/$index").put(data.toRequestBody(OCTET_STREAM)).build(), null)
    }

    override suspend fun getChunk(blobId: String, index: Int): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(authed("/v1/blobs/$blobId/chunks/$index").get().build()).execute().use { response ->
            if (!response.isSuccessful) throw RelayException(response.code, "Chunk $index unavailable")
            response.body?.bytes() ?: throw RelayException(response.code, "Empty chunk")
        }
    }

    suspend fun register(relayUrl: String, request: RegisterRequest): RegisterResponse =
        call(
            Request.Builder().url("$relayUrl/v1/register")
                .post(json(RegisterRequest.serializer(), request)).build(),
            RegisterResponse.serializer(),
        )

    /** Fetches [userId]'s prekey bundle; the relay hands out each one-time prekey only once. */
    suspend fun fetchPreKeyBundle(userId: String): PreKeyBundleDto =
        call(authed("/v1/keys/$userId").get().build(), PreKeyBundleDto.serializer())

    suspend fun preKeyCount(): Int =
        call(authed("/v1/keys/count").get().build(), PreKeyCountResponse.serializer()).count

    suspend fun uploadPreKeys(preKeys: List<PreKeyDto>) {
        call<Unit>(authed("/v1/keys/prekeys").post(json(UploadPreKeysRequest.serializer(), UploadPreKeysRequest(preKeys))).build(), null)
    }

    suspend fun uploadSignedPreKey(signedPreKey: SignedPreKeyDto) {
        call<Unit>(authed("/v1/keys/signed").put(json(SignedPreKeyDto.serializer(), signedPreKey)).build(), null)
    }

    private fun authed(path: String): Request.Builder {
        val account = accounts.current ?: throw RelayException(401, "Not registered")
        if (!account.hasRelay) throw RelayException(0, "No relay server set up (offline account)")
        return Request.Builder().url(account.relayUrl + path).header("Authorization", "Bearer ${account.token}")
    }

    private fun <T> json(serializer: KSerializer<T>, value: T): RequestBody =
        WireJson.encodeToString(serializer, value).toRequestBody(JSON)

    private suspend fun <T> call(request: Request, serializer: KSerializer<T>?): T = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { WireJson.decodeFromString(ErrorResponse.serializer(), body).error }
                    .getOrDefault("HTTP ${response.code}")
                throw RelayException(response.code, message)
            }
            @Suppress("UNCHECKED_CAST")
            if (serializer == null) Unit as T else WireJson.decodeFromString(serializer, body)
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
