package com.harish.heymate.actions

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Phone-side actions the pipeline can trigger. */
object LocalActions {

    private const val CHANNEL_ID = "heymate_agent"
    private var channelCreated = false

    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent replies",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        channelCreated = true
    }

    /** Surface an agent reply (or any pipeline message) as a notification. */
    fun notify(context: Context, title: String, body: String) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(body.hashCode(), notification)
    }
}
