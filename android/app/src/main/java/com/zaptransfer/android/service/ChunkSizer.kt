package com.zaptransfer.android.service

import android.util.Log

private const val TAG = "ChunkSizer"

// ── Tier definitions ───────────────────────────────────────────────────────────

/**
 * Available chunk-size tiers in bytes.
 *
 * Tiers follow a power-of-2 progression from 8 KB to 512 KB. The sizer
 * starts at [TIER_64KB] (a conservative midpoint) and adjusts based on
 * measured ACK round-trip times.
 *
 * Tier selection over a fixed chunk size matters because:
 *  - Small chunks amortize overhead poorly on fast paths (too many round trips).
 *  - Large chunks stall slow relay paths and inflate latency on loss events.
 *  - Adaptive sizing converges to the optimal point automatically.
 */
private val CHUNK_SIZE_TIERS = intArrayOf(
    8 * 1_024,    // 8 KB  — Tier 0 (minimum; relay or extremely poor link)
    16 * 1_024,   // 16 KB — Tier 1
    32 * 1_024,   // 32 KB — Tier 2
    64 * 1_024,   // 64 KB — Tier 3 (default starting tier)
    128 * 1_024,  // 128 KB — Tier 4
    256 * 1_024,  // 256 KB — Tier 5
    512 * 1_024,  // 512 KB — Tier 6 (maximum)
)

private const val DEFAULT_TIER_INDEX = 3        // Start at 64 KB
private const val RTT_WINDOW_SIZE = 8           // Sliding window of 8 ACK RTT samples
private const val GOOD_ACKS_BEFORE_INCREASE = 8 // Consecutive "good" ACKs to step up one tier
private const val RTT_SPIKE_FACTOR = 2.0        // RTT > 2x baseline triggers a tier decrease

/**
 * Adaptive chunk sizer for the Beam transfer engine.
 *
 * ## Algorithm
 * The sizer maintains a sliding window of the last [RTT_WINDOW_SIZE] ACK round-trip
 * time (RTT) measurements. A "baseline" RTT is the median of the window.
 *
 * **Increase trigger**: when [GOOD_ACKS_BEFORE_INCREASE] consecutive ACKs arrive
 * with RTT <= baseline, the chunk size steps up one tier.
 *
 * **Decrease trigger**: when any single ACK RTT exceeds `baseline * RTT_SPIKE_FACTOR`,
 * the chunk size steps down one tier immediately, and the consecutive-good counter
 * is reset to zero. This mirrors slow-start avoidance: react fast to congestion,
 * recover gradually.
 *
 * ## Usage
 * ```kotlin
 * val sizer = ChunkSizer()
 * val chunk = readBytes(sizer.currentChunkSize)
 * val sentAt = System.nanoTime()
 * sendChunk(chunk)
 * val rttMs = (System.nanoTime() - sentAt) / 1_000_000L
 * sizer.onAckReceived(rttMs)
 * ```
 *
 * Thread safety: NOT thread-safe. Must be accessed from a single coroutine context.
 */
class ChunkSizer {

    /** Current tier index into [CHUNK_SIZE_TIERS]. */
    private var tierIndex: Int = DEFAULT_TIER_INDEX

    /** Sliding window of the last [RTT_WINDOW_SIZE] ACK RTT measurements in milliseconds. */
    private val rttWindow = ArrayDeque<Long>(RTT_WINDOW_SIZE)

    /** Number of consecutive ACKs received with RTT <= baseline. Resets on spike. */
    private var consecutiveGoodAcks: Int = 0

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * The current recommended chunk size in bytes.
     *
     * Callers should read this immediately before slicing the file buffer for
     * each chunk — the value may change between chunks as the sizer adapts.
     */
    val currentChunkSize: Int
        get() = CHUNK_SIZE_TIERS[tierIndex]

    /**
     * Reports a received ACK and its round-trip time.
     *
     * Updates the RTT window, computes the baseline, and adjusts the tier if
     * an increase or decrease trigger fires.
     *
     * @param rttMs Round-trip time from chunk send to ACK receipt, in milliseconds.
     *              Must be non-negative; negative values are clamped to 0.
     */
    fun onAckReceived(rttMs: Long) {
        val safeRtt = maxOf(rttMs, 0L)
        updateWindow(safeRtt)

        // Need at least 2 samples to have a meaningful baseline
        if (rttWindow.size < 2) {
            consecutiveGoodAcks++
            return
        }

        val baseline = medianRtt()

        when {
            // RTT spike: decrease tier immediately
            safeRtt > baseline * RTT_SPIKE_FACTOR -> {
                decreaseTier(safeRtt, baseline)
            }
            // Good ACK: increment counter; step up after sustained good performance
            else -> {
                consecutiveGoodAcks++
                if (consecutiveGoodAcks >= GOOD_ACKS_BEFORE_INCREASE) {
                    increaseTier(baseline)
                    // Do NOT reset consecutiveGoodAcks — let the sizer keep trying to climb
                    // Reset the streak so we wait another full window before the next increase
                    consecutiveGoodAcks = 0
                }
            }
        }
    }

    /**
     * Resets the sizer to the default tier and clears RTT history.
     * Call this when starting a new transfer or after a reconnection.
     */
    fun reset() {
        tierIndex = DEFAULT_TIER_INDEX
        rttWindow.clear()
        consecutiveGoodAcks = 0
        Log.d(TAG, "ChunkSizer reset to tier $tierIndex (${currentChunkSize / 1_024} KB)")
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Appends [rttMs] to the sliding window, evicting the oldest sample when full.
     */
    private fun updateWindow(rttMs: Long) {
        if (rttWindow.size >= RTT_WINDOW_SIZE) {
            rttWindow.removeFirst()
        }
        rttWindow.addLast(rttMs)
    }

    /**
     * Computes the median RTT from the current window.
     *
     * Median is more robust than mean in the presence of outliers (a single
     * retransmitted ACK would inflate the mean and trigger a false decrease).
     *
     * @return Median RTT in milliseconds.
     */
    private fun medianRtt(): Long {
        val sorted = rttWindow.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2
        } else {
            sorted[mid]
        }
    }

    /**
     * Decreases the chunk size by one tier, clamped at tier 0.
     * Resets the consecutive-good counter so we must re-earn an increase.
     */
    private fun decreaseTier(rttMs: Long, baseline: Long) {
        val prev = currentChunkSize
        tierIndex = maxOf(tierIndex - 1, 0)
        consecutiveGoodAcks = 0
        Log.d(
            TAG,
            "RTT spike ($rttMs ms > ${(baseline * RTT_SPIKE_FACTOR).toLong()} ms); " +
                "chunk size ${prev / 1_024} KB → ${currentChunkSize / 1_024} KB (tier $tierIndex)"
        )
    }

    /**
     * Increases the chunk size by one tier, clamped at the last tier.
     */
    private fun increaseTier(baseline: Long) {
        val prev = currentChunkSize
        tierIndex = minOf(tierIndex + 1, CHUNK_SIZE_TIERS.size - 1)
        Log.d(
            TAG,
            "$GOOD_ACKS_BEFORE_INCREASE good ACKs (baseline ${baseline} ms); " +
                "chunk size ${prev / 1_024} KB → ${currentChunkSize / 1_024} KB (tier $tierIndex)"
        )
    }
}
