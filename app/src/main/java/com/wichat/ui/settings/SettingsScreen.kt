package com.wichat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wichat.data.prefs.AccountStore
import com.wichat.domain.RegisterUseCase
import com.wichat.transport.MeshState
import com.wichat.transport.MeshTransport
import com.wichat.transport.RelayTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accounts: AccountStore,
    private val register: RegisterUseCase,
    private val relay: RelayTransport,
    private val mesh: MeshTransport,
) : ViewModel() {
    val me: String = accounts.current?.userId.orEmpty()
    val currentRelay: String? get() = accounts.current?.relayUrl
    val meshState: StateFlow<MeshState> = mesh.state

    var relayUrl by mutableStateOf(accounts.current?.relayUrl ?: accounts.relayUrl)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var isError by mutableStateOf(false)
        private set

    /** Registers this phone's existing identity with the relay; chats and keys are kept. */
    fun connectRelay() {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            runCatching { register(me, relayUrl) }
                .onSuccess {
                    relay.accountChanged()
                    isError = false
                    message = "Connected. You can now reach WiChat users anywhere with internet."
                }
                .onFailure {
                    isError = true
                    message = it.message ?: "Couldn't connect to that relay"
                }
            busy = false
        }
    }

    fun setMesh(enabled: Boolean) = viewModelScope.launch { mesh.setEnabled(enabled) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val mesh by vm.meshState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("@${vm.me}", style = MaterialTheme.typography.titleLarge)

            Text("Offline mesh", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Chat with WiChat users nearby with no internet, and pass encrypted messages along for others. " +
                        "Uses Bluetooth and Wi-Fi, which costs some battery.",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Switch(checked = mesh.enabled, onCheckedChange = vm::setMesh)
            }
            if (mesh.enabled) {
                Text(
                    when {
                        mesh.needsPermission -> "Waiting for the nearby devices permission (allow it on the chat list)."
                        mesh.running -> "${mesh.peers} phone(s) connected · carrying ${mesh.carrying} message(s) for others"
                        else -> "Not running"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Text("Relay server (internet)", style = MaterialTheme.typography.titleMedium)
            Text(
                vm.currentRelay?.let { "Connected to $it" } ?: "Not connected: this account only works over the nearby mesh.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = vm.relayUrl,
                onValueChange = { vm.relayUrl = it },
                label = { Text("Relay server address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = vm::connectRelay, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (vm.currentRelay == null) "Connect to relay" else "Switch relay")
            }
            vm.message?.let {
                Text(it, color = if (vm.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Text(
                "Your username must be free on the new relay. Your keys and chats stay on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
