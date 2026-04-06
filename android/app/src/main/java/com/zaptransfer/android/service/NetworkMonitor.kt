package com.zaptransfer.android.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NetworkMonitor"

// ── Domain models ─────────────────────────────────────────────────────────────

/**
 * Physical transport type for an active network connection.
 *
 * Drives behaviour in the transfer engine:
 *  - [WIFI]  / [ETHERNET]: larger default chunk sizes; ICE upgrade attempted.
 *  - [CELLULAR]: smaller chunks; relay-only mode preferred unless P2P succeeds quickly.
 */
enum class Transport {
    /** 802.11 Wi-Fi (most common for inter-device transfers). */
    WIFI,

    /** Mobile data (LTE, 5G, etc.). Metered by default. */
    CELLULAR,

    /** Wired Ethernet (tablets/Chromebooks with adapters). */
    ETHERNET,
}

/**
 * Represents the current state of the device's internet connectivity.
 *
 * The [SignalingClient] observes this flow to trigger proactive ICE restarts
 * when the transport changes (spec §8.5).
 */
sealed class NetworkState {
    /**
     * No validated network is available.
     * Possible causes: airplane mode, no SIM, no Wi-Fi, captive portal not dismissed.
     */
    object Disconnected : NetworkState()

    /**
     * An internet-capable network is active.
     *
     * @param transport The physical layer carrying the connection.
     * @param isMetered true for cellular and some tethered networks; false for typical Wi-Fi.
     *                  Used to avoid opportunistic pre-fetching on metered connections.
     */
    data class Connected(
        val transport: Transport,
        val isMetered: Boolean,
    ) : NetworkState()
}

// ── NetworkMonitor ────────────────────────────────────────────────────────────

/**
 * Observes system network changes and exposes them as a [StateFlow].
 *
 * Implemented with [ConnectivityManager.NetworkCallback] which is the modern
 * (API 26+) replacement for polling or [android.net.ConnectivityManager.CONNECTIVITY_ACTION]
 * broadcasts (deprecated API 28).
 *
 * The initial state is [NetworkState.Disconnected]; the first [NetworkState.Connected]
 * emission happens as soon as the system reports an available validated network.
 *
 * Thread safety: [ConnectivityManager] delivers callbacks on an internal binder
 * thread. [MutableStateFlow.value] is safe to set from any thread.
 *
 * @param context Application context provided by Hilt via [@ApplicationContext].
 *                Never holds an Activity reference — safe for the Singleton scope.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow<NetworkState>(NetworkState.Disconnected)

    /**
     * Current network state.
     *
     * Collectors should use [kotlinx.coroutines.flow.collectLatest] to react to
     * transitions without processing stale intermediate states during rapid switches
     * (e.g., Wi-Fi → cellular handover).
     */
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    init {
        registerCallback()
        // Emit current state immediately so collectors don't start with stale Disconnected
        // if the device is already online when the singleton is first created.
        emitCurrentState()
    }

    // ── NetworkCallback ───────────────────────────────────────────────────────

    /**
     * Registers the [ConnectivityManager.NetworkCallback] for all networks that have
     * [NetworkCapabilities.NET_CAPABILITY_INTERNET] and
     * [NetworkCapabilities.NET_CAPABILITY_VALIDATED] (i.e., confirmed reachability).
     *
     * The VALIDATED capability filters out captive-portal networks that have a
     * connection at the IP layer but no actual internet access.
     */
    private fun registerCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        cm.registerNetworkCallback(request, networkCallback)
        Log.d(TAG, "NetworkCallback registered")
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        /**
         * Called when a validated internet-capable network comes up.
         * We immediately resolve capabilities to determine transport and metered status.
         */
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available: $network")
            updateStateFromNetwork(network)
        }

        /**
         * Called when a previously available network is lost.
         * We re-check the active default network to handle seamless handovers
         * (e.g., Wi-Fi drops but cellular is still available).
         */
        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost: $network")
            // Re-evaluate — another network may still be active
            val active = cm.activeNetwork
            if (active != null) {
                updateStateFromNetwork(active)
            } else {
                _state.value = NetworkState.Disconnected
                Log.d(TAG, "All networks lost; state → Disconnected")
            }
        }

        /**
         * Called when an existing network's capabilities change (e.g., metered status
         * update or transport upgrade). Re-derive the [NetworkState] from fresh data.
         */
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            val newState = resolveState(networkCapabilities)
            if (_state.value != newState) {
                Log.d(TAG, "Capabilities changed → $newState")
                _state.value = newState
            }
        }
    }

    // ── State resolution helpers ──────────────────────────────────────────────

    /**
     * Looks up the capabilities for [network] and updates [_state].
     * Emits [NetworkState.Disconnected] if capabilities cannot be retrieved (race
     * condition where the network was lost before the query completed).
     */
    private fun updateStateFromNetwork(network: Network) {
        val caps = cm.getNetworkCapabilities(network)
        if (caps == null) {
            Log.w(TAG, "No capabilities for network $network — assuming disconnected")
            _state.value = NetworkState.Disconnected
            return
        }
        _state.value = resolveState(caps)
    }

    /**
     * Derives a [NetworkState] from a [NetworkCapabilities] snapshot.
     *
     * Transport priority (first match wins):
     *  1. [NetworkCapabilities.TRANSPORT_WIFI] — most transfers use this.
     *  2. [NetworkCapabilities.TRANSPORT_ETHERNET] — wired; treated like Wi-Fi.
     *  3. [NetworkCapabilities.TRANSPORT_CELLULAR] — mobile data.
     *
     * If none of the above match (e.g., VPN, Bluetooth PAN), we default to
     * [Transport.CELLULAR] as the most conservative assumption.
     *
     * Metered status is queried via [ConnectivityManager.isActiveNetworkMetered]
     * which reflects carrier policy and the user's "Set as metered" toggle.
     */
    private fun resolveState(caps: NetworkCapabilities): NetworkState {
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else -> Transport.CELLULAR  // conservative default for VPN / BT PAN
        }

        val isMetered = cm.isActiveNetworkMetered

        return NetworkState.Connected(transport = transport, isMetered = isMetered)
    }

    /**
     * Checks the currently active network at the moment this function is called
     * and emits the corresponding [NetworkState]. Called from [init] so that the
     * initial state reflects reality rather than always starting as Disconnected.
     */
    private fun emitCurrentState() {
        val active = cm.activeNetwork ?: run {
            // No active network — Disconnected is already the default
            return
        }
        updateStateFromNetwork(active)
    }
}
