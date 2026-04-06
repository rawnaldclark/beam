package com.zaptransfer.android.ui.devicehub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen onboarding composable shown when no paired devices exist.
 *
 * Presents a concise call-to-action for the two pairing entry points:
 *  - Primary: "Scan QR Code" — leads to the CameraX QR scanner.
 *  - Secondary: "Enter PIN instead" — leads to the 8-digit PIN entry screen.
 *
 * This composable is stateless; it receives callbacks for all user interactions
 * and carries no local state of its own.
 *
 * @param onScanQrCode      Called when the user taps the primary "Scan QR Code" button.
 * @param onEnterPin        Called when the user taps the "Enter PIN instead" text button.
 * @param modifier          Layout modifier applied to the root [Column].
 */
@Composable
fun EmptyStateOnboarding(
    onScanQrCode: () -> Unit,
    onEnterPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Device illustration — uses a large icon glyph to avoid a bespoke drawable dep.
        // A future design pass can replace this with a vector illustration asset.
        Text(
            text = "\uD83D\uDCF1",  // 📱 device emoji as a scalable illustration stand-in
            fontSize = 72.sp,
            modifier = Modifier.size(96.dp),
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Primary heading
        Text(
            text = "Pair Your First Device",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Supporting description
        Text(
            text = "Scan the QR code shown in the Beam browser extension to securely link your devices.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Primary action: open QR scanner
        Button(
            onClick = onScanQrCode,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(text = "Scan QR Code")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Secondary action: fall back to PIN entry
        TextButton(onClick = onEnterPin) {
            Text(
                text = "Enter PIN instead",
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
