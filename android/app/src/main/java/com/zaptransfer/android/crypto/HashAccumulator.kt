package com.zaptransfer.android.crypto

import java.security.MessageDigest
import java.util.TreeMap

/**
 * Incrementally accumulates a SHA-256 hash over a stream of chunks that may
 * arrive out of order.
 *
 * Problem: WebRTC data channels and relay connections deliver chunks in roughly
 * sequential order, but network reordering is possible. The final transfer hash
 * must be computed over the chunks in ascending index order regardless of
 * arrival order.
 *
 * Solution: a [TreeMap] reorder buffer holds chunks whose predecessors have not
 * yet been fed. As soon as a contiguous run of in-order chunks is available,
 * they are drained into the [MessageDigest] and the buffer is compacted.
 *
 * Design constraints:
 *  - Maximum reorder buffer depth: [MAX_BUFFERED_CHUNKS] (32). If a chunk arrives
 *    more than 32 positions ahead of the expected index, it will still be buffered,
 *    but callers must ensure the sender respects the receive window; otherwise
 *    memory usage becomes unbounded. A future enhancement would apply back-pressure.
 *  - [finalize] may only be called once. Subsequent calls throw [IllegalStateException].
 *  - After [finalize] the object is sealed — [feedChunk] will also throw.
 *
 * Thread safety: NOT thread-safe. External synchronization is required if chunks
 * arrive from multiple coroutine contexts. The typical usage pattern (single
 * receive coroutine) requires no synchronization.
 *
 * Usage:
 * ```kotlin
 * val acc = HashAccumulator()
 * chunks.forEach { (index, data) -> acc.feedChunk(index, data) }
 * val hash = acc.finalize()
 * ```
 */
class HashAccumulator {

    /** SHA-256 digest updated incrementally as in-order chunks become available. */
    private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")

    /**
     * Out-of-order chunk buffer, keyed by chunk index.
     * [TreeMap] gives O(log n) insert/lookup and O(1) access to the minimum key,
     * which is what we need when draining the front of the buffer.
     */
    private val buffer: TreeMap<Long, ByteArray> = TreeMap()

    /** The next chunk index we expect to feed into the digest. */
    private var nextExpectedIndex: Long = 0L

    /** True after [finalize] has been called. */
    private var finalized: Boolean = false

    /**
     * Maximum number of out-of-order chunks held in the reorder buffer before
     * issuing a warning. This is not enforced as a hard limit to avoid dropping
     * data, but callers should treat it as a flow-control signal.
     */
    companion object {
        const val MAX_BUFFERED_CHUNKS: Int = 32
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Feeds a chunk into the accumulator.
     *
     * If [index] equals [nextExpectedIndex], the chunk is fed directly into the
     * [MessageDigest] and the buffer is drained of any contiguous follow-on chunks.
     *
     * If [index] > [nextExpectedIndex], the chunk is stored in the reorder buffer
     * and will be fed when its predecessors arrive.
     *
     * Duplicate indices (index < nextExpectedIndex) are silently ignored — this
     * handles retransmission scenarios without corrupting the hash.
     *
     * @param index Zero-based chunk index.
     * @param data  Decrypted, unpadded chunk bytes (exactly as they should contribute
     *              to the content hash). Do NOT pass encrypted or padded bytes.
     * @throws IllegalStateException if [finalize] has already been called.
     * @throws IllegalArgumentException if [index] is negative.
     */
    fun feedChunk(index: Long, data: ByteArray) {
        check(!finalized) { "HashAccumulator.feedChunk called after finalize()" }
        require(index >= 0) { "Chunk index must be non-negative, got $index" }

        if (index < nextExpectedIndex) {
            // Duplicate or retransmitted chunk — already hashed, discard silently.
            return
        }

        // Buffer the chunk (or overwrite if this is a retransmission of a buffered chunk)
        buffer[index] = data

        // Drain any contiguous run from the front of the buffer into the digest
        drainBuffer()
    }

    /**
     * Finalizes the hash computation and returns the 32-byte SHA-256 digest.
     *
     * If there are un-drained chunks in the reorder buffer (i.e., the stream ended
     * with a gap), this method throws [IllegalStateException] — a hash over an
     * incomplete stream would be meaningless for integrity verification.
     *
     * This method may only be called once. Subsequent calls throw [IllegalStateException].
     *
     * @return 32-byte SHA-256 digest over all chunks in ascending index order.
     * @throws IllegalStateException if there are buffered chunks not yet fed to the
     *         digest (indicating missing preceding chunks), or if called more than once.
     */
    fun finalize(): ByteArray {
        check(!finalized) { "HashAccumulator.finalize() called more than once" }
        finalized = true

        check(buffer.isEmpty()) {
            "HashAccumulator finalized with ${buffer.size} un-drained chunks still in the " +
                "reorder buffer. Missing chunks with indices < ${buffer.firstKey()}. " +
                "Chunks buffered: ${buffer.keys}"
        }

        return digest.digest()
    }

    /**
     * Returns the number of chunks currently held in the reorder buffer.
     * Useful for flow-control decisions and monitoring.
     */
    val bufferedChunkCount: Int get() = buffer.size

    /**
     * Returns the next chunk index that the accumulator is waiting to hash.
     * Any chunk with index < this value has already been fed into the digest.
     */
    val nextExpected: Long get() = nextExpectedIndex

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Drains contiguous chunks from the front of [buffer] into [digest].
     *
     * Continues as long as the smallest key in the buffer equals [nextExpectedIndex],
     * feeding each chunk's bytes into [MessageDigest.update] and incrementing
     * [nextExpectedIndex].
     *
     * O(k * log n) where k is the number of chunks drained and n is the buffer size.
     */
    private fun drainBuffer() {
        while (buffer.isNotEmpty() && buffer.firstKey() == nextExpectedIndex) {
            val data = buffer.remove(nextExpectedIndex)!!
            digest.update(data)
            nextExpectedIndex++
        }
    }
}
