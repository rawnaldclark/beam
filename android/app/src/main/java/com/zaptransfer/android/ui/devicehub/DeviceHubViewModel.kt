package com.zaptransfer.android.ui.devicehub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zaptransfer.android.data.db.dao.TransferHistoryDao
import com.zaptransfer.android.data.db.entity.PairedDeviceEntity
import com.zaptransfer.android.data.db.entity.TransferHistoryEntity
import com.zaptransfer.android.data.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for the Device Hub screen.
 *
 * Combines two reactive sources:
 *  1. Persistent paired device list from [DeviceRepository.observePairedDevices].
 *  2. Ephemeral online presence set from [DeviceRepository.onlineDevices].
 *
 * The [uiState] flow emits a new [DeviceHubUiState] whenever either source
 * changes — the UI never polls. [recentTransfers] is driven directly by the
 * Room DAO flow and updates as transfers complete in the background service.
 *
 * The 5-second [SharingStarted.WhileSubscribed] timeout keeps the upstream flows
 * alive during brief recompositions (e.g., navigation transitions), preventing
 * unnecessary re-queries on immediate return.
 *
 * @param deviceRepo         Mediates access to [PairedDeviceEntity] records and online presence.
 * @param transferHistoryDao Provides the recent-transfers flow for the history section.
 */
@HiltViewModel
class DeviceHubViewModel @Inject constructor(
    private val deviceRepo: DeviceRepository,
    private val transferHistoryDao: TransferHistoryDao,
) : ViewModel() {

    /**
     * Primary UI state: the list of paired devices enriched with live online status.
     *
     * Loading defaults to true until the first Room emission. After that it is always
     * false — the list may be empty but is never in an indeterminate state.
     */
    val uiState: StateFlow<DeviceHubUiState> = combine(
        deviceRepo.observePairedDevices(),
        deviceRepo.onlineDevices,
    ) { devices, online ->
        DeviceHubUiState(
            devices = devices.map { entity ->
                PairedDeviceUi(
                    entity = entity,
                    isOnline = online.contains(entity.deviceId),
                )
            },
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DeviceHubUiState(),
    )

    /**
     * The 20 most recent transfers across all devices, ordered newest-first.
     *
     * Emits a new list whenever any [TransferHistoryEntity] row changes — the
     * TransferForegroundService writes rows as transfers complete.
     */
    val recentTransfers: StateFlow<List<TransferHistoryEntity>> =
        transferHistoryDao.getRecent(20)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )
}

// ── UI models ──────────────────────────────────────────────────────────────────

/**
 * Top-level UI state for the Device Hub screen.
 *
 * @param devices   List of paired devices, each annotated with live online status.
 * @param isLoading True only during the very first Room emission; false thereafter.
 */
data class DeviceHubUiState(
    val devices: List<PairedDeviceUi> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * A [PairedDeviceEntity] enriched with the current online presence status.
 *
 * Presence is ephemeral — it resets to false on process restart and is
 * re-populated by relay presence events. The UI should treat [isOnline] as
 * best-effort and never gate critical operations on it.
 *
 * @param entity   The persistent device record from Room.
 * @param isOnline True if the device has reported online presence since last app start.
 */
data class PairedDeviceUi(
    val entity: PairedDeviceEntity,
    val isOnline: Boolean,
)
