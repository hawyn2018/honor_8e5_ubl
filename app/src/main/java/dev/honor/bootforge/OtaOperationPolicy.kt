package dev.honor.bootforge

import dev.honor.bootforge.core.Payloads
import java.io.File
import java.io.RandomAccessFile

/** Scope checks also run immediately before publishing the native commit plan. */
internal object OtaOperationPolicy {
    const val MISSING_EFISP = "设备无efisp分区，请先操作解锁后再试！"

    fun requireEfisp(present: Boolean) { check(present) { MISSING_EFISP } }

    fun validateRequest(operation: OtaOperation, source: OtaEfispSource) {
        check(operation == OtaOperation.FULL || source != OtaEfispSource.NONE) { "请先选择 efisp 处理方式" }
        check(operation != OtaOperation.EXPORT_EFISP || source != OtaEfispSource.UPLOADED_EFISP) {
            "直接刷入的上传 efisp 无需再次生成导出"
        }
    }

    fun validateWriteTargets(operation: OtaOperation, targets: List<String>) {
        when (operation) {
            OtaOperation.EXPORT_EFISP -> check(targets.isEmpty()) { "导出流程包含分区写入目标" }
            OtaOperation.EFISP_ONLY -> check(targets == listOf("efisp")) { "仅刷写 efisp 流程包含其他写入目标" }
            OtaOperation.FULL -> check(targets.isNotEmpty() && targets.all {
                it == "efisp" || Regex("[A-Za-z0-9_.-]+_[ab]").matches(it)
            }) { "OTA 写入目标无效" }
        }
    }
}

/** Separate lazy snapshots: a file uploaded for one role never satisfies the other. */
internal class OtaInputSnapshots(snapshot: (String, String) -> File) {
    val ablForWrite: File by lazy { snapshot("abl", "uploaded-abl.img") }
    val ablForEfisp: File by lazy { snapshot("efisp-abl", "uploaded-efisp-source-abl.img") }
}

/** Only the selected source callback runs. File sources have no device dependency. */
internal object OtaEfispInputs {
    fun resolve(source: OtaEfispSource, builtin: () -> File, uploadedAbl: () -> File,
        uploadedEfisp: () -> File, current: () -> File, other: () -> File, existing: () -> File): File? = when (source) {
        OtaEfispSource.NONE -> null
        OtaEfispSource.BUILTIN_ABL -> builtin()
        OtaEfispSource.UPLOADED_ABL -> uploadedAbl()
        OtaEfispSource.UPLOADED_EFISP, OtaEfispSource.UPLOADED_EFISP_UPGRADE -> uploadedEfisp()
        OtaEfispSource.CURRENT_SLOT_ABL -> current()
        OtaEfispSource.OTHER_SLOT_ABL -> other()
        OtaEfispSource.EXISTING_EFISP -> existing()
    }
}

internal object OtaExportArtifact {
    fun prepare(session: File, product: File): File {
        // efisp.img in the session root may be the original partition backup; never replace it.
        val folder = File(session, "export").apply { check(mkdirs()) { "导出成品目录已存在" } }
        val destination = File(folder, "efisp.img")
        val expected = Payloads.sha256(product)
        product.copyTo(destination)
        RandomAccessFile(destination, "rw").use { it.fd.sync() }
        check(Payloads.sha256(destination) == expected) { "efisp 导出成品固定校验失败" }
        return destination
    }
}
