package com.zaptransfer.android.navigation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zaptransfer.android.ui.clipboard.ClipboardHistoryScreen
import com.zaptransfer.android.ui.pairing.DeviceNamingScreen
import com.zaptransfer.android.ui.pairing.PairingViewModel
import com.zaptransfer.android.ui.pairing.PinEntryScreen
import com.zaptransfer.android.ui.pairing.QrScannerScreen
import com.zaptransfer.android.ui.pairing.SasVerificationScreen
import com.zaptransfer.android.ui.settings.SettingsScreen
import com.zaptransfer.android.ui.transfer.TransferCompleteSheet
import com.zaptransfer.android.ui.transfer.TransferProgressScreen

// ─── Argument key constants ───────────────────────────────────────────────────
// Declared before route constants so they can be referenced in const val
// string-template initializers without triggering forward-reference errors.
const val ARG_SESSION_ID = "sessionId"
const val ARG_DEVICE_ID = "deviceId"
const val ARG_TRANSFER_ID = "transferId"

// ─── Route constants ──────────────────────────────────────────────────────────
// Centralised string tokens prevent typos across call sites.
// Argument names match the NavArgument keys declared in each composable() block.

/** Main screen: paired device list + recent transfers + FAB. */
const val ROUTE_DEVICE_HUB = "deviceHub"

/** Pairing step 1A: CameraX + ML Kit QR code scanner. */
const val ROUTE_PAIRING_SCAN = "pairing/scan"

/** Pairing step 1B: 8-digit PIN fallback entry. */
const val ROUTE_PAIRING_PIN = "pairing/pin"

/**
 * Pairing step 2: SAS emoji verification.
 * No nav argument — the in-flight session state is held in PairingViewModel.Verifying,
 * which is scoped to the ROUTE_PAIRING_SCAN back stack entry and shared across all
 * pairing screens.
 */
const val ROUTE_PAIRING_VERIFY = "pairing/verify"

/**
 * Pairing step 3: name the newly paired device and pick its icon.
 * No nav argument — the deviceId is held in PairingViewModel.Naming state, accessed
 * via the shared ViewModel scoped to the ROUTE_PAIRING_SCAN back stack entry.
 */
const val ROUTE_PAIRING_NAME = "pairing/name"

/**
 * Transfer in-progress screen.
 * Argument: [ARG_TRANSFER_ID] — UUID of the active transfer, used to subscribe
 * to TransferViewModel's StateFlow for progress updates.
 */
const val ROUTE_TRANSFER_PROGRESS = "transfer/progress/{$ARG_TRANSFER_ID}"

/**
 * Transfer complete bottom sheet (shown modally over the hub).
 * Argument: [ARG_TRANSFER_ID] — UUID of the completed transfer, used to fetch
 * the final [TransferHistoryEntity] for display.
 */
const val ROUTE_TRANSFER_COMPLETE = "transfer/complete/{$ARG_TRANSFER_ID}"

/** Settings screen: exactly 4 settings per spec §8.3. */
const val ROUTE_SETTINGS = "settings"

/** Clipboard history screen: last 20 received clipboard items. */
const val ROUTE_CLIPBOARD = "clipboard"

/**
 * Root navigation graph for the Beam application.
 *
 * All screens are declared here as a flat list of [composable] destinations.
 * The [NavHostController] is owned by this composable and shared downward
 * only as a parameter to screens that need to navigate — not via CompositionLocal.
 *
 * Navigation rules:
 *  - Pairing flow is linear: scan/pin → verify → name → hub.
 *  - Transfer routes are pushed onto the back stack over the hub.
 *  - Settings and clipboard are top-level destinations accessible from the hub app bar.
 *
 * @param navController  Optional override — inject in tests to assert navigation calls.
 *                       Production callers use the default [rememberNavController].
 */
@Composable
fun BeamNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = ROUTE_DEVICE_HUB,
    ) {

        // ── Device Hub ───────────────────────────────────────────────────────
        composable(ROUTE_DEVICE_HUB) {
            val deviceHubVm: com.zaptransfer.android.ui.devicehub.DeviceHubViewModel = hiltViewModel()

            // Track which device the file picker was opened for.
            val selectedDeviceIdForFile = remember { mutableStateOf<String?>(null) }

            // System file picker launcher — sends the selected file via the relay.
            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                val deviceId = selectedDeviceIdForFile.value
                if (uri != null && deviceId != null) {
                    deviceHubVm.sendFile(deviceId, uri)
                }
                selectedDeviceIdForFile.value = null
            }

            com.zaptransfer.android.ui.devicehub.DeviceHubScreen(
                viewModel = deviceHubVm,
                onNavigateToPairScan = { navController.navigate(ROUTE_PAIRING_SCAN) },
                onNavigateToPairPin = { navController.navigate(ROUTE_PAIRING_PIN) },
                onNavigateToSettings = { navController.navigate(ROUTE_SETTINGS) },
                onSendFile = { deviceId ->
                    selectedDeviceIdForFile.value = deviceId
                    filePickerLauncher.launch("*/*")
                },
                onSendText = { deviceId -> deviceHubVm.sendClipboard(deviceId) },
            )
        }

        // ── Pairing flow ─────────────────────────────────────────────────────
        // All pairing screens share ONE PairingViewModel instance scoped to the
        // navController's ViewModelStoreOwner (the Activity). This avoids the
        // back-stack scoping issues that caused crashes and blank screens.
        //
        // Using `hiltViewModel(navController.getViewModelStoreOwner())` is the
        // recommended pattern for multi-screen flows that share state.

        composable(ROUTE_PAIRING_SCAN) {
            val viewModel: PairingViewModel = hiltViewModel(
                navController.getViewModelStoreOwner(navController.graph.id)
            )
            QrScannerScreen(
                viewModel = viewModel,
                onNavigateToVerify = { navController.navigate(ROUTE_PAIRING_VERIFY) },
                onNavigateToPin = { navController.navigate(ROUTE_PAIRING_PIN) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_PAIRING_PIN) {
            val viewModel: PairingViewModel = hiltViewModel(
                navController.getViewModelStoreOwner(navController.graph.id)
            )
            PinEntryScreen(
                viewModel = viewModel,
                onNavigateToVerify = { navController.navigate(ROUTE_PAIRING_VERIFY) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_PAIRING_VERIFY) {
            val viewModel: PairingViewModel = hiltViewModel(
                navController.getViewModelStoreOwner(navController.graph.id)
            )
            SasVerificationScreen(
                viewModel = viewModel,
                onNavigateToNaming = { navController.navigate(ROUTE_PAIRING_NAME) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(ROUTE_PAIRING_NAME) {
            val viewModel: PairingViewModel = hiltViewModel(
                navController.getViewModelStoreOwner(navController.graph.id)
            )
            DeviceNamingScreen(
                viewModel = viewModel,
                onNavigateToHub = {
                    navController.navigate(ROUTE_DEVICE_HUB) {
                        popUpTo(ROUTE_DEVICE_HUB) { inclusive = false }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ── Transfer: progress ───────────────────────────────────────────────
        composable(
            route = ROUTE_TRANSFER_PROGRESS,
            arguments = listOf(
                navArgument(ARG_TRANSFER_ID) { type = NavType.StringType }
            ),
        ) { backStackEntry ->
            val transferId = backStackEntry.arguments?.getString(ARG_TRANSFER_ID) ?: return@composable
            TransferProgressScreen(
                transferId = transferId,
                onBack = { navController.popBackStack() },
                onCancel = { id ->
                    // Navigate back to hub after cancel
                    navController.navigate(ROUTE_DEVICE_HUB) {
                        popUpTo(ROUTE_DEVICE_HUB) { inclusive = false }
                    }
                },
                onComplete = { id ->
                    navController.navigate("transfer/complete/$id") {
                        popUpTo(ROUTE_TRANSFER_PROGRESS.substringBefore("{")) { inclusive = true }
                    }
                },
            )
        }

        // ── Transfer: complete sheet ─────────────────────────────────────────
        composable(
            route = ROUTE_TRANSFER_COMPLETE,
            arguments = listOf(
                navArgument(ARG_TRANSFER_ID) { type = NavType.StringType }
            ),
        ) { backStackEntry ->
            val transferId = backStackEntry.arguments?.getString(ARG_TRANSFER_ID) ?: return@composable
            TransferCompleteSheet(
                transferId = transferId,
                onDismiss = {
                    navController.navigate(ROUTE_DEVICE_HUB) {
                        popUpTo(ROUTE_DEVICE_HUB) { inclusive = false }
                    }
                },
            )
        }

        // ── Settings ─────────────────────────────────────────────────────────
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        // ── Clipboard History ────────────────────────────────────────────────
        composable(ROUTE_CLIPBOARD) {
            ClipboardHistoryScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}

