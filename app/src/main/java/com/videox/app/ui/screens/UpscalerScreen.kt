package com.videox.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videox.app.download.UpscaleEngine
import com.videox.app.download.UpscaleMath
import com.videox.app.ui.theme.PremiumCard
import com.videox.app.ui.theme.PremiumDropdown
import com.videox.app.ui.theme.PremiumPrimaryButton
import com.videox.app.ui.theme.PremiumProgress
import com.videox.app.ui.theme.PremiumSecondaryButton
import com.videox.app.ui.theme.SectionHeader
import com.videox.app.ui.theme.SegmentedControl
import com.videox.app.ui.viewmodel.UpscaleJobState
import com.videox.app.ui.viewmodel.UpscalerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI video upscaler screen (v2.15): pick a video, pick a profile,
 * benchmark the phone's AI providers, and upscale fully on-device.
 * Everything shown here is measured on this phone — no placeholder
 * numbers, and the finished file's resolution is verified before
 * it is presented as a success.
 */
@Composable
fun UpscalerScreen(viewModel: UpscalerViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.refreshSettings() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onVideoPicked(uri)
    }

    val probe = viewModel.probe
    val profile = UpscaleMath.Profile.fromIndex(viewModel.profileIndex)
    val running = viewModel.jobState == UpscaleJobState.RUNNING || viewModel.jobState == UpscaleJobState.PAUSED
    val usable = probe?.let { UpscaleMath.profileUsable(it.height, profile) } ?: false

    if (viewModel.showExperimentalWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissExperimentalWarning() },
            title = { Text("Experimental profile") },
            text = {
                Text(
                    "The ${profile.label} target is experimental: upscaling to 4K on a phone " +
                        "takes a long time and the phone may get warm. The job pauses itself " +
                        "if the phone overheats, and you can pause or cancel at any time. " +
                        "Keep the phone plugged in for long sessions."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.startUpscaleConfirmed() }) { Text("Start anyway") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissExperimentalWarning() }) { Text("Go back") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "AI Video Upscaler",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Enhance a video with on-device AI. Nothing is uploaded: the model runs " +
                "entirely on this phone, and the finished file's resolution is verified " +
                "before it is saved.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Pick a video.
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                PremiumPrimaryButton(
                    onClick = { picker.launch("video/*") },
                    enabled = !running && !viewModel.probing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (viewModel.pickedName == null) "Choose a video" else "Choose a different video")
                }
                if (viewModel.probing) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Reading the video…", style = MaterialTheme.typography.bodySmall)
                }
                if (probe != null && viewModel.pickedName != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "${viewModel.pickedName}\n${probe.width}x${probe.height} • " +
                            "${"%.2f".format(probe.fps)} fps • " +
                            "${"%.0f".format(probe.durationSec)}s • " +
                            if (probe.hasAudio) "with audio" else "no audio",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Profile.
        SectionHeader(
            title = "Target quality",
            subtitle = "AI enhances each frame 4x, then the result is resized to the target."
        )
        Spacer(modifier = Modifier.height(10.dp))
        PremiumDropdown(
            label = "Profile",
            selectedLabel = profile.label + if (profile.experimental) " (experimental)" else "",
            options = UpscaleMath.Profile.entries.map {
                it.label + if (it.experimental) " (experimental)" else ""
            },
            onSelect = { viewModel.selectProfile(it) },
            modifier = Modifier.fillMaxWidth()
        )
        if (probe != null && !usable) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This video is already ${probe.height}p — pick a target above ${probe.height}p.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Mode.
        SectionHeader(title = "Mode")
        Spacer(modifier = Modifier.height(10.dp))
        SegmentedControl(
            options = listOf("Performance", "Battery Saver"),
            selectedIndex = if (viewModel.batterySaver) 1 else 0,
            onSelect = { viewModel.selectMode(it == 1) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (viewModel.batterySaver) {
                "Battery Saver always uses the CPU provider with smaller tiles: slower, cooler, less memory."
            } else {
                "Performance uses the fastest provider measured by the benchmark."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Provider + benchmark.
        SectionHeader(
            title = "AI provider",
            subtitle = "Measured on this phone. Nothing here is assumed."
        )
        Spacer(modifier = Modifier.height(10.dp))
        PremiumDropdown(
            label = "Provider",
            selectedLabel = if (viewModel.providerOverride == "auto") {
                "Auto (benchmark winner)"
            } else {
                UpscaleEngine.Provider.displayName(viewModel.providerOverride)
            },
            options = listOf("Auto (benchmark winner)") +
                UpscaleEngine.Provider.entries.map { it.displayName },
            onSelect = { index ->
                if (index == 0) viewModel.selectProvider("auto")
                else viewModel.selectProvider(UpscaleEngine.Provider.entries[index - 1].key)
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        PremiumSecondaryButton(
            onClick = { viewModel.runBenchmark() },
            enabled = !viewModel.benchmarking && !running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (viewModel.benchmarking) "Benchmarking…" else "Run benchmark")
        }
        Spacer(modifier = Modifier.height(8.dp))
        val summary = UpscaleMath.benchmarkSummary(
            viewModel.benchmarkResults,
            UpscaleEngine.Provider.PREFERENCE_ORDER
        )
        Text(
            text = summary ?: "Benchmark not run yet. It runs automatically before the first upscale.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "AI model: ${viewModel.modelStatus}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Start / pause / cancel.
        if (!running) {
            PremiumPrimaryButton(
                onClick = { viewModel.startUpscale() },
                enabled = probe != null && usable && !viewModel.probing && !viewModel.benchmarking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start upscaling")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PremiumSecondaryButton(
                    onClick = {
                        if (viewModel.jobState == UpscaleJobState.PAUSED) viewModel.resumeUpscale()
                        else viewModel.pauseUpscale()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (viewModel.jobState == UpscaleJobState.PAUSED) "Resume" else "Pause")
                }
                PremiumSecondaryButton(
                    onClick = { viewModel.cancelUpscale() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
            }
        }

        // Progress.
        val currentProgress = viewModel.progress
        if (running && currentProgress != null) {
            Spacer(modifier = Modifier.height(16.dp))
            val fraction = if (currentProgress.framesEstimated > 0) {
                currentProgress.framesDone.toFloat() / currentProgress.framesEstimated.toFloat()
            } else {
                0f
            }
            PremiumProgress(progress = fraction, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = currentProgress.phase +
                    (currentProgress.detail?.let { " — $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val measured = currentProgress.measuredFps
            if (measured != null && measured > 0 && currentProgress.framesEstimated > currentProgress.framesDone) {
                val remainingSec = (currentProgress.framesEstimated - currentProgress.framesDone) / measured
                Text(
                    text = "About ${"%.1f".format(measured)} frames/sec • " +
                        "roughly ${formatRemaining(remainingSec)} left (estimate from this phone's own speed)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (currentProgress.phase == "Upscaling") {
                Text(
                    text = "Estimating speed…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Message.
        if (viewModel.message != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = viewModel.message!!,
                style = MaterialTheme.typography.bodySmall,
                color = if (viewModel.isMessageError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        // Result.
        val done = viewModel.result
        if (done != null && viewModel.jobState == UpscaleJobState.DONE) {
            Spacer(modifier = Modifier.height(16.dp))
            PremiumCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Upscaled — verified ${done.width}x${done.height}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${done.outputFile.name}\n${done.frames} frames • " +
                            "${done.elapsedMs / 1000}s • ${UpscaleEngine.Provider.displayName(done.providerKey)} • ${done.encoder}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (viewModel.beforeThumb != null && viewModel.afterThumb != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Image(
                                    bitmap = viewModel.beforeThumb!!.asImageBitmap(),
                                    contentDescription = "Original frame",
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Before", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Image(
                                    bitmap = viewModel.afterThumb!!.asImageBitmap(),
                                    contentDescription = "Upscaled frame",
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("After", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PremiumSecondaryButton(
                            onClick = { viewModel.openOutput(done.outputFile.name) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Open") }
                        PremiumSecondaryButton(
                            onClick = { viewModel.shareOutput(done.outputFile.name) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Share") }
                    }
                }
            }
        }

        // History.
        if (viewModel.history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            SectionHeader(title = "Upscale history")
            Spacer(modifier = Modifier.height(10.dp))
            val dateFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
            viewModel.history.forEach { item ->
                PremiumCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = item.outputFileName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${item.profileLabel} • ${item.outputWidth}x${item.outputHeight} • " +
                                dateFormat.format(Date(item.finishedAtMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.openOutput(item.outputFileName) }) { Text("Open") }
                            TextButton(onClick = { viewModel.shareOutput(item.outputFileName) }) { Text("Share") }
                            TextButton(onClick = { viewModel.deleteHistoryItem(item) }) { Text("Delete") }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun formatRemaining(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return if (total < 60) {
        "$total sec"
    } else if (total < 3600) {
        "${total / 60} min ${total % 60} sec"
    } else {
        "${total / 3600} h ${(total % 3600) / 60} min"
    }
}
