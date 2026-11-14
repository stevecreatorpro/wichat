package com.wichat

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.wichat.data.prefs.AccountStore
import com.wichat.service.MessagingService
import com.wichat.ui.WiChatNavHost
import com.wichat.ui.theme.WiChatTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var accounts: AccountStore

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (accounts.current != null) MessagingService.start(this)

        setContent {
            WiChatTheme {
                WiChatNavHost(
                    registered = accounts.current != null,
                    onRegistered = { MessagingService.start(this) },
                )
            }
        }
    }
}
