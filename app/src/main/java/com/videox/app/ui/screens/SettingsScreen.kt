package com.videox.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videox.app.ui.viewmodel.DownloaderViewModel
import java.io.File

@Composable
fun SettingsScreen(viewModel: DownloaderViewModel) {
    val context = LocalContext.current
    val downloads by viewModel.downloads.collectAsState()
    val completed = downloads.count { it.isCompleted }
    val totalBytes = downloads.filter { it.isCompleted }.sumOf { it.totalBytes }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Download folder", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Downloads/VideoX", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VideoX").absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FVideoX"),
                            "vnd.android.document/directory"
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { context.startActivity(intent) } catch (e: Exception) {
                        viewModel.showMessage("Open Files > Downloads > VideoX to see your videos.", isError = false)
                    }
                }) { Text("Open folder in Files") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dark theme", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Dark is the default VideoX look. Turn off for a light theme.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.darkTheme,
                        onCheckedChange = { viewModel.updateDarkTheme(it) }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Simultaneous downloads: ${viewModel.maxConcurrent}", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3).forEach { n ->
                        OutlinedButton(onClick = { viewModel.updateMaxConcurrent(n) }) {
                            Text(if (viewModel.maxConcurrent == n) "● $n" else "$n")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "yt-dlp engine: ${if (viewModel.ytdlpReady) "ready" else "starting…"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("yt-dlp engine", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Version: ${viewModel.ytDlpVersion?.let { "v$it" } ?: "…"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "YouTube changes often break old engine versions (downloads fail with 403). VideoX tries to update the engine itself when the app starts; if a download still fails with 403, update it here and retry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { viewModel.updateYtDlpEngine() },
                    enabled = viewModel.ytdlpReady && !viewModel.updatingEngine
                ) {
                    Text(if (viewModel.updatingEngine) "Updating…" else "Update yt-dlp engine")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Wi-Fi only", fontWeight = FontWeight.SemiBold)
                        Text(
                            "When on, downloads will not use mobile data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.wifiOnly,
                        onCheckedChange = { viewModel.updateWifiOnly(it) }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Warn on metered connection", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Ask before downloading on mobile data, so a big video does not use your data plan silently.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.warnMetered,
                        onCheckedChange = { viewModel.updateWarnMetered(it) }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Proxy (optional)", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = viewModel.proxyUrl,
                    onValueChange = { viewModel.updateProxyUrl(it) },
                    label = { Text("Proxy URL") },
                    placeholder = { Text("http://host:port or socks5://host:port") },
                    singleLine = true,
                    isError = viewModel.proxyUrlInvalid,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
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

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Download subtitles", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Embed subtitles into downloaded videos when the video has them (including auto-generated captions). No separate subtitle files are left behind.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.subtitlesEnabled,
                        onCheckedChange = { viewModel.updateSubtitlesEnabled(it) }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Subtitle languages", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("en" to "English", "hi" to "Hindi", "all" to "All").forEach { (code, label) ->
                        OutlinedButton(onClick = { viewModel.updateSubtitleLangs(code) }) {
                            Text(if (viewModel.subtitleLangs == code) "● $label" else label)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Embed chapters", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Add chapter markers to downloaded files when the video has chapters, so you can jump between sections in your player.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = viewModel.embedChapters,
                        onCheckedChange = { viewModel.updateEmbedChapters(it) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Backup & restore", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Back up your settings to Downloads/VideoX/videox-settings-backup.json, and restore them after reinstalling or on a new phone by placing the file in Downloads/VideoX and tapping Restore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.backupSettings() }) {
                        Text("Back up settings")
                    }
                    OutlinedButton(onClick = { viewModel.restoreSettings() }) {
                        Text("Restore settings")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Storage", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("$completed completed • ${com.videox.app.download.DownloadManagerHelper.formatSize(totalBytes)} downloaded in history")
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.clearHistory() },
                    enabled = downloads.isNotEmpty()
                ) { Text("Clear download history") }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Clearing history does not delete video files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("About VideoX", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Version 2.9  •  Kotlin + Jetpack Compose  •  Min. Android 10 (API 29)")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "VideoX 2.9 downloads public video links with the yt-dlp engine (youtubedl-android 0.18.1 + FFmpeg) and direct media files (MP4, WebM, MOV, MKV, M4V, 3GP, MP3, M4A and more) with Android DownloadManager. Quality is chosen by resolution — Best Quality merges the highest video with the best audio — and is never silently downgraded: if the quality you picked is blocked, VideoX says why and offers the alternatives. If a file is already on your phone, VideoX says so instead of reporting a failure. Quality sizes are honest: when a size is unknown, no size is shown instead of a wrong one, and DNS failures are explained plainly. Every build is signed with the same key, so updates install over the previous version. The engine updates itself to the latest stable yt-dlp so YouTube downloads keep working. New in 2.9: a warning before downloading on a metered connection, an optional proxy setting, subtitle embedding, chapter markers, settings backup and restore, and a cleaner Audio only screen that hides the video quality list. It does not bypass DRM, paywalls or logins, and never collects passwords.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
