package com.wichat.data.remote

import kotlinx.serialization.Serializable

/** All key material is base64. Only public halves ever leave the device. */
@Serializable
data class PreKeyDto(val keyId: Int, val publicKey: String)

@Serializable
data class SignedPreKeyDto(val keyId: Int, val publicKey: String, val signature: String)

@Serializable
data class RegisterRequest(
    val username: String,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberPreKey: SignedPreKeyDto,
    val preKeys: List<PreKeyDto>,
)

@Serializable
data class RegisterResponse(val token: String)

@Serializable
data class PreKeyBundleDto(
    val username: String,
    val deviceId: Int,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberPreKey: SignedPreKeyDto,
    val preKey: PreKeyDto? = null,
)

@Serializable
data class PreKeyCountResponse(val count: Int)

@Serializable
data class UploadPreKeysRequest(val preKeys: List<PreKeyDto>)

@Serializable
data class CreateBlobRequest(val size: Long, val chunkCount: Int)

@Serializable
data class CreateBlobResponse(val blobId: String)

@Serializable
data class ErrorResponse(val error: String)
