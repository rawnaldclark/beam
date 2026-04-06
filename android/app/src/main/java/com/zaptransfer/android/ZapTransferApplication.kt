package com.zaptransfer.android

import android.app.Application
import com.zaptransfer.android.util.NotificationChannels
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point for Beam / ZapTransfer.
 *
 * Annotated with [@HiltAndroidApp] which triggers Hilt's code generation:
 *  - Creates the application-scoped component (AppComponent).
 *  - Injects @Singleton-scoped dependencies on first use.
 *
 * Responsibilities in [onCreate]:
 *  1. Register notification channels (idempotent — safe on every cold start).
 *  2. TODO (Phase G): query [ChunkProgressDao] for incomplete transfers and
 *     restart [TransferForegroundService] to resume from the last checkpoint.
 *     This handles process-death recovery per spec §8.4.
 *
 * Declared in AndroidManifest.xml as:
 * ```xml
 * <application android:name=".ZapTransferApplication" ...>
 * ```
 */
@HiltAndroidApp
class ZapTransferApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Register TRANSFER_PROGRESS and TRANSFER_ALERTS notification channels.
        // Must run before any foreground service or notification is posted.
        NotificationChannels.create(this)

        // Phase G TODO: recover in-progress transfers after process death.
        // Implementation sketch (to be filled in Task 25):
        //
        //   applicationScope.launch(Dispatchers.IO) {
        //       val incomplete = chunkProgressDao.getIncomplete()
        //       if (incomplete.isNotEmpty()) {
        //           val intent = Intent(this@ZapTransferApplication, TransferForegroundService::class.java)
        //               .putExtra(TransferForegroundService.EXTRA_RESUME, true)
        //           startForegroundService(intent)
        //       }
        //   }
    }
}
