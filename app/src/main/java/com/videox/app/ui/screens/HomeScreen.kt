package com.videox.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import com.videox.app.ui.theme.BrandHeader
import com.videox.app.ui.theme.EmptyState
import com.videox.app.ui.theme.PremiumCard
import com.videox.app.ui.theme.PremiumPrimaryButton
import com.videox.app.ui.theme.PremiumProgress
import com.videox.app.ui.theme.PremiumSecondaryButton
import com.videox.app.ui.theme.PremiumTextField
import com.videox.app.ui.theme.SectionHeader
import com.videox.app.ui.theme.SegmentedControl
import com.videox.app.ui.theme.VideoXWarning
import com.videox.app.ui.theme.VideoXWarningContainer
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        BrandHeader(subtitle = "Download videos & audio")
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Paste any video link — YouTube, Vimeo, Dailymotion, Reddit, social posts, " +
                "web pages with embedded video, or a direct MP4/WebM file. VideoX reads the " +
                "page, lists the real qualities, and downloads the one you pick.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))

        // URL input card.
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                PremiumTextField(
                    value = viewModel.urlInput,
                    onValueChange = { viewModel.onUrlChange(it) },
                    label = "Video URL",
                    placeholder = "https://…",
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PremiumPrimaryButton(
                        onClick = { viewModel.pasteFromClipboard() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Paste URL")
                    }
                    PremiumSecondaryButton(
                        onClick = { viewModel.fetchFormats() },
                        enabled = !viewModel.fetchingFormats,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Formats")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Video / Audio mode.
        SegmentedControl(
            options = listOf("Video", "Audio only"),
            selectedIndex = if (viewModel.audioOnly) 1 else 0,
            onSelect = { viewModel.updateAudioOnly(it == 1) },
            modifier = Modifier.fillMaxWidth(),
            icons = listOf(Icons.Outlined.Movie, Icons.Outlined.MusicNote)
        )
        if (viewModel.audioOnly) {
            Spacer(modifier = Modifier.height(12.dp))
            val audioFormats = listOf("mp3", "m4a", "opus")
            SegmentedControl(
                options = audioFormats.map { it.uppercase() },
                selectedIndex = audioFormats.indexOf(viewModel.audioFormat).coerceAtLeast(0),
                onSelect = { viewModel.updateAudioFormat(audioFormats[it]) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        PremiumTextField(
            value = viewModel.customFileName,
            onValueChange = { viewModel.onCustomFileNameChange(it) },
            label = "Custom filename (optional)",
            placeholder = "Leave empty to use the video title",
            leadingIcon = {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        PremiumPrimaryButton(
            onClick = { viewModel.startDownload() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Download", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.height(10.dp))
        PremiumSecondaryButton(
            onClick = { viewModel.extractText() },
            enabled = !viewModel.extractingText,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (viewModel.extractingText) "Extracting text…" else "Extract text from video")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Saves the video's subtitles/captions as a clean .txt file — the video itself is not downloaded. Languages come from Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        viewModel.transcriptionProgress?.let { progress ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = progress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
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
            Spacer(modifier = Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp
                )
                Text("Reading formats…", modifier = Modifier.padding(start = 12.dp))
            }
        }

        info?.let { vinfo ->
            Spacer(modifier = Modifier.height(24.dp))
            Text(vinfo.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            if (vinfo.uploader.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(vinfo.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            viewModel.maxQualityLabel?.let { maxLabel ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(maxLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            // Video quality list — hidden in Audio only mode:
            // an audio download uses the best audio stream no matter
            // what, so showing video qualities there is meaningless.
            if (!viewModel.audioOnly) {
                Spacer(modifier = Modifier.height(14.dp))
                SectionHeader(title = "Pick a quality")
                Spacer(modifier = Modifier.height(10.dp))
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
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { viewModel.selectQuality(height) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = VideoXWarningContainer),
                border = BorderStroke(1.dp, VideoXWarning.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (viewModel.isMessageError)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        SectionHeader(title = "Active downloads (${active.size})")
        Spacer(modifier = Modifier.height(12.dp))

        if (active.isEmpty()) {
            PremiumCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    icon = Icons.Outlined.CloudDownload,
                    title = "Nothing downloading",
                    message = "No downloads running. Your completed downloads are on the Downloads tab."
                )
            }
        } else {
            active.forEach { item ->
                PremiumCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(item.fileName, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${item.status} • ${item.progress}% " +
                                (viewModel.activeEtaText?.let { "• $it " } ?: "") +
                                if (item.totalBytes > 0) "• ${DownloadManagerHelper.formatSize(item.downloadedBytes)} / ${DownloadManagerHelper.formatSize(item.totalBytes)}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        PremiumProgress(
                            progress = (item.progress.coerceIn(0, 100)) / 100f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (item.engine == "YTDLP") {
                            Spacer(modifier = Modifier.height(10.dp))
                            PremiumSecondaryButton(onClick = { viewModel.cancelDownload(item) }) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Metered-network warning: ask before a
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
