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
import com.videox.app.ui.viewmodel.DownloaderViewModel

@Composable
fun HomeScreen(viewModel: DownloaderViewModel) {
    val downloads by viewModel.downloads.collectAsState()
    val active = downloads.filter { it.isRunning }
    val info = viewModel.videoInfo
    val formats = info?.formats.orEmpty()
    // Show combined video+audio formats first, then video-only, then audio-only.
    val shownFormats = formats.sortedWith(
        compareBy({ it.isAudioOnly }, { it.isVideoOnly })
    ).take(12)

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
            text = "Paste a link from a site that permits downloading, or a direct MP4/WebM link. " +
                "VideoX uses yt-dlp for supported sites and DownloadManager for direct links.",
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
                onClick = { viewModel.setAudioOnly(false) },
                label = { Text("Video") }
            )
            FilterChip(
                selected = viewModel.audioOnly,
                onClick = { viewModel.setAudioOnly(true) },
                label = { Text("Audio only") }
            )
            if (viewModel.audioOnly) {
                listOf("mp3", "m4a", "opus").forEach { fmt ->
                    FilterChip(
                        selected = viewModel.audioFormat == fmt,
                        onClick = { viewModel.setAudioFormat(fmt) },
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
            Spacer(modifier = Modifier.height(8.dp))
            Text("Pick a format:", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            shownFormats.forEach { format ->
                Card(
                    onClick = { viewModel.selectFormat(format) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (viewModel.selectedFormat?.formatId == format.formatId)
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
                            selected = viewModel.selectedFormat?.formatId == format.formatId,
                            onClick = { viewModel.selectFormat(format) }
                        )
                        Column {
                            Text(format.label, fontWeight = FontWeight.SemiBold)
                            if (format.fileSize > 0) {
                                Text(
                                    DownloadManagerHelper.formatSize(format.fileSize),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            if (formats.size > shownFormats.size) {
                Text(
                    "Showing ${shownFormats.size} of ${formats.size} formats.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    Text("Only links that allow downloading", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "VideoX does not bypass DRM, paywalls, logins or platform download protections, and it never asks for your passwords. If a site blocks the download, VideoX shows that error instead of pretending it worked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
