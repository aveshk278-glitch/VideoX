package com.videox.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videox.app.data.DownloadItem
import com.videox.app.download.DownloadManagerHelper
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

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Downloads",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { if (downloads.isNotEmpty()) showClearDialog = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Clear history")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Files are saved in Downloads/VideoX. Tap a completed video to play it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("All", filter == DownloadFilter.ALL) { filter = DownloadFilter.ALL }
            FilterChip("Running", filter == DownloadFilter.RUNNING) { filter = DownloadFilter.RUNNING }
            FilterChip("Done", filter == DownloadFilter.COMPLETED) { filter = DownloadFilter.COMPLETED }
            FilterChip("Failed", filter == DownloadFilter.FAILED) { filter = DownloadFilter.FAILED }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (filtered.isEmpty()) {
            Text(
                text = when (filter) {
                    DownloadFilter.ALL -> "No downloads yet. Paste a link on the Home tab to start."
                    DownloadFilter.RUNNING -> "No downloads running."
                    DownloadFilter.COMPLETED -> "No completed downloads yet."
                    DownloadFilter.FAILED -> "No failed downloads. Good news."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filtered, key = { it.id }) { item ->
                    DownloadRow(item = item, onOpen = { viewModel.openDownload(item.downloadId) })
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
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun DownloadRow(item: DownloadItem, onOpen: () -> Unit) {
    val dateText = remember(item.createdAt) {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.createdAt))
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.isCompleted) { onOpen() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = item.fileName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.isCompleted) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(item.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${item.status} • ${item.progress}% • " +
                    "${DownloadManagerHelper.formatSize(item.downloadedBytes)} / " +
                    DownloadManagerHelper.formatSize(item.totalBytes) + " • $dateText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.isRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (item.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (item.isFailed && !item.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(item.errorMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(onClick = { /* Retry is via Home: the user can paste the same link again. */ }) {
                    Text("Retry from Home tab")
                }
            }
        }
    }
}
