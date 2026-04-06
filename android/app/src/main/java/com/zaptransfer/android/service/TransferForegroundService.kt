package com.zaptransfer.android.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Stub foreground service declared in AndroidManifest.xml.
 *
 * This class satisfies the manifest reference so the app can compile and install.
 * Full implementation is provided in Phase G (Task 38), where this service will:
 *  - Acquire a foreground notification via NotificationChannels
 *  - Hold a WakeLock + WifiLock during active transfers
 *  - Bind to the transfer engine to receive progress updates
 *
 * The manifest declares foregroundServiceType="dataSync" (Android 14+) — that
 * constraint must be matched when startForeground() is eventually called with the
 * FOREGROUND_SERVICE_TYPE_DATA_SYNC flag.
 */
class TransferForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null
}
