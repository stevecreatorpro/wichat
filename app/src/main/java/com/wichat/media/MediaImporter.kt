package com.wichat.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.wichat.crypto.AttachmentCodec
import com.wichat.model.AttachmentKind
import java.io.File
import java.io.IOException

class ImportedFile(
    val file: File,
    val fileName: String,
    val mime: String,
    val size: Long,
    val sha256: ByteArray,
    val durationMs: Long?,
)

/**
 * Copies a picked file into app-private storage before it is encrypted and sent.
 * Photos are downscaled and re-encoded, which also strips EXIF metadata such as GPS location.
 */
class MediaImporter(private val context: Context) {

    fun attachmentDir(messageId: String): File =
        File(context.filesDir, "attachments/$messageId").apply { mkdirs() }

    fun import(messageId: String, uri: Uri, kind: AttachmentKind, knownDurationMs: Long? = null): ImportedFile {
        val resolver = context.contentResolver
        var name: String? = null
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) declaredSize = c.getLong(1)
            }
        }
        if (declaredSize > MAX_FILE_BYTES) throw IOException("Files can be up to 2 GB")
        val mime = resolver.getType(uri) ?: guessMime(name ?: uri.lastPathSegment)
        val fileName = FileNames.safe(name ?: uri.lastPathSegment, fallback = defaultName(kind))
        val dir = attachmentDir(messageId)

        val (file, finalMime) = if (kind == AttachmentKind.IMAGE && mime != "image/gif") {
            val target = File(dir, fileName.substringBeforeLast('.') + ".jpg")
            compressImage(uri, target)
            target to "image/jpeg"
        } else {
            val target = File(dir, fileName)
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("Can't read that file")
            target to mime
        }
        if (file.length() > MAX_FILE_BYTES) {
            file.delete()
            throw IOException("Files can be up to 2 GB")
        }

        val duration = knownDurationMs ?: if (kind == AttachmentKind.AUDIO || kind == AttachmentKind.VOICE_NOTE || kind == AttachmentKind.VIDEO) {
            readDuration(file)
        } else null

        return ImportedFile(
            file = file,
            fileName = file.name,
            mime = finalMime,
            size = file.length(),
            sha256 = file.inputStream().use(AttachmentCodec::sha256),
            durationMs = duration,
        )
    }

    private fun compressImage(uri: Uri, target: File) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IOException("That image can't be read")

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("That image can't be read")

        val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        val scale = minOf(1f, MAX_IMAGE_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val output = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        target.outputStream().use { output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (output !== decoded) output.recycle()
        decoded.recycle()
    }

    private fun readDuration(file: File): Long? = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                release()
            }
        }
    }.getOrNull()

    private fun guessMime(name: String?): String {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun defaultName(kind: AttachmentKind) = when (kind) {
        AttachmentKind.IMAGE -> "photo.jpg"
        AttachmentKind.VOICE_NOTE -> "voice-note"
        AttachmentKind.AUDIO -> "audio"
        AttachmentKind.VIDEO -> "video"
        AttachmentKind.DOCUMENT -> "document"
    }

    companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_IMAGE_EDGE = 1600
        private const val JPEG_QUALITY = 82
    }
}
