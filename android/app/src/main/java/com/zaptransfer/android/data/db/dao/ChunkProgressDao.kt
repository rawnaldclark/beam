package com.zaptransfer.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zaptransfer.android.data.db.entity.ChunkProgressEntity

/**
 * DAO for the chunk_progress table.
 *
 * Unlike other DAOs this one has no Flow-returning queries: the TransferEngine
 * reads progress synchronously at startup during crash-recovery, and writes
 * happen on a background IO thread periodically during active transfers.
 *
 * All reads return nullable/List rather than Flow because:
 *  1. Progress is only needed once at startup (not observed reactively).
 *  2. Frequent Flow emissions during chunk ACKs would create unnecessary overhead.
 */
@Dao
interface ChunkProgressDao {

    /**
     * Look up the in-progress checkpoint for a specific transfer.
     * Returns null if no checkpoint has been saved (transfer not yet started or already cleaned up).
     */
    @Query("SELECT * FROM chunk_progress WHERE transfer_id = :transferId LIMIT 1")
    suspend fun getByTransferId(transferId: String): ChunkProgressEntity?

    /**
     * Return all transfers that have not yet reached totalChunks - 1.
     * Called during Application.onCreate to find transfers needing recovery.
     * "Incomplete" means lastAckedChunk < totalChunks - 1.
     *
     * Returns a plain List — this is a one-shot query, not an observable stream.
     */
    @Query(
        """
        SELECT * FROM chunk_progress
        WHERE last_acked_chunk < total_chunks - 1
        ORDER BY updated_at ASC
        """
    )
    suspend fun getIncomplete(): List<ChunkProgressEntity>

    /**
     * Insert a fresh checkpoint record when a transfer begins.
     * REPLACE handles the rare case where a crash left a stale row for the same transferId.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ChunkProgressEntity)

    /**
     * Overwrite the checkpoint with updated progress.
     * Called every 64 chunks (or 10 seconds, whichever is sooner) during receive.
     */
    @Update
    suspend fun update(entity: ChunkProgressEntity)

    /**
     * Convenience update that only writes the fields that change per-checkpoint,
     * avoiding a full entity read-modify-write cycle in the hot path.
     *
     * @param transferId      Identifies the row to update.
     * @param lastAckedChunk  Index of the most recently ACK'd chunk.
     * @param sha256State     Serialised incremental hash state (may be null).
     * @param updatedAt       Current epoch milliseconds.
     */
    @Query(
        """
        UPDATE chunk_progress
        SET last_acked_chunk = :lastAckedChunk,
            sha256_state     = :sha256State,
            updated_at       = :updatedAt
        WHERE transfer_id = :transferId
        """
    )
    suspend fun updateProgress(
        transferId: String,
        lastAckedChunk: Int,
        sha256State: ByteArray?,
        updatedAt: Long,
    )

    /**
     * Delete the checkpoint for a specific transfer once it has completed or been
     * definitively abandoned. Called from TransferEngine when status is terminal.
     */
    @Query("DELETE FROM chunk_progress WHERE transfer_id = :transferId")
    suspend fun delete(transferId: String)

    /**
     * Delete all checkpoints whose [updatedAt] timestamp is older than [cutoffTime].
     * Called on app startup to remove orphaned checkpoints from process-kill scenarios
     * where the transfer cannot be resumed (e.g. connection state lost, 24h elapsed).
     *
     * Callers should also delete the corresponding temp files before calling this.
     *
     * @param cutoffTime Unix epoch milliseconds; rows with updatedAt < cutoffTime are deleted.
     */
    @Query("DELETE FROM chunk_progress WHERE updated_at < :cutoffTime")
    suspend fun deleteStale(cutoffTime: Long)
}
