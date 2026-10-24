package dev.honor.bootforge.core

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Changes only the big-endian AVB0 flags field in a new, full-size image file. */
object VbmetaPatcher {
    const val HEADER_BYTES = 256
    const val FLAGS_OFFSET = 120
    const val RELEASE_OFFSET = 128
    const val VERIFICATION_DISABLED = 2L

    data class Result(val originalFlags: Long, val patchedFlags: Long, val changedBytes: Int, val bytes: Long)

    fun patch(original: File, destination: File): Result {
        require(original.isFile && original.length() >= HEADER_BYTES) { "vbmeta 镜像小于完整 AVB 头" }
        require(original.canonicalFile != destination.canonicalFile && !destination.exists()) { "补丁目标必须是新的独立文件" }
        val bytes = original.length()
        val header = original.inputStream().use { input ->
            ByteArray(HEADER_BYTES).also { require(input.readFullyOrEnd(it, it.size) == it.size) }
        }
        validateHeader(header, bytes)
        val before = unsignedInt(header, FLAGS_OFFSET)
        val after = before or VERIFICATION_DISABLED
        val patchedHeader = header.copyOf()
        ByteBuffer.wrap(patchedHeader).order(ByteOrder.BIG_ENDIAN).putInt(FLAGS_OFFSET, after.toInt())
        try {
            original.inputStream().use { input ->
                val repeatedHeader = ByteArray(HEADER_BYTES)
                require(input.readFullyOrEnd(repeatedHeader, repeatedHeader.size) == HEADER_BYTES && repeatedHeader.contentEquals(header)) {
                    "vbmeta 原始镜像在修补前发生变化"
                }
                destination.outputStream().use { output ->
                    output.write(patchedHeader)
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            require(original.length() == bytes && destination.length() == bytes) { "修补前后镜像长度发生变化" }
            val changed = verifyOnlyFlagsChanged(original, destination, before, after)
            return Result(before, after, changed, bytes)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    internal fun validateHeader(header: ByteArray, fileBytes: Long) {
        require(header.size == HEADER_BYTES && fileBytes >= HEADER_BYTES) { "AVB 头被截断" }
        require(header.copyOfRange(0, 4).contentEquals(byteArrayOf(65, 86, 66, 48))) { "vbmeta 开头缺少 AVB0 标识" }
        require(unsignedInt(header, 4) == 1L) { "AVB 头主版本不受支持" }
        val authentication = unsignedLongInRange(header, 12)
        val auxiliary = unsignedLongInRange(header, 20)
        require(authentication % 64L == 0L && auxiliary % 64L == 0L) { "AVB 数据块未按 64 字节对齐" }
        require(authentication <= fileBytes - HEADER_BYTES && auxiliary <= fileBytes - HEADER_BYTES - authentication) {
            "AVB 数据块超出镜像边界"
        }
        requireRange(header, 32, 40, authentication, "hash")
        requireRange(header, 48, 56, authentication, "signature")
        requireRange(header, 64, 72, auxiliary, "public key")
        requireRange(header, 80, 88, auxiliary, "public key metadata")
        requireRange(header, 96, 104, auxiliary, "descriptors")
        require(header.sliceArray(RELEASE_OFFSET until RELEASE_OFFSET + 48).contains(0.toByte())) { "AVB release 字符串未终止" }
        require(header.sliceArray(176 until HEADER_BYTES).all { it == 0.toByte() }) { "AVB 头保留字段非零" }
    }

    private fun requireRange(header: ByteArray, offsetField: Int, sizeField: Int, blockBytes: Long, name: String) {
        val offset = unsignedLongInRange(header, offsetField)
        val size = unsignedLongInRange(header, sizeField)
        require(offset <= blockBytes && size <= blockBytes - offset) { "AVB $name 字段超出数据块边界" }
    }

    private fun unsignedLongInRange(header: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getLong(offset).also {
            require(it >= 0) { "AVB 字段超出可处理的文件大小" }
        }

    private fun unsignedInt(header: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(offset).toLong() and 0xffffffffL

    private fun verifyOnlyFlagsChanged(original: File, patched: File, before: Long, after: Long): Int {
        var position = 0L
        var changed = 0
        original.inputStream().buffered().use { left ->
            patched.inputStream().buffered().use { right ->
                val originalBuffer = ByteArray(64 * 1024)
                val patchedBuffer = ByteArray(originalBuffer.size)
                while (true) {
                    val count = left.readFullyOrEnd(originalBuffer, originalBuffer.size)
                    require(right.readFullyOrEnd(patchedBuffer, count) == count) { "修补副本被截断" }
                    if (count == 0) { require(right.read() == -1); break }
                    for (index in 0 until count) if (originalBuffer[index] != patchedBuffer[index]) {
                        require(position + index in FLAGS_OFFSET.toLong() until (FLAGS_OFFSET + 4).toLong()) {
                            "修补副本的 AVB flags 之外出现差异"
                        }
                        changed++
                    }
                    position += count
                }
            }
        }
        require((before or VERIFICATION_DISABLED) == after && changed <= 1) { "AVB flags 修补结果不一致" }
        return changed
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray, length: Int): Int {
        var count = 0
        while (count < length) {
            val read = read(buffer, count, length - count)
            if (read < 0) break
            if (read == 0) continue
            count += read
        }
        return count
    }
}
