// SpecSheetScreen — Phase 2b-prereq placeholder. Pushed onto Voyager's
// Navigator from NowPlayingHomeScreen when the user taps the now-playing
// title. The real Spec Sheet (format facts + library aggregate stats)
// lands in Phase 2b Stream A — this file's body is intentionally minimal
// so that navigation can be tested end-to-end before Stream A starts.
//
// See docs/superpowers/plans/2026-05-23-phase-2b-plan.md §6.1.

package com.clayworks.kiln.ui.components.specsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow

class SpecSheetScreen(
    private val trackId: String,
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Top bar with back affordance. Material 3 TopAppBar would also
            // work but adds an experimental-API dependency without buying
            // anything for the placeholder body — Stream A will replace this.
            IconButton(onClick = { navigator.pop() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Spec sheet for $trackId",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
