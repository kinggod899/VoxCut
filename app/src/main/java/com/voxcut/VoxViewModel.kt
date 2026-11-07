package com.voxcut

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voxcut.engine.VoxAudioEngine
import com.voxcut.service.DEFAULT_PARAMS
import com.voxcut.service.VoxParams
import com.voxcut.service.VoxRecordingService
import com.voxcut.service.VoxRecordingService.Companion.ACTION_START
import com.voxcut.service.VoxRecordingService.Companion.ACTION_STOP
import com.voxcut.service.VoxStateBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** 历史列表项。时长由 WAV data 块字节数除以 16000Hz * 2Bytes 推算。 */
data class RecordingFile(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long
)

class VoxViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val BYTES_PER_SECOND = VoxAudioEngine.DEFAULT_SAMPLE_RATE * 2
        private const val WAV_HEADER_BYTES = 44L
    }

    private val appContext: Context get() = getApplication<Application>().applicationContext

    private val _params = MutableStateFlow(DEFAULT_PARAMS)
    val params: StateFlow<VoxParams> = _params.asStateFlow()

    private val _files = MutableStateFlow<List<RecordingFile>>(emptyList())
    val files: StateFlow<List<RecordingFile>> = _files.asStateFlow()

    private val _playingPath = MutableStateFlow<String?>(null)
    val playingPath: StateFlow<String?> = _playingPath.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    val snapshot = VoxStateBus.state
    val serviceAlive = VoxStateBus.serviceAlive
    val fatalError = VoxStateBus.fatalError

    private var player: MediaPlayer? = null

    init {
        VoxStateBus.pendingParams = _params.value
        refreshFiles()
        viewModelScope.launch {
            VoxStateBus.filesVersion.collectLatest { refreshFiles() }
        }
    }

    // ---- 服务控制 ----

    fun startService() {
        VoxStateBus.pendingParams = _params.value
        VoxStateBus.postError(null)
        val intent = Intent(appContext, VoxRecordingService::class.java)
            .setAction(ACTION_START)
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stopService() {
        val intent = Intent(appContext, VoxRecordingService::class.java)
            .setAction(ACTION_STOP)
        appContext.startService(intent)
    }

    fun postError(message: String) = VoxStateBus.postError(message)
    fun clearError() = VoxStateBus.postError(null)

    // ---- 参数热更新 ----

    private fun applyParams(next: VoxParams) {
        _params.value = next
        VoxStateBus.pendingParams = next
        VoxStateBus.controller?.updateParams(next)
    }

    fun setThresholdDb(value: Double) =
        applyParams(_params.value.copy(thresholdDb = value))

    fun setPreRollMs(valueMs: Int) =
        applyParams(_params.value.copy(preRollMs = valueMs))

    fun setHangoverMs(valueMs: Int) =
        applyParams(_params.value.copy(hangoverMs = valueMs))

    fun setGapMs(valueMs: Int) =
        applyParams(_params.value.copy(gapMs = valueMs))

    /** Slider 给的是秒，统一换算成引擎使用的毫秒整数。 */
    fun setPreRollSeconds(seconds: Float) = setPreRollMs((seconds * 1000f).roundToInt())
    fun setHangoverSeconds(seconds: Float) = setHangoverMs((seconds * 1000f).roundToInt())
    fun setGapSeconds(seconds: Float) = setGapMs((seconds * 1000f).roundToInt())

    // ---- 文件列表 ----

    fun refreshFiles() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                val dir = appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
                val raw = dir?.listFiles { f -> f.isFile && f.name.endsWith(".wav", true) }
                    ?: emptyArray()
                raw.sortedByDescending { it.lastModified() }.map { f ->
                    val dataBytes = (f.length() - WAV_HEADER_BYTES).coerceAtLeast(0L)
                    RecordingFile(
                        file = f,
                        name = f.name,
                        sizeBytes = f.length(),
                        durationMs = dataBytes * 1000L / BYTES_PER_SECOND
                    )
                }
            }
            _files.value = list
        }
    }

    fun deleteRecording(item: RecordingFile) {
        if (_playingPath.value == item.file.absolutePath) releasePlayer()
        item.file.delete()
        refreshFiles()
    }

    // ---- 内置试听 ----

    fun togglePlayback(item: RecordingFile) {
        val path = item.file.absolutePath
        val current = player
        if (current != null && _playingPath.value == path) {
            if (current.isPlaying) {
                current.pause()
                _isPlaying.value = false
            } else {
                current.start()
                _isPlaying.value = true
            }
            return
        }
        releasePlayer()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(path)
            mp.setOnCompletionListener { releasePlayer() }
            mp.setOnErrorListener { _, _, _ ->
                releasePlayer()
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            _playingPath.value = path
            _isPlaying.value = true
        } catch (ex: Exception) {
            mp.release()
            releasePlayer()
            VoxStateBus.postError("试听失败：${ex.message}")
        }
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        _playingPath.value = null
        _isPlaying.value = false
    }

    /** 系统 Share Sheet：FileProvider 授权读取应用专属目录下的 .wav。 */
    fun buildShareIntent(item: RecordingFile): Intent {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            item.file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(appContext.contentResolver, "VoxCut WAV", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "分享录音 ${item.name}")
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }
}

/** 人类可读的文件大小。 */
internal fun formatFileSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.2f MB", kb / 1024.0)
}
