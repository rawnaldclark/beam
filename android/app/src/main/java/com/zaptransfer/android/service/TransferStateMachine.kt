package com.zaptransfer.android.service

import android.util.Log

private const val TAG = "TransferStateMachine"

/**
 * Ordered set of states a single transfer session can occupy.
 *
 * Valid transitions are documented on [TransferStateMachine]. States outside
 * the enumeration are structurally impossible because Kotlin enums are closed.
 */
enum class TransferState {
    /**
     * No active transfer. The machine starts here and returns here after any
     * terminal state is acknowledged.
     */
    IDLE,

    /**
     * The sender has initiated a transfer (SENT the metadata envelope) and is
     * awaiting the receiver's ACCEPT or DECLINE.
     */
    REQUESTING,

    /**
     * The receiver has received the metadata envelope and is waiting for the
     * local user or policy to accept or decline the incoming transfer.
     */
    AWAITING_ACCEPT,

    /**
     * Both peers have agreed to the transfer. Chunks are being sent and received.
     */
    TRANSFERRING,

    /**
     * All chunks have been sent/received. The receiver is computing its final hash
     * and comparing it to the sender's declared hash from the metadata envelope.
     */
    VERIFYING,

    /**
     * The transfer completed successfully and the integrity hash matched.
     * Terminal state — machine must be reset to [IDLE] before reuse.
     */
    COMPLETE,

    /**
     * The receiving peer explicitly declined the incoming request.
     * Terminal state — machine must be reset to [IDLE] before reuse.
     */
    DECLINED,

    /**
     * Either peer paused the transfer. Flow controller will resume when the
     * window reopens or the user manually resumes.
     */
    PAUSED,

    /**
     * An unrecoverable error occurred (network failure, hash mismatch, crypto error).
     * Terminal state — machine must be reset to [IDLE] before reuse.
     */
    FAILED,
}

/**
 * Enforces legal [TransferState] transitions for a single transfer session.
 *
 * ## Why a state machine?
 * The transfer protocol has many edge cases — retransmissions, out-of-order ACKs,
 * concurrent send/receive paths. By centralising transition logic here, the
 * [TransferEngine] cannot accidentally skip a required step or revisit an
 * already-terminal state. Any illegal transition immediately throws, making
 * bugs surface at the point of the programming error rather than silently
 * producing corrupted data.
 *
 * ## Legal transitions
 * ```
 * IDLE            → REQUESTING (sender initiates)
 * IDLE            → AWAITING_ACCEPT (receiver gets metadata)
 * REQUESTING      → TRANSFERRING (receiver sent ACCEPT)
 * REQUESTING      → DECLINED (receiver sent DECLINE)
 * REQUESTING      → FAILED (network/timeout)
 * AWAITING_ACCEPT → TRANSFERRING (local accept)
 * AWAITING_ACCEPT → DECLINED (local decline)
 * AWAITING_ACCEPT → FAILED (timeout before decision)
 * TRANSFERRING    → VERIFYING (last chunk sent/received)
 * TRANSFERRING    → PAUSED (flow control back-pressure or user pause)
 * TRANSFERRING    → FAILED (crypto error, network drop)
 * VERIFYING       → COMPLETE (hash matches)
 * VERIFYING       → FAILED (hash mismatch or timeout)
 * PAUSED          → TRANSFERRING (resume)
 * PAUSED          → FAILED (give up)
 * COMPLETE        → IDLE (reset for reuse)
 * DECLINED        → IDLE (reset for reuse)
 * FAILED          → IDLE (reset for reuse)
 * ```
 *
 * All other transitions throw [IllegalStateException].
 *
 * Thread safety: NOT thread-safe. The [TransferEngine] must call [transition] on
 * a single coroutine context (its internal single-threaded dispatcher) to prevent
 * concurrent mutations of [current].
 */
class TransferStateMachine {

    /** Current state. Read by the TransferEngine to determine next action. */
    @Volatile
    var current: TransferState = TransferState.IDLE
        private set

    /**
     * Attempts to move from [current] to [next].
     *
     * Logs the transition at DEBUG level. Logs at WARN on illegal attempts before
     * throwing — the stack trace will pinpoint the offending call site.
     *
     * @param next  The target state.
     * @throws IllegalStateException if the transition [current] → [next] is not in
     *         the legal transition table.
     */
    fun transition(next: TransferState) {
        val from = current
        if (!isLegal(from, next)) {
            val msg = "Illegal transfer state transition: $from → $next"
            Log.w(TAG, msg)
            throw IllegalStateException(msg)
        }
        current = next
        Log.d(TAG, "Transfer state: $from → $next")
    }

    /**
     * Resets the machine to [TransferState.IDLE] unconditionally, bypassing the
     * transition table. Use this only from error-recovery paths where the current
     * state is unknown or already corrupted.
     *
     * Unlike [transition], this method never throws.
     */
    fun forceReset() {
        val prev = current
        current = TransferState.IDLE
        Log.w(TAG, "Transfer state force-reset: $prev → IDLE")
    }

    // ── Transition table ───────────────────────────────────────────────────────

    /**
     * Returns true if the [from] → [to] transition is permitted by the protocol.
     *
     * Using a when expression (exhaustive on [from]) guarantees that adding a new
     * [TransferState] variant will produce a compile-time warning if this table is
     * not updated.
     */
    private fun isLegal(from: TransferState, to: TransferState): Boolean = when (from) {
        TransferState.IDLE -> to in setOf(
            TransferState.REQUESTING,
            TransferState.AWAITING_ACCEPT,
        )
        TransferState.REQUESTING -> to in setOf(
            TransferState.TRANSFERRING,
            TransferState.DECLINED,
            TransferState.FAILED,
        )
        TransferState.AWAITING_ACCEPT -> to in setOf(
            TransferState.TRANSFERRING,
            TransferState.DECLINED,
            TransferState.FAILED,
        )
        TransferState.TRANSFERRING -> to in setOf(
            TransferState.VERIFYING,
            TransferState.PAUSED,
            TransferState.FAILED,
        )
        TransferState.VERIFYING -> to in setOf(
            TransferState.COMPLETE,
            TransferState.FAILED,
        )
        TransferState.PAUSED -> to in setOf(
            TransferState.TRANSFERRING,
            TransferState.FAILED,
        )
        // Terminal states — only transition allowed is back to IDLE for machine reuse
        TransferState.COMPLETE -> to == TransferState.IDLE
        TransferState.DECLINED -> to == TransferState.IDLE
        TransferState.FAILED -> to == TransferState.IDLE
    }
}
