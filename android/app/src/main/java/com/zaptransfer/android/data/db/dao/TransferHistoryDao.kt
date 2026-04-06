package com.zaptransfer.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaptransfer.android.data.db.entity.TransferHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the transfer_history table.
 *
 * Records are written once at transfer completion; subsequent calls only update
 * the [status], [completedAt], [sha256Hash], and [localUri] fields via
 * [updateCompletion]. No full-entity Update is exposed — the record is otherwise
 * immutable.
 *
 * Flows are used for the Device Hub's recent-transfers list so it updates
 * automatically when a background service completes a transfer.
 */
@Dao
interface TransferHistoryDao {

    /**
     * Observe the most recent [limit] transfers, ordered by startedAt descending.
     * [limit] is typically 20 for the Device Hub list.
     * Emits a new list whenever any row changes.
     */
    @Query(
        """
        SELECT * FROM transfer_history
        ORDER BY started_at DESC
        LIMIT :limit
        """
    )
    fun getRecent(limit: Int): Flow<List<TransferHistoryEntity>>

    /**
     * Observe the most recent [limit] transfers for a specific device.
     * Used on the per-device history detail screen.
     */
    @Query(
        """
        SELECT * FROM transfer_history
        WHERE device_id = :deviceId
        ORDER BY started_at DESC
        LIMIT :limit
        """
    )
    fun getRecentForDevice(deviceId: String, limit: Int): Flow<List<TransferHistoryEntity>>

    /**
     * Single-shot lookup by transfer ID.
     * Used by the Transfer Progress and Complete screens.
     */
    @Query("SELECT * FROM transfer_history WHERE transfer_id = :transferId LIMIT 1")
    suspend fun getById(transferId: String): TransferHistoryEntity?

    /**
     * Insert a new history record.
     * IGNORE conflict strategy: if a record with the same transferId somehow
     * already exists, do nothing rather than overwriting the existing entry.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TransferHistoryEntity)

    /**
     * Update the terminal fields of a history record once the transfer finishes.
     * Called by the TransferEngine when status reaches COMPLETED, FAILED, or CANCELLED.
     *
     * @param transferId  The transfer whose record should be updated.
     * @param status      Terminal status string: "COMPLETED", "FAILED", or "CANCELLED".
     * @param sha256Hash  Verified hash hex string; null on failure/cancellation.
     * @param localUri    Content or file URI of the saved file; null on failure/cancellation.
     * @param completedAt Unix epoch milliseconds of completion.
     */
    @Query(
        """
        UPDATE transfer_history
        SET status       = :status,
            sha256_hash  = :sha256Hash,
            local_uri    = :localUri,
            completed_at = :completedAt
        WHERE transfer_id = :transferId
        """
    )
    suspend fun updateCompletion(
        transferId: String,
        status: String,
        sha256Hash: String?,
        localUri: String?,
        completedAt: Long,
    )

    /**
     * Delete a single transfer history record by its ID.
     * Typically called from a "clear this item" swipe gesture in the history list.
     */
    @Query("DELETE FROM transfer_history WHERE transfer_id = :transferId")
    suspend fun deleteById(transferId: String)

    /**
     * Delete all history records older than [cutoffTime] (epoch milliseconds).
     * Used for housekeeping; the spec does not mandate automatic pruning,
     * but this is provided for future use.
     */
    @Query("DELETE FROM transfer_history WHERE started_at < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long)
}
