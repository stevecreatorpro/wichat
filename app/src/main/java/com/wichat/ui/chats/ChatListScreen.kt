package com.wichat.ui.chats

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wichat.data.db.ConversationEntity
import com.wichat.data.prefs.AccountStore
import com.wichat.data.repo.MessageRepository
import com.wichat.mesh.MeshPermissions
import com.wichat.transport.ConnectionState
import com.wichat.transport.MeshState
import com.wichat.transport.MeshTransport
import com.wichat.transport.RelayTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    messages: MessageRepository,
    relay: RelayTransport,
    private val mesh: MeshTransport,
    private val accounts: AccountStore,
) : ViewModel() {
    val me: String = accounts.current?.userId.orEmpty()
    val hasRelay: Boolean get() = accounts.current?.hasRelay == true
    val conversations: StateFlow<List<ConversationEntity>> =
        messages.observeConversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val connection: StateFlow<ConnectionState> = relay.state
    val meshState: StateFlow<MeshState> = mesh.state

    fun permissionsChanged() = viewModelScope.launch { mesh.permissionsChanged() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit,
    vm: ChatListViewModel = hiltViewModel(),
) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val mesh by vm.meshState.collectAsStateWithLifecycle()
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.permissionsChanged()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("WiChat")
                        Text(
                            statusLine(vm.me, vm.hasRelay, connection, mesh),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewChat) { Icon(Icons.Default.Edit, "New chat") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (mesh.enabled && mesh.needsPermission) {
                Banner(
                    "Allow nearby devices so WiChat can chat phone to phone when there's no internet.",
                    action = "Allow",
                    onAction = { askPermissions.launch(MeshPermissions.required) },
                )
            } else if (mesh.error != null) {
                Banner(mesh.error!!, action = "Retry", onAction = vm::permissionsChanged)
            }
            if (conversations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No chats yet. Tap ✎ to message someone.")
                }
            } else {
                LazyColumn {
                    items(conversations, key = { it.id }) { chat ->
                        ListItem(
                            headlineContent = { Text(chat.title) },
                            supportingContent = {
                                Text(chat.lastMessagePreview.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                if (chat.unread > 0) {
                                    Badge { Text(chat.unread.toString()) }
                                } else if (chat.lastMessageAt > 0) {
                                    Text(
                                        DateUtils.getRelativeTimeSpanString(chat.lastMessageAt).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                            modifier = Modifier.clickable { onOpenChat(chat.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.WifiTethering, null)
        Text(text, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onAction) { Text(action) }
    }
}

private fun statusLine(me: String, hasRelay: Boolean, relay: ConnectionState, mesh: MeshState): String {
    val relayPart = when {
        !hasRelay -> "offline account"
        relay == ConnectionState.CONNECTED -> "online"
        relay == ConnectionState.CONNECTING -> "connecting…"
        else -> "no internet"
    }
    val meshPart = when {
        !mesh.enabled -> "nearby off"
        !mesh.running -> "nearby paused"
        mesh.peers == 1 -> "1 phone nearby"
        else -> "${mesh.peers} phones nearby"
    }
    val carrying = if (mesh.carrying > 0) " · carrying ${mesh.carrying}" else ""
    return "@$me · $relayPart · $meshPart$carrying"
}
