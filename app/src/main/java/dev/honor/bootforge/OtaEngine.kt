package dev.honor.bootforge

import android.content.Context
import android.os.Environment
import dev.honor.bootforge.core.AblRollbackInspector
import dev.honor.bootforge.core.AblRollbackGuardPolicy
import dev.honor.bootforge.core.EfispImagePatcher
import dev.honor.bootforge.core.EfispArbFallback
import dev.honor.bootforge.core.ImagePatchRetry
import dev.honor.bootforge.core.PatchRetryTask
import dev.honor.bootforge.core.EfispPatchMode
import dev.honor.bootforge.core.Payloads
import dev.honor.bootforge.core.RootShell
import dev.honor.bootforge.core.normalizeShellScript
import dev.honor.bootforge.core.shellQuote
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone
import java.util.UUID

internal data class OtaProgress(val stage: String, val progress: Float, val slot: String = "", val directory: String = "", val log: String = "")

/** OTA only: operates existing partitions; does not invoke the unlock/GPT workflow. */
internal class OtaEngine(private val context: Context, private val clickedAt: Long, private val zone: TimeZone, private val request: OtaRequest,
    private val operation: OtaOperation = OtaOperation.FULL,
    private val onPatchFailure: ((Exception) -> Boolean)? = null) {
    private val id = UUID.randomUUID().toString()
    private val session = File(context.filesDir, "ota-sessions/$id").apply { check(mkdirs()) }
    private lateinit var runtime: File
    private val uploads = OtaInputSnapshots { kind, name -> OtaInputs.snapshot(context, kind, File(session, name)) }
    private val builtinFile by lazy { builtin() }
    private var exportParts: Map<String, Partition>? = null
    private var slot = ""
    private var output = ""
    private var directoryId = ""
    private val ram = "/dev/hnubl-ota-$id"
    private val logs = mutableListOf<String>()
    private val backups = linkedMapOf<String, Record>()
    private val snapshots = linkedMapOf<String, Snapshot>()
    private val decisions = mutableListOf<JSONObject>()
    private val writes = mutableListOf<Write>()
    private var selectedEfispOrigin: File? = null
    private var ramSourcesFrozen = false
    private var efispResolution = JSONObject().put("requestedSource", request.efispSource.name)
        .put("patchMode", request.patchMode.name).put("status", if (request.efispSource == OtaEfispSource.NONE) "not_requested" else "preparing")
    private var onProgress: suspend (OtaProgress) -> Unit = {}
    private var phase = "preparing"
    private data class Record(val file: File, val hash: String) { val bytes get() = file.length() }
    private data class Partition(val identity: String, val name: String, val device: String, val mm: String, val bytes: Long) {
        companion object {
            fun parse(line: String): Partition {
                val p = line.split('|'); check(p.size == 7) { "分区身份字段不完整" }
                check(Regex("[A-Za-z0-9_.-]+").matches(p[0]) && Regex("/dev/block/[A-Za-z0-9_./-]+").matches(p[1]) &&
                    Regex("/dev/block/[A-Za-z0-9_./-]+").matches(p[2]) && Regex("[0-9]+:[0-9]+").matches(p[3])) { "分区身份格式无效" }
                val bytes = p[4].toLong(); check(bytes > 0 && p[5].toLong() >= 0 && p[6].toLong() >= 512)
                return Partition(line, p[0], p[2], p[3], bytes)
            }
        }
    }
    private data class Snapshot(val partition: Partition, val record: Record)
    private data class Write(val target: Snapshot, val source: Record)

    suspend fun run(progress: suspend (OtaProgress) -> Unit): File? {
        onProgress = progress
        check(operation == OtaOperation.EXPORT_EFISP || BuildConfig.ALLOW_PARTITION_WRITES) { "当前构建关闭分区写入" }
        OtaOperationPolicy.validateRequest(operation, request.efispSource)
        try {
            // Export uses app-private inputs and SAF. Device reads are lazy: a
            // current/other/existing source (or a real ARB fallback) opens the
            // trusted Root channel only when that source is actually requested.
            if (operation == OtaOperation.EXPORT_EFISP) {
                val product = prepareEfisp(
                    { exportPartition("", current = true).record.file },
                    { exportPartition("", other = true).record.file },
                    { exportPartition("efisp").record.file })
                return finishExport(checkNotNull(product) { "没有可导出的 efisp 成品" })
            }
            setup()
            // A missing efisp must stop every action before public backup creation or partition reads.
            OtaOperationPolicy.requireEfisp(command("efisp-presence").trim() == "EFISP=present")
            val inventory = command("inventory").lineSequence().filter(String::isNotBlank).toList()
            slot = inventory.single { it.startsWith("SLOT=") }.substringAfter('=')
            check(slot in listOf("a", "b")); val other = if (slot == "a") "b" else "a"
            val parts = inventory.filter { '|' in it }.map(Partition::parse).groupBy { it.name }.mapValues { (_, p) ->
                check(p.distinct().size == 1) { "by-name 映射存在冲突" }; p.first()
            }
            OtaOperationPolicy.requireEfisp(parts.containsKey("efisp"))
            check(parts.values.map { it.mm }.distinct().size == parts.size) { "多个分区名指向相同块设备" }
            report("已确认 efisp 分区，当前槽位 $slot", .02f)
            if (operation != OtaOperation.EXPORT_EFISP) {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                check(Regex("/storage/emulated/[0-9]+/Download").matches(downloads)) { "内部存储 Download 路径无效" }
                output = "$downloads/hnubl/${OtaPolicy.folder(clickedAt, zone)}"
                directoryId = command("mkdir-backup", output).trim()
                check(Regex("[0-9]+:[0-9]+").matches(directoryId)) { "公开备份目录身份读取失败" }
                report("备份目录已创建，正在固定镜像来源", .03f)
            }
            val preAbl = mutableListOf<Pair<Snapshot, Snapshot>>()
            val arbValues = mutableListOf<OtaPolicy.Arb>()
            if (operation == OtaOperation.FULL) {
                for ((base, role) in OtaPolicy.bootRoles) {
                    val a = parts["${base}_a"]; val b = parts["${base}_b"]
                    if (a == null || b == null) {
                        decisions += JSONObject().put("role", base).put("included", false).put("reason", "不存在完整 A/B 分区，按要求忽略")
                        continue
                    }
                    val active = read(if (slot == "a") a else b)
                    val inactive = read(if (slot == "a") b else a)
                    val currentStructure = signedStructure(active.record.file)
                    val otherStructure = signedStructure(inactive.record.file)
                    val included = currentStructure || otherStructure
                    val result = OtaPolicy.arb(AblRollbackGuardPolicy.inspectReadable(inactive.record.file))
                    decisions += JSONObject().put("role", base).put("roleEvidence", role).put("included", included)
                        .put("currentSignedContainer", currentStructure).put("otherSignedContainer", otherStructure)
                        .put("reason", if (included) "平台引导角色 + 实际双槽节点 + 镜像签名容器结构" else "未识别出签名引导容器结构")
                        .put("otherSlotArb", result.value).put("arbEvidence", result.detail).put("bootRoleEvidence", OtaPolicy.BOOT_EVIDENCE)
                    if (included) {
                        preAbl += active to inactive; arbValues += result
                        log("[引导链] $base · $role · 对槽 ARB=${result.value} · ${result.detail}")
                    } else log("[引导链] $base 未入选：没有已识别签名容器结构")
                }
                val referenceRoles = OtaPolicy.referenceRoles + parts.keys.filter { it.startsWith("tme_") && it.endsWith("_a") }
                    .associate { it.removeSuffix("_a") to "trusted management engine firmware component" }
                for ((base, role) in referenceRoles) {
                    val a = parts["${base}_a"]; val b = parts["${base}_b"]
                    if (a == null || b == null) {
                        decisions += JSONObject().put("role", base).put("included", false).put("reason", "不存在完整 A/B 分区，按要求忽略")
                        continue
                    }
                    val references = preAbl.flatMap { listOf(it.first, it.second) }.mapNotNull { loader ->
                        val offsets = OtaPolicy.referenceOffsets(loader.record.file.readBytes(), base)
                        if (offsets.isEmpty()) null else JSONObject().put("source", loader.partition.name)
                            .put("sha256", loader.record.hash).put("fileOffsets", JSONArray(offsets))
                    }
                    if (references.isEmpty()) {
                        decisions += JSONObject().put("role", base).put("included", false)
                            .put("reason", "已知候选前级角色，但没有已确认前级镜像中的精确名称引用证据；不猜测参与加载")
                        log("[引导链] $base 未入选：没有已确认前级镜像引用证据")
                        continue
                    }
                    val active = read(if (slot == "a") a else b); val inactive = read(if (slot == "a") b else a)
                    val signed = signedStructure(active.record.file) || signedStructure(inactive.record.file)
                    val result = OtaPolicy.arb(AblRollbackGuardPolicy.inspectReadable(inactive.record.file))
                    decisions += JSONObject().put("role", base).put("roleEvidence", role).put("included", signed)
                        .put("literalReferences", JSONArray(references)).put("evidenceStrength", "已知平台角色的前级镜像名称引用，不宣称独立控制流证明")
                        .put("signedContainer", signed).put("otherSlotArb", result.value).put("arbEvidence", result.detail)
                    if (signed) {
                        preAbl += active to inactive; arbValues += result
                        log("[引导链] $base · 前级镜像精确名称引用 + 双槽签名结构 · 对槽 ARB=${result.value} · ${result.detail}")
                    }
                }
                parts.keys.filter { it.endsWith("_a") }.map { it.removeSuffix("_a") }.filter { it !in OtaPolicy.bootRoles && it !in referenceRoles && it != "abl" }.sorted().forEach {
                    decisions += JSONObject().put("role", it).put("included", false).put("reason", "没有已证实的 XBL 至 ABL 前级加载角色；不因具有 A/B 就纳入")
                }
            }
            val copyBootChain = operation == OtaOperation.FULL && OtaPolicy.requiresBootChainCopy(arbValues)
            log(if (operation != OtaOperation.FULL) "[引导链] 本次仅处理 efisp，跳过前级引导链检查与对拷"
                else if (copyBootChain) "[防回滚] 存在非零计数：先备份已识别引导链双槽，再由当前槽复制到对槽"
                else "[防回滚] 全部计数为 0 或未读出：跳过前级引导链公开备份与对拷")
            // Freeze sources before any mutation. Partial actions do not read init_boot or the pre-ABL chain.
            report("读取并固定镜像来源", .20f)
            if (operation == OtaOperation.FULL) {
                for (base in listOf("abl", "init_boot")) for (suffix in listOf("a", "b")) {
                    read(parts["${base}_$suffix"] ?: error("缺少必需分区 ${base}_$suffix"))
                }
            }
            val efisp = if (request.efispSource != OtaEfispSource.NONE &&
                (operation != OtaOperation.EXPORT_EFISP || request.efispSource == OtaEfispSource.EXISTING_EFISP))
                read(parts.getValue("efisp")) else null
            val currentAbl by lazy { read(parts["abl_$slot"] ?: error("缺少当前槽位 ABL 分区")) }
            val otherAbl by lazy { read(parts["abl_$other"] ?: error("缺少对槽 ABL 分区")) }
            val builtin by lazy { builtinFile }
            val parentRecord = if (operation == OtaOperation.FULL) {
                val parent = when (request.ablSource) {
                    OtaAblSource.BUILTIN -> builtin
                    OtaAblSource.UPLOADED -> uploads.ablForWrite
                    OtaAblSource.CURRENT_SLOT_ABL -> currentAbl.record.file
                }
                log("[父 ABL] ${request.ablSource.label} · SHA-256=${Payloads.sha256(parent)}")
                Record(parent, Payloads.sha256(parent))
            } else null
            val efispOutput = prepareEfisp({ currentAbl.record.file }, { otherAbl.record.file }, { checkNotNull(efisp).record.file })
            report("保存并校验所有需要的公开备份", .45f)
            if (copyBootChain) preAbl.forEach { (active, inactive) -> export(active.record); export(inactive.record) }
            if (operation == OtaOperation.FULL) {
                for (base in listOf("abl", "init_boot")) for (suffix in listOf("a", "b")) export(snapshots.getValue("${base}_$suffix").record)
            }
            if (efisp != null) export(efisp.record)
            val activeFile = File(session, "slot_active_$slot").apply { writeText(slot); syncFile(this) }
            export(Record(activeFile, Payloads.sha256(activeFile)))
            if (copyBootChain) preAbl.forEach { (active, inactive) ->
                check(active.record.bytes == inactive.partition.bytes) { "${inactive.partition.name} 对拷容量不一致" }
                writes += Write(inactive, active.record)
            }
            if (operation == OtaOperation.FULL) writes += Write(otherAbl, checkNotNull(parentRecord))
            if (efispOutput != null) writes += Write(checkNotNull(efisp), Record(efispOutput, Payloads.sha256(efispOutput)))
            OtaOperationPolicy.validateWriteTargets(operation, writes.map { it.target.partition.name })
            writes.forEach { check(it.source.bytes in 1..it.target.partition.bytes) { "${it.target.partition.name} 镜像超过分区容量" } }
            phase = "backups_complete"
            val preparedManifest = manifest("ota-backup.json", phase)
            export(Record(preparedManifest, Payloads.sha256(preparedManifest)))
            // The sources include original other-slot ABL, even when parent ABL will overwrite it.
            command("ram-create", ram)
            val sources = session.listFiles().orEmpty().filter { it.isFile && it.extension == "img" }
                .map { Record(it, Payloads.sha256(it)) }.distinctBy { it.file.name }
            sources.forEach { command("pin", ram, it.file.absolutePath, it.file.name, it.hash) }
            ramSourcesFrozen = true
            val plan = File(session, "commit.plan")
            plan.writeText(buildString {
                backups.forEach { (name, r) -> append("BACKUP|$name|${r.bytes}|${r.hash}\n") }
                sources.forEach { append("SOURCE|${it.file.name}|${it.bytes}|${it.hash}\n") }
                writes.forEach { w -> append("WRITE|${w.target.partition.name}|${w.source.file.name}|${w.source.hash}|${w.source.bytes}|${w.target.record.hash}|${w.target.partition.identity}\n") }
            })
            syncFile(plan)
            command("pin", ram, plan.absolutePath, plan.name, Payloads.sha256(plan))
            report(if (operation == OtaOperation.EFISP_ONLY) "efisp 备份与 RAM 成品已固定，正在写入并 O_DIRECT 回读校验"
                else "所有备份与 RAM 来源已固定，正在写入对槽并 O_DIRECT 回读校验", .72f)
            phase = "writing"
            val commit = command("commit", ram, Payloads.sha256(plan), output, directoryId, slot, if (operation == OtaOperation.EFISP_ONLY) "efisp-only" else "full")
            log(commit)
            check(commit.lineSequence().any { it == "RESULT=success" }) { "OTA 写入未返回完整成功证明" }
            phase = "complete"
            val completed = manifest("ota-complete.json", phase)
            export(Record(completed, Payloads.sha256(completed)))
            runCatching { command("ram-cleanup", ram) }.onFailure { logs += "完成后的 RAM 清理未完成：${it.message.orEmpty()}" }
            report(if (operation == OtaOperation.EFISP_ONLY) "仅刷写 efisp 完成；ABL 与槽位保持不变" else "OTA 保持解锁完成；槽位保持不变", 1f)
            return null
        } catch (e: OutOfMemoryError) {
            // Keep this path small: serializing every snapshot while the heap is full is unsafe.
            logs += "ERROR: OutOfMemoryError · phase=$phase · 系统分配给应用的内存不足；保留任务文件，不自动重试刷写"
            throw e
        } catch (e: Exception) {
            logs += "ERROR: ${e.message.orEmpty()}"
            if (directoryId.isNotBlank()) runCatching {
                val failed = manifest("ota-partial.json", "partial", e.message.orEmpty())
                export(Record(failed, Payloads.sha256(failed)))
            }.onFailure { logs += "部分完成清单保存失败：${it.message.orEmpty()}" }
            throw e
        } finally {
            // Diagnostics are auxiliary: a local disk/log failure must not mask
            // the original patch/I/O failure or replay a partially written job.
            try { File(session, "diagnostics.log").writeText(logs.joinToString("\n")) }
            catch (_: Exception) { /* The primary result/error has already been determined. */ }
            // Keep session/RAM evidence after failure; no automatic retry, slot switch or reboot.
        }
    }
    private suspend fun prepareEfisp(currentAbl: () -> File, otherAbl: () -> File, existingEfisp: () -> File): File? {
        val builtin by lazy { builtinFile }
        val initialEfisp = when (request.efispSource) {
            OtaEfispSource.NONE -> null
            OtaEfispSource.UPLOADED_EFISP -> OtaInputs.snapshot(context, "efisp", File(session, "uploaded-efisp.img"))
                .also { selectedEfispOrigin = it }
            else -> {
                val source = checkNotNull(OtaEfispInputs.resolve(request.efispSource,
                    { builtin }, { uploads.ablForEfisp },
                    { OtaInputs.snapshot(context, "efisp", File(session, "uploaded-efisp.img")) },
                    currentAbl, otherAbl, existingEfisp))
                val destination = File(session, "generated-efisp.img")
                selectedEfispOrigin = source
                log("[efisp] 来源=${request.efispSource.label} · 固定 SHA-256=${Payloads.sha256(source)}")
                val patchLogs = mutableListOf<String>()
                phase = "patching_efisp"
                var product = destination
                try {
                    val retry = ImagePatchRetry.run(PatchRetryTask.OTA,
                        eligible = request.efispSource != OtaEfispSource.BUILTIN_ABL && onPatchFailure != null,
                        primary = {
                            patchEfisp(source, destination, request.patchMode == EfispPatchMode.FAKE_LOCK) { patchLogs += it }
                        }, builtin = {
                            val builtinSource = builtin
                            product = File(session, "generated-builtin-retry.img")
                            patchLogs += "[修补重试] 用户选择内置 ABL，保留本次修补模式、ABL 刷写来源及目标范围"
                            patchEfisp(builtinSource, product, request.patchMode == EfispPatchMode.FAKE_LOCK) { patchLogs += it }
                        }, confirm = { failure ->
                            phase = "waiting_patch_retry"
                            val accepted = checkNotNull(onPatchFailure)(failure)
                            phase = "patching_efisp"
                            accepted
                        })
                    if (retry.usedBuiltin) {
                        selectedEfispOrigin = builtin
                        efispResolution.put("patchRetrySource", "BUILTIN_ABL")
                            .put("patchRetryOrigin", imageMetadata(builtin))
                            .put("patchRetrySourceSha256", retry.value.sourceSha256)
                    }
                } finally { patchLogs.forEach { log(it) } }
                product
            }
        }
        val efispOutput = initialEfisp?.let { initial ->
            val messages = mutableListOf<String>()
            efispResolution.put("initialProduct", imageMetadata(initial))
                .put("selectedOrigin", selectedEfispOrigin?.let(::imageMetadata) ?: JSONObject.NULL)
            try {
                val prepared = EfispArbFallback.prepareWithRetry(initial, { currentAbl() }, { builtin }, session,
                    request.patchMode == EfispPatchMode.FAKE_LOCK, { messages += it }, onPatchFailure, ::patchEfisp)
                val actualOrigin = when (prepared.source) {
                    EfispArbFallback.Source.SELECTED -> selectedEfispOrigin ?: initial
                    EfispArbFallback.Source.CURRENT_SLOT_ABL -> currentAbl()
                    EfispArbFallback.Source.BUILTIN_ABL -> builtin
                }
                efispResolution.put("status", "resolved").put("actualSource", prepared.source.name)
                    .put("actualOrigin", imageMetadata(actualOrigin))
                    .put("patchSourceSha256", prepared.sourceSha256 ?: JSONObject.NULL)
                    .put("arbBinding", prepared.arbBinding)
                    .put("rollback", rollbackMetadata(prepared.rollback, prepared.arbBinding == "builtin-unchecked"))
                    .put("finalProduct", imageMetadata(prepared.file))
                prepared.file
            } finally { messages.forEach { log(it) } }
        }
        return efispOutput
    }

    private suspend fun finishExport(product: File): File {
        OtaOperationPolicy.validateWriteTargets(operation, emptyList())
        val exported = OtaExportArtifact.prepare(session, product)
        phase = "export_ready"
        manifest("efisp-export.json", phase)
        report("efisp 成品已生成，正在导出所选文件", .95f)
        return exported
    }

    private fun exportPartition(name: String, current: Boolean = false, other: Boolean = false): Snapshot {
        val parts = exportParts ?: kotlin.run {
            setup()
            val lines = command("inventory").lineSequence().filter(String::isNotBlank).toList()
            slot = lines.single { it.startsWith("SLOT=") }.substringAfter('=')
            check(slot in listOf("a", "b")) { "当前槽位读取失败" }
            lines.filter { '|' in it }.map(Partition::parse).groupBy { it.name }.mapValues { (_, candidates) ->
                check(candidates.distinct().size == 1) { "by-name 映射存在冲突" }
                candidates.first()
            }.also { parts ->
                check(parts.values.map { it.mm }.distinct().size == parts.size) { "多个分区名指向相同块设备" }
                exportParts = parts
            }
        }
        val target = when { current -> "abl_$slot"; other -> "abl_${if (slot == "a") "b" else "a"}"; else -> name }
        if (target == "efisp") OtaOperationPolicy.requireEfisp(parts.containsKey(target))
        return read(parts[target] ?: error("缺少镜像来源分区 $target"))
    }

    private fun patchEfisp(source: File, output: File, fakeLock: Boolean, onLog: (String) -> Unit): EfispImagePatcher.Result =
        OtaPatchFailureGuidance.patch(operation) { EfispImagePatcher.patch(source, output, fakeLock, onLog) }

    private fun setup() {
        val root = RootShell.check(File(session, "root-proof.log"))
        check(root.exitCode == 0 && root.output.lineSequence().any { it == "ROOT_UID=0" }) { "读取或刷写真实设备分区需要 Root 权限，请先完成授权后重试：${root.output}" }
        val staged = Payloads.stageRuntime(context)
        runtime = File(session, "runtime").apply { check(mkdirs()) }
        File(staged, "partition_worker").copyTo(File(runtime, "partition_worker")).setExecutable(true, true)
        File(runtime, "tools").mkdirs()
        File(staged, "tools/busybox").copyTo(File(runtime, "tools/busybox")).setExecutable(true, true)
        context.assets.open("scripts/ota-device.sh").bufferedReader().use { File(runtime, "ota-device.sh").writeText(normalizeShellScript(it.readText())) }
    }
    private fun command(action: String, vararg args: String): String {
        val text = listOf(File(runtime, "tools/busybox").absolutePath, "sh", File(runtime, "ota-device.sh").absolutePath, runtime.absolutePath, action, *args)
            .joinToString(" ", transform = ::shellQuote)
        val result = RootShell.run(text, File(session, "action-$action.log"), if (action == "commit") 0 else 120)
        logs += "[$action] ${result.output}"
        check(result.exitCode == 0) { "OTA $action 已停止（${result.exitCode}）：${result.output}" }
        return result.output
    }
    private fun read(p: Partition): Snapshot = snapshots[p.name] ?: kotlin.run {
        check(p.bytes <= 128L * 1024 * 1024) { "${p.name} 超过 128 MiB 读取范围" }
        val file = File(session, "${p.name}.img")
        val cmd = listOf(File(runtime, "tools/busybox").absolutePath, "sh", File(runtime, "ota-device.sh").absolutePath,
            runtime.absolutePath, "read", p.identity).joinToString(" ", transform = ::shellQuote)
        val result = RootShell.capture(cmd, file, File(session, "read-${p.name}.log"), 180)
        check(result.exitCode == 0 && file.length() == p.bytes) { "${p.name} O_DIRECT 读取失败：${result.output}" }
        Snapshot(p, Record(file, Payloads.sha256(file))).also { snapshots[p.name] = it }
    }
    private fun export(record: Record) {
        check(record.file.name !in backups) { "重复备份文件名" }
        command("export", output, directoryId, record.file.absolutePath, record.file.name, record.hash)
        backups[record.file.name] = record
    }
    private fun builtin(): File = File(session, "builtin-abl.img").also { f ->
        context.assets.open("images/abl.img").use { input -> f.outputStream().use(input::copyTo) }
        check(Payloads.sha256(f) == Payloads.hashes.getValue("abl.img")) { "内置 ABL SHA-256 不匹配" }
    }
    private fun signedStructure(file: File) = OtaPolicy.signedContainer(file.readBytes())
    private suspend fun log(message: String) { logs += message; onProgress(OtaProgress("", 0f, log = message)) }
    private suspend fun report(stage: String, value: Float) { logs += stage; onProgress(OtaProgress(stage, value, slot, output, stage)) }
    private fun manifest(name: String, status: String, error: String = ""): File = File(session, name).apply {
        val json = JSONObject().put("format", "hnubl-ota-v1").put("status", status).put("lastPhase", phase)
            .put("clickedAtEpochMillis", clickedAt).put("clickedAtTimeZone", zone.id).put("currentSlot", slot)
            .put("backupDirectory", output).put("operation", operation.name).put("request", request.encode()).put("error", error)
            .put("ramDirectory", ram)
            .put("ramSourcesFrozen", ramSourcesFrozen).put("efispResolution", efispResolution)
            .put("sourceImages", JSONArray(session.listFiles().orEmpty().filter { it.isFile && it.extension == "img" }
                .sortedBy { it.name }.map { imageMetadata(it).put("ramPath", "$ram/${it.name}") }))
            .put("arbPolicy", "对槽前级引导分区：任意非零触发整套双槽备份和当前到对槽对拷；无可读计数按0。ABL不检查。")
            .put("discovery", JSONArray(decisions)).put("backups", JSONArray(backups.map { (n, r) -> JSONObject().put("name", n).put("bytes", r.bytes).put("sha256", r.hash) }))
            .put("snapshots", JSONArray(snapshots.values.map { s -> JSONObject().put("identity", s.partition.identity).put("sha256", s.record.hash) }))
            .put("writes", JSONArray(writes.map { w -> JSONObject().put("target", w.target.partition.name).put("source", w.source.file.name).put("bytes", w.source.bytes).put("sha256", w.source.hash) }))
            .put("logs", JSONArray(logs))
        writeText(json.toString(2)); syncFile(this)
    }
    private fun imageMetadata(file: File): JSONObject = JSONObject().put("file", file.name)
        .put("bytes", file.length()).put("sha256", Payloads.sha256(file))
    private fun rollbackMetadata(result: AblRollbackInspector.Result?, builtinUnchecked: Boolean): JSONObject {
        val json = JSONObject().put("builtinUnchecked", builtinUnchecked).put("scope", AblRollbackInspector.SCOPE)
        return when (result) {
            is AblRollbackInspector.Result.Parsed -> json.put("status", "parsed").put("format", result.format.name)
                .put("counters", JSONArray(result.counters.map { counter -> JSONObject()
                    .put("authority", counter.authority.name).put("value", counter.value).put("source", counter.source)
                    .put("fileOffset", counter.fileOffset).put("metadataVersion", counter.metadataVersion ?: JSONObject.NULL) }))
                .put("evidence", JSONArray(result.evidence))
            is AblRollbackInspector.Result.NoMetadata -> json.put("status", "absent").put("format", result.format.name)
                .put("counters", JSONArray()).put("evidence", JSONArray(result.evidence))
            is AblRollbackInspector.Result.Invalid -> json.put("status", "unreadable_metadata_as_absent")
                .put("counters", JSONArray()).put("reason", result.reason)
            null -> json.put("status", if (builtinUnchecked) "builtin-unchecked" else "not_inspected").put("counters", JSONArray())
        }
    }
    private fun syncFile(file: File) { java.io.RandomAccessFile(file, "rw").use { it.fd.sync() } }
}
