package com.voxcut.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * VoxCut 核心音频引擎：分贝换算、环形预录、三态防抖状态机、单文件流式追加。
 *
 * 设计铁律：
 * 1. 全部缓冲区在 [start] 中一次性预分配，[run] 的 while 循环内零对象分配（无 new 数组、
 *    无 data class、无字符串拼接、无 lambda 捕获）。
 * 2. 实时数值只写入 @Volatile 原始字段；供 UI 的 StateFlow 由服务层低频轮询 [snapshot] 构造，
 *    音频线程绝不触碰对象分配。
 * 3. 有效 PCM 只追加到同一个 .wav 文件尾，停止时回写 44 字节头。
 *
 * 生命周期契约：服务层按 start() -> launch(IO){ run() } -> stop() -> job.join() -> shutdown()
 * 顺序调用。[shutdown] 幂等，负责在 run() 从未执行或协程被取消时兜底回写文件头并释放硬件。
 *
 * 本类不依赖协程，由 [com.voxcut.service.VoxRecordingService] 在 Dispatchers.IO 上驱动 [run]。
 */
class VoxAudioEngine(
    private val outputDirectory: File,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE
) {
    companion object {
        const val STATE_IDLE = 0
        const val STATE_ACTIVE = 1
        const val STATE_HANGOVER = 2

        const val DEFAULT_SAMPLE_RATE = 16000

        const val ERR_NONE = 0
        const val ERR_AUDIO_RECORD_INIT = 1
        const val ERR_READ_FAILED = 2

        private const val FRAME_MS = 20
        private const val TRIGGER_FRAMES = 3
        private const val HYSTERESIS_DB = 3.0
        private const val DBFS_OFFSET = 95.0
        private const val DISPLAY_DB_MIN = 0.0
        private const val DISPLAY_DB_MAX = 100.0
        private const val SMOOTH_WEIGHT_CURRENT = 0.3
        private const val SMOOTH_WEIGHT_PREVIOUS = 0.7
        private const val FULL_SCALE = 32767.0
        private const val MAX_CONSECUTIVE_READ_ERRORS = 10

        private const val MAX_PRE_ROLL_MS = 2000
        private const val MAX_GAP_MS = 1000

        private const val THRESHOLD_MIN_DB = 20.0
        private const val THRESHOLD_MAX_DB = 90.0
        private const val PRE_ROLL_MIN_MS = 100
        private const val PRE_ROLL_MAX_MS = 2000
        private const val HANGOVER_MIN_MS = 500
        private const val HANGOVER_MAX_MS = 5000
        private const val GAP_MIN_MS = 0
        private const val GAP_MAX_MS = 1000
    }

    // ---- 可热更新参数：UI 线程写，音频线程读 ----
    @Volatile private var thresholdDb = 45.0
    @Volatile private var preRollMs = 500
    @Volatile private var hangoverMs = 1500
    @Volatile private var gapMs = 200

    // ---- 实时状态：热循环只写原始字段 ----
    @Volatile private var running = false
    @Volatile private var released = false
    @Volatile private var paramsApplied = false
    @Volatile private var smoothedDbField = 0.0
    @Volatile private var stateField = STATE_IDLE
    @Volatile private var monitorMsField = 0L
    @Volatile private var effectiveMsField = 0L
    @Volatile private var triggerCountField = 0
    @Volatile private var errorCodeField = ERR_NONE
    @Volatile private var outputPathField: String? = null
    @Volatile private var lastTotalAudioBytes = 0L

    val smoothedDb: Double get() = smoothedDbField
    val stateCode: Int get() = stateField
    val monitorDurationMs: Long get() = monitorMsField
    val effectiveDurationMs: Long get() = effectiveMsField
    val triggerCount: Int get() = triggerCountField
    val errorCode: Int get() = errorCodeField
    val isRunning: Boolean get() = running
    val currentThresholdDb: Double get() = thresholdDb
    val currentPreRollMs: Int get() = preRollMs
    val currentHangoverMs: Int get() = hangoverMs
    val currentGapMs: Int get() = gapMs
    val outputFileOrNull: File? get() = outputFile

    // ---- 预分配资源 ----
    private var audioRecord: AudioRecord? = null
    private var wavWriter: WavFileWriter? = null
    private var outputFile: File? = null
    private lateinit var frameBuffer: ShortArray
    private lateinit var drainBuffer: ShortArray
    private lateinit var silenceBuffer: ShortArray
    private lateinit var preRoll: PreRollRingBuffer

    private var frameSamples = 0
    private var maxPreRollSamples = 0
    private var maxGapSamples = 0

    /**
     * 创建目标文件、预分配全部缓冲区、初始化并启动 AudioRecord。
     * 返回本次录音的目标 .wav 文件。RECORD_AUDIO 权限由调用方保证。
     */
    @SuppressLint("MissingPermission")
    fun start(): File {
        check(!running) { "VoxAudioEngine is already running" }
        errorCodeField = ERR_NONE
        released = false
        lastTotalAudioBytes = 0L

        frameSamples = sampleRate * FRAME_MS / 1000
        maxPreRollSamples = sampleRate * MAX_PRE_ROLL_MS / 1000
        maxGapSamples = sampleRate * MAX_GAP_MS / 1000
        val maxSamplesPerWrite = maxOf(frameSamples, maxPreRollSamples, maxGapSamples)

        // 唯一一次数组分配；run() 内全部复用。
        frameBuffer = ShortArray(frameSamples)
        drainBuffer = ShortArray(maxPreRollSamples)
        silenceBuffer = ShortArray(maxGapSamples) // 恒为全 0，仅读不写
        preRoll = PreRollRingBuffer(maxPreRollSamples)
        preRoll.setCapacitySamples(sampleRate * preRollMs / 1000)
        paramsApplied = true

        if (!outputDirectory.exists()) outputDirectory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val target = File(outputDirectory, "VoxCut_$stamp.wav")
        if (target.exists()) target.delete()
        outputFile = target
        outputPathField = target.absolutePath

        val writer = WavFileWriter(
            file = target,
            sampleRate = sampleRate,
            channelCount = 1,
            bitsPerSample = 16,
            maxSamplesPerWrite = maxSamplesPerWrite
        )
        wavWriter = writer

        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // 采集缓冲至少容纳 4 帧，降低 underrun 概率；仍是原生 16-bit PCM。
        val recordBufferBytes = maxOf(minBufferBytes, frameSamples * 2 * 4)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            recordBufferBytes
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            audioRecord = null
            writer.finalizeWavFile()
            wavWriter = null
            // 初始化失败不留 0 长度残留文件，避免污染历史文件列表。
            target.delete()
            released = true
            errorCodeField = ERR_AUDIO_RECORD_INIT
            throw IllegalStateException("AudioRecord init failed (errorCode=$ERR_AUDIO_RECORD_INIT)")
        }
        audioRecord = recorder
        recorder.startRecording()

        smoothedDbField = 0.0
        stateField = STATE_IDLE
        monitorMsField = 0L
        effectiveMsField = 0L
        triggerCountField = 0
        running = true
        return target
    }

    /** 监听中热更新参数。只改原始字段，不重建任何缓冲区。 */
    fun updateParams(thresholdDb: Double, preRollMs: Int, hangoverMs: Int, gapMs: Int) {
        this.thresholdDb = thresholdDb.coerceIn(THRESHOLD_MIN_DB, THRESHOLD_MAX_DB)
        this.preRollMs = preRollMs.coerceIn(PRE_ROLL_MIN_MS, PRE_ROLL_MAX_MS)
        this.hangoverMs = hangoverMs.coerceIn(HANGOVER_MIN_MS, HANGOVER_MAX_MS)
        this.gapMs = gapMs.coerceIn(GAP_MIN_MS, GAP_MAX_MS)
        if (paramsApplied) {
            preRoll.setCapacitySamples(sampleRate * this.preRollMs / 1000)
        }
    }

    /**
     * 音频采集热循环。必须在 Dispatchers.IO 上调用，阻塞直至 [stop] 或读错误退出。
     * while 内零对象分配：所有写入都是原始字段赋值与对预分配数组的元素级操作。
     */
    fun run() {
        val recorder = audioRecord ?: return
        val writer = wavWriter ?: return
        var consecutiveLoudFrames = 0
        var hangoverElapsedMs = 0
        var consecutiveReadErrors = 0

        try {
            while (running) {
                val read = recorder.read(frameBuffer, 0, frameSamples)
                if (read < 0) {
                    if (!running) break
                    consecutiveReadErrors++
                    errorCodeField = ERR_READ_FAILED
                    // 保持 20ms 节奏，防止麦克风被抢占时忙等烧 CPU。
                    SystemClock.sleep(FRAME_MS.toLong())
                    // 连续 10 次（200ms）仍不可恢复则结束采集，已录数据由 finally 回写头。
                    if (consecutiveReadErrors >= MAX_CONSECUTIVE_READ_ERRORS) break
                    continue
                }
                consecutiveReadErrors = 0
                // 短读用 0 补齐到固定 20ms 帧，保持状态机与写盘粒度一致，零分配。
                if (read < frameSamples) {
                    var i = read
                    while (i < frameSamples) {
                        frameBuffer[i] = 0
                        i++
                    }
                }

                monitorMsField += FRAME_MS.toLong()

                val currentDb = computeDisplayDb(frameBuffer, frameSamples)
                var smoothed = SMOOTH_WEIGHT_PREVIOUS * smoothedDbField +
                        SMOOTH_WEIGHT_CURRENT * currentDb
                if (smoothed.isNaN() || smoothed.isInfinite()) smoothed = currentDb
                smoothedDbField = smoothed

                val x = thresholdDb
                val releaseAt = x - HYSTERESIS_DB
                val holdMs = hangoverMs

                when (stateField) {
                    STATE_IDLE -> {
                        if (currentDb >= x) {
                            consecutiveLoudFrames++
                            if (consecutiveLoudFrames >= TRIGGER_FRAMES) {
                                consecutiveLoudFrames = 0
                                triggerCountField++
                                stateField = STATE_ACTIVE

                                // 回灌过去 T_pre 的历史，开头 1 帧线性淡入。
                                val drained = preRoll.drainTo(drainBuffer)
                                if (drained > 0) {
                                    applyFadeIn(drainBuffer, minOf(frameSamples, drained))
                                    writer.writeSamples(drainBuffer, drained)
                                    effectiveMsField += samplesToMs(drained)
                                }
                                // 当前帧未进入预录缓冲，直接接续写入，无重复样本。
                                writer.writeSamples(frameBuffer, frameSamples)
                                effectiveMsField += FRAME_MS.toLong()
                            } else {
                                preRoll.write(frameBuffer, frameSamples)
                            }
                        } else {
                            consecutiveLoudFrames = 0
                            preRoll.write(frameBuffer, frameSamples)
                        }
                    }

                    STATE_ACTIVE -> {
                        writer.writeSamples(frameBuffer, frameSamples)
                        effectiveMsField += FRAME_MS.toLong()
                        if (currentDb < releaseAt) {
                            stateField = STATE_HANGOVER
                            hangoverElapsedMs = 0
                        }
                    }

                    STATE_HANGOVER -> {
                        val resumed = currentDb >= x && hangoverElapsedMs < holdMs
                        if (resumed) {
                            stateField = STATE_ACTIVE
                            hangoverElapsedMs = 0
                            writer.writeSamples(frameBuffer, frameSamples)
                            effectiveMsField += FRAME_MS.toLong()
                        } else {
                            hangoverElapsedMs += FRAME_MS
                            if (hangoverElapsedMs >= holdMs) {
                                // 最后 1 帧线性淡出，消除切段 Click/Pop。
                                applyFadeOut(frameBuffer, frameSamples)
                                writer.writeSamples(frameBuffer, frameSamples)
                                effectiveMsField += FRAME_MS.toLong()

                                val gapSamples = sampleRate * gapMs / 1000
                                if (gapSamples > 0) {
                                    writer.writeSamples(
                                        silenceBuffer,
                                        minOf(gapSamples, maxGapSamples)
                                    )
                                }
                                preRoll.reset()
                                consecutiveLoudFrames = 0
                                hangoverElapsedMs = 0
                                stateField = STATE_IDLE
                            } else {
                                writer.writeSamples(frameBuffer, frameSamples)
                                effectiveMsField += FRAME_MS.toLong()
                            }
                        }
                    }
                }
            }
        } finally {
            // 用户在 ACTIVE / HANGOVER 中按停止时，尾部帧未经淡出，回退覆写最后 1 帧。
            if (stateField != STATE_IDLE && writer.totalAudioBytes >= frameSamples * 2L) {
                applyFadeOut(frameBuffer, frameSamples)
                writer.rewriteLastSamples(frameBuffer, frameSamples)
            }
            shutdown()
        }
    }

    /** 请求停止；实际回写头与释放在 run() 的 finally 或 [shutdown] 中完成。 */
    @Synchronized
    fun stop() {
        running = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
    }

    /**
     * 幂等收尾：停止并释放 AudioRecord、回写 WAV 头并关闭文件。
     * 服务层在 job.join() 之后调用一次，用于兜底 run() 从未执行或协程被取消的情况。
     */
    @Synchronized
    fun shutdown() {
        if (released) return
        released = true
        running = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            val w = wavWriter
            if (w != null) {
                lastTotalAudioBytes = w.totalAudioBytes
                w.finalizeWavFile()
            }
        } catch (_: Exception) {
        }
        wavWriter = null
        paramsApplied = false
        stateField = STATE_IDLE
    }

    /**
     * RMS -> dBFS -> 显示分贝。
     * RMS 为 0 时用 max(rms, 1.0) 兜底，log10 入参恒 > 0，结果必为有限值；
     * 仍额外拦截 NaN / Infinity 并钳制到 0..100，杜绝 UI 崩溃。
     */
    private fun computeDisplayDb(buffer: ShortArray, count: Int): Double {
        if (count <= 0) return DISPLAY_DB_MIN
        var sum = 0L
        for (i in 0 until count) {
            val s = buffer[i].toInt()
            sum += s.toLong() * s
        }
        val rms = sqrt(sum.toDouble() / count)
        val dbfs = 20.0 * log10(maxOf(rms, 1.0) / FULL_SCALE)
        val display = (dbfs + DBFS_OFFSET).coerceIn(DISPLAY_DB_MIN, DISPLAY_DB_MAX)
        return if (display.isNaN() || display.isInfinite()) DISPLAY_DB_MIN else display
    }

    /** 线性淡入：权重 0.0f -> 1.0f，就地改写复用数组。 */
    private fun applyFadeIn(buffer: ShortArray, count: Int) {
        if (count <= 1) return
        val last = (count - 1).toFloat()
        for (i in 0 until count) {
            val weight = i / last
            buffer[i] = (buffer[i] * weight).toInt().toShort()
        }
    }

    /** 线性淡出：权重 1.0f -> 0.0f，就地改写复用数组。 */
    private fun applyFadeOut(buffer: ShortArray, count: Int) {
        if (count <= 1) return
        val last = (count - 1).toFloat()
        for (i in 0 until count) {
            val weight = 1.0f - i / last
            buffer[i] = (buffer[i] * weight).toInt().toShort()
        }
    }

    private fun samplesToMs(samples: Int): Long = samples * 1000L / sampleRate

    /** 供服务层低频轮询后写入 StateFlow；此调用不在音频热循环内。 */
    fun snapshot(): VoxSnapshot = VoxSnapshot(
        smoothedDb = smoothedDbField,
        stateCode = stateField,
        monitorDurationMs = monitorMsField,
        effectiveDurationMs = effectiveMsField,
        triggerCount = triggerCountField,
        errorCode = errorCodeField,
        isRunning = running,
        thresholdDb = thresholdDb,
        preRollMs = preRollMs,
        hangoverMs = hangoverMs,
        gapMs = gapMs,
        totalAudioBytes = wavWriter?.totalAudioBytes ?: lastTotalAudioBytes,
        outputPath = outputPathField
    )
}

/** UI 层状态快照。仅在服务层的低频协程中构造。 */
data class VoxSnapshot(
    val smoothedDb: Double,
    val stateCode: Int,
    val monitorDurationMs: Long,
    val effectiveDurationMs: Long,
    val triggerCount: Int,
    val errorCode: Int,
    val isRunning: Boolean,
    val thresholdDb: Double,
    val preRollMs: Int,
    val hangoverMs: Int,
    val gapMs: Int,
    val totalAudioBytes: Long,
    val outputPath: String?
) {
    /** 压缩省流比：被剔除的静音时长占监听总时长的百分比。 */
    val savedPercent: Double
        get() = if (monitorDurationMs <= 0L) {
            0.0
        } else {
            ((monitorDurationMs - effectiveDurationMs).toDouble() * 100.0 / monitorDurationMs)
                .coerceIn(0.0, 100.0)
        }
}
