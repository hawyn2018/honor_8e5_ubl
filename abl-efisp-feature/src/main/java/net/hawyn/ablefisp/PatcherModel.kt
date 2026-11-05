package net.hawyn.ablefisp

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.hawyn.ablefisp.io.ImageIo
import net.hawyn.ablefisp.io.RootAblExtractor
import net.hawyn.ablefisp.api.AblEfisp
import net.hawyn.ablefisp.patch.PatchException
import net.hawyn.ablefisp.patch.PatchFailureKind
import net.hawyn.ablefisp.patch.PatchMode
import net.hawyn.ablefisp.patch.PatchResult
import net.hawyn.ablefisp.text.TextEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ImageSource(
    val name: String,
    val description: String,
    val size: Int,
    val sha256: String,
    val slot: String? = null,
)

data class PatcherState(
    val busy: Boolean = false,
    val stage: String = "选择镜像来源",
    val rootStatus: String = "点击检测 Root 权限",
    val rootReady: Boolean = false,
    val source: ImageSource? = null,
    val result: PatchResult? = null,
    val error: String? = null,
    val message: String? = null,
    val logs: List<String> = emptyList(),
    val hasOutput: Boolean = false,
    val outputBytes: Int = 0,
    val outputSha256: String = "",
    val outputRevision: Long = 0,
    val textEntries: List<EditableTextItem> = emptyList(),
    val textNotes: List<String> = emptyList(),
    val textSource: String = "",
    val textAppliedCount: Int = 0,
    val patchMode: PatchMode = PatchMode.FREEDOM,
)

data class EditableTextItem(
    val id: String,
    val title: String,
    val originalText: String,
    val currentText: String,
    val draft: String,
    val originalRva: Long,
    val originalFileOffset: Int,
    val rva: Long,
    val fileOffset: Int,
    val maxBytes: Int,
    val editable: Boolean,
    val reason: String?,
) {
    val changed: Boolean get() = editable && draft != currentText
    val valid: Boolean get() = draft.toByteArray(Charsets.UTF_8).size <= maxBytes &&
        draft.all { it in ' '..'~' || it == '\n' || it == '\r' }
}

class PatcherModel @JvmOverloads constructor(
    application: Application,
    private val fixedPatchMode: PatchMode? = null,
    private val toolName: String = "ABL-EFISP",
    private val toolVersion: String = AblEfispFeature.VERSION,
) : AndroidViewModel(application) {
    val sessionId: String = UUID.randomUUID().toString()
    var state by mutableStateOf(PatcherState(patchMode = fixedPatchMode ?: PatchMode.FREEDOM))
        private set
    private var original: ByteArray? = null
    private var reportText: String? = null
    private var workingImage: ByteArray? = null
    private var textHistory = JSONArray()
    var outputFile: File? = null
        private set
    private var outputReportFile: File? = null
    private var outputSequence = 0L
    private var importedInitialUri: String? = null
    private val root = RootAblExtractor()

    fun checkRoot() = work("检测 Root 权限") {
        val result = withContext(Dispatchers.IO) { root.checkRoot() }
        state = state.copy(rootReady = result.available, rootStatus = result.message)
        log(result.message)
    }

    fun extractCurrentSlot() = work("读取当前槽位 ABL") {
        val extracted = withContext(Dispatchers.IO) { root.extractCurrentAbl() }
        setSource(extracted.bytes, ImageSource(
            name = extracted.sourceName,
            description = extracted.sourcePath,
            size = extracted.bytes.size,
            sha256 = extracted.sha256,
            slot = extracted.slotSuffix,
        ))
        state = state.copy(rootReady = true, rootStatus = "Root 已授权")
        log("提取完成 · ${extracted.slotSuffix ?: "无槽位后缀"} · ${extracted.bytes.size} 字节")
    }

    fun importImage(uri: Uri) = work("导入 ABL 镜像") {
        val app = getApplication<Application>()
        val source = withContext(Dispatchers.IO) {
            var name = "abl.img"
            var declaredSize: Long? = null
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex).take(180)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                }
            }
            require((declaredSize ?: 0) <= ImageIo.MAX_INPUT_BYTES) { "文件超过 128 MiB，请选择 ABL 镜像" }
            val bytes = app.contentResolver.openInputStream(uri)?.use { ImageIo.readBounded(it) }
                ?: error("文件读取失败，请重新选择")
            require(bytes.isNotEmpty()) { "所选文件为空" }
            bytes to ImageSource(name, "从文件管理导入", bytes.size, ImageIo.sha256(bytes))
        }
        setSource(source.first, source.second)
        log("已导入 ${source.second.name} · ${source.first.size} 字节")
    }

    internal fun importInitialImage(uri: Uri) {
        if (state.busy || importedInitialUri == uri.toString()) return
        importedInitialUri = uri.toString()
        importImage(uri)
    }

    private fun setSource(bytes: ByteArray, source: ImageSource) {
        original = bytes
        outputFile = null
        outputReportFile = null
        reportText = null
        workingImage = null
        textHistory = JSONArray()
        state = state.copy(source = source, result = null, error = null, stage = "镜像已就绪",
            hasOutput = false, outputBytes = 0, outputSha256 = "", textEntries = emptyList(),
            textNotes = emptyList(), textSource = "", textAppliedCount = 0)
    }

    fun selectPatchMode(mode: PatchMode) {
        if (fixedPatchMode != null || state.busy || mode == state.patchMode) return
        state = state.copy(patchMode = mode, error = null, message = null)
        val input = original
        val source = state.source
        if (input != null && source != null) setSource(input, source)
        log("修补模式已切换 · ${if (mode == PatchMode.FREEDOM) "Freedom" else "Freedom + 假回锁"}")
    }

    fun patch() = work("正在识别代码并生成 EFISP") {
        val input = original ?: error("请先提取或导入 ABL")
        log("开始解析镜像结构与修补特征")
        val mode = state.patchMode
        val generated = withContext(Dispatchers.Default) { AblEfisp.patchWithKbl(input, mode) }
        workingImage = generated.output
        textHistory = JSONArray()
        state = state.copy(result = generated, stage = "efisp.img 已生成", textEntries = emptyList(),
            textNotes = emptyList(), textSource = "", textAppliedCount = 0)
        persistOutput(generated.output)
        generated.patches.forEach { match ->
            log("${match.name} · RVA 0x${match.rva.toString(16).uppercase()} · ${if (match.alreadyApplied) "已具备" else "已修补"}")
        }
        generated.notes.forEach(::log)
        log("生成完成 · ${generated.output.size} 字节 · SHA-256 ${generated.outputSha256}")
    }

    fun prepareTexts() = work("读取屏幕提示文字") {
        val current = workingImage
        val image: ByteArray
        if (current == null) {
            val input = original ?: error("请先提取或导入镜像")
            val rawPe = input.size >= 2 && input[0] == 0x4d.toByte() && input[1] == 0x5a.toByte()
            val mode = state.patchMode
            val patched = try {
                withContext(Dispatchers.Default) { AblEfisp.patchWithKbl(input, mode) }
            } catch (exception: PatchException) {
                if (!rawPe || exception.kind != PatchFailureKind.NO_CORE_FEATURE) throw exception
                null
            }
            if (patched == null) {
                val extracted = withContext(Dispatchers.Default) { AblEfisp.extract(input) }
                image = extracted.output
                extracted.notes.forEach(::log)
                state = state.copy(textSource = "导入的 PE 镜像（直接文字定制）")
            } else {
                val source = if (patched.alreadyPatched) "导入的 EFISP 镜像（已具备核心修补）" else "本次由 ABL 生成的 EFISP"
                log(if (patched.alreadyPatched) "已识别 EFISP，保留现有核心修补" else "输入包含 ABL 功能路径，先完成 ABL → EFISP 修补")
                state = state.copy(result = patched, textSource = source)
                patched.patches.forEach { log("${it.name} · ${if (it.alreadyApplied) "已具备" else "已修补"}") }
                patched.notes.forEach(::log)
                image = patched.output
            }
        } else {
            state = state.copy(textSource = "当前会话中的 EFISP 镜像")
            image = current
        }
        workingImage = image
        val scan = try {
            withContext(Dispatchers.Default) { AblEfisp.scanText(image) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // A display catalogue failure must not discard a completed core output.
            persistOutput(image)
            throw exception
        }
        val prior = state.textEntries.associateBy { it.id }
        state = state.copy(textEntries = scan.entries.map { entry -> editable(entry, prior[entry.id]) },
            textNotes = scan.notes, stage = "提示文字已读取")
        persistOutput(image)
        log("已读取 ${scan.entries.size} 项屏幕文字")
        scan.notes.forEach(::log)
    }

    private fun editable(entry: TextEntry, previous: EditableTextItem? = null) = EditableTextItem(
        id = entry.id, title = entry.title,
        originalText = previous?.originalText ?: entry.text,
        currentText = entry.text, draft = entry.text,
        originalRva = previous?.originalRva ?: entry.rva,
        originalFileOffset = previous?.originalFileOffset ?: entry.fileOffset,
        rva = entry.rva, fileOffset = entry.fileOffset, maxBytes = entry.maxBytes,
        editable = entry.editable, reason = entry.reason,
    )

    fun updateText(id: String, value: String) {
        if (state.busy) return
        state = state.copy(textEntries = state.textEntries.map {
            if (it.id == id && it.editable) it.copy(draft = value) else it
        })
    }

    fun resetText(id: String) {
        if (state.busy) return
        state = state.copy(textEntries = state.textEntries.map {
            if (it.id == id) it.copy(draft = it.originalText) else it
        })
    }

    fun applyTexts() = work("应用文字与长度调整") {
        val image = workingImage ?: error("请先读取提示文字")
        val entries = state.textEntries.filter { it.changed }
        require(entries.isNotEmpty()) { "尚无需要应用的文字修改" }
        require(entries.all { it.valid }) { "请检查输入字符与长度提示" }
        val edited = withContext(Dispatchers.Default) { AblEfisp.editText(image, entries.associate { it.id to it.draft }) }
        val scan = withContext(Dispatchers.Default) { AblEfisp.scanText(edited.output) }
        val prior = state.textEntries.associateBy { it.id }
        edited.changes.forEach { change ->
            textHistory.put(JSONObject().apply {
                put("id", change.id); put("title", change.title)
                put("oldText", change.oldText); put("newText", change.newText)
                put("oldRva", "0x${change.oldRva.toString(16)}")
                put("newRva", "0x${change.newRva.toString(16)}")
                put("fileOffset", "0x${change.fileOffset.toString(16)}")
            })
            log("${change.title} · 文字修改已应用 · RVA 0x${change.newRva.toString(16).uppercase()}")
        }
        workingImage = edited.output
        state = state.copy(textEntries = scan.entries.map { editable(it, prior[it.id]) },
            textNotes = (scan.notes + edited.notes).distinct(),
            textAppliedCount = state.textAppliedCount + edited.changes.size,
            stage = "文字修改已应用")
        persistOutput(edited.output)
        notifyMessage("已应用 ${edited.changes.size} 项文字修改，可保存新的 efisp.img")
    }

    private suspend fun persistOutput(bytes: ByteArray) {
        val sha = ImageIo.sha256(bytes)
        val report = createReport(state.result, sha, bytes.size)
        val sequence = outputSequence + 1
        try {
            val file = withContext(Dispatchers.IO) {
                val directory = File(getApplication<Application>().filesDir,
                    "ablefisp/sessions/$sessionId/${sequence.toString().padStart(6, '0')}").apply {
                    check(isDirectory || mkdirs()) { "输出目录创建失败" }
                }
                val imageTemp = File(directory, "efisp.img.tmp")
                val reportTemp = File(directory, "efisp-patch-report.json.tmp")
                try {
                    FileOutputStream(imageTemp).use { it.write(bytes); it.fd.sync() }
                    check(ImageIo.sha256(imageTemp.readBytes()) == sha) { "输出回读校验失败" }
                    reportTemp.writeText(report)
                    val target = File(directory, "efisp.img")
                    replaceFile(imageTemp, target)
                    replaceFile(reportTemp, File(directory, "efisp-patch-report.json"))
                    target
                } finally {
                    imageTemp.delete()
                    reportTemp.delete()
                }
            }
            outputFile = file
            outputReportFile = File(file.parentFile, "efisp-patch-report.json")
            outputSequence = sequence
            reportText = report
            state = state.copy(hasOutput = true, outputBytes = bytes.size, outputSha256 = sha,
                outputRevision = sequence)
        } catch (exception: Exception) {
            outputFile = null
            outputReportFile = null
            reportText = null
            state = state.copy(hasOutput = false)
            throw exception
        }
    }

    /** A URI snapshot that remains valid when another page or edit produces output. */
    fun outputResult(): AblEfispOutput? {
        val file = outputFile?.takeIf { it.isFile } ?: return null
        val app = getApplication<Application>()
        val authority = "${app.packageName}.ablefisp.files"
        return AblEfispOutput(
            imageUri = FileProvider.getUriForFile(app, authority, file),
            reportUri = outputReportFile?.takeIf { it.isFile }?.let { FileProvider.getUriForFile(app, authority, it) },
            sha256 = state.outputSha256,
            size = state.outputBytes.toLong(),
            source = state.source,
            sessionId = sessionId,
        )
    }

    private fun replaceFile(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun saveImage(uri: Uri) = work("保存 efisp.img") {
        val file = outputFile ?: error("请先生成镜像")
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: error("保存位置打开失败")
        }
        notifyMessage("efisp.img 已保存")
        log("镜像已导出到所选位置")
    }

    fun saveOriginal(uri: Uri) = work("保存原始 ABL") {
        val bytes = original ?: error("请先提取或导入 ABL")
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("保存位置打开失败")
        }
        notifyMessage("原始 ABL 已保存")
    }

    fun saveReport(uri: Uri) = work("保存修补报告") {
        val report = reportText ?: error("请先生成镜像")
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                ?: error("保存位置打开失败")
        }
        notifyMessage("修补报告已保存")
    }

    private fun createReport(result: PatchResult?, outputHash: String = state.outputSha256, outputSize: Int = state.outputBytes): String = JSONObject().apply {
        put("tool", toolName)
        put("version", toolVersion)
        put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        put("sourceName", state.source?.name)
        put("sourceSlot", state.source?.slot ?: JSONObject.NULL)
        put("sourceFormat", result?.sourceFormat ?: "PE / EFISP")
        put("sourceSha256", result?.sourceSha256 ?: state.source?.sha256)
        put("outputName", "efisp.img")
        put("outputSha256", outputHash)
        put("outputBytes", outputSize)
        put("alreadyPatched", result?.alreadyPatched ?: JSONObject.NULL)
        put("patchMode", result?.mode?.name ?: JSONObject.NULL)
        put("requestedPatchMode", state.patchMode.name)
        put("matches", JSONArray().apply {
            result?.patches?.forEach { match -> put(JSONObject().apply {
                put("name", match.name)
                put("rva", "0x${match.rva.toString(16)}")
                put("fileOffset", "0x${match.fileOffset.toString(16)}")
                put("before", match.before)
                put("after", match.after)
                put("alreadyApplied", match.alreadyApplied)
            }) }
        })
        put("textChanges", textHistory)
        put("notes", JSONArray((result?.notes.orEmpty() + state.textNotes).distinct()))
    }.toString(2)

    private fun work(stage: String, block: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, stage = stage, error = null, message = null)
        viewModelScope.launch {
            try {
                block()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val message = exception.message?.takeIf { it.isNotBlank() } ?: "处理失败，请重新选择镜像"
                state = state.copy(error = message, stage = "处理已结束")
                log(message)
            } finally {
                state = state.copy(busy = false)
            }
        }
    }

    private fun log(message: String) {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        state = state.copy(logs = state.logs + "$time  $message")
    }
    fun logText(): String = state.logs.joinToString("\n")
    fun notifyMessage(message: String) { state = state.copy(message = message) }
    fun clearMessage() { state = state.copy(message = null) }
}
