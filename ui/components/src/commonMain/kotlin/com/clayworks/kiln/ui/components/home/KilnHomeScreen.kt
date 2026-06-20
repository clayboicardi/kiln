// KilnHomeScreen — top-level Compose composable that hosts the 3-tab
// Voyager TabNavigator. Takes MusicSource + PlatformPlayer + onOpenSettings
// callback; constructs the three Tab instances internally.
//
// Track A's gear-icon -> SettingsScreen flow is preserved at the app-module
// layer (MainActivity / Main.kt route `showSettings` boolean toggles
// between KilnHomeScreen and SettingsRoute).

package com.clayworks.kiln.ui.components.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.tab.CurrentTab
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.library.source.MusicSource
import com.clayworks.kiln.ui.components.library.LibraryTab
import com.clayworks.kiln.ui.components.nowplaying.NowPlayingTab
import com.clayworks.kiln.ui.components.search.SearchTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KilnHomeScreen(
    musicSource: MusicSource,
    player: PlatformPlayer,
    onOpenSettings: () -> Unit,
) {
    val libraryTab = remember(musicSource, player) { LibraryTab(musicSource, player) }
    // NowPlayingTab takes no constructor dependency — its inner Screens read the
    // PlatformPlayer from LocalPlayer (provided at the app root). See A5 /
    // LocalLibraryStats.kt for the Voyager Screen-serialization rationale.
    val nowPlayingTab = remember { NowPlayingTab() }
    val searchTab = remember(musicSource, player) { SearchTab(musicSource, player) }

    TabNavigator(libraryTab) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Kiln by Clayworks", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    TabNavigationItem(libraryTab)
                    TabNavigationItem(nowPlayingTab)
                    TabNavigationItem(searchTab)
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                CurrentTab()
            }
        }
    }
}

@Composable
private fun RowScope.TabNavigationItem(tab: Tab) {
    val tabNavigator = LocalTabNavigator.current
    NavigationBarItem(
        selected = tabNavigator.current == tab,
        onClick = { tabNavigator.current = tab },
        icon = {
            tab.options.icon?.let { painter ->
                Icon(painter = painter, contentDescription = tab.options.title)
            }
        },
        label = { Text(tab.options.title) },
    )
}
