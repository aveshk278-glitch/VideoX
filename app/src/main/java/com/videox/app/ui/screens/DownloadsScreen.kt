package com.videox.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videox.app.data.DownloadItem
import com.videox.app.download.DownloadManagerHelper
import com.videox.app.ui.theme.EmptyState
import com.videox.app.ui.theme.PremiumCard
import com.videox.app.ui.theme.PremiumProgress
import com.videox.app.ui.theme.PremiumSecondaryButton
import com.videox.app.ui.theme.SegmentedControl
import com.videox.app.ui.theme.StatusPill
import com.videox.app.ui.theme.StatusTone
import com.videox.app.ui.theme.VideoXInfo
import com.videox.app.ui.theme.VideoXPurple
import com.videox.app.ui.theme.VideoXPurpleSoft
import com.videox.app.ui.viewmodel.DownloaderViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DownloadFilter { ALL, RUNNING, COMPLETED, FAILED }

@Composable
fun DownloadsScreen(viewModel: DownloaderViewModel) {
    val downloads by viewModel.downloads.collectAsState()
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    var showClearDialog by remember { mutableStateOf(false) }

    val filtered = when (filter) {
        DownloadFilter.ALL -> downloads
        DownloadFilter.RUNNING -> downloads.filter { it.isRunning }
        DownloadFilter.COMPLETED -> downloads.filter { it.isCompleted }
        DownloadFilter.FAILED -> downloads.filter { it.isFailed }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Downloads",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { if (downloads.isNotEmpty()) showClearDialog = true }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Clear history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Files are saved in Downloads/VideoX. Tap a completed video to play it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        val filters = listOf(DownloadFilter.ALL, DownloadFilter.RUNNING, DownloadFilter.COMPLETED, DownloadFilter.FAILED)
        SegmentedControl(
            options = listOf("All", "Running", "Done", "Failed"),
            selectedIndex = filters.indexOf(filter),
            onSelect = { filter = filters[it] },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (filtered.isEmpty()) {
            val (icon, title, message) = when (filter) {
                DownloadFilter.ALL -> Triple(
                    Icons.Outlined.CloudDownload,
                    "No downloads yet",
                    "Paste a link on the Home tab to start."
                )
                DownloadFilter.RUNNING -> Triple(
                    Icons.Outlined.CloudDownload,
                    "Nothing running",
                    "No downloads running."
                )
                DownloadFilter.COMPLETED -> Triple(
                    Icons.Outlined.CheckCircle,
                    "Nothing completed",
                    "No completed downloads yet."
                )
                DownloadFilter.FAILED -> Triple(
                    Icons.Outlined.CheckCircle,
                    "No failures",
                    "No failed downloads. Good news."
                )
            }
            PremiumCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(icon = icon, title = title, message = message)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filtered, key = { it.id }) { item ->
                    DownloadRow(
                        item = item,
                        onOpen = {
                            if (item.engine == "YTDLP") viewModel.openFile(item.fileName)
                            else viewModel.openDownload(item.downloadId)
                        },
                        onShare = { viewModel.shareFile(item.fileName) },
                        onRetry = { viewModel.retryDownload(item) },
                        onCancel = { viewModel.cancelDownload(item) }
                    )
                }
            }
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("Clear history?") },
                text = { Text("This clears the download history in VideoX. The video files in Downloads/VideoX are not deleted.") },
                confirmButton = {
                    Button(onClick = {
                        showClearDialog = false
                        viewModel.clearHistory()
                    }) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val dateText = remember(item.createdAt) {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.createdAt))
    }
    val (typeIcon, typeTint) = when (item.mode) {
        "AUDIO" -> Icons.Outlined.MusicNote to VideoXPurpleSoft
        "TEXT" -> Icons.Outlined.Description to VideoXInfo
        else -> Icons.Outlined.Movie to VideoXPurple
    }
    PremiumCard(
        onClick = if (item.isCompleted) onOpen else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(typeTint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = typeIcon,
                        contentDescription = null,
                        tint = typeTint,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        item.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.isCompleted) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = "Play",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(
                    text = item.status,
                    tone = when {
                        item.isCompleted -> StatusTone.Success
                        item.isFailed -> StatusTone.Error
                        item.isRunning -> StatusTone.Active
                        else -> StatusTone.Neutral
                    }
                )
                item.formatLabel?.takeIf { it.isNotBlank() }?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${item.progress}% • " +
                    "${DownloadManagerHelper.formatSize(item.downloadedBytes)} / " +
                    DownloadManagerHelper.formatSize(item.totalBytes) + " • $dateText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.isRunning) {
                Spacer(modifier = Modifier.height(10.dp))
                PremiumProgress(
                    progress = (item.progress.coerceIn(0, 100)) / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (item.isFailed && !item.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        item.errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                PremiumSecondaryButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
            if (item.isRunning && item.engine == "YTDLP") {
                Spacer(modifier = Modifier.height(10.dp))
                PremiumSecondaryButton(onClick = onCancel) { Text("Cancel") }
            }
            if (item.isCompleted) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PremiumSecondaryButton(onClick = onOpen) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open")
                    }
                    PremiumSecondaryButton(onClick = onShare) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share")
                    }
                }
            }
        }
    }
}
