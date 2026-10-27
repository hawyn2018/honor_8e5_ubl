package dev.honor.bootforge.core

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import dev.honor.bootforge.BackupFileInfo
import dev.honor.bootforge.BackupDiskStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.TimeZone

/** Slot properties describe the running slot; bootctl's next active slot is deliberately unused. */
object CurrentBackupSlot {
    fun select(values: Map<String, String>): String {
        val selected = listOf("SLOT_SUFFIX", "SLOT", "CMDLINE_SLOT_SUFFIX", "BOOTCONFIG_SLOT_SUFFIX")
            .mapNotNull { key -> values[key]?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
                when (value) {
                    "a", "_a" -> "_a"
                    "b", "_b" -> "_b"
                    else -> error("当前运行槽位字段 $key 无效：$value")
                }
            } }
        check(selected.isNotEmpty() && selected.distinct().size == 1) { "当前运行槽位缺失或多个来源不一致" }
        return selected.first()
    }
}

data class BackupPartitionDescriptor(
    val name: String,
    val byName: String,
    val device: String,
    val majorMinor: String,
    val bytes: Long,
    val sectors512: Long,
    val start512: Long,
    val logicalSectorBytes: Long,
)

data class SlotBackupResult(
    val slot: String, val directory: String, val files: List<BackupFileInfo>,
    val status: String = "complete", val warnings: List<String> = emptyList(),
    val diskStatuses: List<BackupDiskStatus> = emptyList(),
)
class SlotBackupFailure(message: String, cause: Exception, val result: SlotBackupResult) : IllegalStateException(message, cause)
data class SlotBackupProgress(
    val stage: String,
    val progress: Float,
    val slot: String = "",
    val directory: String = "",
    val files: List<BackupFileInfo> = emptyList(),
    val status: String = "running",
    val warnings: List<String> = emptyList(),
    val diskStatuses: List<BackupDiskStatus> = emptyList(),
)

/** Root only reads block devices. All writes target new image files and the Downloads provider. */
class SlotPartitionBackup(
    private val context: Context,
    private val requestedAt: Date = Date(),
    private val requestedTimeZone: TimeZone = TimeZone.getDefault(),
) {
    private val root = File(context.filesDir, "slot-backup")
    private val session = File(root, "${UUID.randomUUID()}.partial").apply { check(mkdirs()) }
    private val busybox = File(session, "busybox")
    private val gpttool = File(session, "gpttool")
    private val events = File(session, "backup-events.log")
    private val images = listOf("init_boot", "abl")
    private val knownKeys = setOf("SLOT_SUFFIX", "SLOT", "CMDLINE_SLOT_SUFFIX", "BOOTCONFIG_SLOT_SUFFIX")

    suspend fun run(onProgress: suspend (SlotBackupProgress) -> Unit): SlotBackupResult {
        val warnings = mutableListOf<String>()
        val tables = mutableListOf<GptSnapshot>()
        val records = mutableListOf<BackupFileInfo>()
        val originalUris = mutableListOf<Uri>()
        var originals = emptyList<BackupFileInfo>()
        var legacyDirectoryIdentity: String? = null
        var slot = ""
        val date = Date(requestedAt.time)
        val folder = BackupNaming.folderName(date, requestedTimeZone)
        var directory = ""
        var descriptors = emptyList<BackupPartitionDescriptor>()
        fun warning(message: String) {
            if (message !in warnings) warnings += message
            runCatching { log("WARNING: $message") }
        }
        fun diskStatuses() = tables.map { BackupDiskStatus(it.disk.node, it.status, it.warnings.joinToString("；")) }
        suspend fun progress(stage: String, value: Float, status: String = "running") {
            runCatching { log(stage) }
            onProgress(SlotBackupProgress(stage, value, slot, directory, records.toList(), status, warnings.toList(), diskStatuses()))
        }
        fun verifyOriginalExports() {
            if (Build.VERSION.SDK_INT >= 29) verifyMediaStoreOriginals(folder, originalUris, originals)
            else {
                val expected = checkNotNull(legacyDirectoryIdentity)
                check(legacyIdentity(legacyDirectory(folder)) == expected) { "原始备份目录已被替换" }
                originals.forEach { original ->
                    val target = shellQuote(File(legacyDirectory(folder), original.name).path)
                    val stored = query("export-original-hash", "[ -f $target ] && [ ! -L $target ] || exit 50; tool sha256sum $target")
                        .trim().substringBefore(' ')
                    check(stored == original.sha256) { "已保存的 ${original.name} 发生变化" }
                }
            }
        }
        fun publishAdditions(files: List<File>) {
            if (files.isEmpty()) return
            if (Build.VERSION.SDK_INT >= 29) publishMediaStore(folder, files, newDirectory = false)
            else publishLegacyAdditions(folder, checkNotNull(legacyDirectoryIdentity), originals, files)
            records += files.map { BackupFileInfo(it.name, it.length(), hash(it.inputStream())) }
        }
        try {
            progress("以 Root 识别当前运行槽位", 0.03f)
            val bundledBusybox = File(context.applicationInfo.nativeLibraryDir, "libbootforge_busybox.so")
            check(bundledBusybox.isFile && bundledBusybox.length() > 0) { "缺少内置 BusyBox 备用工具" }
            // BusyBox dispatches by argv[0]; an extracted lib*.so name is not a multicall entry point.
            bundledBusybox.copyTo(busybox)
            check(busybox.setExecutable(true, true) && busybox.canExecute() && busybox.length() == bundledBusybox.length()) {
                "内置 BusyBox 备用工具准备失败"
            }
            slot = readSlot()
            descriptors = readDescriptors()
            check(readSlot() == slot) { "读取分区信息期间运行槽位发生变化" }
            val originalRecords = mutableListOf<BackupFileInfo>()
            descriptors.forEachIndexed { index, partition ->
                progress("备份 ${partition.name} 原始分区", 0.1f + index * 0.1f)
                val file = File(session, "${partition.name}.img")
                copyPartition(partition, file)
                val digest = hash(file.inputStream())
                check(digest == sourceHash(partition)) { "${partition.name} 备份与分区 SHA-256 不一致" }
                originalRecords += BackupFileInfo(file.name, file.length(), digest)
            }
            check(readSlot() == slot && readDescriptors() == descriptors) { "备份前后槽位或分区几何信息发生变化" }
            val activeSlotFile = DualSlotBackupFiles.writeActiveSlotFile(session, slot)
            originalRecords += BackupFileInfo(activeSlotFile.name, activeSlotFile.length(), hash(activeSlotFile.inputStream()))
            originals = originalRecords.toList()
            progress("将双槽原始镜像与当前槽位标记复制到 Download 并回读校验", 0.59f)
            val originalFiles = originals.map { File(session, it.name) }
            if (Build.VERSION.SDK_INT >= 29) originalUris += publishMediaStore(folder, originalFiles, newDirectory = true)
            else legacyDirectoryIdentity = publishLegacyOriginals(folder, originalFiles)
            records += originals
            directory = "内部存储/Download/$folder"
            // Original images are durable and visible before partition-table export begins.
            progress("双槽原始镜像与槽位标记已保存，正在原样导出主备 GPT", 0.63f)
            val disks = try { readGptDisks(slot) } catch (error: Exception) {
                warning("GPT 磁盘发现未完成：${error.message.orEmpty()}")
                emptyList()
            }
            val helperReady = try { prepareGptTool(); true } catch (error: Exception) {
                warning("GPT 原始导出工具准备失败：${error.message.orEmpty()}")
                false
            }
            disks.forEachIndexed { index, disk ->
                progress("原样导出 ${disk.node} 主备 GPT（${index + 1}/${disks.size}）", 0.64f)
                val table = if (helperReady) captureGpt(disk) else GptSnapshot(disk, File(session, "gpt_${disk.node}.raw"),
                    warnings = listOf("内置 GPT 原始导出工具不可用"))
                tables += table
                table.warnings.forEach { warning("${disk.node}：$it") }
            }
            val gptFiles = tables.flatMap { it.exportedFiles() }
            verifyOriginalExports()
            try {
                publishAdditions(gptFiles)
                tables.indices.forEach { index ->
                    val table = tables[index]
                    tables[index] = table.copy(publiclySaved = table.exportedFiles().isNotEmpty())
                }
            } catch (error: Exception) {
                val message = "GPT 文件公开保存未完整完成，本地证据保留：${error.message.orEmpty()}"
                warning(message)
                tables.indices.forEach { index ->
                    val table = tables[index]
                    if (table.exportedFiles().isNotEmpty()) tables[index] = table.copy(
                        publiclySaved = false, status = "partial", warnings = table.warnings + message)
                }
            }
            progress("核对原始分区与已保存镜像", 0.76f)
            descriptors.forEach { partition ->
                check(sourceHash(partition) == records.single { it.name == "${partition.name}.img" }.sha256) {
                    "${partition.name} 分区在备份期间发生变化"
                }
            }
            check(readSlot() == slot && readDescriptors() == descriptors) { "备份完成前槽位或分区信息发生变化" }
            tables.indices.forEach { index ->
                val table = tables[index]
                if (table.metadata != null && helperReady) {
                    val readback = File(session, "gpt_${table.disk.node}.readback.raw")
                    try {
                        exportGpt(table.disk, readback, "readback")
                        if (hash(readback.inputStream()) != table.sha256) {
                            val message = "${table.disk.node} GPT 回读字节与首次快照不同，首次与回读证据均已保留"
                            warning(message)
                            tables[index] = table.copy(warnings = table.warnings + message,
                                status = if (table.status == "partial") "partial" else "complete_with_warnings")
                        }
                    } catch (error: Exception) {
                        val message = "${table.disk.node} GPT 回读未完成：${error.message.orEmpty()}"
                        warning(message)
                        tables[index] = table.copy(warnings = table.warnings + message,
                            status = if (table.status == "partial") "partial" else "complete_with_warnings")
                    }
                }
            }
            if (disks.isNotEmpty()) try {
                if (readGptDisks(slot) != disks) warning("备份期间 GPT 磁盘映射发生变化，已保留首次快照")
            } catch (error: Exception) { warning("GPT 磁盘映射复核未完成：${error.message.orEmpty()}") }
            verifyOriginalExports()
            progress("保存 GPT 回读证据与备份清单", 0.84f)
            val readbackEvidence = session.listFiles().orEmpty().filter { it.name.endsWith(".readback.raw") && it.length() > 0 }
            try { publishAdditions(readbackEvidence) } catch (error: Exception) {
                warning("GPT 回读证据公开保存未完整完成，本地副本保留：${error.message.orEmpty()}")
            }
            val status = if (warnings.isEmpty()) "complete" else "complete_with_warnings"
            val gptManifest = writeGptManifest(slot, tables, warnings, status)
            val diagnostic = writeDiagnostics("diagnostics.log", warnings)
            val preManifestRecords = records + listOf(gptManifest, diagnostic).map { BackupFileInfo(it.name, it.length(), hash(it.inputStream())) }
            writeManifest(slot, descriptors, tables, preManifestRecords, date, status, warnings)
            val manifest = File(session, "backup.json")
            val sums = File(session, "SHA256SUMS")
            sums.writeText((preManifestRecords + BackupFileInfo(manifest.name, manifest.length(), hash(manifest.inputStream())))
                .joinToString("\n", postfix = "\n") { "${it.sha256}  ${it.name}" })
            publishAdditions(listOf(gptManifest, diagnostic, manifest, sums))
            progress(if (warnings.isEmpty()) "分区镜像与主备 GPT 备份完成" else "分区镜像备份完成，GPT 存在提示", 1f, status)
            return SlotBackupResult(slot, directory, records.toList(), status, warnings.toList(), diskStatuses())
        } catch (error: Exception) {
            runCatching { log("ERROR: ${error.stackTraceToString()}") }
            // Keep the original exception meaningful, and export evidence without removing
            // already-published images. The app-private session also remains available.
            val diagnostic = runCatching { writeDiagnostics("diagnostics-error.log", warnings + "失败：${error.message.orEmpty()}") }
                .onFailure { warning("诊断日志写入未完成：${it.message.orEmpty()}") }.getOrNull()
            val partial = runCatching { writePartialManifest(slot, descriptors, tables, records, warnings, error) }
                .onFailure { warning("部分完成清单写入未完成：${it.message.orEmpty()}") }.getOrNull()
            val evidence = listOfNotNull(diagnostic, partial)
            try {
                check(evidence.isNotEmpty()) { "应用内诊断文件写入失败，原会话仍保留" }
                if (directory.isEmpty()) {
                    if (Build.VERSION.SDK_INT >= 29) publishMediaStore(folder, evidence, newDirectory = true)
                    else legacyDirectoryIdentity = publishLegacyOriginals(folder, evidence)
                    directory = "内部存储/Download/$folder"
                    records += evidence.map { BackupFileInfo(it.name, it.length(), hash(it.inputStream())) }
                } else publishAdditions(evidence)
            } catch (exportError: Exception) {
                warning("诊断日志公开导出未完成；应用内证据目录 ${session.name} 已保留：${exportError.message.orEmpty()}")
            }
            val result = SlotBackupResult(slot, directory, records.toList(), "partial", warnings.toList(), diskStatuses())
            onProgress(SlotBackupProgress("备份部分完成，已保存文件保留", 0f, slot, directory, records.toList(), "partial", warnings.toList(), diskStatuses()))
            throw SlotBackupFailure(error.message ?: error.javaClass.simpleName, error, result)
        }
    }

    private fun readSlot(): String {
        val result = query("slot", """
            printf 'SLOT_SUFFIX='; getprop ro.boot.slot_suffix
            printf 'SLOT='; getprop ro.boot.slot
            printf 'CMDLINE_SLOT_SUFFIX='
            for ARG in ${'$'}(tool cat /proc/cmdline); do
                case "${'$'}ARG" in androidboot.slot_suffix=*) printf '%s' "${'$'}{ARG#*=}";; esac
            done
            printf '\nBOOTCONFIG_SLOT_SUFFIX='
            if [ -r /proc/bootconfig ]; then
                tool awk '${'$'}1 == "androidboot.slot_suffix" {gsub(/\"/, "", ${'$'}3); printf "%s", ${'$'}3}' /proc/bootconfig
            fi
            printf '\n'
        """.trimIndent())
        val properties = parseLines(result).filterKeys { it in knownKeys }
        return CurrentBackupSlot.select(properties)
    }

    private fun readDescriptors(): List<BackupPartitionDescriptor> {
        val script = DualSlotBackupFiles.partitions.joinToString("\n") { name ->
            """
                SRC=
                LINK=
                for BASE in /dev/block/by-name /dev/block/bootdevice/by-name; do
                    CANDIDATE=${'$'}(tool readlink -f "${'$'}BASE/$name") || continue
                    [ -b "${'$'}CANDIDATE" ] || continue
                    case "${'$'}CANDIDATE" in /dev/block/*) :;; *) exit 21;; esac
                    [ -z "${'$'}SRC" ] || [ "${'$'}SRC" = "${'$'}CANDIDATE" ] || exit 22
                    SRC=${'$'}CANDIDATE
                    LINK=${'$'}BASE/$name
                done
                [ -n "${'$'}SRC" ] || { printf 'ERROR=missing_$name\n'; exit 23; }
                NODE=${'$'}{SRC##*/}
                SYS=${'$'}(tool readlink -f "/sys/class/block/${'$'}NODE") || exit 24
                [ -f "${'$'}SYS/partition" ] || exit 25
                PARENT=${'$'}{SYS%/*}
                printf '$name.BY_NAME=%s\n$name.DEVICE=%s\n' "${'$'}LINK" "${'$'}SRC"
                printf '$name.MAJOR_MINOR='; tool cat "${'$'}SYS/dev" || exit 26
                printf '$name.BYTES='; tool blockdev --getsize64 "${'$'}SRC" || exit 27
                printf '$name.SECTORS_512='; tool cat "${'$'}SYS/size" || exit 28
                printf '$name.START_512='; tool cat "${'$'}SYS/start" || exit 29
                printf '$name.LOGICAL_SECTOR_BYTES='; tool cat "${'$'}PARENT/queue/logical_block_size" || exit 30
            """.trimIndent()
        }
        val values = parseLines(query("geometry", script))
        return DualSlotBackupFiles.partitions.map { name ->
            fun get(key: String) = values["$name.$key"] ?: error("$name 缺少分区参数 $key")
            fun number(key: String) = get(key).toLongOrNull()?.also { check(it >= 0) } ?: error("$name 分区参数 $key 无效")
            val descriptor = BackupPartitionDescriptor(name, get("BY_NAME"), get("DEVICE"), get("MAJOR_MINOR"),
                number("BYTES"), number("SECTORS_512"), number("START_512"), number("LOGICAL_SECTOR_BYTES"))
            check(descriptor.byName in listOf("/dev/block/by-name/$name", "/dev/block/bootdevice/by-name/$name"))
            check(Regex("/dev/block/[A-Za-z0-9_./-]+").matches(descriptor.device) && !descriptor.device.contains(".."))
            check(Regex("[0-9]+:[0-9]+").matches(descriptor.majorMinor))
            check(descriptor.bytes > 0 && Math.multiplyExact(descriptor.sectors512, 512L) == descriptor.bytes) { "$name 分区容量校验失败" }
            val sector = descriptor.logicalSectorBytes
            check(sector >= 512 && sector <= 65536 && sector and (sector - 1) == 0L && descriptor.bytes % sector == 0L)
            descriptor
        }.also { partitions -> check(partitions.map { it.majorMinor }.distinct().size == partitions.size) { "备份分区指向了同一设备" } }
    }

    private fun copyPartition(partition: BackupPartitionDescriptor, file: File) {
        val source = shellQuote(partition.device)
        val prefix = "[ -b $source ] || exit 31; "
        val system = RootShell.capture(prefix + "exec dd if=$source bs=1048576", file, File(session, "read-error.log"), 600)
        if (system.exitCode != 0 || file.length() != partition.bytes) {
            val bundled = RootShell.capture(prefix + "exec ${shellQuote(busybox.absolutePath)} dd if=$source bs=1048576", file,
                File(session, "read-error.log"), 600)
            check(bundled.exitCode == 0 && file.length() == partition.bytes) { "${partition.name} 原始分区读取失败：${bundled.output.take(1000)}" }
        }
        check(file.length() == partition.bytes) { "${partition.name} 备份长度与分区不符" }
        java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
    }

    private fun sourceHash(partition: BackupPartitionDescriptor): String {
        val output = query("source-hash", "[ -b ${shellQuote(partition.device)} ] || exit 32; tool sha256sum ${shellQuote(partition.device)}")
        return output.trim().substringBefore(' ').lowercase(Locale.ROOT).also { check(Regex("[a-f0-9]{64}").matches(it)) { "分区 SHA-256 读取失败" } }
    }

    private data class GptSnapshot(
        val disk: GptBackupDisk,
        val file: File,
        val metadata: GptRawSnapshotMetadata? = null,
        val sha256: String = "",
        val regionFiles: List<File> = emptyList(),
        val warnings: List<String> = emptyList(),
        val status: String = "failed",
        val publiclySaved: Boolean = false,
    ) {
        fun exportedFiles(): List<File> = (listOf(file) + regionFiles).filter { it.isFile && it.length() > 0 }
    }

    private fun prepareGptTool() {
        val source = File(context.applicationInfo.nativeLibraryDir, "libbootforge_gpttool.so")
        check(source.isFile && source.length() > 0) { "缺少内置 gpttool" }
        source.copyTo(gpttool)
        check(gpttool.setExecutable(true, true) && gpttool.canExecute() && source.length() == gpttool.length()) {
            "内置 GPT 原始导出工具准备失败"
        }
    }

    private fun readGptDisks(slot: String): List<GptBackupDisk> = GptBackupDisks.parse(
        query("gpt-discovery", GptBackupDisks.script),
        images.map { "$it$slot" }.toSet() + "userdata",
    )

    private fun exportGpt(disk: GptBackupDisk, file: File, label: String) {
        val command = toolPrelude() + "\n[ -b ${shellQuote(disk.device)} ] && [ -x ${shellQuote(gpttool.absolutePath)} ] || exit 61\n" +
            "exec ${shellQuote(gpttool.absolutePath)} export ${shellQuote(disk.device)}"
        // Binary stdout and diagnostic stderr are drained into different files.
        val result = RootShell.capture(command, file, File(session, "gpt-${disk.node}-$label.log"), 120)
        check(result.exitCode == 0) { "原始导出退出码 ${result.exitCode}：${result.output.take(1200)}" }
        check(file.length() > 0) { "GPT 原始导出为空" }
        java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
    }

    private fun captureGpt(disk: GptBackupDisk): GptSnapshot {
        val file = File(session, "gpt_${disk.node}.raw")
        return try {
            exportGpt(disk, file, "export")
            val metadata = GptRawSnapshot.validate(file, disk.bytes, disk.logicalSectorBytes)
            val warnings = metadata.warnings.toMutableList()
            val files = try { GptRawSnapshot.extractRegions(file, metadata, session, "gpt_${disk.node}") }
                catch (error: Exception) {
                    warnings += "GPT 独立区域文件提取未完成，完整原始快照保留：${error.message.orEmpty()}"
                    emptyList()
                }
            GptSnapshot(disk, file, metadata, hash(file.inputStream()), files, warnings,
                if (warnings.isEmpty()) "complete" else "complete_with_warnings")
        } catch (error: Exception) {
            runCatching { log("GPT ${disk.node}: ${error.stackTraceToString()}") }
            GptSnapshot(disk, file, sha256 = runCatching { if (file.isFile && file.length() > 0) hash(file.inputStream()) else "" }.getOrDefault(""),
                warnings = listOf("GPT 原始快照未完整导出或结构校验未通过：${error.message.orEmpty()}"))
        }
    }

    private fun gptMetadata(table: GptSnapshot): JSONObject {
        val result = JSONObject().put("file", if (table.file.isFile) table.file.name else JSONObject.NULL)
            .put("format", "HUBLGT22").put("status", table.status).put("publicly_saved", table.publiclySaved)
            .put("disk", table.disk.device).put("major_minor", table.disk.majorMinor)
            .put("disk_bytes", table.disk.bytes).put("logical_sector_bytes", table.disk.logicalSectorBytes)
            .put("sources", JSONArray(table.disk.sources)).put("bytes", table.file.length()).put("sha256", table.sha256)
            .put("tool_source", "bundled-gpttool").put("normalized", false).put("warnings", JSONArray(table.warnings))
        table.metadata?.let { metadata ->
            fun crc(value: GptRawCrcStatus) = JSONObject()
                .put("header_crc_valid", value.headerValid).put("header_crc_stored", value.headerStored).put("header_crc_computed", value.headerComputed)
                .put("entries_crc_valid", value.entriesValid).put("entries_crc_stored", value.entriesStored).put("entries_crc_computed", value.entriesComputed)
            result.put("disk_guid", metadata.diskGuid).put("entry_count", metadata.entryCount).put("entry_size", metadata.entrySize)
                .put("entry_bytes", metadata.entryBytes).put("primary", crc(metadata.primary)).put("backup", crc(metadata.backup))
                .put("attribute_difference_count", metadata.attributeDifferenceCount).put("attribute_difference_mask", metadata.attributeDifferenceMask)
                .put("other_entry_difference_count", metadata.otherEntryDifferenceCount)
                .put("regions", JSONArray().apply { metadata.regions.forEach { region ->
                    val expectedName = "gpt_${table.disk.node}_${region.name}.bin"
                    put(JSONObject().put("name", region.name).put("file", if (table.regionFiles.any { it.name == expectedName }) expectedName else JSONObject.NULL)
                        .put("disk_offset_bytes", region.diskOffsetBytes).put("snapshot_offset_bytes", region.snapshotOffsetBytes)
                        .put("length", region.length).put("sha256", region.sha256))
                } })
        }
        return result
    }

    private fun writeGptManifest(slot: String, tables: List<GptSnapshot>, warnings: List<String>, status: String): File =
        File(session, "gpt-backup.json").apply {
            writeText(JSONObject().put("format", "honor-ubl-gpt-raw-backup-v2.2").put("status", status)
                .put("requested_at_millis", requestedAt.time).put("running_slot", slot).put("partition_writes", false)
                .put("warnings", JSONArray(warnings)).put("tables", JSONArray().apply { tables.forEach { put(gptMetadata(it)) } }).toString(2) + "\n")
        }

    private fun log(message: String) {
        events.appendText("${System.currentTimeMillis()} $message\n")
    }

    private fun writeDiagnostics(name: String, warnings: List<String>): File = File(session, name).apply {
        writeText("honor_ubl ${dev.honor.bootforge.BuildConfig.VERSION_NAME} 分区备份诊断\npartition_writes=false\nrequested_at_millis=${requestedAt.time}\n" +
            "time_zone=${requestedTimeZone.id}\nsession=${session.name}\n\n" + warnings.joinToString("\n") { "WARNING: $it" } + "\n")
        session.listFiles().orEmpty().filter { it.extension == "log" && it != this && !it.name.startsWith("diagnostics") }
            .sortedBy { it.name }.forEach { source ->
                appendText("\n========== ${source.name} ==========\n")
                source.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    appendText(String(buffer, 0, count, Charsets.UTF_8))
                }
                appendText("\n")
            }
    }

    private fun writePartialManifest(slot: String, partitions: List<BackupPartitionDescriptor>, tables: List<GptSnapshot>,
        records: List<BackupFileInfo>, warnings: List<String>, error: Exception): File = File(session, "backup-partial.json").apply {
        writeText(JSONObject().put("format", "bootforge-slot-backup-v2.2").put("status", "partial")
            .put("requested_at_millis", requestedAt.time).put("time_zone", requestedTimeZone.id).put("running_slot", slot)
            .put("partition_writes", false).put("error", error.message ?: error.javaClass.simpleName)
            .put("warnings", JSONArray(warnings)).put("local_evidence_retained", true).put("session", session.name)
            .put("partitions", JSONArray(partitions.map { it.name }))
            .put("partition_tables", JSONArray().apply { tables.forEach { put(gptMetadata(it)) } })
            .put("published_files", JSONArray().apply { records.forEach { put(JSONObject().put("name", it.name).put("bytes", it.bytes).put("sha256", it.sha256)) } })
            .toString(2) + "\n")
    }

    private fun query(label: String, body: String): String {
        val result = RootShell.run(toolPrelude() + "\n" + body, File(session, "$label.log"), 600)
        check(result.exitCode == 0) { "备份读取检查失败（${result.exitCode}）：${result.output.take(1500)}" }
        return result.output
    }

    private fun toolPrelude(): String = """
        export LC_ALL=C
        BB=${shellQuote(busybox.absolutePath)}
        tool() {
            NAME=${'$'}1; shift
            SYSTEM=${'$'}(command -v "${'$'}NAME" 2>/dev/null)
            if [ -n "${'$'}SYSTEM" ]; then
                VALUE=${'$'}("${'$'}SYSTEM" "${'$'}@" 2>/dev/null)
                if [ "${'$'}?" = 0 ]; then printf '%s\n' "${'$'}VALUE"; return 0; fi
            fi
            "${'$'}BB" "${'$'}NAME" "${'$'}@"
        }
        [ "${'$'}(tool id -u)" = 0 ] || exit 20
    """.trimIndent()

    private fun writeManifest(slot: String, partitions: List<BackupPartitionDescriptor>, tables: List<GptSnapshot>, records: List<BackupFileInfo>,
        date: Date, status: String, warnings: List<String>) {
        val manifest = JSONObject().put("format", "bootforge-slot-backup-v2.2").put("status", status)
            .put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { timeZone = requestedTimeZone }.format(date))
            .put("requested_at_millis", requestedAt.time).put("time_zone", requestedTimeZone.id)
            .put("warnings", JSONArray(warnings)).put("local_evidence_retained", true)
            .put("running_slot", slot).put("partition_writes", false)
            .put("partitions", JSONArray().apply { partitions.forEach { p ->
                put(JSONObject().put("name", p.name).put("by_name", p.byName).put("device", p.device)
                    .put("major_minor", p.majorMinor).put("bytes", p.bytes).put("sectors_512", p.sectors512)
                    .put("start_512", p.start512).put("logical_sector_bytes", p.logicalSectorBytes))
            } }).put("partition_tables", JSONArray().apply { tables.forEach { put(gptMetadata(it)) } })
            .put("files", JSONArray().apply { records.forEach { record ->
                put(JSONObject().put("name", record.name).put("bytes", record.bytes).put("sha256", record.sha256))
            } })
        File(session, "backup.json").writeText(manifest.toString(2) + "\n")
    }

    @android.annotation.TargetApi(29)
    private fun publishMediaStore(folder: String, files: List<File>, newDirectory: Boolean): List<Uri> {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        if (newDirectory) resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$folder/"), null).use { cursor ->
            check(cursor != null && !cursor.moveToFirst()) { "本次备份目录已经存在，请重新开始以使用新的时间目录" }
        }
        val uris = mutableListOf<Uri>()
        try {
            files.forEach { file ->
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (file.extension == "json") "application/json" else "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = checkNotNull(resolver.insert(collection, values)) { "下载目录创建文件失败" }
                uris += uri
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null).use { cursor ->
                    check(cursor != null && cursor.moveToFirst() && cursor.getString(0) == file.name &&
                        cursor.getString(1) == "${Environment.DIRECTORY_DOWNLOADS}/$folder/") { "下载目录文件名称发生变化" }
                }
                val descriptor = checkNotNull(resolver.openFileDescriptor(uri, "w"))
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { out ->
                    file.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                    out.fd.sync()
                }
                val stored = checkNotNull(resolver.openInputStream(uri)).use { hash(it) }
                check(stored == hash(file.inputStream())) { "下载目录中的 ${file.name} 回读校验失败" }
            }
            // Each phase is its own batch: original images first, GPT evidence and manifests afterwards.
            val operations = ArrayList<ContentProviderOperation>()
            uris.forEach { uri -> operations += ContentProviderOperation.newUpdate(uri)
                .withValue(MediaStore.MediaColumns.IS_PENDING, 0).withExpectedCount(1).build() }
            val results = resolver.applyBatch(MediaStore.AUTHORITY, operations)
            check(results.size == uris.size && results.all { it.count == 1 }) { "下载目录发布备份文件失败" }
            return uris.toList()
        } catch (error: Exception) {
            uris.forEach { uri -> runCatching {
                // A later publication failure never rolls back files that are already public.
                val pending = resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null).use { cursor ->
                    cursor != null && cursor.moveToFirst() && cursor.getInt(0) == 1
                }
                if (pending) resolver.delete(uri, null, null)
            } }
            throw error
        }
    }

    @android.annotation.TargetApi(29)
    private fun verifyMediaStoreOriginals(folder: String, uris: List<Uri>, originals: List<BackupFileInfo>) {
        check(uris.size == originals.size && originals.map { it.name }.distinct().size == originals.size)
        uris.zip(originals).forEach { (uri, original) ->
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.IS_PENDING), null, null, null).use { cursor ->
                check(cursor != null && cursor.moveToFirst() && cursor.getString(0) == original.name &&
                    cursor.getString(1) == "${Environment.DIRECTORY_DOWNLOADS}/$folder/" && cursor.getInt(2) == 0) {
                    "已保存的原始镜像被移动或删除，后续保存已停止"
                }
            }
            val stored = hash(checkNotNull(context.contentResolver.openInputStream(uri)))
            check(stored == original.sha256) { "已保存的 ${original.name} 发生变化，后续保存已停止" }
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacyOriginals(folder: String, files: List<File>): String {
        val final = legacyDirectory(folder)
        val downloads = checkNotNull(final.parentFile)
        val partial = File(downloads, "$folder.partial")
        var created = false
        try {
            query("export-mkdir", "[ ! -e ${shellQuote(final.path)} ] && [ ! -e ${shellQuote(partial.path)} ] || exit 40; " +
                "tool mkdir -p ${shellQuote(downloads.path)} || exit 41; tool mkdir ${shellQuote(partial.path)} || exit 42")
            created = true
            files.forEach { file ->
                val target = File(partial, file.name)
                query("export-copy", "tool cp ${shellQuote(file.path)} ${shellQuote(target.path)} || exit 43")
                val output = query("export-hash", "tool sha256sum ${shellQuote(target.path)}").trim().substringBefore(' ')
                check(output == hash(file.inputStream())) { "下载目录中的 ${file.name} 回读校验失败" }
            }
            // A same-directory rename exposes only a complete, verified folder on Android 9.
            val identity = legacyIdentity(partial)
            query("export-publish", renameNew(partial, final) + "\n[ \"\$(tool stat -c '%d:%i' ${shellQuote(final.path)})\" = ${shellQuote(identity)} ] || exit 48")
            created = false
            MediaScannerConnection.scanFile(context, files.map { File(final, it.name).path }.toTypedArray(), null, null)
            return identity
        } finally {
            if (created) runCatching {
                // Remove exactly the fixed names in this newly created partial folder, never recurse.
                query("export-cleanup", files.joinToString("\n") { "tool rm -f ${shellQuote(File(partial, it.name).path)} || exit 47" } +
                    "\ntool rmdir ${shellQuote(partial.path)}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyDirectory(folder: String): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absoluteFile
        check(downloads.name == Environment.DIRECTORY_DOWNLOADS && downloads.absolutePath.startsWith("/storage/")) { "下载目录路径无效" }
        check(Regex("ublbackup_[0-9]{8}_[0-9]{6}").matches(folder))
        return File(downloads, folder)
    }

    private fun legacyIdentity(directory: File): String =
        query("export-identity", "[ -d ${shellQuote(directory.path)} ] || exit 49; tool stat -c '%d:%i' ${shellQuote(directory.path)}")
            .trim().also { check(Regex("[0-9]+:[0-9]+").matches(it)) { "备份目录身份读取失败" } }

    private fun publishLegacyAdditions(folder: String, identity: String, originals: List<BackupFileInfo>, files: List<File>) {
        val directory = legacyDirectory(folder)
        check(legacyIdentity(directory) == identity) { "原始备份目录已被替换，后续保存已停止" }
        originals.forEach { original ->
            val path = shellQuote(File(directory, original.name).path)
            val stored = query("export-original-hash", "[ -f $path ] && [ ! -L $path ] || exit 50; tool sha256sum $path")
                .trim().substringBefore(' ')
            check(stored == original.sha256) { "已保存的 ${original.name} 发生变化，后续保存已停止" }
        }
        val pending = files.associateWith { File(directory, "${it.name}.partial") }
        val published = mutableListOf<File>()
        try {
            query("export-append-check", pending.flatMap { (file, partial) -> listOf(File(directory, file.name), partial) }
                .joinToString("\n") { "[ ! -e ${shellQuote(it.path)} ] && [ ! -L ${shellQuote(it.path)} ] || exit 51" })
            pending.forEach { (file, partial) ->
                check(legacyIdentity(directory) == identity) { "原始备份目录已被替换，后续保存已停止" }
                query("export-append-copy", "tool cp ${shellQuote(file.path)} ${shellQuote(partial.path)} || exit 52")
                val stored = query("export-append-hash", "tool sha256sum ${shellQuote(partial.path)}").trim().substringBefore(' ')
                check(stored == hash(file.inputStream())) { "下载目录中的 ${file.name} 回读校验失败" }
            }
            // Publish the completion manifest last; the original backup batch is never removed.
            pending.forEach { (file, partial) ->
                check(legacyIdentity(directory) == identity) { "原始备份目录已被替换，后续保存已停止" }
                val destination = File(directory, file.name)
                query("export-append-publish", renameNew(partial, destination))
                published += destination
            }
            MediaScannerConnection.scanFile(context, published.map(File::getPath).toTypedArray(), null, null)
        } catch (error: Exception) {
            runCatching {
                if (legacyIdentity(directory) == identity) query("export-append-cleanup",
                    pending.values.filter { it.exists() }.joinToString("\n") { "tool rm -f ${shellQuote(it.path)} || exit 53" })
            }
            throw error
        }
    }

    private fun renameNew(from: File, to: File): String {
        val source = shellQuote(from.path)
        val destination = shellQuote(to.path)
        return "[ -e $source ] && [ ! -e $destination ] && [ ! -L $destination ] || exit 44; " +
            "SYSTEM_MV=\$(command -v mv 2>/dev/null); " +
            "if [ -n \"\$SYSTEM_MV\" ]; then \"\$SYSTEM_MV\" -nT $source $destination 2>/dev/null; fi; " +
            "if [ -e $destination ] && [ ! -e $source ]; then :; " +
            "elif [ -e $destination ] || [ -L $destination ]; then exit 45; " +
            "else \"\$BB\" mv -nT $source $destination || exit 46; fi; " +
            "[ -e $destination ] && [ ! -e $source ] || exit 47"
    }

    private fun parseLines(output: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        output.lineSequence().filter { '=' in it }.forEach { line ->
            val key = line.substringBefore('=')
            check(key !in result) { "备份分区参数重复：$key" }
            result[key] = line.substringAfter('=').trim()
        }
        return result
    }

    private fun hash(stream: InputStream): String = stream.use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
