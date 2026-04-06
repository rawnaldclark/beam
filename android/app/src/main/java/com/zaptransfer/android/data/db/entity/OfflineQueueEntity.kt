package com.zaptransfer.android.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An outbound transfer intent queued while the target device is offline.
 *
 * This is a stretch-goal feature (spec §8.7): when the user tries to send to an
 * offline device, the intent is persisted here with a 24-hour TTL. The
 * DeviceRepository monitors presence events; when [targetDeviceId] comes online,
 * the pending queue entries for that device are read and sent automatically, then
 * the entry status is updated to COMPLETED or FAILED_FILE_MISSING.
 *
 * Caps: max 10 pending entries per device (enforced at enqueue time in the repository).
 *
 * [contentOrUri] semantics depend on [type]:
 *  - "FILE": an absolute content:// or file:// URI valid in this process.
 *    IMPORTANT: acquire a persistent URI permission at enqueue time so the URI
 *    survives across process restarts.
 *  - "TEXT": the literal text/URL content (≤ 64 KB).
 *
 * [expiresAt] = [enqueuedAt] + 24h. Expired rows are purged by
 * [OfflineQueueDao.deleteExpired] on each relay reconnect and app startup.
 */
@Entity(
    tableName = "offline_queue",
    indices = [
        Index("target_device_id"),
        Index("status"),
        Index("expires_at"),
    ]
)
data class OfflineQueueEntity(

    /** Auto-generated surrogate key. */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "queue_id")
    val queueId: Long = 0,

    /** Device ID of the intended recipient. */
    @ColumnInfo(name = "target_device_id")
    val targetDeviceId: String,

    /**
     * Transfer type.
     * Valid values: "FILE", "TEXT".
     */
    @ColumnInfo(name = "type")
    val type: String,

    /**
     * For FILE: content:// or file:// URI string with acquired persistent URI permission.
     * For TEXT: literal clipboard/text content (≤ 64 KB).
     */
    @ColumnInfo(name = "content_or_uri")
    val contentOrUri: String,

    /** Original file name, used as the fileName in the transfer metadata envelope. Null for TEXT. */
    @ColumnInfo(name = "file_name")
    val fileName: String?,

    /** File byte size for display and protocol metadata. Null for TEXT transfers. */
    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long?,

    /** Unix epoch milliseconds when this entry was created. */
    @ColumnInfo(name = "enqueued_at")
    val enqueuedAt: Long,

    /** Unix epoch milliseconds when this entry expires — enqueuedAt + 24 hours. */
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,

    /**
     * Current lifecycle state of this queue entry.
     * Valid values:
     *  - "PENDING": waiting for device to come online.
     *  - "SENDING": actively being transferred (prevents double-send on reconnect race).
     *  - "EXPIRED": TTL elapsed without successful send; will be purged.
     *  - "COMPLETED": transfer finished successfully.
     *  - "FAILED_FILE_MISSING": file URI was no longer accessible at send time.
     */
    @ColumnInfo(name = "status")
    val status: String,
)
