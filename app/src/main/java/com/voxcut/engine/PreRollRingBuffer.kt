package com.voxcut.engine

/**
 * 环形预录缓冲区。
 *
 * 底层只有一个在构造期一次性分配的 [ShortArray]，录制热循环内不产生任何对象分配。
 * [maxCapacitySamples] 按最大预录时长（2000ms）预分配；[capacitySamples] 是运行时生效容量，
 * 允许 UI 在监听过程中热更新而不重新分配数组。
 */
class PreRollRingBuffer(private val maxCapacitySamples: Int) {

    init {
        require(maxCapacitySamples > 0) { "maxCapacitySamples must be positive" }
    }

    private val data = ShortArray(maxCapacitySamples)
    private var writeIndex = 0
    private var availableSamples = 0

    /** 当前生效容量（样本数），恒 <= [maxCapacitySamples]。仅由 UI 线程写、音频线程读。 */
    @Volatile
    var capacitySamples: Int = maxCapacitySamples
        private set

    fun setCapacitySamples(value: Int) {
        capacitySamples = value.coerceIn(0, maxCapacitySamples)
    }

    /**
     * 覆盖写入一帧。count 大于 [maxCapacitySamples] 时只保留最近的数据。
     * 循环内只有原始类型运算，无 new。
     */
    fun write(src: ShortArray, count: Int) {
        val n = minOf(count, src.size)
        for (i in 0 until n) {
            data[writeIndex] = src[i]
            writeIndex++
            if (writeIndex == maxCapacitySamples) writeIndex = 0
        }
        availableSamples = minOf(availableSamples + n, maxCapacitySamples)
    }

    /**
     * 按时间先后（最老 -> 最新）导出最近 [capacitySamples] 个可用样本到 [outputBuffer]，
     * 随后清空缓冲区。返回实际导出的样本数。
     *
     * 输出容量不足时以 outputBuffer.size 截断；分段拷贝用 System.arraycopy，零分配。
     */
    fun drainTo(outputBuffer: ShortArray): Int {
        val count = minOf(minOf(availableSamples, capacitySamples), outputBuffer.size)
        if (count <= 0) {
            reset()
            return 0
        }

        // writeIndex 指向下一个写入位，也就是最老样本所在位（写满时）；
        // 从它往回数 count 个即为按时间顺序的起点。
        var start = writeIndex - count
        if (start < 0) start += maxCapacitySamples

        val tailLength = maxCapacitySamples - start
        if (count <= tailLength) {
            System.arraycopy(data, start, outputBuffer, 0, count)
        } else {
            System.arraycopy(data, start, outputBuffer, 0, tailLength)
            System.arraycopy(data, 0, outputBuffer, tailLength, count - tailLength)
        }

        reset()
        return count
    }

    /** 重置逻辑索引；底层数组直接复用，不做无谓零填充。 */
    fun reset() {
        writeIndex = 0
        availableSamples = 0
    }
}
