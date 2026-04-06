package com.zaptransfer.android.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Resume checkpoint for an in-progress file transfer.
 *
 * Written to Room every 64 chunks (or at most every 10 seconds) during active
 * receiving. On process death, [ZapTransferApplication.onCreate] queries this
 * table for incomplete transfers and restarts the TransferForegroundService to
 * resume from [lastAckedChunk] + 1.
 *
 * Rows expire 24 hours after [updatedAt] — stale entries are purged by
 * [ChunkProgressDao.deleteStale] on application startup, and their [tempFilePath]
 * partial files are deleted by FileUtil.cleanStaleTempFiles().
 *
 * [sha256State]: serialised MessageDigest state (32-byte rolling hash accumulator
 * snapshot). Allows the incremental SHA-256 to resume without re-hashing chunks
 * 0..lastAckedChunk. Null if state serialisation is unavailable on this API level.
 *
 * NOTE: [tempFilePath] is an absolute file path into the app's cacheDir — not a
 * content URI — because we need random-write access for chunk reassembly before
 * the file is moved to the final URI.
 */
@Entity(tableName = "chunk_progress")
data class ChunkProgressEntity(

    /** Transfer UUID — matches [TransferHistoryEntity.transferId]. */
    @PrimaryKey
    @ColumnInfo(name = "transfer_id")
    val transferId: String,

    /** Total number of chunks the sender declared in the transfer metadata. */
    @ColumnInfo(name = "total_chunks")
    val totalChunks: Int,

    /**
     * Index of the last chunk that has been fully received, decrypted, written to
     * [tempFilePath], and ACK'd to the sender.
     * Resume from lastAckedChunk + 1.
     * Value is -1 if no chunks have been ACK'd yet (transfer just started).
     */
    @ColumnInfo(name = "last_acked_chunk")
    val lastAckedChunk: Int,

    /**
     * Absolute path to the partial reassembly file in the app's cache directory.
     * Created at transfer start, moved to the final location on COMPLETE.
     * Deleted by cleanup if transfer is stale.
     */
    @ColumnInfo(name = "temp_file_path")
    val tempFilePath: String,

    /**
     * Serialised state of the incremental SHA-256 accumulator, produced by
     * HashAccumulator.serializeState(). Null if unavailable.
     * Allows deterministic resume of integrity verification across process deaths.
     */
    @ColumnInfo(name = "sha256_state")
    val sha256State: ByteArray?,

    /** Unix epoch milliseconds of the most recent checkpoint write. Used for stale detection. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    // ByteArray content equality for sha256State
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkProgressEntity) return false
        return transferId == other.transferId &&
            totalChunks == other.totalChunks &&
            lastAckedChunk == other.lastAckedChunk &&
            tempFilePath == other.tempFilePath &&
            (sha256State?.contentEquals(other.sha256State ?: byteArrayOf()) ?: (other.sha256State == null)) &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + totalChunks
        result = 31 * result + lastAckedChunk
        result = 31 * result + tempFilePath.hashCode()
        result = 31 * result + (sha256State?.contentHashCode() ?: 0)
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
