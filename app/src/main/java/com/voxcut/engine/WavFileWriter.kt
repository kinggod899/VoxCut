package com.voxcut.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * 单文件流式追加写入器。
 *
 * 构造时创建目标 .wav 并写入 44 字节全 0 占位头；之后所有有效 PCM 只追加到文件尾；
 * 停止时由 [WavHeaderWriter] 通过 seek(0) 回写标准 RIFF/WAVE 头。
 * 全程不产生碎片文件，也不做二次合并。
 *
 * 热循环复用同一个 [ByteBuffer] / [ShortBuffer] / 底层 byte[]，零对象分配。
 */
class WavFileWriter(
    val file: File,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitsPerSample: Int,
    maxSamplesPerWrite: Int
) {
    private val randomAccessFile = RandomAccessFile(file, "rw")
    private val byteBuffer: ByteBuffer =
        ByteBuffer.allocate(maxSamplesPerWrite * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
    private val shortView: ShortBuffer = byteBuffer.asShortBuffer()
    private val backingBytes: ByteArray = byteBuffer.array()

    /** 已追加的纯音频数据字节数（不含 44 字节头、不含段间留白之外的任何元数据）。 */
    @Volatile
    var totalAudioBytes: Long = 0L
        private set

    @Volatile
    private var closed = false

    init {
        require(maxSamplesPerWrite > 0) { "maxSamplesPerWrite must be positive" }
        // 构造期一次性分配，不进入音频热循环。
        randomAccessFile.write(ByteArray(WAV_HEADER_SIZE))
    }

    /**
     * 将 samples[0 until count] 以小端 16-bit PCM 批量转换并追加写入文件尾。
     * count 超过短视图容量时按容量截断，保证不会越界写 backingBytes。
     */
    fun writeSamples(samples: ShortArray, count: Int) {
        if (closed || count <= 0) return
        val n = minOf(count, samples.size, shortView.capacity())
        if (n <= 0) return
        shortView.clear()
        shortView.put(samples, 0, n)
        randomAccessFile.write(backingBytes, 0, n * BYTES_PER_SAMPLE)
        totalAudioBytes += (n * BYTES_PER_SAMPLE).toLong()
    }

    /**
     * 正常停止时若正处在有效段中，文件尾是硬切帧，直接收尾会出现 Click/Pop。
     * 本方法回退文件指针到末尾 [count] 个样本处并覆盖为已淡出的数据，
     * 不修改 [totalAudioBytes]、不改变文件长度，随后仍由 [finalizeWavFile] 回写头。
     */
    fun rewriteLastSamples(samples: ShortArray, count: Int) {
        if (closed || count <= 0) return
        val n = minOf(count, samples.size, shortView.capacity())
        val byteCount = n * BYTES_PER_SAMPLE
        if (byteCount <= 0 || totalAudioBytes < byteCount.toLong()) return
        shortView.clear()
        shortView.put(samples, 0, n)
        randomAccessFile.seek(WAV_HEADER_SIZE.toLong() + totalAudioBytes - byteCount)
        randomAccessFile.write(backingBytes, 0, byteCount)
    }

    /** 回写文件头并关闭，幂等。 */
    fun finalizeWavFile() {
        if (closed) return
        closed = true
        WavHeaderWriter.finalizeWavFile(
            randomAccessFile = randomAccessFile,
            totalAudioBytes = totalAudioBytes,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample
        )
    }

    companion object {
        const val WAV_HEADER_SIZE = 44
        const val BYTES_PER_SAMPLE = 2
    }
}

/** 标准 44 字节 RIFF / WAVE(PCM) 头回写器。 */
object WavHeaderWriter {

    private val ASCII = Charsets.US_ASCII

    /**
     * 通过 [randomAccessFile].seek(0) 覆写占位头。
     * RIFF 与 data 长度字段为无符号 32 位，用 and 0xFFFFFFFFL 折回 Int，支持到 4GB。
     */
    fun finalizeWavFile(
        randomAccessFile: RandomAccessFile,
        totalAudioBytes: Long,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int
    ) {
        val blockAlign = channelCount * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val dataSize = (totalAudioBytes and 0xFFFFFFFFL).toInt()
        val riffSize = ((36L + totalAudioBytes) and 0xFFFFFFFFL).toInt()

        val header = ByteBuffer.allocate(WavFileWriter.WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(ASCII))
        header.putInt(riffSize)
        header.put("WAVE".toByteArray(ASCII))
        header.put("fmt ".toByteArray(ASCII))
        header.putInt(16)                            // fmt 子块长度
        header.putShort(1.toShort())                 // WAVE_FORMAT_PCM
        header.putShort(channelCount.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(ASCII))
        header.putInt(dataSize)

        randomAccessFile.seek(0)
        randomAccessFile.write(header.array())
        // 文件长度精确收敛到 头 + 已写 PCM，防止同名旧文件残留尾部字节。
        randomAccessFile.setLength(WavFileWriter.WAV_HEADER_SIZE.toLong() + totalAudioBytes)
        randomAccessFile.close()
    }
}
