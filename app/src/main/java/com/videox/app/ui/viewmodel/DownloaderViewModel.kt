package com.videox.app.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videox.app.VideoXApp
import com.videox.app.data.DownloadItem
import com.videox.app.download.DownloadManagerHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as VideoXApp).repository

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

    init {
        // Poll DownloadManager while the app is open so progress bars stay live.
        // DownloadManager itself continues in the background when the app is closed,
        // and will resume automatically after an interruption when possible.
        viewModelScope.launch {
            while (true) {
                try {
                    repository.refreshStatuses()
                } catch (e: Exception) {
                    // Polling must never crash the UI; the next tick retries.
                }
                delay(1_500)
            }
        }
    }

    fun onUrlChange(value: String) {
        urlInput = value
        message = null
    }

    fun onPaste(text: String) {
        urlInput = text.trim()
        message = null
    }

    fun setWifiOnly(enabled: Boolean) {
        wifiOnly = enabled
        repository.wifiOnly = enabled
    }

    fun showMessage(text: String, isError: Boolean = false) {
        message = text
        isMessageError = isError
    }

    fun clearMessage() {
        message = null
    }

    fun startDownload() {
        val error = DownloadManagerHelper.validateUrl(urlInput)
        if (error != null) {
            showMessage(error, isError = true)
            return
        }
        viewModelScope.launch {
            val result = repository.startDownload(urlInput)
            result.onSuccess {
                val name = DownloadManagerHelper.fileNameFromUrl(urlInput)
                showMessage("Download started: $name — saved to Downloads/VideoX")
                urlInput = ""
            }.onFailure { e ->
                showMessage(e.message ?: "Download could not be started. Try again.", isError = true)
            }
        }
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
}
