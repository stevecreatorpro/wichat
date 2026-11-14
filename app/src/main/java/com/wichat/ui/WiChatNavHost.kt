package com.wichat.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wichat.ui.chats.ChatListScreen
import com.wichat.ui.conversation.ConversationScreen
import com.wichat.ui.newchat.NewChatScreen
import com.wichat.ui.onboarding.OnboardingScreen
import com.wichat.ui.settings.SettingsScreen

private object Routes {
    const val ONBOARDING = "onboarding"
    const val CHATS = "chats"
    const val NEW_CHAT = "new_chat"
    const val SETTINGS = "settings"
    const val CONVERSATION = "conversation/{peerId}"
    fun conversation(peerId: String) = "conversation/${Uri.encode(peerId)}"
}

@Composable
fun WiChatNavHost(registered: Boolean, onRegistered: () -> Unit) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = if (registered) Routes.CHATS else Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = {
                onRegistered()
                nav.navigate(Routes.CHATS) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }
        composable(Routes.CHATS) {
            ChatListScreen(
                onOpenChat = { nav.navigate(Routes.conversation(it)) },
                onNewChat = { nav.navigate(Routes.NEW_CHAT) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.NEW_CHAT) {
            NewChatScreen(
                onBack = { nav.popBackStack() },
                onStarted = { peer ->
                    nav.navigate(Routes.conversation(peer)) { popUpTo(Routes.CHATS) }
                },
            )
        }
        composable(Routes.CONVERSATION, arguments = listOf(navArgument("peerId") { type = NavType.StringType })) {
            ConversationScreen(onBack = { nav.popBackStack() })
        }
    }
}
