package com.zaptransfer.android.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ── Icon definitions ──────────────────────────────────────────────────────────

/**
 * Available device icon options shown in the icon picker grid.
 *
 * The [token] value is stored in [PairedDeviceEntity.icon] and used by the
 * Device Hub to select the correct icon when rendering device cards.
 */
private data class DeviceIconOption(
    val token: String,
    val icon: ImageVector,
    val label: String,
)

private val ICON_OPTIONS = listOf(
    DeviceIconOption("LAPTOP", Icons.Default.Computer, "Laptop"),
    DeviceIconOption("DESKTOP", Icons.Default.DesktopWindows, "Desktop"),
    DeviceIconOption("PHONE", Icons.Default.PhoneAndroid, "Phone"),
    DeviceIconOption("TABLET", Icons.Default.Tablet, "Tablet"),
)

/** Default icon token used when no selection has been made explicitly. */
private const val DEFAULT_ICON = "LAPTOP"

// ── DeviceNamingScreen ────────────────────────────────────────────────────────

/**
 * Device naming screen — Phase D, Task 15.
 *
 * The final step of the pairing ceremony. Allows the user to:
 *  1. Edit the suggested device name (pre-filled from the peer's self-reported name).
 *  2. Pick an icon from a 4-option grid (Laptop, Desktop, Phone, Tablet).
 *  3. Tap "Done" to save the [PairedDeviceEntity] to Room and complete pairing.
 *
 * Validation:
 *  - The "Done" button is disabled when the name field is empty or blank.
 *  - Name is trimmed before saving.
 *
 * Navigation triggers:
 *  - "Done" → [viewModel.onNamingComplete] → [PairingUiState.Complete] → [onNavigateToHub]
 *  - Back → [onBack] (returns to SAS verification; user cannot re-verify from here)
 *
 * @param viewModel       Shared [PairingViewModel]; must be in [PairingUiState.Naming].
 * @param onNavigateToHub Callback to navigate to the Device Hub (clears pairing back stack).
 * @param onBack          Pop back to the SAS verification screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceNamingScreen(
    viewModel: PairingViewModel,
    onNavigateToHub: () -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    // Navigate to hub when naming completes
    LaunchedEffect(uiState) {
        if (uiState is PairingUiState.Complete) {
            onNavigateToHub()
        }
    }

    // Guard: only render when in the Naming state
    val namingState = uiState as? PairingUiState.Naming ?: return

    // Local UI state — not hoisted to ViewModel since it is ephemeral form state
    var name by remember { mutableStateOf(namingState.suggestedName) }
    var selectedIcon by remember { mutableStateOf(DEFAULT_ICON) }

    val isDoneEnabled = name.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Name This Device") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            Spacer(modifier = Modifier.height(32.dp))

            // ── Subtitle ──────────────────────────────────────────────────────
            Text(
                text = "Give this device a name so you can recognise it in your device list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ── Device name field ─────────────────────────────────────────────
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Device name") },
                placeholder = { Text("e.g. Work Laptop, Home Desktop") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                isError = name.isBlank() && name.isNotEmpty(), // error only after user clears
                supportingText = if (name.isBlank() && name.isNotEmpty()) {
                    { Text("Name cannot be empty") }
                } else null,
            )

            Spacer(modifier = Modifier.height(40.dp))

            // ── Icon picker ───────────────────────────────────────────────────
            Text(
                text = "Choose an icon",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ICON_OPTIONS.forEach { option ->
                    DeviceIconTile(
                        option = option,
                        isSelected = selectedIcon == option.token,
                        onClick = { selectedIcon = option.token },
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // ── Done button ───────────────────────────────────────────────────
            Button(
                onClick = {
                    viewModel.onNamingComplete(name = name.trim(), icon = selectedIcon)
                },
                enabled = isDoneEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    text = "Done",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ── Device icon tile ──────────────────────────────────────────────────────────

/**
 * A tappable icon tile for the icon picker grid.
 *
 * Selected state: elevated primary-coloured background with a primary border.
 * Unselected state: surface background with a subtle outline border.
 *
 * Accessible: the tile serves as a toggle button — the selected state change is
 * communicated via [Modifier.border] colour change which also affects contrast.
 *
 * @param option     The [DeviceIconOption] this tile represents.
 * @param isSelected Whether this tile is the currently selected icon.
 * @param onClick    Callback to select this icon.
 */
@Composable
private fun DeviceIconTile(
    option: DeviceIconOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(width = 80.dp, height = 80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = option.icon,
                contentDescription = option.label,
                tint = contentColor,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = option.label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
            )
        }
    }
}
