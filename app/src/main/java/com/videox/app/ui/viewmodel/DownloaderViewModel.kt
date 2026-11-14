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
import com.videox.app.download.ExtractionLanguage
import com.videox.app.download.QualitySelector
import com.videox.app.download.SpeechTranscriber
import com.videox.app.download.SubtitleText
import com.videox.app.download.Translator
import com.videox.app.download.YtDlpEngine
import com.videox.app.download.YtDlpJson
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

/** Offered when full quality is blocked but a 360p download would work — the user must choose it. */
data class FallbackOffer(
    val url: String,
    val fileName: String,
    val displayName: String
)

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

    // ---- v2.9 settings ----

    var warnMetered by mutableStateOf(repository.warnMetered)
        private set

    var proxyUrl by mutableStateOf(repository.proxyUrl)
        private set

    var subtitlesEnabled by mutableStateOf(repository.subtitlesEnabled)
        private set

    var subtitleLangs by mutableStateOf(repository.subtitleLangs)
        private set

    var embedChapters by mutableStateOf(repository.embedChapters)
        private set

    var transcribeFallback by mutableStateOf(repository.transcribeFallback)
        private set

    // ---- v2.14 settings (original-language text extraction) ----

    var extractionLanguage by mutableStateOf(repository.extractionLanguage)
        private set

    var translateEnabled by mutableStateOf(repository.translateEnabled)
        private set

    var translateTargetLanguage by mutableStateOf(repository.translateTargetLanguage)
        private set

    /** Per-extraction language override from Home, or null = use the Settings default. */
    var extractionOverride by mutableStateOf<String?>(null)
        private set

    /** "Hindi • original subtitles" for the last extraction, shown on Home. */
    var detectedLanguageLabel by mutableStateOf<String?>(null)
        private set

    /** Progress text while speech transcription (or its model download) runs. */
    var transcriptionProgress by mutableStateOf<String?>(null)
        private set

    /** The proxy actually used for yt-dlp requests: only a valid URL, never a typo. */
    private val effectiveProxyUrl: String?
        get() = proxyUrl.takeIf { it.isNotBlank() && QualitySelector.isValidProxyUrl(it) }

    /** True when the metered-network warning dialog should be shown. */
    var meteredConfirmPending by mutableStateOf(false)
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

    /** True while a video-to-text extraction is running. */
    var extractingText by mutableStateOf(false)
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

    /** Shown when full quality is blocked but 360p would work — the user must tap to accept it. */
    var fallbackOffer by mutableStateOf<FallbackOffer?>(null)
        private set

    /** True when the current format list came from the Android client (360p only). */
    var videoInfoFromFallback by mutableStateOf(false)
        private set

    /** Direct file found by content-type probing, awaiting the next Download tap. */
    private var pendingDirectUrl: String? = null
    private var pendingDirectName: String? = null

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
        detectedLanguageLabel = null
        if (videoInfo != null) {
            videoInfo = null
            selectedQualityHeight = null
        }
        fallbackOffer = null
        videoInfoFromFallback = false
        pendingDirectUrl = null
        pendingDirectName = null
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

    fun updateWarnMetered(enabled: Boolean) {
        warnMetered = enabled
        repository.warnMetered = enabled
    }

    fun updateProxyUrl(value: String) {
        proxyUrl = value
        repository.proxyUrl = value
    }

    fun updateSubtitlesEnabled(enabled: Boolean) {
        subtitlesEnabled = enabled
        repository.subtitlesEnabled = enabled
    }

    fun updateSubtitleLangs(value: String) {
        subtitleLangs = QualitySelector.sanitizeSubtitleLangs(value)
        repository.subtitleLangs = subtitleLangs
    }

    fun updateEmbedChapters(enabled: Boolean) {
        embedChapters = enabled
        repository.embedChapters = enabled
    }

    fun updateTranscribeFallback(enabled: Boolean) {
        transcribeFallback = enabled
        repository.transcribeFallback = enabled
    }

    fun updateExtractionLanguage(value: String) {
        extractionLanguage = if (ExtractionLanguage.isKnownCode(value)) value else ExtractionLanguage.AUTO
        repository.extractionLanguage = extractionLanguage
    }

    fun updateTranslateEnabled(enabled: Boolean) {
        translateEnabled = enabled
        repository.translateEnabled = enabled
    }

    fun updateTranslateTargetLanguage(value: String) {
        translateTargetLanguage = if (ExtractionLanguage.isTranslationTarget(value)) value else "en"
        repository.translateTargetLanguage = translateTargetLanguage
    }

    fun updateExtractionOverride(value: String?) {
        extractionOverride = value?.takeIf { ExtractionLanguage.isKnownCode(it) }
    }

    /** True when the typed proxy text is non-empty but not a usable proxy URL. */
    val proxyUrlInvalid: Boolean
        get() = proxyUrl.isNotBlank() && !QualitySelector.isValidProxyUrl(proxyUrl)

    fun dismissMeteredWarning() {
        meteredConfirmPending = false
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

    /**
     * Ask yt-dlp for the title and available formats of the pasted URL.
     * Routing, in order:
     * 1. Direct file link (known extension) — no formats needed.
     * 2. yt-dlp with default clients — every supported site, pages with
     *    embedded video, and direct files the generic extractor finds.
     * 3. YouTube blocked? Retry with the Android client — only 360p is
     *    reachable that way, and the list will honestly show just that.
     * 4. Still nothing? Probe the URL's content-type: a real media file
     *    (extension-less / signed / redirected link) goes to
     *    DownloadManager.
     * 5. Otherwise the accurate yt-dlp error — never a generic
     *    "not supported" message for a link that might work.
     * When [autoStartDirect] is true (called from Download), a direct
     * file found by probing starts downloading immediately.
     */
    fun fetchFormats(autoStartDirect: Boolean = false) {
        val url = urlInput.trim()
        if (url.isEmpty()) {
            showMessage("Paste a video link first.", isError = true)
            return
        }
        if (DownloadManagerHelper.isSupportedVideoUrl(url)) {
            // Direct file link — no formats to fetch, it downloads as-is.
            showMessage("Direct media link — tap Download, no format list needed.")
            return
        }
        if (!YtDlpEngine.initialized) {
            showMessage("yt-dlp is still starting up. Wait a few seconds and try again.", isError = true)
            return
        }
        fetchingFormats = true
        message = null
        fallbackOffer = null
        videoInfoFromFallback = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Newest engine first, so the format list matches what
                // the download will actually be able to fetch.
                ensureEngineUpdated()
                val (info, fromFallback) = fetchInfo(url)
                withContext(Dispatchers.Main) {
                    videoInfo = info
                    videoInfoFromFallback = fromFallback
                    selectedQualityHeight = null
                    fetchingFormats = false
                    if (info.formats.isEmpty()) {
                        showMessage("No downloadable formats found for this link.", isError = true)
                    } else if (fromFallback) {
                        showMessage(
                            "Only basic quality (360p) is reachable for this video right now — " +
                                "YouTube is blocking higher qualities. You can still download 360p."
                        )
                    } else if (audioOnly) {
                        // Audio mode has no video quality list (Seal #750):
                        // say what will actually download instead.
                        showMessage(
                            "Audio ($audioFormat) will be downloaded from this video. Tap Download."
                        )
                    } else {
                        showMessage(
                            "${QualitySelector.maxQualityLabel(info.formats)} — " +
                                "Best Quality is selected. Pick another quality if you want, then tap Download."
                        )
                    }
                }
            } catch (e: Exception) {
                // yt-dlp could not read this link at all. Before giving
                // up, check whether the URL is actually a direct media
                // file (extension-less / signed / redirected).
                val probe = try {
                    DownloadManagerHelper.probeDirectMedia(url)
                } catch (_: Exception) {
                    null
                }
                withContext(Dispatchers.Main) {
                    fetchingFormats = false
                    videoInfo = null
                    if (probe != null) {
                        val sizeText = if (probe.contentLength > 0) {
                            " (${DownloadManagerHelper.formatSize(probe.contentLength)})"
                        } else ""
                        if (autoStartDirect) {
                            pendingDirectUrl = null
                            pendingDirectName = null
                            startDirectDownload(url, probe.fileName)
                        } else {
                            pendingDirectUrl = url
                            pendingDirectName = probe.fileName
                            showMessage(
                                "This is a direct ${probe.contentType} file$sizeText — " +
                                    "tap Download to save it as ${probe.fileName}."
                            )
                        }
                    } else {
                        showMessage(
                            QualitySelector.describeYtDlpError(e.message, effectiveProxyUrl != null),
                            isError = true
                        )
                    }
                }
            }
        }
    }

    /**
     * Fetch formats, default clients first. On a YouTube block, retry
     * with the Android client (360p only) and say so in the result.
     * Throws the ORIGINAL error when both fail.
     */
    private suspend fun fetchInfo(url: String): Pair<YtVideoInfo, Boolean> {
        return try {
            YtDlpEngine.getInfo(url, effectiveProxyUrl) to false
        } catch (e: Exception) {
            if (QualitySelector.isYouTubeUrl(url)) {
                try {
                    YtDlpEngine.getInfoWithArgs(url, QualitySelector.ANDROID_FALLBACK_ARGS, effectiveProxyUrl) to true
                } catch (_: Exception) {
                    throw e
                }
            } else {
                throw e
            }
        }
    }

    fun startDownload(confirmedMetered: Boolean = false) {
        val url = urlInput.trim()
        if (url.isEmpty()) {
            showMessage("Paste a video link first.", isError = true)
            return
        }
        if (!repository.isOnline()) {
            showMessage("No internet connection. Check your connection and try again.", isError = true)
            return
        }
        // Metered-network warning: on mobile data,
        // ask once before a download that may use a lot of data. The
        // dialog's "Download anyway" re-enters here with the flag set.
        if (!confirmedMetered &&
            QualitySelector.shouldWarnMetered(repository.isNetworkMetered(), warnMetered)
        ) {
            meteredConfirmPending = true
            return
        }
        meteredConfirmPending = false
        // A direct file found earlier by content-type probing.
        if (pendingDirectUrl == url && pendingDirectName != null) {
            val name = pendingDirectName
            pendingDirectUrl = null
            pendingDirectName = null
            startDirectDownload(url, name)
            return
        }
        if (DownloadManagerHelper.isSupportedVideoUrl(url)) {
            startDirectDownload(url)
            return
        }
        // yt-dlp path.
        if (videoInfo == null) {
            // Fetch formats first, then the user picks one and taps Download again.
            // A direct file discovered during the fetch starts itself.
            fetchFormats(autoStartDirect = true)
            showMessage("Reading formats… tap Download again after picking one.")
            return
        }
        startYtDlpDownload(url)
    }

    private fun startDirectDownload(url: String, fileName: String? = null) {
        viewModelScope.launch {
            val result = repository.startDownload(url, fileName)
            result.onSuccess {
                val name = fileName ?: DownloadManagerHelper.fileNameFromUrl(url)
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
        // When the format list itself came from the Android client, the
        // download must use that client too — the list already showed
        // only what it can reach, so this is not a downgrade.
        val attempts = if (videoInfoFromFallback) {
            listOf(QualitySelector.ANDROID_FALLBACK_ARGS)
        } else {
            listOf<String?>(null)
        }
        runYtDlpDownload(
            url = url,
            qualityLabel = qualityLabel,
            formatSelector = formatSelector,
            fileName = fileName,
            displayName = displayName,
            attempts = attempts,
            allowFallbackOffer = !videoInfoFromFallback
        )
    }

    /** The user explicitly chose the 360p fallback after full quality was blocked. */
    fun startFallbackDownload() {
        val offer = fallbackOffer ?: return
        fallbackOffer = null
        updateAudioOnly(false)
        runYtDlpDownload(
            url = offer.url,
            qualityLabel = "360p (basic quality)",
            formatSelector = QualitySelector.ANDROID_FALLBACK_SELECTOR,
            fileName = offer.fileName,
            displayName = offer.displayName,
            attempts = listOf(QualitySelector.ANDROID_FALLBACK_ARGS),
            allowFallbackOffer = false
        )
    }

    fun dismissFallbackOffer() {
        fallbackOffer = null
    }

    /**
     * Video-to-text extraction (v2.10, original-language upgrade in
     * v2.14): save the video's subtitles / captions as a clean .txt
     * file in Downloads/VideoX, without downloading the video.
     *
     * Language handling (v2.14): the effective language is the
     * per-extraction override from Home, or the Settings default
     * ("Auto Detect"). Auto Detect / Original Language probe the
     * video's own language and subtitle tracks and prefer the
     * video's language; an explicit language requests that track
     * directly; "All" saves every track. Text is NEVER translated
     * or transliterated unless the optional translation toggle is
     * on, and the translation is always a SEPARATE file — the
     * original-language file is kept untouched.
     *
     * When no subtitles exist and speech transcription is on, the
     * speech is transcribed on the phone with the voice model for
     * the video's language — never silently with the English model
     * (the v2.10-v2.13 bug). Languages with no offline voice model
     * get an honest message instead of a wrong-language transcript.
     * No metered-network warning here: subtitle files are kilobytes.
     */
    fun extractText() {
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
            showMessage("This is a direct media file — it has no subtitles to extract.", isError = true)
            return
        }
        if (!YtDlpEngine.initialized) {
            showMessage("yt-dlp is still starting up. Wait a few seconds and try again.", isError = true)
            return
        }
        extractingText = true
        message = null
        detectedLanguageLabel = null
        // Snapshot the settings on the main thread: the extraction
        // runs on IO and must not read Compose state there.
        val effectiveLanguage = extractionOverride ?: extractionLanguage
        val fallbackOn = transcribeFallback
        val doTranslate = translateEnabled
        val translateTarget = translateTargetLanguage
        viewModelScope.launch(Dispatchers.IO) {
            queueSemaphore.acquire()
            try {
                ensureEngineUpdated()
                // The title decides the filename, exactly as downloads do.
                val (info, _) = fetchInfo(url)
                val baseName = QualitySelector.resolveOutputBaseName(info.title, customFileName)
                val dir = YtDlpEngine.downloadDir()
                val proxy = effectiveProxyUrl
                when (effectiveLanguage) {
                    ExtractionLanguage.ALL ->
                        extractAllLanguages(url, baseName, dir, proxy, fallbackOn)
                    ExtractionLanguage.AUTO, ExtractionLanguage.ORIGINAL ->
                        extractAutoLanguage(url, baseName, dir, proxy, fallbackOn, doTranslate, translateTarget)
                    else ->
                        extractOneLanguage(url, baseName, dir, proxy, effectiveLanguage, fallbackOn, doTranslate, translateTarget)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage(
                        "Text extraction failed: " +
                            QualitySelector.describeYtDlpError(e.message, effectiveProxyUrl != null),
                        isError = true
                    )
                }
            } finally {
                queueSemaphore.release()
                withContext(Dispatchers.Main) {
                    extractingText = false
                    transcriptionProgress = null
                }
            }
        }
    }

    /** Outcome of requesting subtitle tracks from yt-dlp. */
    private sealed interface SubtitleOutcome {
        /** .txt files written this run (normally exactly one). */
        data class Written(val txtNames: List<String>) : SubtitleOutcome
        /** The subtitle files exist but their .txt files already did. */
        data object AlreadyExtracted : SubtitleOutcome
        /** yt-dlp wrote no subtitle files at all. */
        data object NoSubtitles : SubtitleOutcome
    }

    /** "All languages": save every subtitle track, each to its own .txt file. */
    private suspend fun extractAllLanguages(
        url: String,
        baseName: String,
        dir: File,
        proxy: String?,
        fallbackOn: Boolean
    ) {
        YtDlpEngine.extractSubtitles(
            url = url,
            outputBaseName = baseName,
            subtitleLangs = ExtractionLanguage.ALL,
            proxyUrl = proxy,
            processId = "videox_text_${System.currentTimeMillis()}"
        )
        val (converted, alreadyCount) = convertSubtitleFiles(dir, baseName)
        for (name in converted) {
            recordTextFile(url, name, "Text (.txt)")
        }
        withContext(Dispatchers.Main) {
            when {
                converted.isNotEmpty() -> {
                    urlInput = ""
                    videoInfo = null
                    detectedLanguageLabel = "All languages • original subtitles"
                    showMessage("Text extracted: ${converted.joinToString(", ")} — saved to Downloads/VideoX")
                }
                alreadyCount > 0 -> showMessage("Already extracted: the text files are already in Downloads/VideoX.")
                else -> showMessage(
                    "This video has no subtitles or captions in any language — so there is no text to extract. " +
                        if (fallbackOn) {
                            "Speech transcription needs one language: pick Auto Detect or a language " +
                                "in Settings (or the override on Home) to transcribe this video."
                        } else {
                            "Turn on speech transcription in Settings and pick Auto Detect or a language " +
                                "to transcribe the spoken audio instead."
                        },
                    isError = true
                )
            }
        }
    }

    /** Auto Detect / Original Language: probe the video, prefer its own language's track. */
    private suspend fun extractAutoLanguage(
        url: String,
        baseName: String,
        dir: File,
        proxy: String?,
        fallbackOn: Boolean,
        doTranslate: Boolean,
        translateTarget: String
    ) {
        // A failed probe throws: the outer catch shows the mapped error.
        val json = YtDlpEngine.dumpVideoJson(url, proxy, "videox_probe_${System.currentTimeMillis()}")
        val probe = YtDlpJson.parse(json)
        val chosenRaw = YtDlpJson.chooseSubtitleLanguage(probe, ExtractionLanguage.AUTO)
        if (chosenRaw == null) {
            transcribeOrExplain(url, baseName, dir, proxy, probe.videoLanguage, fallbackOn, doTranslate, translateTarget)
            return
        }
        // Track keys can be regional ("pt-BR", "en-US"). Subtitle
        // requests only accept plain 2-3 letter codes (the sanitiser
        // drops anything else and would fall back to English), so the
        // request, the label and the translation source all use the
        // base language — "pt-BR" is requested and reported as
        // Portuguese, never silently as English.
        val chosen = chosenRaw.substringBefore('-').substringBefore('_').lowercase()
        when (val outcome = requestAndConvert(url, baseName, dir, proxy, chosen)) {
            is SubtitleOutcome.Written -> finishExtraction(
                url = url,
                dir = dir,
                txtNames = outcome.txtNames,
                sourceCode = chosen,
                label = "${ExtractionLanguage.displayName(chosen)} • original subtitles",
                formatLabel = "Text (.txt)",
                actionWord = "Text extracted",
                note = null,
                doTranslate = doTranslate,
                translateTarget = translateTarget
            )
            SubtitleOutcome.AlreadyExtracted -> withContext(Dispatchers.Main) {
                showMessage("Already extracted: the text file is already in Downloads/VideoX.")
            }
            // The probe listed a track but yt-dlp wrote nothing
            // usable: fall through to transcription with the video's
            // own language, exactly as if there were no tracks.
            SubtitleOutcome.NoSubtitles ->
                transcribeOrExplain(url, baseName, dir, proxy, probe.videoLanguage, fallbackOn, doTranslate, translateTarget)
        }
    }

    /** One explicit language: request that track directly, no probe needed. */
    private suspend fun extractOneLanguage(
        url: String,
        baseName: String,
        dir: File,
        proxy: String?,
        languageCode: String,
        fallbackOn: Boolean,
        doTranslate: Boolean,
        translateTarget: String
    ) {
        val subtitleCode = ExtractionLanguage.subtitleCodeFor(languageCode)
        when (val outcome = requestAndConvert(url, baseName, dir, proxy, subtitleCode)) {
            is SubtitleOutcome.Written -> finishExtraction(
                url = url,
                dir = dir,
                txtNames = outcome.txtNames,
                sourceCode = languageCode,
                label = "${ExtractionLanguage.displayName(languageCode)} • original subtitles",
                formatLabel = "Text (.txt)",
                actionWord = "Text extracted",
                note = null,
                doTranslate = doTranslate,
                translateTarget = translateTarget
            )
            SubtitleOutcome.AlreadyExtracted -> withContext(Dispatchers.Main) {
                showMessage("Already extracted: the text file is already in Downloads/VideoX.")
            }
            SubtitleOutcome.NoSubtitles ->
                transcribeOrExplain(url, baseName, dir, proxy, languageCode, fallbackOn, doTranslate, translateTarget)
        }
    }

    /** Request one subtitle track and convert whatever yt-dlp wrote. */
    private suspend fun requestAndConvert(
        url: String,
        baseName: String,
        dir: File,
        proxy: String?,
        subtitleCode: String
    ): SubtitleOutcome {
        YtDlpEngine.extractSubtitles(
            url = url,
            outputBaseName = baseName,
            subtitleLangs = subtitleCode,
            proxyUrl = proxy,
            processId = "videox_text_${System.currentTimeMillis()}"
        )
        val (converted, alreadyCount) = convertSubtitleFiles(dir, baseName)
        return when {
            converted.isNotEmpty() -> SubtitleOutcome.Written(converted)
            alreadyCount > 0 -> SubtitleOutcome.AlreadyExtracted
            else -> SubtitleOutcome.NoSubtitles
        }
    }

    /**
     * No subtitles: transcribe the speech on the phone with the voice
     * model for [languageCode], or explain honestly when that is not
     * possible. [languageCode] is the video's own language (Auto
     * Detect) or the requested language (explicit choice), or null
     * when the language is genuinely unknown — only then is the
     * English model used, and the result is labelled as such.
     */
    private suspend fun transcribeOrExplain(
        url: String,
        baseName: String,
        dir: File,
        proxy: String?,
        languageCode: String?,
        fallbackOn: Boolean,
        doTranslate: Boolean,
        translateTarget: String
    ) {
        if (!fallbackOn) {
            val langText = languageCode?.let { ExtractionLanguage.displayName(it) }
            withContext(Dispatchers.Main) {
                showMessage(
                    if (langText != null) {
                        "This video has no subtitles or captions in $langText — so there is no text to extract. " +
                            "Turn on speech transcription in Settings to transcribe the spoken audio instead."
                    } else {
                        "This video has no subtitles or captions — so there is no text to extract. " +
                            "Turn on speech transcription in Settings to transcribe the spoken audio instead."
                    },
                    isError = true
                )
            }
            return
        }
        val resolved = languageCode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val modelCode = resolved ?: "en"
        if (SpeechTranscriber.modelNameFor(modelCode) == null) {
            val name = ExtractionLanguage.displayName(modelCode)
            withContext(Dispatchers.Main) {
                showMessage(
                    "This video has no subtitles, and there is no offline voice model for $name yet, " +
                        "so its speech cannot be transcribed on this phone. Offline transcription works for " +
                        "${ExtractionLanguage.voskSupportedNames()}.",
                    isError = true
                )
            }
            return
        }
        val text = transcribeSpeech(url, proxy, modelCode)
        if (text.isBlank()) {
            withContext(Dispatchers.Main) {
                showMessage(
                    "No speech could be recognised in this video — " +
                        "it may have no spoken audio, or only music.",
                    isError = true
                )
            }
            return
        }
        val txtName = "$baseName.txt"
        File(dir, txtName).writeText(text)
        val label = if (resolved != null) {
            "${ExtractionLanguage.displayName(resolved)} • transcribed speech"
        } else {
            "Unknown language • transcribed speech (English voice model)"
        }
        finishExtraction(
            url = url,
            dir = dir,
            txtNames = listOf(txtName),
            sourceCode = modelCode,
            label = label,
            formatLabel = "Transcribed text",
            actionWord = "Transcribed",
            note = "Speech recognition is automatic, so a few words may be wrong.",
            doTranslate = doTranslate,
            translateTarget = translateTarget
        )
    }

    /** Transcribe the video's speech on the phone. Returns recognised text (may be blank). */
    private suspend fun transcribeSpeech(url: String, proxy: String?, languageCode: String): String {
        val reportProgress: (String) -> Unit = { text ->
            viewModelScope.launch { transcriptionProgress = text }
        }
        reportProgress("No subtitles found — preparing speech transcription…")
        val modelDir = SpeechTranscriber.ensureModel(appContext, languageCode) { percent ->
            reportProgress("Downloading voice model (once)… $percent%")
        }
        val wavFile = File(appContext.cacheDir, "videox_stt_${System.currentTimeMillis()}.wav")
        try {
            reportProgress("Extracting audio for transcription…")
            YtDlpEngine.extractAudioWav(
                url = url,
                outputFile = wavFile,
                proxyUrl = proxy,
                processId = "videox_stt_${System.currentTimeMillis()}"
            )
            if (!wavFile.exists() || wavFile.length() == 0L) {
                throw IllegalStateException("The video's audio could not be extracted.")
            }
            return SpeechTranscriber.transcribe(wavFile, modelDir) { percent ->
                reportProgress("Transcribing speech… $percent%")
            }
        } finally {
            try {
                wavFile.delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Convert subtitle files for [baseName] in [dir] to .txt. Returns
     * the names written this run plus how many already existed.
     * The .txt is the deliverable — the intermediate subtitle file is
     * removed so the folder is not cluttered with two files per video.
     */
    private suspend fun convertSubtitleFiles(dir: File, baseName: String): Pair<List<String>, Int> {
        val subFiles = dir.listFiles()
            ?.filter {
                it.name.startsWith(baseName) &&
                    (it.name.endsWith(".srt") || it.name.endsWith(".vtt")) &&
                    !it.name.endsWith(".part")
            }
            ?: emptyList()
        val converted = mutableListOf<String>()
        var alreadyCount = 0
        for (sub in subFiles) {
            val txtName = sub.nameWithoutExtension + ".txt"
            val txtFile = File(dir, txtName)
            if (txtFile.exists()) {
                alreadyCount++
                continue
            }
            val plain = try {
                SubtitleText.toPlainText(sub.readText())
            } catch (_: Exception) {
                ""
            }
            if (plain.isBlank()) continue
            txtFile.writeText(plain)
            converted += txtName
            try {
                sub.delete()
            } catch (_: Exception) {
                // Keep going — a leftover .srt is harmless.
            }
        }
        return converted to alreadyCount
    }

    /** Record a finished extraction, optionally translate it, and report the result. */
    private suspend fun finishExtraction(
        url: String,
        dir: File,
        txtNames: List<String>,
        sourceCode: String,
        label: String,
        formatLabel: String,
        actionWord: String,
        note: String?,
        doTranslate: Boolean,
        translateTarget: String
    ) {
        for (name in txtNames) {
            recordTextFile(url, name, formatLabel)
        }
        var translatedName: String? = null
        var translationError: String? = null
        if (doTranslate && txtNames.isNotEmpty()) {
            try {
                val result = maybeTranslate(dir, txtNames.first(), sourceCode, translateTarget)
                if (result != null) {
                    translatedName = result.first
                    if (result.second) {
                        recordTextFile(url, result.first, "Translated text")
                    }
                }
            } catch (e: Exception) {
                // The original extraction succeeded: keep it, and say
                // the translation failed instead of failing everything.
                translationError = e.message ?: "translation failed"
            }
        }
        withContext(Dispatchers.Main) {
            urlInput = ""
            videoInfo = null
            detectedLanguageLabel = label
            val files = txtNames + listOfNotNull(translatedName)
            var text = "$actionWord: ${files.joinToString(", ")} — saved to Downloads/VideoX"
            if (note != null) text += " $note"
            if (translationError != null) {
                text += " Translation failed: $translationError"
            }
            showMessage(text)
        }
    }

    /**
     * Translate one extracted file when the languages differ and both
     * are supported. Returns the translated file name plus whether it
     * was newly written, or null when translation was skipped (same
     * language, unsupported language, or empty text). Throws when
     * translation itself fails. The original file is never touched.
     */
    private suspend fun maybeTranslate(
        dir: File,
        txtName: String,
        sourceCode: String,
        targetCode: String
    ): Pair<String, Boolean>? {
        val source = Translator.mlKitCode(sourceCode) ?: return null
        val target = Translator.mlKitCode(targetCode) ?: return null
        if (source == target) return null
        val translatedName = txtName.removeSuffix(".txt") + "_translated_$targetCode.txt"
        val translatedFile = File(dir, translatedName)
        if (translatedFile.exists()) return translatedName to false
        val text = File(dir, txtName).readText()
        if (text.isBlank()) return null
        viewModelScope.launch { transcriptionProgress = "Translating text…" }
        val translated = Translator.translateText(sourceCode, targetCode, text) { percent ->
            viewModelScope.launch { transcriptionProgress = "Translating text… $percent%" }
        }
        if (translated.isBlank()) return null
        translatedFile.writeText(translated)
        return translatedName to true
    }

    /** Record one extracted/translated .txt file in the Downloads history. */
    private suspend fun recordTextFile(url: String, fileName: String, formatLabel: String) {
        repository.insertYtDownload(
            DownloadItem(
                url = url,
                fileName = fileName,
                status = "COMPLETED",
                progress = 100,
                mode = "TEXT",
                formatLabel = formatLabel,
                engine = "YTDLP"
            )
        )
    }

    private fun runYtDlpDownload(
        url: String,
        qualityLabel: String,
        formatSelector: String,
        fileName: String,
        displayName: String,
        attempts: List<String?>,
        allowFallbackOffer: Boolean
    ) {
        // Snapshot the settings on the calling (main) thread: the
        // download runs on IO and must not read Compose state there.
        val proxy = effectiveProxyUrl
        val options = QualitySelector.extraDownloadOptions(
            subtitlesEnabled, subtitleLangs, embedChapters, audioOnly
        )
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
                var attemptIndex = 0
                var engineRetried = false
                var lastErr: String? = null
                var done = false
                while (attemptIndex < attempts.size && !done) {
                    val args = attempts[attemptIndex]
                    // Snapshot the folder before the attempt: a file only
                    // counts as "downloaded" if it appeared or changed
                    // during THIS attempt (an older same-named file from
                    // a previous download must not fake a success).
                    val dir = YtDlpEngine.downloadDir()
                    val beforeNames = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
                    val attemptStartedAt = System.currentTimeMillis()
                    try {
                        val response = YtDlpEngine.download(
                            url = url,
                            formatSelector = formatSelector,
                            audioOnly = audioOnly,
                            audioFormat = audioFormat,
                            outputBaseName = fileName,
                            extractorArgs = args,
                            processId = processId,
                            proxyUrl = proxy,
                            extraOptions = options
                        ) { progress, eta ->
                            viewModelScope.launch {
                                if (rowId > 0) repository.updateYtProgress(rowId, progress.toInt(), 0L, 0L)
                                withContext(Dispatchers.Main) {
                                    activeEtaText = if (eta > 0) "ETA ${eta}s" else null
                                }
                            }
                        }
                        // yt-dlp finished without throwing — check a REAL
                        // file actually appeared (.part files don't count).
                        // EXCEPTION: "--no-overwrites" makes yt-dlp skip a
                        // file that is already complete and report "has
                        // already been downloaded". That file is real and
                        // finished, so it is a SUCCESS, not a failure —
                        // v2.5 wrongly showed "Download failed" for it.
                        val alreadyThere = QualitySelector.isAlreadyDownloaded(response.out) ||
                            QualitySelector.isAlreadyDownloaded(response.err)
                        val foundFile = if (alreadyThere) {
                            dir.listFiles()
                                ?.filter { it.name.startsWith(fileName) && !it.name.endsWith(".part") }
                                ?.firstOrNull()
                        } else {
                            dir.listFiles()
                                ?.filter { it.name.startsWith(fileName) && !it.name.endsWith(".part") }
                                ?.firstOrNull { it.name !in beforeNames || it.lastModified() >= attemptStartedAt }
                        }
                        if (foundFile != null) {
                            // The real extension may differ from the
                            // assumed one (e.g. a direct WebM stays .webm,
                            // not .mp4) — record the actual filename so
                            // the Downloads tab can open the right file.
                            val actualName = foundFile.name
                            if (rowId > 0) {
                                repository.markYtStatus(rowId, "COMPLETED", null)
                                if (actualName != displayName) {
                                    repository.updateYtFileName(rowId, actualName)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                showMessage(
                                    if (alreadyThere) "Already downloaded: $actualName"
                                    else "Download finished: $actualName"
                                )
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
                    // Every attempt failed. Never silently downgrade: if
                    // the 360p fallback WOULD work, offer it as an
                    // explicit choice instead of downloading it.
                    val blocked = lastErr != null && (
                        lastErr.contains("403") ||
                            lastErr.contains("not available", ignoreCase = true) ||
                            lastErr.contains("bot", ignoreCase = true)
                        )
                    val canOffer = allowFallbackOffer && !audioOnly &&
                        blocked && QualitySelector.isYouTubeUrl(url)
                    if (canOffer && YtDlpEngine.probeFallback(url, proxy)) {
                        if (rowId > 0) repository.markYtStatus(rowId, "FAILED", lastErr?.take(200))
                        withContext(Dispatchers.Main) {
                            fallbackOffer = FallbackOffer(url, fileName, displayName)
                            showMessage(
                                "The quality you picked couldn't be downloaded — YouTube is blocking it right now. " +
                                    "360p is available instead. Tap “Download 360p” below if you want it.",
                                isError = true
                            )
                        }
                    } else {
                        // Accurate error, never a generic restriction.
                        val err = QualitySelector.describeYtDlpError(lastErr, proxy != null)
                        if (rowId > 0) repository.markYtStatus(rowId, "FAILED", err.take(200))
                        withContext(Dispatchers.Main) {
                            showMessage("Download failed: ${err.take(1000)}", isError = true)
                        }
                    }
                }
            } catch (e: Exception) {
                // Only reached for errors outside the attempt loop
                // (queue insert, engine check): report honestly.
                val errText = QualitySelector.describeYtDlpError(e.message, proxy != null)
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

    // ---- Settings backup and restore (v2.9) ----

    fun backupSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.backupSettings()
            withContext(Dispatchers.Main) {
                result.onSuccess { file ->
                    showMessage("Settings backed up to Downloads/VideoX/${file.name}")
                }.onFailure { e ->
                    showMessage("Backup failed: ${e.message}", isError = true)
                }
            }
        }
    }

    fun restoreSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.restoreSettings()
            withContext(Dispatchers.Main) {
                result.onSuccess { applied ->
                    refreshSettingsFromRepository()
                    showMessage("Restored $applied settings from the backup file.")
                }.onFailure { e ->
                    showMessage(e.message ?: "Restore failed.", isError = true)
                }
            }
        }
    }

    /** Re-read every setting from storage after a restore. */
    private fun refreshSettingsFromRepository() {
        wifiOnly = repository.wifiOnly
        darkTheme = repository.darkTheme
        updateMaxConcurrent(repository.maxConcurrentDownloads)
        warnMetered = repository.warnMetered
        proxyUrl = repository.proxyUrl
        subtitlesEnabled = repository.subtitlesEnabled
        subtitleLangs = repository.subtitleLangs
        embedChapters = repository.embedChapters
        transcribeFallback = repository.transcribeFallback
        extractionLanguage = repository.extractionLanguage
        translateEnabled = repository.translateEnabled
        translateTargetLanguage = repository.translateTargetLanguage
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
