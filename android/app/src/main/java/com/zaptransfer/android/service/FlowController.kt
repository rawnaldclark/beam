package com.zaptransfer.android.service

import android.util.Log
import kotlinx.coroutines.sync.Semaphore

private const val TAG = "FlowController"

// ── AIMD parameters ────────────────────────────────────────────────────────────

/** Starting congestion window size (in chunks). */
private const val INITIAL_WINDOW = 4

/** Minimum window size — never go below 2 to preserve pipelining benefits. */
private const val MIN_WINDOW = 2

/**
 * Maximum window size for a direct (LAN/WebRTC) path.
 * Higher ceiling because direct paths are lower latency and more predictable.
 */
private const val MAX_WINDOW_DIRECT = 64

/**
 * Maximum window size for a relay (WSS) path.
 * Relay adds server-side queuing — a large window would cause uncontrolled
 * bufferbloat on the relay's WebSocket write buffer.
 */
private const val MAX_WINDOW_RELAY = 8

/**
 * Additive increase amount — the window grows by one chunk per full window's
 * worth of acknowledged data (TCP Reno-style slow avoidance phase).
 */
private const val ADDITIVE_INCREASE = 1

/**
 * Multiplicative decrease factor — the window is halved on any loss event.
 * Integer division with a floor of [MIN_WINDOW] applies.
 */
private const val MULTIPLICATIVE_DECREASE_DIVISOR = 2

/**
 * AIMD (Additive Increase / Multiplicative Decrease) flow controller.
 *
 * ## Background
 * AIMD is the congestion control algorithm underlying TCP. Applying it to
 * application-level chunked transfers over WebSockets gives us:
 *  - Throughput that naturally ramps up to the available bandwidth.
 *  - Immediate back-off on congestion signals (loss or relay pressure).
 *  - Fairness with other traffic sharing the same relay WebSocket.
 *
 * ## Window semantics
 * The window defines how many chunks can be "in flight" (sent but not yet
 * ACK-ed) at any time. The [TransferEngine] acquires a permit from this
 * controller before sending each chunk and releases a permit when the ACK
 * arrives. If the window is full, the send coroutine suspends until an ACK
 * frees a slot.
 *
 * ## Congestion signals
 * - **Loss / timeout**: call [onLoss]. Window halves immediately.
 * - **ACK received**: call [onAck]. Tracks acknowledged chunks within the current
 *   window; after a full window of ACKs the window increments by [ADDITIVE_INCREASE].
 *
 * ## Path type
 * Construct with [isDirectPath] = true for a WebRTC SCTP data channel (peer-to-peer)
 * and false for a relay WebSocket path. The maximum window differs accordingly
 * (64 vs. 8 chunks).
 *
 * ## Thread safety
 * The [Semaphore] is coroutine-safe. [onAck] and [onLoss] must be called from a
 * single coroutine to avoid races on [acksInCurrentWindow] and [windowSize].
 *
 * @param isDirectPath True if the transfer path is WebRTC direct; false for relay.
 */
class FlowController(private val isDirectPath: Boolean = false) {

    /** Maximum window size for this path type. */
    private val maxWindow: Int = if (isDirectPath) MAX_WINDOW_DIRECT else MAX_WINDOW_RELAY

    /**
     * Current congestion window size (number of in-flight chunks allowed).
     *
     * Mutations happen only in [onAck] and [onLoss] — both must be called from
     * the same coroutine context. Reads from other coroutines are safe because
     * [windowSize] is only used for logging/stats; the [semaphore] is the actual
     * gating mechanism.
     */
    @Volatile
    var windowSize: Int = INITIAL_WINDOW
        private set

    /**
     * Counting semaphore with [windowSize] initial permits.
     *
     * One permit is acquired per chunk sent and released per ACK received.
     * Suspending here provides back-pressure when the window is full.
     */
    private var semaphore = Semaphore(permits = INITIAL_WINDOW, acquiredPermits = 0)

    /**
     * Tracks how many ACKs have been received within the current window epoch.
     * When this reaches [windowSize] the window grows by [ADDITIVE_INCREASE].
     */
    private var acksInCurrentWindow: Int = 0

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Suspends the caller until the congestion window has a free slot.
     *
     * Called by the [TransferEngine] before sending each chunk. Automatically
     * provides back-pressure when the receiver is slow to ACK.
     */
    suspend fun acquire() {
        semaphore.acquire()
    }

    /**
     * Called when a chunk ACK arrives.
     *
     * Releases one permit back to the semaphore (unblocking a pending [acquire] call
     * if any). Tracks progress through the current window epoch; after a full window
     * of consecutive ACKs the window size grows by [ADDITIVE_INCREASE].
     *
     * Must be called from the same coroutine as [onLoss] to prevent data races
     * on [acksInCurrentWindow].
     */
    fun onAck() {
        // Release the semaphore slot before potentially growing the window
        semaphore.release()
        acksInCurrentWindow++

        if (acksInCurrentWindow >= windowSize) {
            // Full window acknowledged — additive increase
            val prev = windowSize
            windowSize = minOf(windowSize + ADDITIVE_INCREASE, maxWindow)
            acksInCurrentWindow = 0

            if (windowSize != prev) {
                // Window grew — add the extra permit(s) so they are immediately available
                repeat(windowSize - prev) { semaphore.release() }
                Log.d(TAG, "AIMD increase: window $prev → $windowSize (max $maxWindow)")
            }
        }
    }

    /**
     * Called when a loss event is detected (timeout, explicit NACK, or relay error).
     *
     * Halves the window size (multiplicative decrease) with a floor of [MIN_WINDOW].
     * Excess in-flight permits beyond the new window are reclaimed by reducing the
     * semaphore's effective permit count via a matching acquire.
     *
     * Must be called from the same coroutine as [onAck] to prevent data races.
     */
    fun onLoss() {
        val prev = windowSize
        windowSize = maxOf(windowSize / MULTIPLICATIVE_DECREASE_DIVISOR, MIN_WINDOW)
        acksInCurrentWindow = 0

        // Reclaim excess permits that would push the in-flight count above the new window.
        // We must not acquire below zero — tryAcquire returns false if no permit available,
        // which is safe: it means all permits were already consumed by in-flight chunks.
        val excess = prev - windowSize
        if (excess > 0) {
            repeat(excess) { semaphore.tryAcquire() }
        }

        Log.w(TAG, "AIMD decrease: window $prev → $windowSize (loss event)")
    }

    /**
     * Returns the number of chunks currently in flight (sent but not yet ACK-ed).
     *
     * This is an approximation — the semaphore does not expose its acquired count
     * directly, so we compute it as `windowSize - availablePermits`. Accurate only
     * when called from the same coroutine that calls [acquire]/[onAck].
     */
    val inFlightCount: Int
        get() = windowSize - semaphore.availablePermits

    /**
     * Resets the flow controller to its initial state for reuse across transfer sessions.
     *
     * Rebuilds the semaphore rather than trying to drain and re-fill it — the old
     * semaphore may have suspended coroutines waiting on it; those will be cancelled
     * by the engine's Job before [reset] is called.
     */
    fun reset(directPath: Boolean = isDirectPath) {
        windowSize = INITIAL_WINDOW
        acksInCurrentWindow = 0
        // Replace the semaphore entirely so stale permits don't carry over
        semaphore = Semaphore(permits = INITIAL_WINDOW, acquiredPermits = 0)
        Log.d(TAG, "FlowController reset; window=$windowSize directPath=$directPath")
    }
}
