package com.zaptransfer.android.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single entry in the clipboard history — text or URL received from a paired device.
 *
 * Capped at 20 entries total across all devices. When a new entry would exceed the
 * cap, [ClipboardDao.deleteOldest] removes the entry with the smallest [entryId]
 * (i.e., the oldest received item) before inserting.
 *
 * [content] is stored as received (UTF-8). The sender's metadata declares [isUrl];
 * the receiver trusts this flag for displaying the "Open in Browser" action. Very
 * long content is truncated by the sender to ≤ 64 KB per the relay message size limit.
 *
 * This table is indexed by [receivedAt] DESC for the history screen's display order,
 * and by [deviceId] if per-device filtering is needed.
 */
@Entity(
    tableName = "clipboard_entries",
    indices = [
        Index("received_at"),
        Index("device_id"),
    ]
)
data class ClipboardEntryEntity(

    /** Auto-generated surrogate key. Used by deleteOldest() to find the oldest entry. */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "entry_id")
    val entryId: Long = 0,

    /** Device ID of the sender. May not exist in paired_devices if device was unpaired. */
    @ColumnInfo(name = "device_id")
    val deviceId: String,

    /** UTF-8 clipboard content, ≤ 64 KB (relay message limit). */
    @ColumnInfo(name = "content")
    val content: String,

    /**
     * True if the sender classified this as a URL (triggers "Open in Browser" action).
     * Set from the clipboard message's [autoCopy] hint and content heuristic.
     */
    @ColumnInfo(name = "is_url")
    val isUrl: Boolean,

    /** Unix epoch milliseconds when this item was received and auto-copied. */
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
)
