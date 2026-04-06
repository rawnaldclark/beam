package com.zaptransfer.android.ui.devicehub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zaptransfer.android.data.db.entity.TransferHistoryEntity
import java.text.DecimalFormat
import kotlin.math.abs

/**
 * Single row in the recent-transfers list on the Device Hub screen.
 *
 * Displays:
 *  - Direction arrow icon (up = sent, down = received).
 *  - File name (single-line, ellipsized at end).
 *  - Peer device name (if provided) and relative time string.
 *  - Human-readable file size.
 *
 * Stateless — accepts an [entity] and an optional [peerName] resolved by the
 * caller (the ViewModel must join [TransferHistoryEntity.deviceId] → device name).
 *
 * @param entity    The completed or failed transfer record from Room.
 * @param peerName  Display name of the remote device; null if the device was unpaired.
 * @param modifier  Layout modifier applied to the root [Row].
 */
@Composable
fun TransferHistoryItem(
    entity: TransferHistoryEntity,
    peerName: String?,
    modifier: Modifier = Modifier,
) {
    val isSent = entity.direction == "SENT"
    val relativeTime = remember(entity.startedAt) { formatRelativeTime(entity.startedAt) }
    val sizeLabel = remember(entity.fileSizeBytes) { formatFileSize(entity.fileSizeBytes) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Direction arrow icon
        Icon(
            imageVector = if (isSent) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
            contentDescription = if (isSent) "Sent" else "Received",
            tint = if (isSent)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        // File name + peer/time row
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entity.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                if (!peerName.isNullOrBlank()) {
                    append(peerName)
                    append(" · ")
                }
                append(relativeTime)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // File size
        Text(
            text = sizeLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Formatting helpers ─────────────────────────────────────────────────────────

/**
 * Formats an epoch-millisecond timestamp as a human-readable relative time string.
 *
 * Resolution tiers:
 *  - < 60 s    → "just now"
 *  - < 60 min  → "N min ago"
 *  - < 24 h    → "N h ago"
 *  - >= 24 h   → "N d ago"
 *
 * @param epochMs Unix epoch milliseconds of the event.
 * @return Relative time string, always in English.
 */
private fun formatRelativeTime(epochMs: Long): String {
    val deltaSec = abs(System.currentTimeMillis() - epochMs) / 1_000L
    return when {
        deltaSec < 60L -> "just now"
        deltaSec < 3_600L -> "${deltaSec / 60} min ago"
        deltaSec < 86_400L -> "${deltaSec / 3_600} h ago"
        else -> "${deltaSec / 86_400} d ago"
    }
}

/**
 * Formats a raw byte count into a compact SI-prefixed string.
 *
 * Examples: 512 → "512 B", 1536 → "1.5 KB", 1_048_576 → "1.0 MB".
 *
 * @param bytes Non-negative byte count.
 * @return Human-readable size string with one decimal place for KB/MB/GB.
 */
private fun formatFileSize(bytes: Long): String {
    val fmt = DecimalFormat("0.#")
    return when {
        bytes < 1_024L -> "$bytes B"
        bytes < 1_048_576L -> "${fmt.format(bytes / 1_024.0)} KB"
        bytes < 1_073_741_824L -> "${fmt.format(bytes / 1_048_576.0)} MB"
        else -> "${fmt.format(bytes / 1_073_741_824.0)} GB"
    }
}
