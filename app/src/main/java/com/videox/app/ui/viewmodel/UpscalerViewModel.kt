package com.videox.app.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videox.app.VideoXApp
import com.videox.app.download.FFmpegRunner
import com.videox.app.download.UpscaleCancelled
import com.videox.app.download.UpscaleControl
import com.videox.app.download.UpscaleEngine
import com.videox.app.download.UpscaleHistory
import com.videox.app.download.UpscaleHistoryItem
import com.videox.app.download.UpscaleMath
import com.videox.app.download.UpscaleModel
import com.videox.app.download.UpscalePipeline
import com.videox.app.download.UpscaleProgress
import com.videox.app.download.UpscaleResult
import com.videox.app.download.UpscaleService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UpscaleJobState { IDLE, RUNNING, PAUSED, DONE, ERROR, CANCELLED }

/**
 * AI video upscaler screen state (v2.15). The job itself runs in
 * UpscalePipeline; this ViewModel owns picking, probing, settings,
 * the benchmark, progress state, history and result actions.
 */
class UpscalerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as VideoXApp).repository
    private val appContext = application.applicationContext

    var pickedName by mutableStateOf<String?>(null)
        private set

    var probe by mutableStateOf<UpscaleMath.ProbeResult?>(null)
        private set

    var probing by mutableStateOf(false)
        private set

    var profileIndex by mutableStateOf(0)
        private set

    var batterySaver by mutableStateOf(repository.upscaleMode == "battery")
        private set

    var providerOverride by mutableStateOf(repository.upscaleProvider)
        private set

    var benchmarkResults by mutableStateOf<List<UpscaleMath.ProviderResult>>(emptyList())
        private set

    var benchmarking by mutableStateOf(false)
        private set

    var modelStatus by mutableStateOf("Checking…")
        private set

    var jobState by mutableStateOf(UpscaleJobState.IDLE)
        private set

    var progress by mutableStateOf<UpscaleProgress?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var isMessageError by mutableStateOf(false)
        private set

    var result by mutableStateOf<UpscaleResult?>(null)
        private set

    var beforeThumb by mutableStateOf<Bitmap?>(null)
        private set

    var afterThumb by mutableStateOf<Bitmap?>(null)
        private set

    var history by mutableStateOf<List<UpscaleHistoryItem>>(emptyList())
        private set

    var showExperimentalWarning by mutableStateOf(false)
        private set

    private var inputTempFile: File? = null
    private var control: UpscaleControl? = null
    private var job: Job? = null

    init {
        history = UpscaleHistory.decode(repository.upscaleHistoryJson)
        benchmarkResults = UpscaleMath.decodeBenchmark(repository.upscaleBenchmark)
        viewModelScope.launch(Dispatchers.IO) {
            val ready = UpscaleModel.existingModelFile(appContext) != null
            withContext(Dispatchers.Main) {
                modelStatus = if (ready) "Ready on this phone" else "Not downloaded yet (4.7 MB, first use only)"
            }
        }
    }

    /** Re-read settings (they can change on the Settings screen). */
    fun refreshSettings() {
        batterySaver = repository.upscaleMode == "battery"
        providerOverride = repository.upscaleProvider
        benchmarkResults = UpscaleMath.decodeBenchmark(repository.upscaleBenchmark)
    }

    fun selectProfile(index: Int) {
        profileIndex = index.coerceIn(0, UpscaleMath.Profile.entries.size - 1)
    }

    fun selectMode(battery: Boolean) {
        batterySaver = battery
        repository.upscaleMode = if (battery) "battery" else "performance"
    }

    fun selectProvider(key: String) {
        providerOverride = key
        repository.upscaleProvider = key
    }

    fun onVideoPicked(uri: Uri) {
        viewModelScope.launch {
            probing = true
            message = null
            result = null
            beforeThumb = null
            afterThumb = null
            try {
                val name = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "video"
                val dir = File(appContext.cacheDir, "upscale-input").apply { mkdirs() }
                val temp = File(dir, "input-${System.currentTimeMillis()}.mp4")
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("The picked video could not be read.")
                }
                inputTempFile?.let { old ->
                    if (old != temp) {
                        try {
                            old.delete()
                        } catch (_: Exception) {
                        }
                    }
                }
                inputTempFile = temp
                pickedName = name
                val runner = FFmpegRunner(appContext)
                val probed = runner.probeVideo(temp)
                probe = probed
                val profile = UpscaleMath.Profile.fromIndex(profileIndex)
                if (!UpscaleMath.profileUsable(probed.height, profile)) {
                    showMessage(
                        "This video is already ${probed.height}p, which is at or above the " +
                            "${profile.targetHeight}p target. Pick a higher target.",
                        isError = true
                    )
                }
            } catch (e: Exception) {
                probe = null
                pickedName = null
                showMessage("That video could not be read: ${e.message}", isError = true)
            } finally {
                probing = false
            }
        }
    }

    fun runBenchmark() {
        if (benchmarking || jobState == UpscaleJobState.RUNNING) return
        viewModelScope.launch {
            benchmarking = true
            message = null
            try {
                val modelFile = withContext(Dispatchers.IO) {
                    UpscaleModel.ensureModel(appContext) { percent ->
                        message = "Downloading the AI model (4.7 MB)… $percent%"
                    }
                }
                modelStatus = "Ready on this phone"
                val results = withContext(Dispatchers.Default) {
                    UpscaleEngine.runBenchmark(modelFile, warmupRuns = 1, timedRuns = 3) { done ->
                        benchmarkResults = benchmarkResults.filter { it.provider != done.provider } + done
                    }
                }
                benchmarkResults = results
                repository.upscaleBenchmark = UpscaleMath.encodeBenchmark(results)
                val winner = UpscaleMath.selectBest(results, UpscaleEngine.Provider.PREFERENCE_ORDER)
                showMessage(
                    if (winner != null) {
                        "Benchmark done. Fastest on this phone: ${UpscaleEngine.Provider.displayName(winner)}."
                    } else {
                        "Benchmark done, but no provider ran successfully on this phone."
                    },
                    isError = winner == null
                )
            } catch (e: Exception) {
                showMessage("Benchmark failed: ${e.message}", isError = true)
            } finally {
                benchmarking = false
                if (message?.startsWith("Downloading the AI model") == true) message = null
            }
        }
    }

    fun startUpscale() {
        val probed = probe ?: return
        val profile = UpscaleMath.Profile.fromIndex(profileIndex)
        if (!UpscaleMath.profileUsable(probed.height, profile)) {
            showMessage("Pick a target above ${probed.height}p first.", isError = true)
            return
        }
        if (profile.experimental) {
            showExperimentalWarning = true
            return
        }
        startUpscaleConfirmed()
    }

    fun dismissExperimentalWarning() {
        showExperimentalWarning = false
    }

    fun startUpscaleConfirmed() {
        showExperimentalWarning = false
        val input = inputTempFile ?: return
        val profile = UpscaleMath.Profile.fromIndex(profileIndex)
        val useControl = UpscaleControl()
        control = useControl
        jobState = UpscaleJobState.RUNNING
        result = null
        message = null
        progress = null
        job = viewModelScope.launch {
            startService()
            try {
                val outcome = UpscalePipeline.runJob(
                    context = appContext,
                    inputFile = input,
                    profile = profile,
                    batterySaver = batterySaver,
                    providerOverrideKey = providerOverride,
                    storedBenchmark = repository.upscaleBenchmark,
                    onBenchmarkDone = { encoded ->
                        repository.upscaleBenchmark = encoded
                        benchmarkResults = UpscaleMath.decodeBenchmark(encoded)
                    },
                    control = useControl,
                    onProgress = { update -> progress = update }
                )
                result = outcome
                jobState = UpscaleJobState.DONE
                val item = UpscaleHistoryItem(
                    inputName = outcome.inputName,
                    profileLabel = profile.label,
                    outputFileName = outcome.outputFile.name,
                    outputWidth = outcome.width,
                    outputHeight = outcome.height,
                    finishedAtMillis = System.currentTimeMillis()
                )
                history = (listOf(item) + history).take(UpscaleHistory.MAX_ITEMS)
                repository.upscaleHistoryJson = UpscaleHistory.encode(history)
                loadThumbs(input, outcome.outputFile)
                val seconds = outcome.elapsedMs / 1000
                showMessage(
                    "Done: ${outcome.width}x${outcome.height} in ${seconds}s " +
                        "(${outcome.frames} frames, ${UpscaleEngine.Provider.displayName(outcome.providerKey)}).",
                    isError = false
                )
            } catch (cancelled: UpscaleCancelled) {
                jobState = UpscaleJobState.CANCELLED
                showMessage("Upscaling was cancelled. No partial file was kept.", isError = false)
            } catch (e: Exception) {
                jobState = UpscaleJobState.ERROR
                showMessage(e.message ?: "Upscaling failed.", isError = true)
            } finally {
                stopService()
            }
        }
    }

    fun pauseUpscale() {
        control?.paused = true
        if (jobState == UpscaleJobState.RUNNING) jobState = UpscaleJobState.PAUSED
    }

    fun resumeUpscale() {
        control?.paused = false
        if (jobState == UpscaleJobState.PAUSED) jobState = UpscaleJobState.RUNNING
    }

    fun cancelUpscale() {
        control?.cancelled = true
    }

    fun openOutput(fileName: String) {
        repository.openFile("Upscaled/$fileName")
    }

    fun shareOutput(fileName: String) {
        repository.shareFile("Upscaled/$fileName")
    }

    fun deleteHistoryItem(item: UpscaleHistoryItem) {
        history = history.filter { it != item }
        repository.upscaleHistoryJson = UpscaleHistory.encode(history)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
                    "VideoX/Upscaled"
                )
                File(dir, item.outputFileName).delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun loadThumbs(input: File, output: File) {
        viewModelScope.launch(Dispatchers.IO) {
            beforeThumb = frameThumb(input)
            afterThumb = frameThumb(output)
        }
    }

    private fun frameThumb(file: File): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val frame = retriever.getFrameAtTime(1_000_000) ?: return null
                val scale = 320f / frame.width.coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(
                    frame, 320, (frame.height * scale).toInt().coerceAtLeast(1), true
                )
                if (scaled != frame) {
                    try {
                        frame.recycle()
                    } catch (_: Exception) {
                    }
                }
                scaled
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun startService() {
        try {
            appContext.startForegroundService(Intent(appContext, UpscaleService::class.java))
        } catch (_: Exception) {
            // The job still runs in the foreground Activity; background survival is best-effort.
        }
    }

    private fun stopService() {
        try {
            appContext.stopService(Intent(appContext, UpscaleService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun showMessage(text: String, isError: Boolean) {
        message = text
        isMessageError = isError
    }

    override fun onCleared() {
        control?.cancelled = true
        job?.cancel()
        try {
            inputTempFile?.delete()
        } catch (_: Exception) {
        }
        super.onCleared()
    }
}
