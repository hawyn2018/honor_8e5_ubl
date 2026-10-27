package net.hawyn.ablefisp.patch

import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

data class ExtractedImage(val output: ByteArray, val sourceFormat: String, val notes: List<String>)

internal object FirmwareExtractor {
    const val MAX_INPUT_BYTES = 128 * 1024 * 1024
    private const val MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024
    private const val MAX_DICTIONARY_BYTES = 64 * 1024 * 1024
    private val FV_SIGNATURE = "_FVH".toByteArray(Charsets.US_ASCII)
    private val LZMA_GUID = byteArrayOf(0x98.toByte(), 0x58, 0x4e, 0xee.toByte(), 0x14, 0x39, 0x59, 0x42, 0x9d.toByte(), 0x6e, 0xdc.toByte(), 0x7b, 0xd7.toByte(), 0x94.toByte(), 0x03, 0xcf.toByte())

    private data class Candidate(val image: PeImage, val path: String)

    fun extract(input: ByteArray): ExtractedImage {
        if (input.isEmpty()) throw PatchException("文件为空。")
        if (input.size > MAX_INPUT_BYTES) throw PatchException("输入超过 128 MiB，请选择 ABL 分区镜像。")
        if (input.matches(0, byteArrayOf(0x4d, 0x5a))) {
            val pe = PeImage.parse(input)
            return ExtractedImage(pe.bytes, "ARM64 PE", listOf("已识别独立 EFI 程序。"))
        }
        val candidates = ArrayList<Candidate>()
        val notes = ArrayList<String>()
        val visited = HashSet<String>()

        fun addPe(data: ByteArray, start: Int, size: Int, path: String) {
            if (candidates.size >= 64) throw PatchException("镜像中的 EFI 程序数量超出分析上限。")
            try {
                val image = PeImage.parse(data, start, size)
                candidates += Candidate(image, path)
            } catch (_: PatchException) {
                // A firmware volume can contain components for a different architecture.
            }
        }

        lateinit var sections: (ByteArray, Int, Int, String, Int) -> Unit
        lateinit var volume: (ByteArray, Int, Int, String, Int) -> Unit

        fun decompress(data: ByteArray, offset: Int, size: Int): ByteArray {
            data.range(offset, size)
            if (size < 13) throw PatchException("LZMA 数据头被截断。")
            val property = data.u8(offset)
            if (property >= 225) throw PatchException("LZMA 参数无效。")
            val dictionary = data.u32(offset + 1)
            if (dictionary > MAX_DICTIONARY_BYTES) throw PatchException("LZMA 字典超过 64 MiB 分析上限。")
            val declaredSize = data.u64(offset + 5)
            if (declaredSize != -1L && (declaredSize < 0 || declaredSize > MAX_DECOMPRESSED_BYTES)) {
                throw PatchException("LZMA 声明的解压长度无效或超过 64 MiB。")
            }
            try {
                val source = ByteArrayInputStream(data, offset + 13, size - 13)
                val initial = if (declaredSize in 1..MAX_DECOMPRESSED_BYTES.toLong()) declaredSize.toInt() else 32 * 1024
                val output = ByteArrayOutputStream(initial)
                LZMAInputStream(source, declaredSize, property.toByte(), dictionary.toInt()).use { stream ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (output.size().toLong() + read > MAX_DECOMPRESSED_BYTES) throw PatchException("LZMA 解压结果超过 64 MiB。")
                        output.write(buffer, 0, read)
                    }
                }
                val result = output.toByteArray()
                if (declaredSize >= 0 && result.size.toLong() != declaredSize) throw PatchException("LZMA 解压长度与声明不一致。")
                return result
            } catch (e: PatchException) {
                throw e
            } catch (e: Exception) {
                throw PatchException("LZMA 数据解压失败：${e.message ?: e.javaClass.simpleName}")
            }
        }

        sections = sectionLoop@{ data, start, length, path, depth ->
            if (depth > 12) throw PatchException("固件嵌套层数超出分析上限。")
            data.range(start, length)
            val end = start + length
            var at = start
            var count = 0
            while (at <= end - 4) {
                if (++count > 8192) throw PatchException("固件 section 数量异常。")
                if (data.u32(at) == 0xffffffffL || data.u32(at) == 0L) break
                var size = data.u24(at)
                val kind = data.u8(at + 3)
                var header = 4
                if (size == 0xffffff) {
                    if (at + 8 > end) throw PatchException("扩展 section 头被截断。")
                    size = data.u32(at + 4).asBoundedInt("扩展 section 长度")
                    header = 8
                }
                if (size < header || at.toLong() + size > end) throw PatchException("固件 section 长度超出容器边界。")
                when (kind) {
                    0x10 -> addPe(data, at + header, size - header, "$path / PE")
                    0x17 -> volume(data, at + header, size - header, "$path / FV", depth + 1)
                    0x02 -> {
                        if (size < header + 20) throw PatchException("GUID-defined section 头被截断。")
                        val payload = data.u16(at + header + 16)
                        if (payload < header + 20 || payload > size) throw PatchException("GUID-defined section 数据位置无效。")
                        if (data.matches(at + header, LZMA_GUID)) {
                            val decoded = decompress(data, at + payload, size - payload)
                            val key = decoded.sha256()
                            if (visited.add(key)) sections(decoded, 0, decoded.size, "$path / LZMA", depth + 1)
                        } else if (data.u16(at + header + 18) and 1 == 0) {
                            sections(data, at + payload, size - payload, path, depth + 1)
                        } else notes += "已跳过其他 GUID 编码的固件组件。"
                    }
                    0x01 -> {
                        if (size < header + 5) throw PatchException("压缩 section 头被截断。")
                        val uncompressedSize = data.u32(at + header)
                        val compressionType = data.u8(at + header + 4)
                        if (compressionType == 0) {
                            if (uncompressedSize != (size - header - 5).toLong()) throw PatchException("未压缩 section 长度无效。")
                            sections(data, at + header + 5, size - header - 5, path, depth + 1)
                        } else notes += "发现 EFI/Tiano 编码组件；当前分析器处理 PE 与 LZMA 路径。"
                    }
                }
                val next = align(at + size, 4)
                if (next <= at) throw PatchException("固件 section 步进无效。")
                at = next
            }
        }

        volume = volumeLoop@{ data, start, available, path, depth ->
            if (depth > 12) throw PatchException("固件嵌套层数超出分析上限。")
            data.range(start, available)
            if (available < 0x38 || !data.matches(start + 0x28, FV_SIGNATURE)) throw PatchException("固件卷头无效。")
            val size = data.u64(start + 0x20).asBoundedInt("固件卷长度")
            val header = data.u16(start + 0x30)
            if (size < 0x38 || size > available || header < 0x38 || header > size || header and 1 != 0) throw PatchException("固件卷长度或头部无效。")
            // FV checksum covers the complete declared header, including its block map.
            var checksum = 0
            for (n in 0 until header step 2) checksum = (checksum + data.u16(start + n)) and 0xffff
            if (checksum != 0) throw PatchException("固件卷头校验失败。")
            val end = start + size
            var at = align(start + header, 8)
            var files = 0
            while (at <= end - 24) {
                if (++files > 4096) throw PatchException("固件文件数量异常。")
                if ((0 until 24).all { data.u8(at + it) == 255 } || (0 until 24).all { data.u8(at + it) == 0 }) break
                var fileSize = data.u24(at + 20)
                var fileHeader = 24
                if (fileSize == 0xffffff) {
                    if (at + 32 > end) throw PatchException("扩展固件文件头被截断。")
                    fileSize = data.u64(at + 24).asBoundedInt("固件文件长度")
                    fileHeader = 32
                }
                if (fileSize < fileHeader || at.toLong() + fileSize > end) throw PatchException("固件文件长度超出固件卷。")
                val type = data.u8(at + 18)
                if (type != 0xf0 && type != 0x01) sections(data, at + fileHeader, fileSize - fileHeader, path, depth + 1)
                at = align(at + fileSize, 8)
            }
        }

        fun scanRegion(start: Int, length: Int, label: String) {
            input.range(start, length)
            if (length >= 0x40 && input.matches(start, byteArrayOf(0x4d, 0x5a))) addPe(input, start, length, "$label / PE")
            // The signature is used only to locate candidates; every FV structure and its
            // nested lengths/checksum are validated before compressed data is accessed.
            var at = start + 0x28
            val end = start + length
            while (at <= end - 4) {
                if (input.matches(at, FV_SIGNATURE)) {
                    val volumeStart = at - 0x28
                    if ((volumeStart - start) and 7 == 0) {
                        volume(input, volumeStart, end - volumeStart, "$label / FV", 0)
                        val volumeLength = input.u64(volumeStart + 0x20).asBoundedInt("固件卷长度")
                        at = volumeStart + volumeLength
                        continue
                    }
                }
                at++
            }
        }

        val rootFormat: String
        if (input.matches(0, byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) {
            if (input.u8(5) != 1) throw PatchException("ELF 字节序需要为 little-endian。")
            val cls = input.u8(4)
            val headerSize = if (cls == 1) 52 else if (cls == 2) 64 else throw PatchException("ELF class 无效。")
            input.range(0, headerSize)
            val phoff = (if (cls == 1) input.u32(28) else input.u64(32)).asBoundedInt("ELF program header 位置")
            val entrySize = input.u16(if (cls == 1) 42 else 54)
            val count = input.u16(if (cls == 1) 44 else 56)
            val required = if (cls == 1) 32 else 56
            if (count !in 1..256 || entrySize < required) throw PatchException("ELF program header 参数无效。")
            if (phoff.toLong() + entrySize.toLong() * count > input.size) throw PatchException("ELF program header 被截断。")
            rootFormat = if (cls == 1) "ELF32" else "ELF64"
            repeat(count) { index ->
                val at = phoff + index * entrySize
                val type = input.u32(at)
                val offset = (if (cls == 1) input.u32(at + 4) else input.u64(at + 8)).asBoundedInt("ELF segment 位置")
                val size = (if (cls == 1) input.u32(at + 16) else input.u64(at + 32)).asBoundedInt("ELF segment 长度")
                input.range(offset, size)
                if (type == 1L && size > 0) scanRegion(offset, size, rootFormat)
            }
        } else {
            rootFormat = "UEFI 固件卷"
            scanRegion(0, input.size, rootFormat)
        }
        val distinct = candidates.distinctBy { it.image.bytes.sha256() }
        if (distinct.isEmpty()) throw PatchException("未找到可提取的 ARM64 EFI 程序。${notes.distinct().joinToString(" ")}")
        val likelyLoader = distinct.filter { candidate ->
            candidate.image.bytes.occurrences("fastboot flash:rdmodev1".toByteArray()).isNotEmpty() ||
                candidate.image.bytes.occurrences("PHONE_UNLOCKED".toByteArray()).isNotEmpty()
        }
        val selected = when {
            likelyLoader.size == 1 -> likelyLoader.single()
            likelyLoader.isEmpty() && distinct.size == 1 -> distinct.single()
            else -> throw PatchException("发现多个不同的 EFI 程序，目标存在歧义，请选择独立 ABL 镜像。")
        }
        if (candidates.size > distinct.size) notes += "已合并重复的 EFI 程序副本。"
        notes += "已从固件容器中提取 ARM64 EFI 程序。"
        return ExtractedImage(selected.image.bytes, selected.path, notes.distinct())
    }
}
