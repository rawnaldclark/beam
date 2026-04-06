package com.zaptransfer.android.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zaptransfer.android.data.db.entity.ClipboardEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the clipboard_entries table.
 *
 * The clipboard history is capped at 20 total entries across all paired devices.
 * The capping logic is enforced by the repository layer:
 *
 *   1. Call [getCount].
 *   2. If count >= 20, call [deleteOldest] (deletes the single row with the
 *      smallest entry_id — i.e., the oldest received item).
 *   3. Call [insert].
 *
 * This is intentionally not done in a DAO transaction here because Room does
 * not allow multiple @Query annotations in a single @Transaction method with
 * different return types without a wrapper. The repository uses a
 * withTransaction { } block from room-ktx for atomicity.
 */
@Dao
interface ClipboardDao {

    /**
     * Observe the most recent [limit] clipboard entries across all devices,
     * ordered newest-first. [limit] is typically 20.
     */
    @Query(
        """
        SELECT * FROM clipboard_entries
        ORDER BY received_at DESC
        LIMIT :limit
        """
    )
    fun getRecent(limit: Int): Flow<List<ClipboardEntryEntity>>

    /**
     * Observe clipboard entries from a specific device.
     * Useful for per-device filtering on the clipboard history screen.
     */
    @Query(
        """
        SELECT * FROM clipboard_entries
        WHERE device_id = :deviceId
        ORDER BY received_at DESC
        LIMIT :limit
        """
    )
    fun getRecentForDevice(deviceId: String, limit: Int): Flow<List<ClipboardEntryEntity>>

    /**
     * Insert a new clipboard entry.
     * ABORT conflict strategy: duplicate inserts (same content from same device in
     * rapid succession) will throw and roll back — the repository should de-dupe.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: ClipboardEntryEntity): Long

    /**
     * Return the total number of rows in clipboard_entries.
     * Used by the repository to decide whether to prune before inserting.
     */
    @Query("SELECT COUNT(*) FROM clipboard_entries")
    suspend fun getCount(): Int

    /**
     * Delete the single oldest entry (lowest entry_id = first inserted).
     * Called when the total count is at the 20-item cap before a new insert.
     *
     * Uses a correlated subquery to identify the target row, which works
     * reliably on both WAL and journal modes.
     */
    @Query(
        """
        DELETE FROM clipboard_entries
        WHERE entry_id = (
            SELECT entry_id FROM clipboard_entries ORDER BY entry_id ASC LIMIT 1
        )
        """
    )
    suspend fun deleteOldest()

    /**
     * Delete all clipboard entries — used when the user clears history or when
     * the originating device is unpaired and the user opts to wipe its data.
     */
    @Query("DELETE FROM clipboard_entries")
    suspend fun deleteAll()

    /**
     * Delete all entries from a specific device.
     * Called when a device is unpaired if the user consents to data removal.
     */
    @Query("DELETE FROM clipboard_entries WHERE device_id = :deviceId")
    suspend fun deleteAllForDevice(deviceId: String)
}
