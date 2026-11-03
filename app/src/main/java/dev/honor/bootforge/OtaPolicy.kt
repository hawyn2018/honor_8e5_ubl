package dev.honor.bootforge

import dev.honor.bootforge.core.AblRollbackInspector
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Platform boot roles, never a device-model/offset allowlist or all A/B partitions. */
object OtaPolicy {
    val bootRoles = linkedMapOf(
        "xbl" to "Qualcomm eXtensible Boot Loader", "xbl_config" to "XBL authenticated configuration",
        "uefi" to "XBL APPSBL/UEFI stage (observed before ABL)", "sbl1" to "secondary boot loader stage 1",
        "sbl2" to "secondary boot loader stage 2", "sbl3" to "secondary boot loader stage 3",
        "tz" to "TrustZone boot firmware", "hyp" to "hypervisor boot firmware",
        "devcfg" to "boot device configuration", "aop" to "always-on processor boot firmware",
        "aop_config" to "AOP boot configuration", "rpm" to "resource power manager boot firmware",
        "pmic" to "PMIC boot firmware", "qupfw" to "QUP boot firmware",
        "uefisecapp" to "UEFI secure application", "storsec" to "secure storage boot firmware",
        "keymaster" to "trusted keymaster boot application", "cmnlib" to "trusted common library",
        "cmnlib64" to "trusted 64-bit common library", "featenabler" to "boot feature-enabler firmware",
        "shrm" to "authenticated SHRM load before UEFI (boot evidence L152-155)",
        "hyp_ac_config" to "authenticated HYP_AC_CFG before UEFI (L230-233)",
        "dcp_dtb" to "authenticated DCP_DTB before UEFI (L246-249)",
        "dcp" to "authenticated DCP before UEFI (L250-253)",
        "tz_ac_config" to "authenticated TZ_AC_CFG before UEFI (L274-277)",
        "soccp_dtb" to "authenticated SOCCP_DTB before UEFI (L278-281)",
        "soccp" to "authenticated SOCCP before UEFI (L282-285)",
        "soccp_debug" to "authenticated SOCCP_DEBUG before UEFI (L286-289)",
        "soccp_dcd" to "authenticated SOCCP_DCD before UEFI (L291-294)",
        "pdp_cdb" to "authenticated PDP_CDB before UEFI (L295-298)",
        "pdp" to "authenticated PDP before UEFI (L299-302)",
        "cpucp_dtb" to "authenticated CPUCP_DTB before UEFI (L304-307)",
        "cpucp" to "CPUCPFW partition alias; authenticated before UEFI (L308-311)",
        "cpucpfw" to "authenticated CPUCPFW before UEFI (L308-311)",
        "tz_qti_config" to "authenticated TZ_QTI_Config before UEFI (L313-316)",
        "sec" to "authenticated SEC before UEFI (L333-336)",
    )
    const val BOOT_EVIDENCE = "UefiLog17.canonical.txt sha256=d9c98036f88289b11a7294fe9989dde9674b5afd09ec1e97ba5daa0fca9fede6; pre-UEFI Auth Metadata -> Segments hash check -> Image Loaded"
    val referenceRoles = linkedMapOf("xbl_ac_config" to "XBL access-control configuration", "imagefv" to "UEFI firmware-volume payload",
        "soccp_config" to "SOCCP configuration", "dpm" to "boot power firmware", "tme" to "trusted management engine firmware")

    /** Supplemental literal evidence for known firmware roles; not a control-flow proof. */
    fun referenceOffsets(bytes: ByteArray, base: String): List<Int> {
        require(Regex("[a-z0-9_]+").matches(base))
        val result = linkedSetOf<Int>()
        for (value in listOf(base, "${base}_a", "${base}_b", "$base.elf", "$base.mbn")) for (charset in listOf(Charsets.US_ASCII, Charsets.UTF_16LE)) {
            val pattern = (value + "\u0000").toByteArray(charset)
            var at = 0
            while (at <= bytes.size - pattern.size && result.size < 32) {
                if (bytes[at] == pattern[0] && pattern.indices.all { bytes[at + it] == pattern[it] }) {
                    val width = if (charset == Charsets.UTF_16LE) 2 else 1
                    val previous = if (at < width) 0 else bytes[at - width].toInt() and 255
                    if (previous !in 48..57 && previous !in 65..90 && previous !in 97..122 && previous != 95) result += at
                }
                at++
            }
        }
        return result.toList()
    }
    fun folder(clickedAt: Long, zone: TimeZone): String {
        require(clickedAt > 0)
        return "hnubl_ota_backup_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = zone }.format(Date(clickedAt))
    }
    data class Arb(val value: Long, val detail: String)
    fun arb(result: AblRollbackInspector.Result): Arb = when (result) {
        is AblRollbackInspector.Result.Parsed -> Arb(result.counters.maxOfOrNull { it.value } ?: 0,
            result.counters.joinToString("; ") { "${it.authority}=${it.value} (${it.source}, offset=${it.fileOffset})" }.ifEmpty { "没有可识别计数，按 0" })
        is AblRollbackInspector.Result.NoMetadata -> Arb(0, "${result.format} 没有防回滚元数据，按 0：${result.evidence.joinToString("; ")}")
        is AblRollbackInspector.Result.Invalid -> Arb(0, "未读出防回滚计数，按 0：${result.reason}")
    }
    fun requiresBootChainCopy(values: List<Arb>) = values.any { it.value != 0L }
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Structural signed-container evidence independent of whether an ARB field is understood. */
    fun signedContainer(bytes: ByteArray): Boolean = runCatching {
        fun u32(at: Int): Long { require(at >= 0 && at <= bytes.size - 4); return (0..3).fold(0L) { n, i -> n or ((bytes[at + i].toLong() and 255) shl (8 * i)) } }
        fun u64(at: Int): Long { val lo = u32(at); val hi = u32(at + 4); require(hi <= 0x7fffffff); return lo or (hi shl 32) }
        if (bytes.size >= 64 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(127, 69, 76, 70))) {
            val cls = bytes[4].toInt(); require(cls in 1..2 && bytes[5].toInt() == 1)
            val ph = if (cls == 1) u32(28) else u64(32)
            val stride = if (cls == 1) 32 else 56
            val countAt = if (cls == 1) 44 else 56
            val count = (bytes[countAt].toInt() and 255) or ((bytes[countAt + 1].toInt() and 255) shl 8)
            require(count in 1..1024 && ph >= 0 && ph + count.toLong() * stride <= bytes.size)
            (0 until count).any { i ->
                val p = (ph + i.toLong() * stride).toInt()
                val flags = u32(p + if (cls == 1) 24 else 4)
                val off = if (cls == 1) u32(p + 4) else u64(p + 8)
                val size = if (cls == 1) u32(p + 16) else u64(p + 32)
                ((flags ushr 24) and 7) == 2L && size >= 40 && off >= 0 && off <= bytes.size && size <= bytes.size - off
            }
        } else {
            // Qualcomm MBN v3/v5 header: image ID, version, image/code/signature/cert sizes.
            val version = u32(4)
            if (version in listOf(7L, 8L)) {
                val header = if (version == 8L) 56 else 40
                val common = u32(8); val qti = u32(12); val oem = u32(16); val hashes = u32(20)
                require(u32(0) == 0L && common <= 65536 && qti <= 65536 && oem <= 65536 && hashes in 1..1048576L)
                var cursor = header + common + qti + oem + hashes
                require(cursor <= bytes.size)
                var signing = 0
                for (at in if (version == 8L) listOf(24, 32, 40, 48) else listOf(24, 32)) {
                    val signature = u32(at); val cert = u32(at + 4)
                    require((signature == 0L) == (cert == 0L) && signature + cert <= bytes.size - cursor)
                    if (signature > 0) { require(bytes[(cursor + signature).toInt()] == 0x30.toByte()); signing++ }
                    cursor += signature + cert
                }
                signing > 0
            } else if (version !in listOf(3L, 5L)) false else {
                val image = u32(16); val code = u32(20); val signature = u32(28); val cert = u32(36)
                code > 0 && signature > 0 && cert > 0 && code + signature + cert <= image && image <= bytes.size - 40L
            }
        }
    }.getOrDefault(false)
}
