package com.videox.app.ui.viewmodel

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videox.app.VideoXApp
import com.videox.app.data.DownloadItem
import com.videox.app.download.DownloadManagerHelper
import com.videox.app.download.QualitySelector
import com.videox.app.download.YtDlpEngine
import com.videox.app.download.YtVideoInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

class DownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as VideoXApp).repository
    private val appContext = application.applicationContext

    /** True once this session's pre-download engine update has been tried. */
    private var engineUpdateChecked = false

    val downloads: StateFlow<List<DownloadItem>> =
        repository.allDownloads.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    var urlInput by mutableStateOf("")
        private set

    var message by mutableStateOf<String?>(null)
        private set

    var isMessageError by mutableStateOf(false)
        private set

    var wifiOnly by mutableStateOf(repository.wifiOnly)
        private set

    var darkTheme by mutableStateOf(repository.darkTheme)
        private set

    var maxConcurrent by mutableStateOf(repository.maxConcurrentDownloads)
        private set

    /** True = audio only, false = video. */
    var audioOnly by mutableStateOf(false)
        private set

    var audioFormat by mutableStateOf("mp3")
        private set

    var customFileName by mutableStateOf("")
        private set

    var fetchingFormats by mutableStateOf(false)
        private set

    var videoInfo by mutableStateOf<YtVideoInfo?>(null)
        private set

    /**
     * Selected video quality height in pixels, or null = Best Quality
     * (highest genuinely available, video + best audio merged).
     * Quality is chosen by height, never by raw format id — a raw
     * high-resolution format id is video-only and would download silent.
     */
    var selectedQualityHeight by mutableStateOf<Int?>(null)
        private set

    /** Distinct heights actually available for the current video, best first. */
    val availableQualities: List<Int>
        get() = videoInfo?.let { QualitySelector.availableHeights(it.formats) } ?: emptyList()

    /** Honest "Max available: 1080p Full HD" line for the current video. */
    val maxQualityLabel: String?
        get() = videoInfo?.let { QualitySelector.maxQualityLabel(it.formats) }

    /** Live ETA / progress text for the most recent yt-dlp download. */
    var activeEtaText by mutableStateOf<String?>(null)
        private set

    var ytdlpReady by mutableStateOf(false)
        private set

    /** Bundled/updated yt-dlp version, e.g. "2025.11.12". Shown in Settings. */
    var ytDlpVersion by mutableStateOf<String?>(null)
        private set

    var updatingEngine by mutableStateOf(false)
        private set

    private var queueSemaphore = Semaphore(maxConcurrent)

    init {
        viewModelScope.launch {
            // Wait for yt-dlp to finish initialising (a few seconds on first run).
            repeat(20) {
                if (YtDlpEngine.initialized) {
                    ytdlpReady = true
                    return@launch
                }
                delay(500)
            }
            ytdlpReady = YtDlpEngine.initialized
            if (!ytdlpReady && YtDlpEngine.initError != null) {
                showMessage("yt-dlp could not start: ${YtDlpEngine.initError}", isError = true)
            }
        }
        // Poll DownloadManager while the app is open so progress bars stay live.
        viewModelScope.launch {
            while (true) {
                try {
                    repository.refreshStatuses()
                } catch (_: Exception) {
                    // Polling must never crash the UI; the next tick retries.
                }
                delay(1_500)
            }
        }
        // Read the yt-dlp version for Settings once the engine is ready.
        viewModelScope.launch(Dispatchers.IO) {
            var waited = 0
            while (!YtDlpEngine.initialized && waited < 20) {
                delay(500)
                waited++
            }
            ytDlpVersion = YtDlpEngine.ytDlpVersion(appContext)
        }
    }

    /**
     * Make sure the yt-dlp engine has been updated once this session
     * before any yt-dlp work runs. The bundled engine (2025.11.12) is
     * too old for YouTube's current (SABR) delivery: its web formats
     * fail with 403, and the Android-client workaround only gives 360p.
     * The update downloads the latest stable yt-dlp from GitHub; if it
     * fails (no network, GitHub blocked) the download still tries with
     * the bundled engine instead of refusing to run.
     */
    private suspend fun ensureEngineUpdated() {
        if (engineUpdateChecked || !YtDlpEngine.initialized) return
        engineUpdateChecked = true
        try {
            YtDlpEngine.updateEngine(appContext)
            ytDlpVersion = YtDlpEngine.ytDlpVersion(appContext)
        } catch (_: Exception) {
            // Silent — the caller proceeds with the bundled engine.
        }
    }

    /** Manual engine update from Settings. Shows the result to the user. */
    fun updateYtDlpEngine() {
        if (updatingEngine || !YtDlpEngine.initialized) return
        updatingEngine = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val status = YtDlpEngine.updateEngine(appContext)
                ytDlpVersion = YtDlpEngine.ytDlpVersion(appContext)
                withContext(Dispatchers.Main) {
                    updatingEngine = false
                    if (status == "DONE") {
                        showMessage("yt-dlp updated to v${ytDlpVersion ?: "?"} — try your download again.")
                    } else {
                        showMessage("yt-dlp is already up to date (v${ytDlpVersion ?: "?"}).")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updatingEngine = false
                    showMessage("Could not update yt-dlp: ${e.message ?: "check your internet and try again."}", isError = true)
                }
            }
        }
    }

    fun onUrlChange(value: String) {
        urlInput = value
        message = null
        if (videoInfo != null) {
            videoInfo = null
            selectedQualityHeight = null
        }
    }

    fun onPaste(text: String) {
        urlInput = text.trim()
        message = null
        videoInfo = null
        selectedQualityHeight = null
    }

    /** Paste whatever text link is currently on the clipboard, if any. */
    fun pasteFromClipboard() {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (text.startsWith("http")) {
            onPaste(text)
        } else {
            showMessage("Clipboard has no link to paste.", isError = true)
        }
    }

    fun updateWifiOnly(enabled: Boolean) {
        wifiOnly = enabled
        repository.wifiOnly = enabled
    }

    fun updateDarkTheme(enabled: Boolean) {
        darkTheme = enabled
        repository.darkTheme = enabled
    }

    fun updateMaxConcurrent(value: Int) {
        maxConcurrent = value.coerceIn(1, 3)
        repository.maxConcurrentDownloads = maxConcurrent
        queueSemaphore = Semaphore(maxConcurrent)
    }

    fun updateAudioOnly(value: Boolean) {
        audioOnly = value
        selectedQualityHeight = null
    }

    fun updateAudioFormat(value: String) {
        audioFormat = value
    }

    fun onCustomFileNameChange(value: String) {
        customFileName = value
    }

    fun selectQuality(height: Int?) {
        // Clamp to what actually exists — never promise an unavailable height.
        selectedQualityHeight = videoInfo?.let { QualitySelector.clampHeight(it.formats, height) }
    }

    fun showMessage(text: String, isError: Boolean = false) {
        message = text
        isMessageError = isError
    }

    fun clearMessage() {
        message = null
    }

    /** Ask yt-dlp for the title and available formats of the pasted URL. */
    fun fetchFormats() {
        val url = urlInput.trim()
        if (url.isEmpty()) {
            showMessage("Paste a video link first.", isError = true)
            return
        }
        if (DownloadManagerHelper.isSupportedVideoUrl(url)) {
            // Direct file link — no formats to fetch, it downloads as-is.
            showMessage("Direct video link — tap Download, no format list needed.")
            return
        }
        if (!YtDlpEngine.initialized) {
            showMessage("yt-dlp is still starting up. Wait a few seconds and try again.", isError = true)
            return
        }
        fetchingFormats = true
        message = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Newest engine first, so the format list matches what
                // the download will actually be able to fetch.
                ensureEngineUpdated()
                val info = YtDlpEngine.getInfo(url)
                withContext(Dispatchers.Main) {
                    videoInfo = info
                    selectedQualityHeight = null
                    fetchingFormats = false
                    if (info.formats.isEmpty()) {
                        showMessage("No downloadable formats found for this link.", isError = true)
                    } else {
                        showMessage(
                            "${QualitySelector.maxQualityLabel(info.formats)} — " +
                                "Best Quality is selected. Pick another quality if you want, then tap Download."
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    fetchingFormats = false
                    videoInfo = null
                    showMessage(
                        "Could not read this link: ${e.message ?: "yt-dlp reported an error."} " +
                            "The site may block downloads, or the link may need login.",
                        isError = true
                    )
                }
            }
        }
    }

    fun startDownload() {
        val url = urlInput.trim()
        if (url.isEmpty()) {
            showMessage("Paste a video link first.", isError = true)
            return
        }
        if (!repository.isOnline()) {
            showMessage("No internet connection. Check your connection and try again.", isError = true)
            return
        }
        if (DownloadManagerHelper.isSupportedVideoUrl(url)) {
            startDirectDownload(url)
            return
        }
        // yt-dlp path.
        if (videoInfo == null) {
            // Fetch formats first, then the user picks one and taps Download again.
            fetchFormats()
            showMessage("Reading formats… tap Download again after picking one.")
            return
        }
        startYtDlpDownload(url)
    }

    private fun startDirectDownload(url: String) {
        viewModelScope.launch {
            val result = repository.startDownload(url)
            result.onSuccess {
                val name = DownloadManagerHelper.fileNameFromUrl(url)
                showMessage("Download started: $name — saved to Downloads/VideoX")
                urlInput = ""
                videoInfo = null
            }.onFailure { e ->
                showMessage(e.message ?: "Download could not be started. Try again.", isError = true)
            }
        }
    }

    private fun startYtDlpDownload(url: String) {
        val info = videoInfo ?: return
        val qualityHeight = QualitySelector.clampHeight(info.formats, selectedQualityHeight)
        val qualityLabel = if (audioOnly) {
            "Audio ($audioFormat)"
        } else if (qualityHeight == null) {
            "Best Quality"
        } else {
            QualitySelector.qualityLabel(qualityHeight)
        }
        val formatSelector = QualitySelector.buildFormatSelector(qualityHeight)
        // One deterministic name, used both for the file yt-dlp writes
        // and the file the app looks for afterwards.
        val fileName = QualitySelector.resolveOutputBaseName(info.title, customFileName)
        val displayName = fileName + if (audioOnly) ".$audioFormat" else ".mp4"
        runYtDlpDownload(
            url = url,
            qualityLabel = qualityLabel,
            formatSelector = formatSelector,
            fileName = fileName,
            displayName = displayName
        )
    }

    private fun runYtDlpDownload(
        url: String,
        qualityLabel: String,
        formatSelector: String,
        fileName: String,
        displayName: String
    ) {
        val processId = "videox_${System.currentTimeMillis()}"
        viewModelScope.launch(Dispatchers.IO) {
            queueSemaphore.acquire()
            var rowId = -1L
            try {
                // Engine first: the bundled yt-dlp is too old for
                // YouTube (SABR 403) — update it before the first download.
                ensureEngineUpdated()
                val item = DownloadItem(
                    url = url,
                    fileName = displayName,
                    status = "QUEUED",
                    mode = if (audioOnly) "AUDIO" else "VIDEO",
                    formatLabel = qualityLabel,
                    processId = processId,
                    engine = "YTDLP"
                )
                rowId = repository.insertYtDownload(item)
                withContext(Dispatchers.Main) {
                    showMessage("Download queued: $displayName")
                    urlInput = ""
                    videoInfo = null
                    selectedQualityHeight = null
                }
                val attempts = QualitySelector.downloadAttempts(url)
                var attemptIndex = 0
                var engineRetried = false
                var lastErr: String? = null
                var done = false
                while (attemptIndex < attempts.size && !done) {
                    val args = attempts[attemptIndex]
                    val isFallback = attemptIndex > 0
                    if (isFallback) {
                        // Drop the full-quality attempt's partial files so
                        // the 360p attempt starts clean instead of trying
                        // to resume the wrong format's .part.
                        YtDlpEngine.downloadDir().listFiles()
                            ?.filter { it.name.startsWith(fileName) && it.name.endsWith(".part") }
                            ?.forEach { it.delete() }
                        withContext(Dispatchers.Main) {
                            showMessage("Full quality is blocked — trying the basic (360p) download…")
                        }
                    }
                    try {
                        val response = YtDlpEngine.download(
                            url = url,
                            formatSelector = formatSelector,
                            audioOnly = audioOnly,
                            audioFormat = audioFormat,
                            outputBaseName = fileName,
                            extractorArgs = args,
                            processId = processId
                        ) { progress, eta ->
                            viewModelScope.launch {
                                if (rowId > 0) repository.updateYtProgress(rowId, progress.toInt(), 0L, 0L)
                                withContext(Dispatchers.Main) {
                                    activeEtaText = if (eta > 0) "ETA ${eta}s" else null
                                }
                            }
                        }
                        // yt-dlp finished without throwing — check the file actually exists.
                        // (.part files don't count: a failed download leaves them behind.)
                        val expected = File(YtDlpEngine.downloadDir(), displayName)
                        val found = expected.exists() || YtDlpEngine.downloadDir().listFiles()
                            ?.any { it.name.startsWith(fileName) && !it.name.endsWith(".part") } == true
                        if (found) {
                            if (rowId > 0) repository.markYtStatus(rowId, "COMPLETED", null)
                            val suffix = if (isFallback) {
                                " — only 360p was available; YouTube blocked the higher quality."
                            } else ""
                            withContext(Dispatchers.Main) {
                                showMessage("Download finished: $displayName$suffix")
                            }
                            done = true
                        } else {
                            // No file, no exception — try the next attempt.
                            lastErr = response.err?.takeIf { it.isNotBlank() }
                                ?: response.out
                                ?: "yt-dlp finished but no file was created."
                            attemptIndex++
                        }
                    } catch (e: Exception) {
                        val errText = e.message ?: "yt-dlp reported an error."
                        val looksBlocked = errText.contains("403") ||
                            errText.contains("not available") ||
                            errText.contains("bot", ignoreCase = true)
                        if (attemptIndex == 0 && !engineRetried && looksBlocked) {
                            // First attempt blocked: update the engine once
                            // and retry the full-quality attempt.
                            withContext(Dispatchers.Main) {
                                showMessage("YouTube blocked this download — updating the yt-dlp engine and retrying…")
                            }
                            try {
                                YtDlpEngine.updateEngine(appContext)
                                ytDlpVersion = YtDlpEngine.ytDlpVersion(appContext)
                            } catch (_: Exception) {
                                // Update failed — the retry still tries.
                            }
                            engineRetried = true
                            // attemptIndex stays 0: retry full quality once.
                        } else {
                            lastErr = errText
                            attemptIndex++
                        }
                    }
                }
                if (!done) {
                    // Every attempt failed — show the FULL last error
                    // (yt-dlp logs to stderr); the old 200-char cut hid
                    // the real reason from the user.
                    val err = lastErr ?: "yt-dlp reported an error."
                    if (rowId > 0) repository.markYtStatus(rowId, "FAILED", err.take(200))
                    withContext(Dispatchers.Main) {
                        showMessage("Download failed: ${err.take(1000)}", isError = true)
                    }
                }
            } catch (e: Exception) {
                // Only reached for errors outside the attempt loop
                // (queue insert, engine check): report honestly.
                val errText = e.message ?: "yt-dlp reported an error."
                if (rowId > 0) repository.markYtStatus(rowId, "FAILED", errText.take(200))
                withContext(Dispatchers.Main) {
                    showMessage("Download failed: $errText", isError = true)
                }
            } finally {
                queueSemaphore.release()
                withContext(Dispatchers.Main) { activeEtaText = null }
            }
        }
    }

    fun cancelDownload(item: DownloadItem) {
        viewModelScope.launch {
            item.processId?.let { YtDlpEngine.cancel(it) }
            repository.markYtStatus(item.id, "FAILED", "Cancelled by user.")
            showMessage("Download cancelled: ${item.fileName}")
        }
    }

    fun retryDownload(item: DownloadItem) {
        urlInput = item.url
        audioOnly = item.mode == "AUDIO"
        videoInfo = null
        selectedQualityHeight = null
        showMessage("Tap Download to retry: ${item.fileName}")
    }

    fun clearHistory(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.clearHistory()
            showMessage("Download history cleared.")
            onDone()
        }
    }

    fun openDownload(downloadId: Long) {
        repository.openDownload(downloadId)
    }

    fun openFile(fileName: String) {
        try {
            repository.openFile(fileName)
        } catch (e: Exception) {
            showMessage("Could not open file: ${e.message}", isError = true)
        }
    }

    fun shareFile(fileName: String) {
        try {
            repository.shareFile(fileName)
        } catch (e: Exception) {
            showMessage("Could not share file: ${e.message}", isError = true)
        }
    }
}
