package dev.honor.bootforge.core

import net.hawyn.ablefisp.api.AblEfisp
import net.hawyn.ablefisp.io.ImageIo
import net.hawyn.ablefisp.patch.PatchMode
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File-only adapter around the supplied ABL-EFISP engine; never accesses a partition. */
object EfispImagePatcher {
    const val FASTBOOT_TEXT = "device freedom (github hawyn2018)"
    const val BOOT_WARNING = "your device has been unlocked and now freedom\n" +
        "unlocktool opensource address\n" +
        "https://github.com/hawyn2018/honor_8e5_ubl"

    data class Result(
        val bytes: Long,
        val sha256: String,
        val sourceSha256: String,
        val sourceFormat: String,
        val fakeLock: Boolean,
        val patchCount: Int,
        val textChanges: Int,
        val notes: List<String>,
    )

    @JvmOverloads
    fun patch(input: File, output: File, fakeLock: Boolean, onLog: (String) -> Unit = {}): Result {
        // A diagnostic consumer must not change whether a verified image is published.
        val log: (String) -> Unit = { message -> runCatching { onLog("[ABL修补] $message") } }
        log("开始 · ${if (fakeLock) "假回锁模式" else "仅解锁模式"}")
        return try {
            patchVerified(input, output, fakeLock, log)
        } catch (error: Exception) {
            log("修补失败：${error.message ?: error.javaClass.simpleName}")
            throw error
        }
    }

    private fun patchVerified(input: File, output: File, fakeLock: Boolean, log: (String) -> Unit): Result {
        require(input.isFile) { "ABL 源文件缺失" }
        require(input.canonicalFile != output.canonicalFile) { "ABL 源文件与 efisp 输出须使用不同路径" }
        val source = input.inputStream().use { ImageIo.readBounded(it) }
        require(source.isNotEmpty()) { "ABL 源文件为空" }
        val sourceHash = ImageIo.sha256(source)
        log("输入 ${input.name} · ${source.size} 字节")
        log("源 SHA-256=$sourceHash")
        val mode = if (fakeLock) PatchMode.FREEDOM_FAKE_LOCK else PatchMode.FREEDOM
        log("正在提取 PE、分析并应用核心补丁")
        val patched = AblEfisp.patch(source, mode)
        check(patched.sourceSha256 == sourceHash) { "ABL 来源哈希与修补结果不一致" }
        log("输入格式=${patched.sourceFormat} · PE 大小=${patched.imageSize} 字节 · 核心补丁=${patched.patches.size} 项")
        log("核心修补后 SHA-256（文字定制前）=${patched.outputSha256}")
        log("偏移说明：RVA 为 PE 相对虚拟地址；PE 文件偏移相对于提取后的 PE/efisp 文件")

        log("正在扫描 Fastboot 与开机提示文字")
        val catalogue = AblEfisp.scanText(patched.output)
        val unlocked = catalogue.entries.singleOrNull { it.id == "fastboot-unlocked" }
            ?: error("当前 ABL 的 Fastboot 解锁文字未形成唯一匹配，文字定制已停止")
        check(unlocked.editable) { "Fastboot 文字暂不可编辑：${unlocked.reason.orEmpty()}" }
        check(FASTBOOT_TEXT.toByteArray(Charsets.US_ASCII).size <= unlocked.maxBytes) {
            "Fastboot 定制文字需要 ${FASTBOOT_TEXT.length} 字节，当前 ABL 上限为 ${unlocked.maxBytes} 字节"
        }
        log("Fastboot 标签：${FASTBOOT_TEXT.toByteArray(Charsets.US_ASCII).size} 字节 · 文字容量上限=${unlocked.maxBytes} 字节 · 关闭自动换行")
        val menuBefore = catalogue.entries.filter { it.id.startsWith("resource-") }.associate { it.id to it.text }
        val edits = linkedMapOf(unlocked.id to FASTBOOT_TEXT)
        val warnings = catalogue.entries.filter { entry ->
            entry.id.startsWith("resource-") &&
                (entry.text.contains("has been unlocked", ignoreCase = true) || entry.title.startsWith("开机解锁提示"))
        }
        if (!fakeLock) {
            check(warnings.isNotEmpty()) { "当前 ABL 中未找到开机解锁提示资源，文字定制已停止" }
            warnings.forEach { entry ->
                check(entry.editable) { "开机提示暂不可编辑：${entry.reason.orEmpty()}" }
                check(BOOT_WARNING.toByteArray(Charsets.US_ASCII).size <= entry.maxBytes) {
                    "开机定制文字需要 ${BOOT_WARNING.length} 字节，当前资源上限为 ${entry.maxBytes} 字节"
                }
                edits[entry.id] = BOOT_WARNING
            }
        } else {
            log("假回锁模式：保留原有开机提示资源文字")
        }
        val customized = AblEfisp.editText(patched.output, edits, autoWrapFastboot = false)
        val verified = AblEfisp.scanText(customized.output).entries.associateBy { it.id }
        check(verified[unlocked.id]?.text == FASTBOOT_TEXT) {
            "Fastboot 定制文字回读不一致"
        }
        if (fakeLock) {
            check(menuBefore.all { (id, text) -> verified[id]?.text == text }) { "假回锁模式的开机菜单文字发生意外变化" }
        } else {
            check(warnings.all { verified[it.id]?.text == BOOT_WARNING }) { "开机定制文字回读不一致" }
        }
        log("Fastboot 标签与开机提示文字回读校验通过")
        customized.changes.forEachIndexed { index, change ->
            log("文字定制 ${index + 1}/${customized.changes.size}：${change.title} · ${change.id}")
            log("旧 RVA ${hex(change.oldRva)} → 新 RVA ${hex(change.newRva)} · 输出文件偏移 ${hex(change.fileOffset.toLong())}")
            log("旧文字=${quoted(change.oldText)}")
            log("新文字=${quoted(change.newText)}")
        }
        // The text engine keeps original offsets. Preserve every core edit in the published image.
        patched.patches.forEachIndexed { index, match ->
            val expected = match.after.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            check(match.fileOffset >= 0 && match.fileOffset.toLong() + expected.size <= customized.output.size &&
                customized.output.copyOfRange(match.fileOffset, match.fileOffset + expected.size).contentEquals(expected)) {
                "文字定制后核心修补结果不一致：${match.name}"
            }
            log("补丁 ${index + 1}/${patched.patches.size}：${match.name} · RVA ${hex(match.rva)} · PE 文件偏移 ${hex(match.fileOffset.toLong())} · ${if (match.alreadyApplied) "原已应用" else "已应用"} · 文字定制后字节校验通过")
            log("修改前字节=${match.before}")
            log("修改后字节=${match.after}")
        }
        val notes = (patched.notes + catalogue.notes + customized.notes).distinct()
        notes.forEach { note ->
            val clarification = if (note.contains("Fastboot") && note.contains("自动分行"))
                "（扫描器通用说明；本次已关闭自动换行）" else ""
            log("引擎说明：$note$clarification")
        }

        val image = customized.output
        val hash = ImageIo.sha256(image)
        val parent = checkNotNull(output.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "efisp 输出目录创建失败" }
        val temporary = File.createTempFile(".efisp-generated-", ".tmp", parent)
        try {
            log("正在写入临时文件并校验大小、SHA-256")
            temporary.outputStream().use { stream -> stream.write(image); stream.fd.sync() }
            check(temporary.length() == image.size.toLong() && ImageIo.sha256(temporary.readBytes()) == hash) {
                "efisp 临时文件写入回读不一致"
            }
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            check(output.isFile && output.length() == image.size.toLong() && ImageIo.sha256(output.readBytes()) == hash) {
                "efisp 输出文件写入回读不一致"
            }
            log("输出文件回读校验通过 · ${output.name} · ${image.size} 字节")
            log("最终 efisp SHA-256=$hash")
        } finally {
            temporary.delete()
        }
        log("修补完成 · 核心补丁=${patched.patches.size} 项 · 文字定制=${customized.changes.size} 项")
        return Result(image.size.toLong(), hash, sourceHash, patched.sourceFormat, fakeLock,
            patched.patches.size, customized.changes.size,
            notes)
    }

    private fun hex(value: Long): String = "0x${value.toString(16).uppercase()}"
    private fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\")
        .replace("\r", "\\r").replace("\n", "\\n").replace("\"", "\\\"") + "\""
}
