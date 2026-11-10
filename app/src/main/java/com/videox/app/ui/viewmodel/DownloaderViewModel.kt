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
import com.videox.app.download.YtDlpEngine
import com.videox.app.download.YtFormat
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

    var selectedFormat by mutableStateOf<YtFormat?>(null)
        private set

    /** Live ETA / progress text for the most recent yt-dlp download. */
    var activeEtaText by mutableStateOf<String?>(null)
        private set

    var ytdlpReady by mutableStateOf(false)
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
    }

    fun onUrlChange(value: String) {
        urlInput = value
        message = null
        if (videoInfo != null) {
            videoInfo = null
            selectedFormat = null
        }
    }

    fun onPaste(text: String) {
        urlInput = text.trim()
        message = null
        videoInfo = null
        selectedFormat = null
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

    fun setAudioOnly(value: Boolean) {
        audioOnly = value
        selectedFormat = null
    }

    fun setAudioFormat(value: String) {
        audioFormat = value
    }

    fun onCustomFileNameChange(value: String) {
        customFileName = value
    }

    fun selectFormat(format: YtFormat) {
        selectedFormat = format
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
                    selectedFormat = null
                    fetchingFormats = false
                    if (info.formats.isEmpty()) {
                        showMessage("No downloadable formats found for this link.", isError = true)
                    } else {
                        showMessage("${info.formats.size} formats found — pick one, then tap Download.")
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
        val format = selectedFormat
        val fileName = if (customFileName.isBlank()) {
            YtDlpEngine.sanitizeFileName(info.title)
        } else {
            YtDlpEngine.sanitizeFileName(customFileName)
        }
        val displayName = fileName + if (audioOnly) ".$audioFormat" else ".${format?.ext ?: "mp4"}"
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
                    formatLabel = format?.label,
                    processId = processId,
                    engine = "YTDLP"
                )
                rowId = repository.insertYtDownload(item)
                withContext(Dispatchers.Main) {
                    showMessage("Download queued: $displayName")
                    urlInput = ""
                    videoInfo = null
                    selectedFormat = null
                }
                val response = YtDlpEngine.download(
                    url = url,
                    formatId = format?.formatId,
                    audioOnly = audioOnly,
                    audioFormat = audioFormat,
                    customFileName = if (customFileName.isBlank()) null else customFileName,
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
                    val err = response.out ?: "yt-dlp finished but no file was created."
                    if (rowId > 0) repository.markYtStatus(rowId, "FAILED", err.take(200))
                    withContext(Dispatchers.Main) { showMessage("Download failed: ${err.take(200)}", isError = true) }
                }
            } catch (e: Exception) {
                if (rowId > 0) repository.markYtStatus(rowId, "FAILED", e.message?.take(200))
                withContext(Dispatchers.Main) {
                    showMessage("Download failed: ${e.message ?: "yt-dlp reported an error."}", isError = true)
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
        selectedFormat = null
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
