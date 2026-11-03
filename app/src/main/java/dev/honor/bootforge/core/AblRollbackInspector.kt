package dev.honor.bootforge.core

import java.io.File
import java.io.FileInputStream

/** Reads image-declared Qualcomm counters, not device fuses or certificate trust. */
object AblRollbackInspector {
    const val MAX_IMAGE_BYTES = 128 * 1024 * 1024
    const val SCOPE = "仅解析镜像结构内声明的防回滚值；未验证签名信任链，未读取设备熔丝、RPMB 或实际防回滚门槛。"
    enum class Format { SIGNED_ELF, SIGNED_MBN, EFI_PE }
    enum class Authority { OEM, QTI }
    data class Counter(
        val authority: Authority,
        val value: Long,
        val source: String,
        val fileOffset: Long,
        val metadataVersion: String? = null,
    )
    sealed class Result {
        val scope: String get() = SCOPE
        data class Parsed(val format: Format, val counters: List<Counter>, val evidence: List<String>) : Result()
        data class NoMetadata(val format: Format, val evidence: List<String>) : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun inspect(file: File): Result = try {
        val size = file.length()
        if (!file.isFile || size !in 1..MAX_IMAGE_BYTES.toLong()) Result.Invalid("镜像文件大小无效或超过 128 MiB")
        else FileInputStream(file).use { input ->
            val bytes = ByteArray(size.toInt())
            var at = 0
            while (at < bytes.size) {
                val n = input.read(bytes, at, bytes.size - at)
                if (n <= 0) throw Bad("镜像读取不完整或读取时发生变化")
                at += n
            }
            if (input.read() != -1) throw Bad("镜像读取时大小发生变化")
            inspect(bytes)
        }
    } catch (e: Exception) { Result.Invalid(e.message ?: "读取镜像失败") }

    fun inspect(bytes: ByteArray): Result = try {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) throw Bad("镜像大小无效或超过 128 MiB")
        val reader = Reader(bytes)
        when {
            bytes.size >= 4 && bytes[0] == 0x7f.toByte() && bytes[1] == 69.toByte() && bytes[2] == 76.toByte() && bytes[3] == 70.toByte() -> reader.elf()
            bytes.size >= 2 && bytes[0] == 77.toByte() && bytes[1] == 90.toByte() -> reader.pe()
            else -> reader.mbn()
        }
    } catch (e: Bad) { Result.Invalid(e.message ?: "镜像结构无效") }
      catch (e: Exception) { Result.Invalid("镜像解析失败：${e.message ?: e.javaClass.simpleName}") }

    private class Bad(message: String) : Exception(message)
    private class Reader(val data: ByteArray) {
        private fun fail(message: String): Nothing = throw Bad(message)
        private fun range(at: Long, size: Long, end: Long = data.size.toLong()): Int {
            if (at < 0 || size < 0 || end > data.size || at > end || size > end - at) fail("镜像结构越界或长度不完整")
            return at.toInt()
        }
        private fun u8(at: Long): Int = data[range(at, 1)].toInt() and 255
        private fun u16(at: Long): Int = u8(at) or (u8(at + 1) shl 8)
        private fun u32(at: Long): Long = (0..3).fold(0L) { v, i -> v or (u8(at + i).toLong() shl (i * 8)) }
        private fun u64(at: Long): Long {
            val lo = u32(at); val hi = u32(at + 4)
            if (hi > 0x7fffffff) fail("64 位镜像地址超出支持范围")
            return lo or (hi shl 32)
        }
        private fun padding(at: Long, end: Long): Boolean {
            range(at, end - at)
            return (at until end).all { u8(it) in listOf(0, 255) }
        }
        private fun little(at: Long, n: Int) = (0 until n).fold(0L) { v, i -> v or (u8(at + i).toLong() shl (i * 8)) }

        fun elf(): Result {
            range(0, 16)
            val cls = u8(4)
            if (cls !in 1..2 || u8(5) != 1 || u8(6) != 1) fail("不支持或损坏的 ELF 标识")
            val eh = if (cls == 1) 52 else 64
            range(0, eh.toLong())
            if (u16(16) !in listOf(2, 3) || u16(18) !in listOf(40, 183) || u32(20) != 1L) fail("不是受支持的 ARM 引导 ELF")
            val ph = if (cls == 1) u32(28) else u64(32)
            val headerSize = u16(if (cls == 1) 40 else 52)
            val stride = u16(if (cls == 1) 42 else 54)
            val count = u16(if (cls == 1) 44 else 56)
            if (headerSize != eh || stride != (if (cls == 1) 32 else 56) || count !in 1..1024 || ph < eh) fail("ELF 程序头无效")
            range(ph, stride.toLong() * count)
            val hashes = ArrayList<Pair<Long, Long>>()
            repeat(count) { i ->
                val p = ph + i.toLong() * stride
                val flags = u32(p + if (cls == 1) 24 else 4)
                val offset = if (cls == 1) u32(p + 4) else u64(p + 8)
                val length = if (cls == 1) u32(p + 16) else u64(p + 32)
                range(offset, length)
                if ((flags ushr 24) and 7 == 2L) {
                    if (length < 40 || offset < ph + stride.toLong() * count) fail("Qualcomm HASH 段范围无效")
                    hashes += offset to length
                }
            }
            if (hashes.size != 1) fail("Qualcomm HASH 段缺失或不唯一")
            val (at, length) = hashes.single()
            return signed(at, length, Format.SIGNED_ELF)
        }

        fun mbn(): Result {
            range(0, 8)
            val version = u32(4)
            if (version !in listOf(3L, 5L, 6L, 7L, 8L)) fail("未识别为结构完整的 ELF、MBN 或 EFI PE")
            return signed(0, data.size.toLong(), Format.SIGNED_MBN)
        }

        private fun signed(at: Long, length: Long, format: Format): Result {
            val end = at + length
            range(at, length)
            return when (u32(at + 4)) {
                7L, 8L -> modern(at, end, format)
                3L, 5L -> legacy(at, end, format)
                // MBNv6 has multiple incompatible 40/48-byte layouts. It is
                // deliberately not guessed from plausible numbers or strings.
                else -> fail("此 MBN 版本的签名元数据布局尚未明确识别")
            }
        }

        private fun modern(at: Long, end: Long, format: Format): Result {
            val version = u32(at + 4)
            val header = if (version == 8L) 56L else 40L
            range(at, header, end)
            if (u32(at) != 0L) fail("Qualcomm HASH 头保留字段异常")
            val common = u32(at + 8); val qti = u32(at + 12); val oem = u32(at + 16); val hashes = u32(at + 20)
            if (common > 65536 || qti > 65536 || oem > 65536 || hashes !in 1..1048576L) fail("HASH 元数据或摘要表大小无效")
            var cursor = at + header
            if (common > 0) {
                range(cursor, common, end)
                val major = u32(cursor); val minor = u32(cursor + 4)
                val minimum = if (major == 0L && minor == 0L) 24 else if (major == 0L && minor == 1L) 28 else fail("Common metadata 版本未识别")
                if (common < minimum) fail("Common metadata 不完整")
            }
            cursor += common
            val counters = ArrayList<Counter>()
            for ((authority, n) in listOf(Authority.QTI to qti, Authority.OEM to oem)) {
                if (n > 0) counters += metadata(cursor, n, end, authority)
                cursor += n
            }
            if (counters.isEmpty()) fail("签名 HASH 段中没有明确的防回滚元数据")
            range(cursor, hashes, end); cursor += hashes
            val pairs = mutableListOf(Triple(Authority.QTI, u32(at + 24), u32(at + 28)), Triple(Authority.OEM, u32(at + 32), u32(at + 36)))
            if (version == 8L) pairs += listOf(Triple(Authority.QTI, u32(at + 40), u32(at + 44)), Triple(Authority.OEM, u32(at + 48), u32(at + 52)))
            var signing = 0
            for ((_, sig, cert) in pairs) {
                if ((sig == 0L) != (cert == 0L)) fail("签名与证书链大小不一致")
                range(cursor, sig + cert, end)
                if (sig > 0) {
                    signing++
                    certificates(cursor + sig, cert)
                }
                cursor += sig + cert
            }
            if (signing == 0) fail("HASH 段没有结构完整的签名和证书链")
            if (!padding(cursor, end)) fail("签名段尾部存在未识别数据")
            return Result.Parsed(format, counters, listOf("MBNv$version；Header → Common → QTI → OEM → hash → signature/certificate", "证书链仅验证 DER/X.509 结构，不作信任链验签"))
        }

        private fun metadata(at: Long, size: Long, end: Long, authority: Authority): Counter {
            range(at, size, end)
            if (size < 8) fail("$authority metadata 不完整")
            val major = u32(at); val minor = u32(at + 4)
            val offset: Long
            val minimum: Long
            when (major to minor) {
                0L to 0L, 1L to 0L -> { offset = 116; minimum = 120 }
                2L to 0L, 3L to 0L -> { offset = 8; minimum = 224 }
                3L to 1L -> { offset = 8; minimum = 228 }
                else -> fail("$authority metadata 版本 $major.$minor 未识别")
            }
            if (size < minimum) fail("$authority metadata $major.$minor 长度不足")
            return Counter(authority, u32(at + offset), "$authority metadata anti_rollback_version", at + offset, "$major.$minor")
        }

        private fun legacy(at: Long, end: Long, format: Format): Result {
            range(at, 40, end)
            val version = u32(at + 4)
            val code = u32(at + 20); val imageSize = u32(at + 16)
            val qtiSig = if (version == 5L) u32(at + 8) else 0
            val qtiCert = if (version == 5L) u32(at + 12) else 0
            val oemSig = u32(at + 28); val oemCert = u32(at + 36)
            val total = code + qtiSig + qtiCert + oemSig + oemCert
            if (code == 0L || imageSize < total || imageSize - total > 3) fail("传统 MBN 大小关系未明确识别")
            range(at + 40, imageSize, end)
            var cursor = at + 40 + code
            val counters = ArrayList<Counter>()
            for ((authority, sig, cert) in listOf(Triple(Authority.QTI, qtiSig, qtiCert), Triple(Authority.OEM, oemSig, oemCert))) {
                if ((sig == 0L) != (cert == 0L)) fail("传统 MBN 签名与证书大小不一致")
                if (sig > 0) {
                    range(cursor, sig + cert, end)
                    val chain = certificates(cursor + sig, cert)
                    val leaf = chain.first()
                    val values = subjectOu(leaf).filter { it.first.contains("SW_ID") }
                    if (values.size != 1) fail("$authority leaf 证书 SW_ID 缺失或不唯一")
                    val match = Regex("^01\\s+([0-9A-Fa-f]{16})\\s+SW_ID$").matchEntire(values.single().first)
                        ?: fail("$authority leaf 证书 SW_ID 格式未识别")
                    counters += Counter(authority, match.groupValues[1].take(8).toLong(16), "$authority leaf X.509 subject OU SW_ID[63:32]", values.single().second.toLong())
                }
                cursor += sig + cert
            }
            if (counters.isEmpty()) fail("传统 MBN 无明确签名证书计数")
            if (!padding(cursor, end)) fail("传统 MBN 尾部存在未识别数据")
            return Result.Parsed(format, counters, listOf("传统 MBNv$version；仅使用各签名槽 leaf 证书的 subject OU", "没有全文件或整证书链字符串扫描；未验证签名信任链"))
        }

        private data class Der(val tag: Int, val start: Int, val body: Int, val end: Int)
        private fun der(at: Int, bound: Int): Der {
            if (at < 0 || at + 2 > bound || bound > data.size) fail("DER 结构不完整")
            val tag = data[at].toInt() and 255
            if (tag and 31 == 31) fail("不支持的 DER 高位标签")
            val first = data[at + 1].toInt() and 255
            var body = at + 2
            val length = if (first < 128) first.toLong() else {
                val n = first and 127
                if (n !in 1..4 || body + n > bound || data[body] == 0.toByte()) fail("DER 长度编码无效")
                var value = 0L
                repeat(n) { value = (value shl 8) or (data[body++].toLong() and 255) }
                if (value < 128) fail("DER 长度编码不是最短形式")
                value
            }
            if (length > bound - body) fail("DER 对象越界")
            return Der(tag, at, body, body + length.toInt())
        }
        private fun children(node: Der): List<Der> {
            val result = ArrayList<Der>(); var at = node.body
            while (at < node.end) { val child = der(at, node.end); result += child; at = child.end }
            return result
        }
        private fun certificates(at: Long, size: Long): List<Der> {
            val start = range(at, size); val end = (at + size).toInt()
            val result = ArrayList<Der>(); var cursor = start
            while (cursor < end && u8(cursor.toLong()) == 0x30) {
                if (result.size >= 32) fail("证书链数量超出边界")
                val cert = der(cursor, end)
                val top = children(cert)
                if (top.size != 3 || top[0].tag != 0x30 || top[1].tag != 0x30 || top[2].tag != 3 || top[2].end - top[2].body < 2 || u8(top[2].body.toLong()) > 7) fail("不是完整 X.509 Certificate")
                validateCertificate(cert)
                result += cert; cursor = cert.end
            }
            if (result.isEmpty() || !padding(cursor.toLong(), end.toLong())) fail("证书链缺失、损坏或含未识别尾部")
            return result
        }
        private fun subject(cert: Der): Der {
            val tbs = children(children(cert)[0]); val first = if (tbs.firstOrNull()?.tag == 0xa0) 1 else 0
            if (tbs.size < first + 6 || tbs[first].tag != 2 || (1..5).any { tbs[first + it].tag != 0x30 }) fail("X.509 TBSCertificate 结构无效")
            return tbs[first + 4]
        }
        /** Validate X.509 grammar without depending on platform support for a certificate's curve. */
        private fun validateCertificate(cert: Der) {
            fun walk(node: Der, depth: Int = 0) {
                if (depth > 32) fail("证书 DER 嵌套过深")
                if (node.tag and 32 != 0) children(node).forEach { walk(it, depth + 1) }
                if (node.tag == 5 && node.body != node.end) fail("DER NULL 编码异常")
                if (node.tag == 2 && node.body == node.end) fail("DER INTEGER 为空")
                if (node.tag == 3 && (node.body == node.end || u8(node.body.toLong()) > 7)) fail("DER BIT STRING 编码异常")
                if (node.tag == 6 && (node.body == node.end || u8((node.end - 1).toLong()) and 128 != 0)) fail("DER OID 编码异常")
            }
            fun algorithm(node: Der) {
                val fields = children(node)
                if (node.tag != 0x30 || fields.size !in 1..2 || fields[0].tag != 6) fail("X.509 AlgorithmIdentifier 无效")
            }
            fun name(node: Der) {
                if (node.tag != 0x30) fail("X.509 Name 无效")
                for (set in children(node)) {
                    if (set.tag != 0x31 || set.body == set.end) fail("X.509 Name RDN 无效")
                    for (attribute in children(set)) {
                        val values = children(attribute)
                        if (attribute.tag != 0x30 || values.size != 2 || values[0].tag != 6) fail("X.509 Name attribute 无效")
                    }
                }
            }
            walk(cert)
            val top = children(cert); val tbs = children(top[0]); val first = if (tbs.firstOrNull()?.tag == 0xa0) 1 else 0
            subject(cert)
            if (first == 1) {
                val v = children(tbs[0])
                if (v.size != 1 || v[0].tag != 2 || v[0].end - v[0].body != 1 || u8(v[0].body.toLong()) > 2) fail("X.509 version 无效")
            }
            algorithm(tbs[first + 1]); algorithm(top[1])
            if (!data.copyOfRange(tbs[first + 1].start, tbs[first + 1].end).contentEquals(data.copyOfRange(top[1].start, top[1].end))) fail("X.509 签名算法字段不一致")
            name(tbs[first + 2]); name(tbs[first + 4])
            val validity = children(tbs[first + 3])
            if (validity.size != 2) fail("X.509 validity 无效")
            for (time in validity) {
                val length = if (time.tag == 23) 13 else if (time.tag == 24) 15 else fail("X.509 validity 时间类型无效")
                if (time.end - time.body != length || u8((time.end - 1).toLong()) != 90 || (time.body until time.end - 1).any { u8(it.toLong()) !in 48..57 }) fail("X.509 validity 时间编码无效")
            }
            val spki = children(tbs[first + 5])
            if (spki.size != 2 || spki[1].tag != 3 || spki[1].end - spki[1].body < 2) fail("X.509 SubjectPublicKeyInfo 无效")
            algorithm(spki[0])
        }
        private fun subjectOu(cert: Der): List<Pair<String, Int>> {
            val result = ArrayList<Pair<String, Int>>()
            for (set in children(subject(cert))) {
                if (set.tag != 0x31) fail("X.509 subject RDN 无效")
                for (attribute in children(set)) {
                    if (attribute.tag != 0x30) fail("X.509 subject attribute 无效")
                    val pair = children(attribute)
                    if (pair.size != 2 || pair[0].tag != 6) fail("X.509 subject OID 无效")
                    val oid = data.copyOfRange(pair[0].body, pair[0].end)
                    if (!oid.contentEquals(byteArrayOf(0x55, 4, 11))) continue
                    val v = pair[1]
                    val charset = when (v.tag) { 12 -> Charsets.UTF_8; 19, 22 -> Charsets.US_ASCII; 30 -> Charsets.UTF_16BE; else -> fail("X.509 OU 字符串类型未识别") }
                    val text = charset.newDecoder().decode(java.nio.ByteBuffer.wrap(data, v.body, v.end - v.body)).toString()
                    result += text to v.body
                }
            }
            return result
        }

        fun pe(): Result {
            range(0, 64)
            val p = u32(0x3c)
            range(p, 24)
            if (u32(p) != 0x4550L || u16(p + 4) != 0xaa64) fail("PE 签名或 ARM64 机器类型无效")
            val n = u16(p + 6); val optional = u16(p + 20); val op = p + 24
            if (n !in 1..96 || optional < 112) fail("PE 头大小无效")
            range(op, optional.toLong())
            if (u16(op) != 0x20b || u16(op + 68) !in listOf(10, 11, 12)) fail("不是 ARM64 EFI PE32+ 镜像")
            val imageSize = u32(op + 56); val headers = u32(op + 60); val entry = u32(op + 16)
            val sections = op + optional
            range(sections, n.toLong() * 40)
            if (headers < sections + n * 40 || headers > data.size || imageSize < headers) fail("PE header/image 范围无效")
            val directories = u32(op + 108)
            if (directories > (optional - 112) / 8) fail("PE 数据目录截断")
            if (directories > 4 && (u32(op + 144) != 0L || u32(op + 148) != 0L)) fail("带 PE 证书表的防回滚语义尚未明确，未视作无元数据")
            val rawRanges = ArrayList<Pair<Long, Long>>(); val virtualRanges = ArrayList<Pair<Long, Long>>()
            var entryFound = false
            repeat(n) { i ->
                val s = sections + i * 40L
                val virtual = u32(s + 8); val va = u32(s + 12); val size = u32(s + 16); val raw = u32(s + 20); val flags = u32(s + 36)
                range(raw, size)
                if (size > 0 && raw < headers || va > imageSize || maxOf(virtual, size) > imageSize - va) fail("PE 节范围无效")
                if (size > 0) rawRanges += raw to raw + size
                if (maxOf(virtual, size) > 0) virtualRanges += va to va + maxOf(virtual, size)
                if (entry >= va && entry - va < size && flags and 0x20000000 != 0L) entryFound = true
            }
            for (ranges in listOf(rawRanges, virtualRanges)) {
                val sorted = ranges.sortedBy { it.first }
                if (sorted.zipWithNext().any { (a, b) -> a.second > b.first }) fail("PE 节重叠，布局歧义")
            }
            if (!entryFound) fail("PE 入口未映射到可执行原始节")
            return Result.NoMetadata(Format.EFI_PE, listOf("ARM64 EFI PE32+ 节/入口范围有效，证书目录为空", "未在代码、资源、填充或字符串中猜测 Qualcomm 防回滚值"))
        }
    }
}
