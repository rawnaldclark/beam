package com.zaptransfer.android.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SAS (Short Authentication String) emoji verification screen — Phase D, Task 14.
 *
 * Displays the 4 emoji derived from the X25519 key exchange and prompts the user
 * to visually confirm they match the emoji shown on the paired device (Chrome extension).
 *
 * The emoji table is identical between this app and the Chrome extension (spec §4.4.2).
 * Both sides must derive the same 4 emoji from the shared secret — a mismatch means
 * a MITM attack is in progress and the user should tap "No Match".
 *
 * Layout:
 *  - Instruction text explaining the verification step
 *  - 4 large emoji in a Row, each with a text label below
 *  - "They Match" filled button (green accent)
 *  - "No Match" outlined button (error colour)
 *
 * Navigation triggers:
 *  - "They Match" → [viewModel.onSasConfirmed] → [PairingUiState.Naming] → [onNavigateToNaming]
 *  - "No Match"   → [viewModel.onSasDenied]    → [PairingUiState.Scanning] → [onBack] twice
 *
 * @param viewModel         Shared [PairingViewModel]; must be in [PairingUiState.Verifying].
 * @param onNavigateToNaming Callback to push the device naming screen.
 * @param onBack             Pop back to the scanner (after "No Match").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SasVerificationScreen(
    viewModel: PairingViewModel,
    onNavigateToNaming: () -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    // Navigate to naming when SAS is confirmed
    LaunchedEffect(uiState) {
        when (uiState) {
            is PairingUiState.Naming -> onNavigateToNaming()
            is PairingUiState.Scanning -> onBack()  // "No Match" or error reset
            else -> Unit
        }
    }

    // Guard: only render the SAS UI when in the Verifying state
    val verifyingState = uiState as? PairingUiState.Verifying
        ?: return  // Screen was opened without going through the correct flow

    val sas = verifyingState.sas

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify Connection") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.onSasDenied()
                        // onBack is called reactively via the LaunchedEffect above
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel pairing")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {

            // ── Instruction ───────────────────────────────────────────────────
            Text(
                text = "Do these emoji match?",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Compare the four symbols below with those shown on your computer. " +
                    "If they match, your connection is secure.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(48.dp))

            // ── 4 emoji ───────────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                sas.emoji.forEachIndexed { index, emoji ->
                    EmojiCard(
                        emoji = emoji,
                        label = sas.labels.getOrElse(index) { emoji },
                    )
                }
            }

            Spacer(modifier = Modifier.height(64.dp))

            // ── Action buttons ────────────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

                Button(
                    onClick = { viewModel.onSasConfirmed() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        text = "They Match",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.onSasDenied() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(
                        text = "No Match",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "If the symbols don't match, someone may be intercepting " +
                    "your connection. Tap \"No Match\" to cancel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── Single emoji card ─────────────────────────────────────────────────────────

/**
 * Displays one SAS emoji with a text label below it.
 *
 * The large emoji size (48sp) ensures the symbol is clearly readable from arm's
 * length and is distinct enough for reliable cross-device comparison.
 *
 * @param emoji The emoji character to display (single code point or ZWJ sequence).
 * @param label Descriptive label below the emoji (currently mirrors the emoji).
 */
@Composable
private fun EmojiCard(
    emoji: String,
    label: String,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = emoji,
                fontSize = 48.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}
