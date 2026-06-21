package de.aarondietz.beetmeister.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.aarondietz.beetmeister.MainActivity
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.model.update.BeetMaintenanceUpdatePhase

internal class BeetMaintenanceForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SYNC, null -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.maintenance_notification_title)
                val detail = intent?.getStringExtra(EXTRA_DETAIL) ?: getString(R.string.maintenance_notification_fallback)
                ensureChannel()
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(title, detail),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            }
        }
        return START_STICKY
    }

    private fun buildNotification(title: String, detail: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.maintenance_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val ACTION_SYNC = "de.aarondietz.beetmeister.action.MAINTENANCE_SYNC"
        private const val ACTION_STOP = "de.aarondietz.beetmeister.action.MAINTENANCE_STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DETAIL = "detail"
        private const val CHANNEL_ID = "maintenance_update"
        private const val NOTIFICATION_ID = 2001

        fun sync(context: Context, state: BeetRepositoryState) {
            if (state.maintenanceUpdate.phase !in ACTIVE_PHASES) {
                stop(context)
                return
            }
            val detail = state.maintenanceUpdate.statusDetail?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.maintenance_notification_fallback)
            val intent = Intent(context, BeetMaintenanceForegroundService::class.java).apply {
                action = ACTION_SYNC
                putExtra(EXTRA_TITLE, context.getString(R.string.maintenance_notification_title))
                putExtra(EXTRA_DETAIL, detail)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BeetMaintenanceForegroundService::class.java))
        }

        private val ACTIVE_PHASES = setOf(
            BeetMaintenanceUpdatePhase.Uploading,
            BeetMaintenanceUpdatePhase.Reconnecting,
            BeetMaintenanceUpdatePhase.Rebooting,
        )
    }
}
