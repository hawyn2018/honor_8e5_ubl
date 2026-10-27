package net.hawyn.ablefisp.patch

import java.security.MessageDigest

enum class PatchFailureKind {
    STRUCTURE_INVALID,
    NO_CORE_FEATURE,
    INCOMPLETE_CORE_FEATURE,
    AMBIGUOUS_CORE_FEATURE,
}

class PatchException(
    message: String,
    val kind: PatchFailureKind = PatchFailureKind.STRUCTURE_INVALID,
) : IllegalArgumentException(message)

internal fun ByteArray.range(offset: Int, count: Int) {
    if (offset < 0 || count < 0 || offset.toLong() + count > size.toLong()) {
        throw PatchException("镜像结构超出文件边界（0x${offset.toString(16)}）。")
    }
}

internal fun ByteArray.u8(at: Int): Int { range(at, 1); return this[at].toInt() and 255 }
internal fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
internal fun ByteArray.u24(at: Int): Int = u16(at) or (u8(at + 2) shl 16)
internal fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
internal fun ByteArray.word(at: Int): Int = u32(at).toInt()
internal fun ByteArray.u64(at: Int): Long = u32(at) or (u32(at + 4) shl 32)
internal fun ByteArray.putWord(at: Int, value: Int) {
    range(at, 4)
    repeat(4) { this[at + it] = (value ushr (it * 8)).toByte() }
}
internal fun ByteArray.matches(at: Int, needle: ByteArray): Boolean =
    at >= 0 && at.toLong() + needle.size <= size && needle.indices.all { this[at + it] == needle[it] }
internal fun ByteArray.occurrences(needle: ByteArray): List<Int> {
    if (needle.isEmpty()) return emptyList()
    val result = ArrayList<Int>()
    var at = 0
    while (at <= size - needle.size) {
        if (this[at] == needle[0] && matches(at, needle)) result += at
        at++
    }
    return result
}
internal fun ByteArray.asciiZ(at: Int, max: Int = 512): String? {
    if (at !in indices) return null
    var end = at
    while (end < size && end - at < max && this[end] != 0.toByte()) {
        val c = u8(end)
        if (c !in 32..126 && c !in listOf(9, 10, 13)) return null
        end++
    }
    return if (end < size && this[end] == 0.toByte()) copyOfRange(at, end).toString(Charsets.US_ASCII) else null
}
internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).hex(false)
internal fun ByteArray.hex(spaces: Boolean = true): String = joinToString(if (spaces) " " else "") { "%02x".format(it.toInt() and 255) }
internal fun signExtend(value: Long, bits: Int): Long = (value shl (64 - bits)) shr (64 - bits)
internal fun align(value: Int, boundary: Int): Int = ((value.toLong() + boundary - 1) / boundary * boundary).let {
    if (it > Int.MAX_VALUE) throw PatchException("镜像对齐长度超出范围。") else it.toInt()
}
internal fun Long.asBoundedInt(label: String): Int = if (this < 0 || this > Int.MAX_VALUE) {
    throw PatchException("$label 超出支持的镜像长度。")
} else toInt()
