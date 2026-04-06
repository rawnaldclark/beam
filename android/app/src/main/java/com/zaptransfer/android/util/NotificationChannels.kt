package com.zaptransfer.android.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Utility object for creating and registering notification channels.
 *
 * Android 8.0+ (API 26) requires channels to be registered before any notification
 * can be posted. Channels are idempotent — calling createNotificationChannel() with
 * an already-registered channel ID is a no-op, so it is safe to call [create] on
 * every [Application.onCreate].
 *
 * Two channels are defined:
 *
 * 1. [CHANNEL_TRANSFER_PROGRESS] — used by [TransferForegroundService] for the
 *    persistent progress notification. LOW importance to avoid sound/vibration on
 *    every chunk update, but still visible in the status bar.
 *
 * 2. [CHANNEL_TRANSFER_ALERTS] — used for one-shot events: transfer complete,
 *    transfer failed, incoming file received. DEFAULT importance (sound + banner).
 */
object NotificationChannels {

    /** Channel ID for the foreground service progress notification. */
    const val CHANNEL_TRANSFER_PROGRESS = "zap_transfer_progress"

    /** Channel ID for completion, failure, and incoming-receive alerts. */
    const val CHANNEL_TRANSFER_ALERTS = "zap_transfer_alerts"

    /**
     * Register all application notification channels with the system.
     *
     * Safe to call on every [Application.onCreate] — the OS ignores duplicate calls
     * for already-registered channel IDs without modifying user-set preferences.
     *
     * @param context Application context — activity context works too but Application
     *                is preferred to avoid leaks.
     */
    fun create(context: Context) {
        // Channels only exist on API 26+; the minSdk is already 26, so no version check needed,
        // but the check is included defensively for clarity.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // ── Progress channel ────────────────────────────────────────────────
        // LOW importance: no sound, no heads-up banner. The notification is always
        // visible in the shade while the foreground service is running.
        val progressChannel = NotificationChannel(
            CHANNEL_TRANSFER_PROGRESS,
            "Transfer Progress",            // Shown in Settings > Notifications
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows ongoing file transfer progress in the status bar."
            setShowBadge(false)             // No launcher badge for ongoing progress
        }

        // ── Alerts channel ──────────────────────────────────────────────────
        // DEFAULT importance: plays the default notification sound and shows a
        // heads-up banner when the screen is on.
        val alertsChannel = NotificationChannel(
            CHANNEL_TRANSFER_ALERTS,
            "Transfer Alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifies when a transfer completes, fails, or a file arrives."
            setShowBadge(true)
        }

        manager.createNotificationChannel(progressChannel)
        manager.createNotificationChannel(alertsChannel)
    }
}
