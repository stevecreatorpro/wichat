package com.wichat.ui.conversation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.wichat.data.db.AttachmentEntity
import com.wichat.data.db.TransferState
import com.wichat.media.FileNames
import com.wichat.model.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Everything an attachment bubble shows: preview, file info, and transfer progress or actions. */
@Composable
fun AttachmentContent(
    attachment: AttachmentEntity,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val ready = attachment.localPath != null &&
        (attachment.outgoing || attachment.state == TransferState.DONE)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            attachment.kind == AttachmentKind.IMAGE && ready ->
                ImagePreview(attachment.localPath!!) { openAttachment(context, attachment) }
            (attachment.kind == AttachmentKind.VOICE_NOTE || attachment.kind == AttachmentKind.AUDIO) && ready ->
                AudioPlayer(attachment.localPath!!, attachment.durationMs)
            else -> FileRow(attachment, onClick = {
                when {
                    ready -> openAttachment(context, attachment)
                    attachment.state == TransferState.PENDING_DOWNLOAD -> onDownload()
                    attachment.state == TransferState.FAILED -> if (attachment.outgoing) onRetry() else onDownload()
                }
            })
        }
        TransferStatus(attachment, onDownload, onRetry)
    }
}

@Composable
private fun FileRow(attachment: AttachmentEntity, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (attachment.kind) {
                AttachmentKind.IMAGE -> Icons.Default.Image
                AttachmentKind.AUDIO, AttachmentKind.VOICE_NOTE -> Icons.Default.Audiotrack
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            modifier = Modifier.size(36.dp),
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text(attachment.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                FileNames.humanSize(attachment.size) + (attachment.durationMs?.let { " · " + FileNames.duration(it) } ?: ""),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun TransferStatus(attachment: AttachmentEntity, onDownload: () -> Unit, onRetry: () -> Unit) {
    val progress = attachment.chunksDone.toFloat() / attachment.chunkCount.coerceAtLeast(1)
    when (attachment.state) {
        TransferState.UPLOADING, TransferState.DOWNLOADING -> Column {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(200.dp))
            Text(
                (if (attachment.state == TransferState.UPLOADING) "Encrypting and uploading " else "Downloading ") +
                    "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        TransferState.PENDING_DOWNLOAD -> Row(
            Modifier.clickable(onClick = onDownload),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
            Text(" Download ${FileNames.humanSize(attachment.size)}", style = MaterialTheme.typography.labelMedium)
        }
        TransferState.FAILED -> Row(
            Modifier.clickable(onClick = if (attachment.outgoing) onRetry else onDownload),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            Text(
                if (attachment.outgoing) " Upload failed. Tap to retry" else " Download failed. Tap to retry",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TransferState.UPLOADED, TransferState.DONE -> Unit
    }
}

@Composable
private fun ImagePreview(path: String, onClick: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { decodeSampled(path, 720) }
    }
    Box(
        Modifier.width(240.dp).height(240.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth()) }
            ?: Icon(Icons.Default.Image, null)
    }
}

@Composable
private fun AudioPlayer(path: String, durationMs: Long?) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    val total = durationMs ?: 0L

    DisposableEffect(path) {
        onDispose {
            player?.release()
            player = null
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = player?.currentPosition?.toLong() ?: 0L
            delay(200)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            val p = player ?: MediaPlayer().apply {
                setDataSource(path)
                prepare()
                setOnCompletionListener {
                    playing = false
                    position = 0
                    seekTo(0)
                }
            }.also { player = it }
            if (playing) p.pause() else p.start()
            playing = !playing
        }) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play")
        }
        Column(Modifier.width(170.dp)) {
            LinearProgressIndicator(
                progress = { if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                FileNames.duration(if (playing || position > 0) position else total),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun decodeSampled(path: String, maxEdge: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/** Opens the decrypted file in another app, only ever after the user taps it. */
fun openAttachment(context: Context, attachment: AttachmentEntity) {
    val path = attachment.localPath ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, attachment.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can open ${attachment.fileName}", Toast.LENGTH_SHORT).show()
    }
}
