// SAF folder picker — Compose hook that wraps ActivityResultContracts.OpenDocumentTree
// and persists the URI permission so the grant survives cold restarts.
//
// Returns a () -> Unit "launch" function. Wire to the SettingsScreen's
// onPickFolder callback. On a successful pick, the URI is passed to onPicked
// AFTER takePersistableUriPermission has been called — ordering matters:
// once the activity result is dispatched, the granted permission is implicit
// and can be made persistent for at most a few hundred ms. Calling later may
// fail silently.

package com.clayworks.kiln.saf

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger

private val log = Logger.withTag("SafFolderPicker")

/**
 * Composable hook for the Android Storage Access Framework folder picker.
 *
 * Caller invokes the returned launcher when the user taps "Add Folder".
 * On a successful pick:
 *   1. takePersistableUriPermission is invoked with FLAG_GRANT_READ_URI_PERMISSION
 *      so the URI grant survives process death (matches Track B's spec).
 *   2. onPicked is invoked with the URI as a String (the same form
 *      SettingsRepository.scanFolders stores).
 *
 * Cancel or any other failure path is silent — the launcher is a no-op
 * fire-and-forget; no UI state to clean up.
 */
@Composable
fun rememberSafFolderPicker(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            log.w(e) { "takePersistableUriPermission failed for $uri" }
            return@rememberLauncherForActivityResult
        }
        onPicked(uri.toString())
    }
    return remember(launcher) { { launcher.launch(/* input = */ null) } }
}
