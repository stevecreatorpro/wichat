package com.wichat.ui.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wichat.data.prefs.AccountStore
import com.wichat.data.remote.RelayException
import com.wichat.data.repo.ContactRepository
import com.wichat.data.repo.MessageRepository
import com.wichat.domain.SendMessageUseCase
import com.wichat.model.normalizeUserId
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wichat.data.db.MeshDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewChatViewModel @Inject constructor(
    private val send: SendMessageUseCase,
    private val contacts: ContactRepository,
    private val messages: MessageRepository,
    private val accounts: AccountStore,
    meshDao: MeshDao,
) : ViewModel() {
    /** People whose public keys reached us over the offline mesh: chat-able with no internet. */
    val nearby: StateFlow<List<String>> = meshDao.observeDirectoryUsers()
        .map { users -> users.filter { it != accounts.current?.userId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var username by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Fetching the prekey bundle both proves the user exists and sets up the E2EE session. */
    fun start(onStarted: (String) -> Unit) {
        val peer = normalizeUserId(username)
        if (peer.isEmpty() || busy) return
        if (peer == accounts.current?.userId) {
            error = "That's you"
            return
        }
        busy = true
        error = null
        viewModelScope.launch {
            runCatching {
                send.ensureSession(peer)
                contacts.ensure(peer)
                messages.ensureConversation(peer)
            }.onSuccess { onStarted(peer) }
                .onFailure {
                    error = if (it is RelayException && it.code == 404) "No WiChat user called @$peer" else it.message
                }
            busy = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(onBack: () -> Unit, onStarted: (String) -> Unit, vm: NewChatViewModel = hiltViewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New chat") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = vm.username,
                onValueChange = { vm.username = it },
                label = { Text("Their username") },
                prefix = { Text("@") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { vm.start(onStarted) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Start secure chat")
            }
            val nearby by vm.nearby.collectAsStateWithLifecycle()
            if (nearby.isNotEmpty()) {
                Text("Reachable without internet", style = MaterialTheme.typography.titleSmall)
                LazyColumn {
                    items(nearby) { user ->
                        ListItem(
                            headlineContent = { Text("@$user") },
                            supportingContent = { Text("Seen on the nearby mesh") },
                            modifier = Modifier.clickable {
                                vm.username = user
                                vm.start(onStarted)
                            },
                        )
                    }
                }
            }
        }
    }
}
