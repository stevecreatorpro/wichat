package com.wichat.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wichat.crypto.SessionManager
import com.wichat.data.db.MessageWithAttachment
import com.wichat.data.db.UserEntity
import com.wichat.data.prefs.AccountStore
import com.wichat.data.repo.ContactRepository
import com.wichat.data.repo.MessageRepository
import com.wichat.domain.SendMessageUseCase
import com.wichat.media.AttachmentTransfers
import com.wichat.media.FileNames
import com.wichat.media.VoiceRecorder
import com.wichat.model.AttachmentKind
import com.wichat.model.MessageContent
import com.wichat.model.MessageStatus
import com.wichat.model.ReceiptStatus
import com.wichat.service.Notifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val messages: MessageRepository,
    private val contacts: ContactRepository,
    private val send: SendMessageUseCase,
    private val transfers: AttachmentTransfers,
    private val recorder: VoiceRecorder,
    private val sessions: SessionManager,
    private val accounts: AccountStore,
    private val notifier: Notifier,
) : ViewModel() {
    val peerId: String = checkNotNull(savedState["peerId"])

    val messageList: StateFlow<List<MessageWithAttachment>> = messages.observeMessages(peerId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val peer: StateFlow<UserEntity?> = contacts.observe(peerId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var draft by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
        private set
    var safetyNumber by mutableStateOf<String?>(null)
        private set
    var recording by mutableStateOf(false)
        private set

    fun onVisible(visible: Boolean) {
        notifier.activeConversation.value = if (visible) peerId else null
    }

    /** Sends read receipts for anything that arrived while the chat is open. */
    fun markRead() = viewModelScope.launch {
        val ids = messages.markConversationRead(peerId)
        runCatching { send.sendReceipt(peerId, ids, ReceiptStatus.READ) }
    }

    fun sendDraft() {
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        viewModelScope.launch {
            runCatching { send(peerId, MessageContent.Text(text)) }
                .onFailure { error = it.message ?: "Couldn't send" }
        }
    }

    fun sendFile(uri: Uri, kind: AttachmentKind, durationMs: Long? = null) {
        viewModelScope.launch {
            runCatching { send.sendAttachment(peerId, uri, kind, durationMs) }
                .onFailure { error = it.message ?: "Couldn't send that file" }
        }
    }

    fun startRecording() {
        runCatching { recorder.start() }
            .onSuccess { recording = true }
            .onFailure { error = "Couldn't start the microphone" }
    }

    fun recordingElapsedMs() = recorder.elapsedMs()

    fun finishRecording() {
        recording = false
        val result = recorder.stop()
        if (result == null) {
            error = "Hold on a little longer to record a voice note"
            return
        }
        viewModelScope.launch {
            runCatching { send.sendAttachment(peerId, Uri.fromFile(result.file), AttachmentKind.VOICE_NOTE, result.durationMs) }
                .onFailure { error = it.message ?: "Couldn't send voice note" }
            withContext(Dispatchers.IO) { result.file.delete() }
        }
    }

    fun cancelRecording() {
        recording = false
        recorder.cancel()
    }

    fun download(id: String) = transfers.download(id)

    fun retryUpload(id: String) = send.retryAttachment(id)

    fun showSafetyNumber() = viewModelScope.launch {
        val me = accounts.current?.userId ?: return@launch
        safetyNumber = withContext(Dispatchers.IO) { sessions.safetyNumber(me, peerId) } ?: "No session yet"
    }

    fun dismissSafetyNumber(verified: Boolean) {
        safetyNumber = null
        if (verified) viewModelScope.launch { contacts.setVerified(peerId, true) }
    }

    fun reportError(message: String) {
        error = message
    }

    fun clearError() {
        error = null
    }

    override fun onCleared() {
        if (recorder.isRecording) recorder.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(onBack: () -> Unit, vm: ConversationViewModel = hiltViewModel()) {
    val list by vm.messageList.collectAsStateWithLifecycle()
    val peer by vm.peer.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.sendFile(it, kindForMime(context.contentResolver.getType(it))) }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.sendFile(it, AttachmentKind.IMAGE) }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else vm.reportError("Allow microphone access to record voice notes")
    }

    DisposableEffect(Unit) {
        vm.onVisible(true)
        onDispose { vm.onVisible(false) }
    }
    LaunchedEffect(list.size) {
        vm.markRead()
        if (list.isNotEmpty()) listState.animateScrollToItem(list.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.peerId)
                        Text(
                            if (peer?.verified == true) "Verified · end-to-end encrypted" else "End-to-end encrypted",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { vm.showSafetyNumber() }) { Icon(Icons.Default.Lock, "Safety number") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (peer?.keyChanged == true) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Warning, null)
                    Text(
                        "${vm.peerId}'s security key changed. Tap the lock to verify it's really them.",
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(list, key = { it.message.id }) { item ->
                    MessageBubble(
                        item,
                        onDownload = { vm.download(item.message.id) },
                        onRetry = { vm.retryUpload(item.message.id) },
                    )
                }
            }
            vm.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                LaunchedEffect(it) {
                    delay(4_000)
                    vm.clearError()
                }
            }
            if (vm.recording) {
                RecordingBar(
                    elapsedMs = vm::recordingElapsedMs,
                    onCancel = vm::cancelRecording,
                    onSend = vm::finishRecording,
                )
            } else {
                InputBar(
                    draft = vm.draft,
                    onDraftChange = { vm.draft = it },
                    onSend = vm::sendDraft,
                    onPickDocument = { pickDocument.launch(arrayOf("*/*")) },
                    onPickPhoto = {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onMic = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) vm.startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }
    }

    vm.safetyNumber?.let { number ->
        AlertDialog(
            onDismissRequest = { vm.dismissSafetyNumber(false) },
            title = { Text("Safety number") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Compare these numbers with ${vm.peerId} in person or on a call. If they match, nobody is intercepting your messages.")
                    Text(number.chunked(5).chunked(4).joinToString("\n") { it.joinToString(" ") }, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { vm.dismissSafetyNumber(true) }) { Text("Mark verified") } },
            dismissButton = { TextButton(onClick = { vm.dismissSafetyNumber(false) }) { Text("Close") } },
        )
    }
}

@Composable
private fun InputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickDocument: () -> Unit,
    onPickPhoto: () -> Unit,
    onMic: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.AttachFile, "Attach") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Photo") },
                    leadingIcon = { Icon(Icons.Default.Image, null) },
                    onClick = { menuOpen = false; onPickPhoto() },
                )
                DropdownMenuItem(
                    text = { Text("Document or audio file") },
                    leadingIcon = { Icon(Icons.Default.Description, null) },
                    onClick = { menuOpen = false; onPickDocument() },
                )
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Message") },
            modifier = Modifier.weight(1f),
            maxLines = 5,
        )
        if (draft.isBlank()) {
            IconButton(onClick = onMic) { Icon(Icons.Default.Mic, "Record voice note") }
        } else {
            IconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}

@Composable
private fun RecordingBar(elapsedMs: () -> Long, onCancel: () -> Unit, onSend: () -> Unit) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsed = elapsedMs()
            delay(250)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel recording") }
        Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
        Text(
            "  Recording ${FileNames.duration(elapsed)}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        IconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Filled.Send, "Send voice note") }
    }
}

@Composable
private fun MessageBubble(item: MessageWithAttachment, onDownload: () -> Unit, onRetry: () -> Unit) {
    val message = item.message
    val mine = message.outgoing
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            item.attachment?.let { AttachmentContent(it, onDownload, onRetry) }
            message.body?.takeIf { it.isNotBlank() }?.let { Text(it) }
            if (mine) {
                Text(
                    statusLabel(message.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.status == MessageStatus.READ) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

private fun kindForMime(mime: String?): AttachmentKind = when {
    mime == null -> AttachmentKind.DOCUMENT
    mime.startsWith("audio/") -> AttachmentKind.AUDIO
    mime.startsWith("video/") -> AttachmentKind.VIDEO
    else -> AttachmentKind.DOCUMENT
}

private fun statusLabel(status: MessageStatus) = when (status) {
    MessageStatus.QUEUED -> "🕒"
    MessageStatus.SENT -> "✓"
    MessageStatus.DELIVERED -> "✓✓"
    MessageStatus.READ -> "✓✓ read"
    MessageStatus.FAILED -> "⚠ not sent"
}
