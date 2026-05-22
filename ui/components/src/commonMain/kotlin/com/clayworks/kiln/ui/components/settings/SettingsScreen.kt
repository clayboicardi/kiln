package com.clayworks.kiln.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clayworks.kiln.library.settings.ThemeMode

/**
 * Stateless Phase 2a Track A settings surface. Three sections: theme
 * (radio group), behavior (scan-on-launch switch), library (scan-folder
 * list + add/remove buttons). State and callbacks are hoisted to app
 * modules where SettingsRepository writes happen.
 *
 * Folder-pick: the screen surfaces the button; the app module owns the
 * platform-specific picker (Desktop: JFileChooser; Android: SAF in
 * Track B, stub-toast for now).
 *
 * HorizontalDivider over the deprecated Material3 Divider — Compose-MP 1.9
 * deprecated the latter; allWarningsAsErrors would fail the build otherwise.
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    onThemeModeChange: (ThemeMode) -> Unit,
    onScanOnLaunchChange: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        // === Theme section ===
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.selectableGroup()) {
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.themeMode == mode, onClick = null)
                    Text(
                        text = mode.name,
                        modifier = Modifier.padding(start = 16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // === Behavior section ===
        Text("Behavior", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Scan library on launch", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = state.scanOnLaunch,
                onCheckedChange = onScanOnLaunchChange,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // === Library section ===
        Text("Library folders", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        if (state.scanFolders.isEmpty()) {
            Text(
                "No folders configured. Click 'Add Folder' to choose your music library.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            state.scanFolders.forEach { folder ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(folder, style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { onRemoveFolder(folder) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove $folder")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onPickFolder) { Text("Add Folder") }
    }
}
