package com.wichat.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wichat.MainActivity
import com.wichat.R
import kotlinx.coroutines.flow.MutableStateFlow

class Notifier(private val context: Context) {

    /** Chat currently on screen; no notification for messages that land there. */
    val activeConversation = MutableStateFlow<String?>(null)

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun serviceNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.service_running))
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()

    fun showMessage(from: String, preview: String) {
        if (activeConversation.value == from) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(from)
            .setContentText(preview)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        NotificationManagerCompat.from(context).notify(from.hashCode(), notification)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val SERVICE_NOTIFICATION_ID = 1
        private const val CHANNEL_SERVICE = "service"
        private const val CHANNEL_MESSAGES = "messages"
    }
}
