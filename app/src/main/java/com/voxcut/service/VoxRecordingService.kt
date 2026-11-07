package com.voxcut.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.voxcut.engine.VoxAudioEngine
import com.voxcut.engine.VoxSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** UI 侧四个可调参数；同时作为服务启动前的初始参数快照。 */
data class VoxParams(
    val thresholdDb: Double = 45.0,
    val preRollMs: Int = 500,
    val hangoverMs: Int = 1500,
    val gapMs: Int = 200
)

internal val DEFAULT_PARAMS = VoxParams()

/** ViewModel 对服务下命令的窄接口，服务 onCreate 时注册到 [VoxStateBus]。 */
interface VoxController {
    fun updateParams(params: VoxParams)
    fun requestStop()
    val isCapturing: Boolean
}

/**
 * 同进程单例状态总线。Service 与 Activity 处于同一默认进程，
 * 用 StateFlow 直接桥接，避免 AIDL / Messenger 的序列化开销与生命周期错配。
 *
 * MutableStateFlow.value 赋值本身线程安全，音频 IO 协程可以低频直写；
 * 高频的原始数值仍只留在引擎的 @Volatile 字段里。
 */
object VoxStateBus {
    private val _state = MutableStateFlow(idleSnapshot())
    val state: StateFlow<VoxSnapshot> = _state.asStateFlow()

    private val _serviceAlive = MutableStateFlow(false)
    val serviceAlive: StateFlow<Boolean> = _serviceAlive.asStateFlow()

    /** 每次保存 / 删除录音后自增，驱动 UI 重扫目录。 */
    private val _filesVersion = MutableStateFlow(0L)
    val filesVersion: StateFlow<Long> = _filesVersion.asStateFlow()

    private val _fatalError = MutableStateFlow<String?>(null)
    val fatalError: StateFlow<String?> = _fatalError.asStateFlow()

    @Volatile
    var pendingParams: VoxParams = DEFAULT_PARAMS

    @Volatile
    var controller: VoxController? = null

    fun pushState(snapshot: VoxSnapshot) {
        _state.value = snapshot
    }

    fun resetState() {
        _state.value = idleSnapshot()
    }

    fun setAlive(alive: Boolean) {
        _serviceAlive.value = alive
    }

    fun bumpFilesVersion() {
        _filesVersion.value = _filesVersion.value + 1L
    }

    fun postError(message: String?) {
        _fatalError.value = message
    }

    private fun idleSnapshot(): VoxSnapshot = VoxSnapshot(
        smoothedDb = 0.0,
        stateCode = VoxAudioEngine.STATE_IDLE,
        monitorDurationMs = 0L,
        effectiveDurationMs = 0L,
        triggerCount = 0,
        errorCode = VoxAudioEngine.ERR_NONE,
        isRunning = false,
        thresholdDb = DEFAULT_PARAMS.thresholdDb,
        preRollMs = DEFAULT_PARAMS.preRollMs,
        hangoverMs = DEFAULT_PARAMS.hangoverMs,
        gapMs = DEFAULT_PARAMS.gapMs,
        totalAudioBytes = 0L,
        outputPath = null
    )
}

/**
 * 声控录音前台服务。
 *
 * 生命周期：onStartCommand(ACTION_START) -> startForeground -> WakeLock ->
 * 两条协程（IO 上跑 engine.run()，Default 上低频轮询 snapshot 并刷新通知）；
 * ACTION_STOP / controller.requestStop() -> engine.stop() -> join -> shutdown ->
 * 回写头、刷新文件版本、释放锁、移除前台、stopSelf。
 *
 * 引擎因连续读错误自行退出时，轮询协程检测到 isRunning=false 后走同一套收尾，
 * 保证文件头一定被回写，不留坏文件。
 */
class VoxRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.voxcut.action.START"
        const val ACTION_STOP = "com.voxcut.action.STOP"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voxcut_recording"
        private const val CHANNEL_NAME = "VoxCut 声控监听"
        private const val WAKE_LOCK_TAG = "VoxCut::RecordingWakeLock"
        private const val SAMPLE_RATE = 16000
        private const val POLL_INTERVAL_MS = 200L
        private const val NOTIFY_EVERY_POLLS = 5
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var engine: VoxAudioEngine? = null
    @Volatile
    private var finishing = false
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    private var audioJob: Job? = null
    private var pollJob: Job? = null

    private val bridgeController = object : VoxController {
        override fun updateParams(params: VoxParams) {
            VoxStateBus.pendingParams = params
            engine?.updateParams(
                params.thresholdDb,
                params.preRollMs,
                params.hangoverMs,
                params.gapMs
            )
        }

        override fun requestStop() {
            this@VoxRecordingService.requestStop()
        }

        override val isCapturing: Boolean
            get() = engine != null && !finishing
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        VoxStateBus.controller = bridgeController
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> requestStop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun recordingDirectory(): File =
        getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir

    private fun startRecording() {
        if (engine != null || finishing) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            failAndStop("缺少 RECORD_AUDIO 权限，无法启动声控监听")
            return
        }

        val e = VoxAudioEngine(recordingDirectory(), SAMPLE_RATE)
        try {
            e.start()
        } catch (ex: Exception) {
            e.shutdown()
            failAndStop("音频采集启动失败：${ex.message}")
            return
        }

        engine = e
        finishing = false
        val p = VoxStateBus.pendingParams
        e.updateParams(p.thresholdDb, p.preRollMs, p.hangoverMs, p.gapMs)

        acquireWakeLock()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(0L, 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (ex: Exception) {
            e.stop()
            e.shutdown()
            engine = null
            releaseWakeLock()
            failAndStop("前台服务启动失败：${ex.message}")
            return
        }

        VoxStateBus.postError(null)
        VoxStateBus.setAlive(true)
        VoxStateBus.pushState(e.snapshot())

        audioJob = serviceScope.launch(Dispatchers.IO) {
            e.run()
        }
        pollJob = serviceScope.launch(Dispatchers.Default) {
            pollLoop(e)
        }
    }

    /** 低频轮询：推 StateFlow、每秒刷新通知、监测引擎异常自停。 */
    private suspend fun pollLoop(e: VoxAudioEngine) {
        var tick = 0
        while (currentCoroutineContext().isActive) {
            val snapshot = e.snapshot()
            VoxStateBus.pushState(snapshot)
            tick++
            if (tick % NOTIFY_EVERY_POLLS == 0) {
                refreshNotification(snapshot.effectiveDurationMs, snapshot.monitorDurationMs)
            }
            val job = audioJob
            if (!snapshot.isRunning && job != null && job.isCompleted) {
                if (snapshot.errorCode != VoxAudioEngine.ERR_NONE) {
                    VoxStateBus.postError("采集异常终止（code=${snapshot.errorCode}），已保存当前片段")
                }
                finishRecording(e)
                return
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun failAndStop(message: String) {
        VoxStateBus.postError(message)
        VoxStateBus.setAlive(false)
        stopSelf()
    }

    /** 用户主动停止。实际收尾放到协程里，避免阻塞主线程与前台服务回调。 */
    private fun requestStop() {
        val e = engine
        if (e == null) {
            VoxStateBus.setAlive(false)
            stopSelf()
            return
        }
        e.stop()
        finishRecording(e)
    }

    /**
     * 幂等收尾：等待音频循环退出 -> shutdown（回写 WAV 头）-> 刷新目录 ->
     * 释放 WakeLock -> 移除前台 -> stopSelf。
     */
    @Synchronized
    private fun finishRecording(e: VoxAudioEngine) {
        if (finishing) return
        finishing = true
        pollJob?.cancel()
        e.stop()

        serviceScope.launch(Dispatchers.IO) {
            val job = audioJob
            if (job != null) job.join()
            e.shutdown()
            val finalSnapshot = e.snapshot()
            VoxStateBus.pushState(finalSnapshot)
            VoxStateBus.bumpFilesVersion()
            engine = null
            audioJob = null
            pollJob = null
            // 服务实例可能被系统复用；复位闸门，允许下一次 ACTION_START 重新采集。
            finishing = false

            withContext(Dispatchers.Main) {
                releaseWakeLock()
                try {
                    ServiceCompat.stopForeground(
                        this@VoxRecordingService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                } catch (_: Exception) {
                }
                VoxStateBus.setAlive(false)
                stopSelf()
            }
        }
    }

    // ---- 通知 ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "声控监听常驻通知"
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(effectiveMs: Long, monitorMs: Long): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(this, "com.voxcut.MainActivity").apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, VoxRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VoxCut 正在监听")
            .setContentText("正在监听 | 已捕获有效音频 ${formatMinSec(effectiveMs)}")
            .setSubText("已监听 ${formatClock(monitorMs)}")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "停止并保存", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun refreshNotification(effectiveMs: Long, monitorMs: Long) {
        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(effectiveMs, monitorMs))
        } catch (_: Exception) {
            // 缺 POST_NOTIFICATIONS 时系统可能拒绝，前台服务本身不受影响。
        }
    }

    // ---- WakeLock ----

    private fun acquireWakeLock() {
        releaseWakeLock()
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        audioJob?.cancel()
        pollJob?.cancel()
        val e = engine
        if (e != null) {
            e.stop()
            e.shutdown()
            VoxStateBus.pushState(e.snapshot())
            VoxStateBus.bumpFilesVersion()
        }
        engine = null
        releaseWakeLock()
        VoxStateBus.controller = null
        VoxStateBus.setAlive(false)
        serviceScope.cancel()
        super.onDestroy()
    }
}

/** mm分ss秒，用于通知文案。 */
internal fun formatMinSec(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d分%02d秒", total / 60L, total % 60L)
}

/** HH:mm:ss，用于统计卡片与通知副文本。 */
internal fun formatClock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        total / 3600L,
        (total % 3600L) / 60L,
        total % 60L
    )
}
