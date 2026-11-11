package com.videox.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videox.app.download.DownloadManagerHelper
import com.videox.app.download.QualitySelector
import com.videox.app.ui.viewmodel.DownloaderViewModel

@Composable
fun HomeScreen(viewModel: DownloaderViewModel) {
    val downloads by viewModel.downloads.collectAsState()
    val active = downloads.filter { it.isRunning }
    val info = viewModel.videoInfo
    val qualities = viewModel.availableQualities

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Download videos & audio",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Paste any video link — YouTube, Vimeo, Dailymotion, Reddit, social posts, " +
                "web pages with embedded video, or a direct MP4/WebM file. VideoX reads the " +
                "page, lists the real qualities, and downloads the one you pick.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = viewModel.urlInput,
            onValueChange = { viewModel.onUrlChange(it) },
            label = { Text("Video URL") },
            placeholder = { Text("https://…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.pasteFromClipboard() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null)
                Text("  Paste URL")
            }
            OutlinedButton(
                onClick = { viewModel.fetchFormats() },
                enabled = !viewModel.fetchingFormats,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Text("  Formats")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Video / Audio mode.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !viewModel.audioOnly,
                onClick = { viewModel.updateAudioOnly(false) },
                label = { Text("Video") }
            )
            FilterChip(
                selected = viewModel.audioOnly,
                onClick = { viewModel.updateAudioOnly(true) },
                label = { Text("Audio only") }
            )
            if (viewModel.audioOnly) {
                listOf("mp3", "m4a", "opus").forEach { fmt ->
                    FilterChip(
                        selected = viewModel.audioFormat == fmt,
                        onClick = { viewModel.updateAudioFormat(fmt) },
                        label = { Text(fmt.uppercase()) }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = viewModel.customFileName,
            onValueChange = { viewModel.onCustomFileNameChange(it) },
            label = { Text("Custom filename (optional)") },
            placeholder = { Text("Leave empty to use the video title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { viewModel.startDownload() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Text("  Download")
        }
        if (!viewModel.ytdlpReady) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "yt-dlp is still starting up… (first run takes a few seconds)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (viewModel.fetchingFormats) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text("  Reading formats…", modifier = Modifier.padding(start = 12.dp))
            }
        }

        info?.let { vinfo ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(vinfo.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            if (vinfo.uploader.isNotBlank()) {
                Text(vinfo.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            viewModel.maxQualityLabel?.let { maxLabel ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(maxLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Video quality list — hidden in Audio only mode (Seal #750):
            // an audio download uses the best audio stream no matter
            // what, so showing video qualities there is meaningless.
            if (!viewModel.audioOnly) {
                Text("Pick a quality:", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
            // Best Quality first (highest genuinely available, video + audio merged),
            // then every real resolution, highest first. No fake qualities:
            // a height only appears if the source actually offers it.
            val qualityOptions: List<Pair<Int?, String>> =
                listOf(null to "Best Quality (Original)") +
                    qualities.map { h -> h to QualitySelector.qualityLabel(h) }
            qualityOptions.forEach { (height, label) ->
                val selected = viewModel.selectedQualityHeight == height
                val sizeText = if (height != null) {
                    val est = QualitySelector.estimatedSizeForHeight(vinfo.formats, height)
                    if (est > 0) DownloadManagerHelper.formatSize(est) else null
                } else null
                Card(
                    onClick = { viewModel.selectQuality(height) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { viewModel.selectQuality(height) }
                        )
                        Column {
                            Text(label, fontWeight = FontWeight.SemiBold)
                            if (height == null) {
                                Text(
                                    "Highest video + best audio, merged into one file",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (sizeText != null) {
                                Text(
                                    "Approx. $sizeText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            }
        }

        // The user must choose the 360p fallback explicitly — VideoX never
        // silently downgrades a requested quality.
        viewModel.fallbackOffer?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Full quality is blocked — 360p is available", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "YouTube is blocking the quality you picked for this video right now. " +
                            "A 360p download would work. Download it, or try the higher quality again later.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { viewModel.startFallbackDownload() }) {
                            Text("Download 360p")
                        }
                        OutlinedButton(onClick = { viewModel.dismissFallbackOffer() }) {
                            Text("Not now")
                        }
                    }
                }
            }
        }

        viewModel.message?.let { msg ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (viewModel.isMessageError)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Active downloads (${active.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (active.isEmpty()) {
            Text(
                text = "No downloads running. Your completed downloads are on the Downloads tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            active.forEach { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(item.fileName, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${item.status} • ${item.progress}% " +
                                (viewModel.activeEtaText?.let { "• $it " } ?: "") +
                                if (item.totalBytes > 0) "• ${DownloadManagerHelper.formatSize(item.downloadedBytes)} / ${DownloadManagerHelper.formatSize(item.totalBytes)}" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (item.progress.coerceIn(0, 100)) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (item.engine == "YTDLP") {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = { viewModel.cancelDownload(item) }) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(14.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("Only public links that allow downloading", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "VideoX downloads public videos from sites that permit it, and direct media files. " +
                            "It does not bypass DRM, paywalls, logins or platform download protections, and it never " +
                            "asks for your passwords. If a video genuinely can't be downloaded, VideoX says why " +
                            "instead of pretending it worked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    // Metered-network warning (Seal Roadmap #1251): ask before a
    // download on mobile data, so a big video does not eat the
    // user's data plan silently. Can be turned off in Settings.
    if (viewModel.meteredConfirmPending) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissMeteredWarning() },
            title = { Text("Metered connection") },
            text = {
                Text(
                    "You're on a metered connection (mobile data). This download may use a lot of data. " +
                        "Download anyway, or wait until you're on Wi-Fi?"
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.startDownload(confirmedMetered = true) }) {
                    Text("Download anyway")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.dismissMeteredWarning() }) {
                    Text("Wait for Wi-Fi")
                }
            }
        )
    }
}
