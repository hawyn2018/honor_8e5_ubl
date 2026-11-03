package net.hawyn.ablefisp.io

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import net.hawyn.ablefisp.patch.PeImage

/** Bounded file/pipe readers shared by the document import and root extraction flows. */
object ImageIo {
    const val MAX_INPUT_BYTES: Int = 128 * 1024 * 1024

    /** The source digest always covers the complete received file, including partition padding. */
    data class PreparedFile(
        val bytes: ByteArray,
        val sourceBytes: Long,
        val sourceSha256: String,
        val analysisSha256: String,
        val trimmedPaddingBytes: Long,
    )

    /**
     * Snapshot a file without a doubling ByteArrayOutputStream. A bare PE with a
     * validated section/certificate extent and only 00/FF tail padding is retained
     * at its real file size. Containers and non-padding overlays keep the original
     * extractor path. No instruction, section, certificate or opaque overlay is
     * discarded based on a firmware identity or a guessed fixed image length.
     */
    @JvmOverloads
    fun prepareFile(file: File, maxBytes: Int = MAX_INPUT_BYTES): PreparedFile {
        require(maxBytes > 0)
        val sourceLength = file.length()
        if (sourceLength !in 1..maxBytes.toLong()) throw AblReadException("输入文件为空或超过 ${maxBytes / 1024 / 1024} MiB 限制")
        val extent = peFileExtent(file, sourceLength) ?: sourceLength.toInt()
        val retained = ByteArray(extent)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var paddingOnly = true
        file.inputStream().use { stream ->
            while (true) {
                var count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val one = stream.read()
                    if (one < 0) break
                    buffer[0] = one.toByte(); count = 1
                }
                if (total + count > sourceLength) throw AblReadException("输入文件在读取时增长")
                digest.update(buffer, 0, count)
                val copied = minOf(count.toLong(), (extent.toLong() - total).coerceAtLeast(0)).toInt()
                if (copied > 0) buffer.copyInto(retained, total.toInt(), 0, copied)
                for (i in copied until count) if (buffer[i] != 0.toByte() && buffer[i] != 0xff.toByte()) paddingOnly = false
                total += count
            }
        }
        if (total != sourceLength || file.length() != sourceLength) throw AblReadException("输入文件在读取时长度发生变化")
        val sourceHash = digest.digest().hexDigest()
        if (extent < sourceLength && paddingOnly) {
            // Full PE validation uses the same parser as the normal extractor.
            // Its result must retain exactly the declared sections/certificate.
            if (PeImage.parse(retained).bytes.size != extent) throw AblReadException("PE 文件边界在读取时发生变化")
            return PreparedFile(retained, sourceLength, sourceHash, sha256(retained), sourceLength - extent)
        }
        if (extent == sourceLength.toInt()) return PreparedFile(retained, sourceLength, sourceHash, sourceHash, 0)
        // An opaque overlay is not assumed to be expendable. Preserve the old
        // complete-input behavior and prove that the second read is the same file.
        val complete = ByteArray(sourceLength.toInt())
        file.inputStream().use { stream -> readExact(stream, complete) }
        if (sha256(complete) != sourceHash) throw AblReadException("输入文件在读取时内容发生变化")
        return PreparedFile(complete, sourceLength, sourceHash, sourceHash, 0)
    }

    /** Compute an extent from received PE metadata; return null for the legacy extractor path. */
    private fun peFileExtent(file: File, length: Long): Int? {
        return try {
          RandomAccessFile(file, "r").use { input ->
            fun u16(at: Long): Int {
                if (at < 0 || at + 2 > length) throw AblReadException("PE 字段被截断")
                input.seek(at)
                return input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            }
            fun u32(at: Long): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
            if (length < 0x40 || u16(0) != 0x5a4d) return null
            val pe = u32(0x3c)
            if (pe < 0x40 || pe + 24 > length || u32(pe) != 0x4550L || u16(pe + 4) != 0xaa64) return null
            val count = u16(pe + 6); val optionalSize = u16(pe + 20)
            if (count < 1 || optionalSize < 112) return null
            val optional = pe + 24; val table = optional + optionalSize
            if (table + count * 40L > length || u16(optional) != 0x20b) return null
            val headers = u32(optional + 60)
            if (headers < table + count * 40L || headers > length) return null
            var extent = headers
            repeat(count) { i ->
                val at = table + i * 40L; val size = u32(at + 16); val raw = u32(at + 20)
                if (raw + size > length || (size > 0 && raw < headers)) return null
                extent = maxOf(extent, raw + size)
            }
            if (optionalSize >= 152 && u32(optional + 108) > 4) {
                val certificate = u32(optional + 144); val size = u32(optional + 148)
                if (size > 0) {
                    if (certificate + size > length) return null
                    extent = maxOf(extent, certificate + size)
                }
            }
            extent.toInt()
          }
        } catch (_: Exception) { null }
    }

    private fun readExact(stream: InputStream, target: ByteArray) {
        var at = 0
        while (at < target.size) {
            val count = stream.read(target, at, target.size - at)
            if (count < 0) throw AblReadException("输入文件在读取时被截断")
            if (count == 0) {
                val one = stream.read()
                if (one < 0) throw AblReadException("输入文件在读取时被截断")
                target[at++] = one.toByte()
            } else at += count
        }
        if (stream.read() >= 0) throw AblReadException("输入文件在读取时增长")
    }

    fun readBounded(input: InputStream, maxBytes: Int = MAX_INPUT_BYTES): ByteArray {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (count == 0) {
                val one = input.read()
                if (one < 0) return output.toByteArray()
                if (output.size() == maxBytes) throw AblReadException("输入超过 ${maxBytes / 1024 / 1024} MiB 限制")
                output.write(one)
            } else {
                if (count > maxBytes - output.size()) throw AblReadException("输入超过 ${maxBytes / 1024 / 1024} MiB 限制")
                output.write(buffer, 0, count)
            }
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).hexDigest()

    /** Hash a file/pipe without allocating another copy of its content. */
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return digest.digest().hexDigest()
            if (count == 0) {
                val one = input.read()
                if (one < 0) return digest.digest().hexDigest()
                digest.update(one.toByte())
            } else digest.update(buffer, 0, count)
        }
    }

    private fun ByteArray.hexDigest(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

class AblReadException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
