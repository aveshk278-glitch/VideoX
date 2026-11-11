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
            displayName = displayName,
            retriedAfterUpdate = false
        )
    }

    private fun runYtDlpDownload(
        url: String,
        qualityLabel: String,
        formatSelector: String,
        fileName: String,
        displayName: String,
        retriedAfterUpdate: Boolean
    ) {
        val processId = "videox_${System.currentTimeMillis()}"
        viewModelScope.launch(Dispatchers.IO) {
            queueSemaphore.acquire()
            var rowId = -1L
            try {
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
                val response = YtDlpEngine.download(
                    url = url,
                    formatSelector = formatSelector,
                    audioOnly = audioOnly,
                    audioFormat = audioFormat,
                    outputBaseName = fileName,
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
                val expected = File(YtDlpEngine.downloadDir(), displayName)
                val found = expected.exists() || YtDlpEngine.downloadDir().listFiles()
                    ?.any { it.name.startsWith(fileName) } == true
                if (found) {
                    if (rowId > 0) repository.markYtStatus(rowId, "COMPLETED", null)
                    withContext(Dispatchers.Main) { showMessage("Download finished: $displayName") }
                } else {
                    // Show the FULL error (yt-dlp logs to stderr): the old
                    // 200-char cut hid the real reason from the user.
                    val err = response.err?.takeIf { it.isNotBlank() }
                        ?: response.out
                        ?: "yt-dlp finished but no file was created."
                    if (rowId > 0) repository.markYtStatus(rowId, "FAILED", err.take(200))
                    withContext(Dispatchers.Main) { showMessage("Download failed: ${err.take(1000)}", isError = true) }
                }
            } catch (e: Exception) {
                val errText = e.message ?: "yt-dlp reported an error."
                if (!retriedAfterUpdate && errText.contains("403")) {
                    // YouTube blocked the old engine (SABR 403): update
                    // yt-dlp once and retry this download automatically.
                    if (rowId > 0) repository.markYtStatus(rowId, "FAILED", "403 — updating engine, retrying.")
                    withContext(Dispatchers.Main) {
                        showMessage("YouTube blocked this download (403) — updating the yt-dlp engine and retrying…")
                    }
                    try {
                        YtDlpEngine.updateEngine(appContext)
                        ytDlpVersion = YtDlpEngine.ytDlpVersion(appContext)
                    } catch (_: Exception) {
                        // Update failed — the retry below still tries the
                        // Android player client; if it fails too, the
                        // normal error path reports it.
                    }
                    runYtDlpDownload(
                        url = url,
                        qualityLabel = qualityLabel,
                        formatSelector = formatSelector,
                        fileName = fileName,
                        displayName = displayName,
                        retriedAfterUpdate = true
                    )
                    return@launch
                }
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
