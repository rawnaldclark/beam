package com.zaptransfer.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaptransfer.android.data.db.entity.OfflineQueueEntity

/**
 * DAO for the offline_queue table.
 *
 * The offline queue is a stretch-goal feature (spec §8.7). Entries are created
 * when the user tries to send to an offline device; the repository polls when
 * a presence "online" event arrives for that device.
 *
 * All reads return plain Lists (not Flows) because:
 *  1. Queue consumption is event-driven (presence change), not continuously observed.
 *  2. Flows would create overhead for an infrequently-used feature.
 *
 * Cap enforcement: max 10 PENDING entries per device is enforced at enqueue time
 * by the repository querying [countPending] before calling [insert].
 */
@Dao
interface OfflineQueueDao {

    /**
     * Return all PENDING entries for a given target device, ordered by enqueued_at ASC
     * so the oldest queued intent is sent first (FIFO).
     *
     * Only PENDING status is returned — SENDING entries from a previous crashed
     * session are intentionally excluded here. The repository must reset SENDING →
     * PENDING on startup if recovery is needed.
     */
    @Query(
        """
        SELECT * FROM offline_queue
        WHERE target_device_id = :deviceId
          AND status = 'PENDING'
          AND expires_at > :nowEpochMs
        ORDER BY enqueued_at ASC
        """
    )
    suspend fun getPendingForDevice(deviceId: String, nowEpochMs: Long): List<OfflineQueueEntity>

    /**
     * Return PENDING + SENDING entries for ALL devices that have not yet expired.
     * Used on app startup to audit queue state and reset stale SENDING rows.
     */
    @Query(
        """
        SELECT * FROM offline_queue
        WHERE status IN ('PENDING', 'SENDING')
          AND expires_at > :nowEpochMs
        ORDER BY enqueued_at ASC
        """
    )
    suspend fun getAllActive(nowEpochMs: Long): List<OfflineQueueEntity>

    /**
     * Insert a new queue entry.
     * ABORT conflict strategy: the auto-generated queueId is unique; an ABORT would
     * only fire on a programming error (manually specified duplicate ID), so this is
     * the safest default.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: OfflineQueueEntity): Long

    /**
     * Update the [status] field of a single queue entry.
     * Transitions: PENDING → SENDING (pick up), SENDING → COMPLETED / FAILED_FILE_MISSING.
     *
     * @param queueId  The entry to update.
     * @param status   New status string.
     */
    @Query("UPDATE offline_queue SET status = :status WHERE queue_id = :queueId")
    suspend fun updateStatus(queueId: Long, status: String)

    /**
     * Delete all entries whose [expiresAt] is strictly less than [nowEpochMs].
     * Called on every relay reconnect and app startup.
     *
     * The repository should attempt to delete temp file URIs for FILE-type entries
     * before calling this, to avoid orphaned content resolver grants.
     *
     * @param nowEpochMs Current Unix epoch milliseconds.
     * @return Number of rows deleted (for logging).
     */
    @Query("DELETE FROM offline_queue WHERE expires_at <= :nowEpochMs")
    suspend fun deleteExpired(nowEpochMs: Long): Int

    /**
     * Count PENDING entries for a specific device.
     * Used by the repository to enforce the max-10-per-device cap at enqueue time.
     *
     * @param deviceId The target device.
     */
    @Query(
        """
        SELECT COUNT(*) FROM offline_queue
        WHERE target_device_id = :deviceId
          AND status = 'PENDING'
        """
    )
    suspend fun countPending(deviceId: String): Int

    /**
     * Delete a specific queue entry by its primary key.
     * Used after a successful send or explicit user cancellation.
     */
    @Query("DELETE FROM offline_queue WHERE queue_id = :queueId")
    suspend fun deleteById(queueId: Long)
}
