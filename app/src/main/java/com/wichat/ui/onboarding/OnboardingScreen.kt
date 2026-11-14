package com.wichat.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.viewModelScope
import com.wichat.data.prefs.AccountStore
import com.wichat.domain.RegisterUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val register: RegisterUseCase,
    accounts: AccountStore,
) : ViewModel() {
    var username by mutableStateOf("")
    var relayUrl by mutableStateOf(accounts.relayUrl)
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun submit(onDone: () -> Unit) = run(onDone) { register(username, relayUrl) }

    fun submitOffline(onDone: () -> Unit) = run(onDone) { register.createOffline(username) }

    private fun run(onDone: () -> Unit, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess { onDone() }
                .onFailure { error = it.message ?: "Couldn't create your account" }
            busy = false
        }
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Welcome to WiChat", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Your encryption keys are created on this phone and never leave it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = vm.username,
                onValueChange = { vm.username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.relayUrl,
                onValueChange = { vm.relayUrl = it },
                label = { Text("Relay server") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { vm.submit(onDone) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Create account")
            }
            TextButton(onClick = { vm.submitOffline(onDone) }, enabled = !vm.busy) {
                Text("No internet? Create it on this phone only")
            }
            Text(
                "Offline accounts chat with WiChat users nearby, phone to phone. You can connect to a relay later in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
