package com.videox.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videox.app.download.DownloadManagerHelper
import com.videox.app.ui.theme.PremiumCard
import com.videox.app.ui.theme.PremiumSecondaryButton
import com.videox.app.ui.theme.PremiumTextField
import com.videox.app.ui.theme.SectionHeader
import com.videox.app.ui.theme.SegmentedControl
import com.videox.app.ui.theme.SettingRow
import com.videox.app.ui.viewmodel.DownloaderViewModel
import java.io.File

@Composable
fun SettingsScreen(viewModel: DownloaderViewModel) {
    val context = LocalContext.current
    val downloads by viewModel.downloads.collectAsState()
    val completed = downloads.count { it.isCompleted }
    val totalBytes = downloads.filter { it.isCompleted }.sumOf { it.totalBytes }

    // Real free/total space on the volume that holds the Downloads folder.
    val storageSpace = remember {
        try {
            val stat = StatFs(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
            )
            stat.availableBytes to stat.totalBytes
        } catch (e: Exception) {
            null
        }
    }
    val downloadFolder = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "VideoX"
    )
    val openFolder: () -> Unit = {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FVideoX"),
                "vnd.android.document/directory"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            viewModel.showMessage("Open Files > Downloads > VideoX to see your videos.", isError = false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(20.dp))

        // ---- General ----
        SectionHeader(title = "General")
        Spacer(modifier = Modifier.height(10.dp))
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingRow(
                    icon = Icons.Outlined.Palette,
                    title = "Dark theme",
                    description = "Dark is the default VideoX look. Turn off for a light theme."
                ) {
                    Switch(
                        checked = viewModel.darkTheme,
                        onCheckedChange = { viewModel.updateDarkTheme(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                SettingsDivider()
                Text("Simultaneous downloads", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                SegmentedControl(
                    options = listOf("1", "2", "3"),
                    selectedIndex = (viewModel.maxConcurrent - 1).coerceIn(0, 2),
                    onSelect = { viewModel.updateMaxConcurrent(it + 1) },
                    modifier = Modifier.fillMaxWidth()
                )
                SettingsDivider()
                SettingRow(
                    icon = Icons.Outlined.Code,
                    title = "yt-dlp engine",
                    description = "Version: ${viewModel.ytDlpVersion?.let { "v$it" } ?: "…"} • " +
                        if (viewModel.ytdlpReady) "ready" else "starting…"
                ) {
                    // Trailing slot kept empty: the update action is below.
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "YouTube changes often break old engine versions (downloads fail with 403). VideoX tries to update the engine itself when the app starts; if a download still fails with 403, update it here and retry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                PremiumSecondaryButton(
                    onClick = { viewModel.updateYtDlpEngine() },
                    enabled = viewModel.ytdlpReady && !viewModel.updatingEngine
                ) {
                    Text(if (viewModel.updatingEngine) "Updating…" else "Update yt-dlp engine")
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // ---- Download ----
        SectionHeader(title = "Download")
        Spacer(modifier = Modifier.height(10.dp))
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openFolder() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("Download location", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Downloads/VideoX", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = downloadFolder.absolutePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = "Open folder in Files",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SettingsDivider()
                SettingRow(
                    icon = Icons.Outlined.Wifi,
                    title = "Wi-Fi only",
                    description = "When on, downloads will not use mobile data."
                ) {
                    Switch(
                        checked = viewModel.wifiOnly,
                        onCheckedChange = { viewModel.updateWifiOnly(it) }
                    )
                }
                SettingsDivider()
                SettingRow(
                    icon = Icons.Outlined.WarningAmber,
                    title = "Warn on metered connection",
                    description = "Ask before downloading on mobile data, so a big video does not use your data plan silently."
                ) {
                    Switch(
                        checked = viewModel.warnMetered,
                        onCheckedChange = { viewModel.updateWarnMetered(it) }
                    )
                }
                SettingsDivider()
                Text("Proxy (optional)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                PremiumTextField(
                    value = viewModel.proxyUrl,
                    onValueChange = { viewModel.updateProxyUrl(it) },
                    label = "Proxy URL",
                    placeholder = "http://host:port or socks5://host:port",
                    isError = viewModel.proxyUrlInvalid,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    if (viewModel.proxyUrlInvalid) {
                        "This proxy address is not valid — downloads will ignore it until it is fixed."
                    } else {
                        "Leave empty for a direct connection. The proxy is used for yt-dlp downloads and format lists."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (viewModel.proxyUrlInvalid) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // ---- Subtitles & text ----
        SectionHeader(title = "Subtitles & text")
        Spacer(modifier = Modifier.height(10.dp))
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingRow(
                    icon = Icons.Outlined.Subtitles,
                    title = "Download subtitles",
                    description = "Embed subtitles into downloaded videos when the video has them (including auto-generated captions). No separate subtitle files are left behind."
                ) {
                    Switch(
                        checked = viewModel.subtitlesEnabled,
                        onCheckedChange = { viewModel.updateSubtitlesEnabled(it) }
                    )
                }
                SettingsDivider()
                Text("Subtitle languages", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Also decides which languages video-to-text extraction saves.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val langOptions = listOf("en" to "English", "hi" to "Hindi", "all" to "All")
                SegmentedControl(
                    options = langOptions.map { it.second },
                    selectedIndex = langOptions.indexOfFirst { it.first == viewModel.subtitleLangs }.coerceAtLeast(0),
                    onSelect = { viewModel.updateSubtitleLangs(langOptions[it].first) },
                    modifier = Modifier.fillMaxWidth(),
                    icons = listOf(Icons.Outlined.Language, Icons.Outlined.Language, Icons.Outlined.Language)
                )
                SettingsDivider()
                SettingRow(
                    icon = Icons.Outlined.GraphicEq,
                    title = "Transcribe speech when there are no subtitles",
                    description = "When a video has no subtitles, VideoX recognises the speech on your phone instead, so text can be extracted from any video with spoken audio. The first transcription downloads a voice model once (about 40 MB, English or Hindi by the language choice above) and no audio ever leaves your phone. Automatic transcription can mishear a few words."
                ) {
                    Switch(
                        checked = viewModel.transcribeFallback,
                        onCheckedChange = { viewModel.updateTranscribeFallback(it) }
                    )
                }
                SettingsDivider()
                SettingRow(
                    icon = Icons.Outlined.Bookmarks,
                    title = "Embed chapters",
                    description = "Add chapter markers to downloaded files when the video has chapters, so you can jump between sections in your player."
                ) {
                    Switch(
                        checked = viewModel.embedChapters,
                        onCheckedChange = { viewModel.updateEmbedChapters(it) }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // ---- Backup & restore ----
        SectionHeader(title = "Backup & restore")
        Spacer(modifier = Modifier.height(10.dp))
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Back up your settings to Downloads/VideoX/videox-settings-backup.json, and restore them after reinstalling or on a new phone by placing the file in Downloads/VideoX and tapping Restore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PremiumSecondaryButton(onClick = { viewModel.backupSettings() }) {
                        Icon(Icons.Outlined.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text("Back up")
                    }
                    PremiumSecondaryButton(onClick = { viewModel.restoreSettings() }) {
                        Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text("Restore")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // ---- Storage ----
        SectionHeader(title = "Storage")
        Spacer(modifier = Modifier.height(10.dp))
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingRow(
                    icon = Icons.Outlined.Storage,
                    title = "Download history",
                    description = "$completed completed • ${DownloadManagerHelper.formatSize(totalBytes)} downloaded in history" +
                        (storageSpace?.let { (free, total) ->
                            "\n${DownloadManagerHelper.formatSize(free)} free of ${DownloadManagerHelper.formatSize(total)} on this device"
                        } ?: "")
                ) {
                    // No trailing control.
                }
                Spacer(modifier = Modifier.height(12.dp))
                PremiumSecondaryButton(
                    onClick = { viewModel.clearHistory() },
                    enabled = downloads.isNotEmpty()
                ) { Text("Clear download history") }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Clearing history does not delete video files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // ---- About ----
        SectionHeader(title = "About")
        Spacer(modifier = Modifier.height(10.dp))
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingRow(
                    icon = Icons.Outlined.Info,
                    title = "About VideoX",
                    description = "Version 2.11  •  Kotlin + Jetpack Compose  •  Min. Android 10 (API 29)"
                ) {
                    // No trailing control.
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "VideoX 2.11 downloads public video links with the yt-dlp engine (youtubedl-android 0.18.1 + FFmpeg) and direct media files (MP4, WebM, MOV, MKV, M4V, 3GP, MP3, M4A and more) with Android DownloadManager. Quality is chosen by resolution — Best Quality merges the highest video with the best audio — and is never silently downgraded: if the quality you picked is blocked, VideoX says why and offers the alternatives. If a file is already on your phone, VideoX says so instead of reporting a failure. Quality sizes are honest: when a size is unknown, no size is shown instead of a wrong one, and DNS failures are explained plainly. Every build is signed with the same key, so updates install over the previous version. The engine updates itself to the latest stable yt-dlp so YouTube downloads keep working. New in 2.9: a warning before downloading on a metered connection, an optional proxy setting, subtitle embedding, chapter markers, settings backup and restore, and a cleaner Audio only screen. New in 2.10: video-to-text extraction — save a video's subtitles/captions as a clean .txt file without downloading the video, or transcribe the speech on your phone when the video has no subtitles, so text can be extracted from any video with spoken audio. New in 2.11: a premium redesign — new purple identity, floating cards, gradient actions and a floating bottom bar. Every feature works exactly as before. It does not bypass DRM, paywalls or logins, and never collects passwords.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 14.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
