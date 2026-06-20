// SpecSheetContent — stateless Spec Sheet detail view. Renders the per-track
// format facts, ReplayGain values, file facts, and a library-wide aggregate
// footer. Fully driven by SpecSheetUiState; no data wiring, no navigation
// (the back affordance is a hoisted onBack callback). Data wiring lands in A5,
// rendered-layout tests in A6.
//
// Aesthetic frame: "Mastering Engineer's Apartment" — calm spacing, plain
// labelled rows in a monospace-leaning face, clinical but not sterile.

package com.clayworks.kiln.ui.components.specsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.clayworks.kiln.library.source.LibraryAggregate
import com.clayworks.kiln.library.source.SpecSheetEntry
import java.time.Instant

@Composable
fun SpecSheetContent(
    state: SpecSheetUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
            )
        }

        when (state) {
            SpecSheetUiState.Loading -> CenteredMessage("Loading…")
            SpecSheetUiState.NotFound -> CenteredMessage("Track not found")
            is SpecSheetUiState.Loaded -> LoadedBody(state.entry, state.aggregate)
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LoadedBody(entry: SpecSheetEntry, aggregate: LibraryAggregate?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Title + the one-line format summary — the headline of the sheet.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = entry.title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = formatLine(entry),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
        }

        SpecSection(title = "ReplayGain") {
            LabeledRow("Track", formatReplayGain(entry.replayGainTrackDb, entry.replayGainTrackPeak))
            LabeledRow("Album", formatReplayGain(entry.replayGainAlbumDb, entry.replayGainAlbumPeak))
        }

        SpecSection(title = "File") {
            LabeledRow("Path", entry.filePath)
            LabeledRow("Size", formatBytes(entry.fileSizeBytes))
            LabeledRow("Modified", formatMtime(entry.fileMtimeMs, entry.hasKnownMtime))
            LabeledRow("Embedded art", if (entry.hasEmbeddedArt) "yes" else "no")
        }

        aggregate?.let { AggregateFooter(it) }
    }
}

@Composable
private fun SpecSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        content()
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AggregateFooter(aggregate: LibraryAggregate) {
    SpecSection(title = "Library") {
        LabeledRow("Tracks", aggregate.totalTracks.toString())
        LabeledRow("Total size", formatBytes(aggregate.totalBytes))
        LabeledRow("ReplayGain", formatCoverage(aggregate.replayGainCoverage))
        LabeledRow("Known mtime", formatCoverage(aggregate.knownMtimeCoverage))
        if (aggregate.codecCounts.isNotEmpty()) {
            LabeledRow("Codecs", formatCodecCounts(aggregate.codecCounts))
        }
    }
}

// --- Pure formatting helpers -------------------------------------------------

private const val EM_DASH_SEP = " — "

/**
 * One-line format summary. bitDepth present → "{codec} — {depth}/{kHz} — {ch} ch — {kbps} kbps";
 * bitDepth null → "{codec} — {kHz} kHz — {ch} ch — {kbps} kbps". The trailing
 * " — {kbps} kbps" segment is omitted entirely when bitrate is null.
 */
internal fun formatLine(entry: SpecSheetEntry): String {
    val khz = formatKhz(entry.sampleRateHz)
    val head = if (entry.bitDepth != null) {
        "${entry.codec}$EM_DASH_SEP${entry.bitDepth}/$khz$EM_DASH_SEP${entry.channels} ch"
    } else {
        "${entry.codec}$EM_DASH_SEP$khz kHz$EM_DASH_SEP${entry.channels} ch"
    }
    return if (entry.bitrateKbps != null) "$head$EM_DASH_SEP${entry.bitrateKbps} kbps" else head
}

/** Sample rate in kHz with .1 precision only when non-integer (96000 → "96", 44100 → "44.1"). */
private fun formatKhz(sampleRateHz: Int): String =
    if (sampleRateHz % 1000 == 0) {
        (sampleRateHz / 1000).toString()
    } else {
        // JVM-only String.format is fine — :ui:components targets JVM + Android only.
        String.format("%.1f", sampleRateHz / 1000.0)
    }

/** "+0.3 dB / peak 0.998", "—" when the dB value is absent; peak appended only when present. */
private fun formatReplayGain(db: Double?, peak: Double?): String {
    if (db == null) return "—"
    val gain = "${String.format("%+.1f", db)} dB"
    return if (peak != null) "$gain / peak ${String.format("%.3f", peak)}" else gain
}

/** Humanized byte count — B / KB / MB / GB, 1 decimal above bytes (1024-based). */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return "${String.format("%.1f", value)} ${units[unitIndex]}"
}

/** ISO-8601 UTC instant, or "unknown" when the provider didn't report an mtime. */
private fun formatMtime(fileMtimeMs: Long, hasKnownMtime: Boolean): String =
    if (hasKnownMtime) Instant.ofEpochMilli(fileMtimeMs).toString() else "unknown"

/** 0.0..1.0 fraction → integer percent, e.g. 0.873 → "87%". */
private fun formatCoverage(fraction: Double): String =
    "${(fraction * 100).toInt()}%"

/** "FLAC 1203, MP3 412" — descending by count, then by codec name for ties. */
private fun formatCodecCounts(counts: Map<String, Long>): String =
    counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .joinToString(", ") { "${it.key} ${it.value}" }
