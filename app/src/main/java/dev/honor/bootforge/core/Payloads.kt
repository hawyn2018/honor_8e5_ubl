package dev.honor.bootforge.core

import android.content.Context
import android.net.Uri
import dev.honor.bootforge.BuildConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Payloads {
    data class ImageInfo(val name: String, val bytes: Long, val sha256: String, val custom: Boolean)
    val hashes = linkedMapOf(
        "abl.img" to BUILTIN_ABL_SHA256,
    )
    fun info(context: Context, target: ImageTarget, useCustomImages: Boolean = true): ImageInfo =
        info(context, UploadRole.forTarget(target), useCustomImages)
    fun info(context: Context, role: UploadRole, useCustomImages: Boolean = true): ImageInfo =
        if (useCustomImages) CustomImages.info(context, role) else when (role) {
            UploadRole.ABL_FLASH -> ImageInfo("abl.img", 642136L, BUILTIN_ABL_SHA256, false)
            UploadRole.EFISP_IMAGE -> ImageInfo("efisp.img · 运行时生成", 0, "", false)
            UploadRole.EFISP_ABL -> ImageInfo("尚未上传用于修补 EFISP 的 ABL", 0, "", false)
        }
    fun importImage(context: Context, target: ImageTarget, uri: Uri): ImageInfo = CustomImages.importImage(context, UploadRole.forTarget(target), uri)
    fun importImage(context: Context, role: UploadRole, uri: Uri): ImageInfo = CustomImages.importImage(context, role, uri)
    fun resetImage(context: Context, target: ImageTarget) = CustomImages.reset(context, UploadRole.forTarget(target))
    fun resetImage(context: Context, role: UploadRole) = CustomImages.reset(context, role)

    fun stage(context: Context, request: UnlockRequest = UnlockRequest(), onLog: (String) -> Unit = {},
        onPatchFailure: ((Exception) -> Boolean)? = null): File {
        val selection = request.resolved()
        val dir = stageRuntime(context)
        // A failed preparation must not leave an older runnable plan published.
        listOf("payload-source.txt", "payloads.sha256").forEach { name ->
            val previous = File(dir, name)
            check(!previous.exists() || previous.delete()) { "旧镜像准备记录清理失败：$name" }
        }
        val pending = File(context.cacheDir, "payload-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val sourceLines = mutableListOf("REQUEST_MODE=${selection.mode.name}",
            "EFI_SOURCE=${selection.efispSource.name}", "PATCH_MODE=${selection.patchMode.name}",
            "ABL_WRITE_TARGET=${selection.ablTarget.name}")
        val details = mutableListOf<String>()
        fun log(message: String) {
            details += message.lines()
            runCatching { onLog(message) }
        }
        val uploadedSnapshots = mutableMapOf<UploadRole, File>()
        fun uploaded(role: UploadRole): File = uploadedSnapshots.getOrPut(role) {
            CustomImages.snapshot(context, role, File(pending, "uploaded-${role.key}.img"))
        }
        try {
            log("[镜像准备] 解锁方式：${selection.mode.label}；ABL：${selection.ablSource.label}；ABL 槽位：${selection.ablTarget.label}；efisp：${selection.efispSource.label}；修补：${selection.patchMode.label}")
            val efisp: File
            val summary: String
            var sourceSnapshot: CurrentAblSnapshot.Snapshot? = null
            var sourceHash: String? = null
            var effectiveSource = selection.efispSource
            fun builtinAbl(): File = File(pending, "builtin-abl.img").also { destination ->
                if (!destination.exists()) context.assets.open("images/abl.img").use { input ->
                    destination.outputStream().use(input::copyTo)
                }
                check(sha256(destination) == hashes.getValue("abl.img")) { "内置 ABL SHA-256 校验失败" }
            }
            if (selection.efispSource == EfispSource.UPLOADED_EFISP) {
                efisp = uploaded(UploadRole.EFISP_IMAGE)
                summary = "使用上传的 efisp，保持原始字节\n"
                log("[镜像准备] 使用上传的 efisp 原始字节，跳过 ABL 修补")
            } else {
                val source = when (selection.efispSource) {
                    EfispSource.UPLOADED_ABL -> uploaded(UploadRole.EFISP_ABL)
                    EfispSource.BUILTIN_ABL -> builtinAbl()
                    EfispSource.CURRENT_SLOT_ABL, EfispSource.OTHER_SLOT_ABL -> {
                        val otherSlot = selection.efispSource == EfispSource.OTHER_SLOT_ABL
                        log("[ABL提取] 正在直接读取${if (otherSlot) "对槽" else "当前槽位"} ABL")
                        val snapshot = CurrentAblSnapshot.extract(context, dir,
                            File(pending, if (otherSlot) "other-abl.img" else "current-abl.img"), useOtherSlot = otherSlot)
                        sourceSnapshot = snapshot
                        log("[ABL提取] 已完成 · 槽位=${snapshot.identity.slot} · 设备=${snapshot.identity.device} · 主次设备号=${snapshot.identity.majorMinor} · ${snapshot.identity.bytes} 字节")
                        log("[ABL提取] 源 SHA-256=${snapshot.sha256} · 提取前后身份核对通过")
                        snapshot.file
                    }
                    EfispSource.UPLOADED_EFISP -> error("上传 EFI 不经过修补")
                }
                efisp = File(pending, "efisp.img")
                val attempt = ImagePatchRetry.run(PatchRetryTask.UNLOCK,
                    eligible = selection.efispSource.canRetryPatchWithBuiltin && onPatchFailure != null,
                    primary = {
                        EfispImagePatcher.patch(source, efisp, selection.patchMode == EfispPatchMode.FAKE_LOCK, onLog = ::log)
                    },
                    builtin = {
                        log("[镜像准备] 用户选择使用应用内置 ABL 重新修补，保留原修补模式与 ABL 写入选项")
                        sourceSnapshot = null
                        effectiveSource = EfispSource.BUILTIN_ABL
                        EfispImagePatcher.patch(builtinAbl(), efisp, selection.patchMode == EfispPatchMode.FAKE_LOCK, onLog = ::log)
                    },
                    confirm = { error ->
                        log("[镜像准备] 选定镜像修补失败，等待用户选择是否使用应用内置 ABL")
                        checkNotNull(onPatchFailure).invoke(error)
                    })
                val patched = attempt.value
                if (attempt.usedBuiltin) {
                    check(patched.sourceSha256 == BUILTIN_ABL_SHA256) { "内置 ABL 重试来源哈希不一致" }
                    sourceLines += "PATCH_RETRY_SOURCE=${EfispSource.BUILTIN_ABL.name}"
                }
                sourceHash = patched.sourceSha256
                summary = "模式=${selection.patchMode.label}\n所选来源=${selection.efispSource.label}\n实际来源=${effectiveSource.label}\n" +
                    "源 SHA-256=${patched.sourceSha256}\nEFI SHA-256=${patched.sha256}\nEFI 字节=${patched.bytes}\n" +
                    "输入格式=${patched.sourceFormat}\n核心修补项=${patched.patchCount}\n文字定制项=${patched.textChanges}\n"
            }
            sourceSnapshot?.let { snapshot ->
                check(sourceHash == snapshot.sha256) { "最终采用的 ABL 来源与固定槽位快照哈希不一致" }
                sourceLines += listOf("SOURCE_SLOT=${snapshot.identity.slot}",
                    "SOURCE_ABL_DEVICE=${snapshot.identity.device}", "SOURCE_ABL_MM=${snapshot.identity.majorMinor}",
                    "SOURCE_ABL_CAPACITY=${snapshot.identity.bytes}")
            }
            sourceHash?.let { sourceLines += "SOURCE_ABL_SHA256=$it" }
            log("[镜像准备] 最终采用来源=${effectiveSource.label} · 成品 SHA-256=${sha256(efisp)}")
            val overrides = mutableMapOf(ImageTarget.EFISP to efisp)
            if (selection.ablSource == AblWriteSource.UPLOADED) overrides[ImageTarget.ABL] = uploaded(UploadRole.ABL_FLASH)
            val staged = PayloadFiles.stageImages(dir, overrides, true) { name ->
                check(name == "abl.img") { "应用未内置 efisp，必须选择来源或生成" }
                context.assets.open("images/$name")
            }
            if (selection.ablSource == AblWriteSource.BUILTIN) {
                check(staged.single { it.target == ImageTarget.ABL }.sha256 == hashes.getValue("abl.img")) { "内置 ABL SHA-256 校验失败" }
            }
            staged.forEach { log("[镜像准备] ${it.target.stagedName} 已固定并校验 · ${it.bytes} 字节 · SHA-256=${it.sha256}") }
            File(pending, "generation-report.txt").writeText(summary + "\n详细日志\n" + details.joinToString("\n", postfix = "\n"))
            val binding = File(pending, "payload-source.txt").apply { writeText(sourceLines.joinToString("\n", postfix = "\n")) }
            Files.move(binding.toPath(), File(dir, "payload-source.txt").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            File(pending, "generation-report.txt").copyTo(File(dir, "generation-report.txt"), overwrite = true)
            check(File(dir, "payload-source.txt").isFile)
            return dir
        } catch (error: Exception) {
            log("[镜像准备] 生成流程停止：${error.message ?: error.javaClass.simpleName}")
            throw error
        } finally {
            // Retain the latest attempt, including failures before any report was published.
            runCatching { File(context.filesDir, "generation-attempt.log").writeText(details.joinToString("\n", postfix = "\n")) }
            pending.listFiles().orEmpty().forEach { it.delete() }
            pending.delete()
        }
    }

    fun stageRuntime(context: Context): File {
        val dir = File(context.filesDir, "run").apply { mkdirs() }
        listOf("unlock.sh", "online-create.sh", "tool-dispatch.sh").forEach { script ->
            context.assets.open("recovery/$script").use { input ->
                val text = input.bufferedReader(Charsets.UTF_8).readText()
                File(dir, script).writeText(normalizeShellScript(text), Charsets.UTF_8)
            }
        }
        listOf("fsprobe", "partition_worker", "gpttool").forEach { tool ->
            val source = File(context.applicationInfo.nativeLibraryDir, "libbootforge_$tool.so")
            check(source.isFile) { "缺少 arm64 工具：$tool" }
            source.copyTo(File(dir, tool), overwrite = true).setExecutable(true, true)
        }
        val tools = File(dir, "tools").apply { mkdirs() }
        listOf("busybox", "sgdisk", "e2fsck", "resize2fs", "dumpe2fs", "dmprobe").forEach { tool ->
            val source = File(context.applicationInfo.nativeLibraryDir, "libbootforge_$tool.so")
            val destination = File(tools, tool)
            if (source.isFile) source.copyTo(destination, overwrite = true).setExecutable(true, true)
            else if (destination.exists()) check(destination.delete()) { "旧工具副本清理失败：$tool" }
        }
        ToolInventory.requiredApplets.filter { it != "busybox" }.forEach { applet ->
            val path = File(tools, applet).toPath()
            if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.createSymbolicLink(path, File("busybox").toPath())
            }
        }
        return dir
    }

    fun engineHash(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val names = listOf("unlock.sh", "online-create.sh", "tool-dispatch.sh", "fsprobe", "partition_worker", "gpttool", "payload-source.txt")
        for (name in names) digest.update("$name=${sha256(File(directory, name))}\n".toByteArray())
        File(directory, "tools").listFiles().orEmpty().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .sortedBy { it.name }.forEach { digest.update("tools/${it.name}=${sha256(it)}\n".toByteArray()) }
        File(directory, "selected-tools").listFiles().orEmpty().sortedBy { it.name }.forEach {
            check(Files.isSymbolicLink(it.toPath())) { "工具选择记录无效" }
            digest.update("selected/${it.name}=${Files.readSymbolicLink(it.toPath())}\n".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    fun bundle(context: Context, preparedBackup: File? = null, request: UnlockRequest = UnlockRequest()): File {
        // Freeze the generated image: bundling must never read the current ABL again.
        val dir = File(context.filesDir, "run")
        check(File(dir, "payload-source.txt").isFile) { "镜像来源记录缺失，请重新准备" }
        val binding = Probe.parse(File(dir, "payload-source.txt").readText()).values
        check(request.matchesPayloadBinding(binding)) { "已固定镜像与本次选项不同，请重新准备" }
        val manifest = File(dir, "payloads.sha256").readLines().filter(String::isNotBlank)
        check(manifest.size == 2)
        ImageTarget.entries.forEach { target ->
            check(manifest.single { it.endsWith("  ${target.stagedName}") }.substringBefore(' ') == sha256(File(dir, target.stagedName))) { "已固定镜像发生变化" }
        }
        val archive = File(context.cacheDir, "honor_ubl-Recovery.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf("unlock.sh", "online-create.sh", "tool-dispatch.sh", "fsprobe", "partition_worker", "gpttool", "payloads.sha256", "payload-source.txt", "generation-report.txt", "abl.img", "efisp.img").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                File(dir, name).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            File(dir, "tools").listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) && it.isFile }.forEach { tool ->
                zip.putNextEntry(ZipEntry("tools/${tool.name}"))
                tool.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            context.assets.list("licenses").orEmpty().forEach { name ->
                zip.putNextEntry(ZipEntry("licenses/$name"))
                context.assets.open("licenses/$name").use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("selected-images.txt"))
            zip.write(("request=${request.resolved().encode()}\n" + File(dir, "payload-source.txt").readText() +
                File(dir, "payloads.sha256").readText()).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(instructions.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (preparedBackup != null) {
                check(preparedBackup.isFile && preparedBackup.length() > 0) { "GPT 备份包缺失，请重新准备" }
                zip.putNextEntry(ZipEntry("device-backup.tar"))
                preparedBackup.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return archive
    }
    val instructions = """
        honor_ubl ${BuildConfig.VERSION_NAME}
        此包是手动执行包，不是 Recovery install/update.zip。
        工具不设置机型白名单；按所选镜像来源、修补模式和写入槽位执行。
        自定义页的ABL写入文件、写入槽位与efisp生成来源独立，上传来源必须先选择文件。
        efisp可独立选择从当前槽位或对槽的ABL直接读取并修补；来源槽位不随ABL写入目标改变。
        ABL槽位提供当槽刷写、对槽刷写、双槽刷写，默认双槽刷写；双槽使用同一份所选ABL镜像。
        假回锁优先；三个页面默认均从当前槽位ABL生成efisp，并向双槽ABL分区写入内置abl.img。自定义仍可明确选择其他来源和刷写目标。
        仅在当前槽位或对槽ABL作为efisp来源时直接读取对应槽位快照；上传efisp按原始字节采用。非内置来源修补失败时，仅经用户确认后改用内置ABL重新修补，ABL写入文件和目标保持原选项。
        此操作包使用已固定的efisp成品，不再次执行应用内的修补或确认；来源记录保留原选项和经确认采用的内置来源。
        保留所选文件完整性、来源槽位身份及快照哈希校验；真实文件或分区读取失败仍停止。
        记录所选来源及最终SHA-256后，进入原工具准备、备份与分区调整流程；执行中确认容量及写后结果。
        解压到 Recovery 的 /tmp/bootforge（RAM），不要放到待缩容的 userdata 上。
        解压后运行：chmod 700 tools/* fsprobe partition_worker gpttool
        用内置BusyBox准备命令：tools/busybox --install -s tools
        gpttool按原始字节备份两份GPT，分别校验各自CRC，保留两表原有属性差异。
        分区修改仅接受完整一致布局及已识别的bit50/51属性差异；其余损坏或布局冲突仍停止。
        GPT修改前生成原始与目标双表快照；逐表保留既有属性，只调整userdata末端并添加efisp。
        gpt-before.bin、gpt-planned.bin为HUBLGT22原始双表容器，不是sgdisk单数组备份格式。
        系统工具优先，系统缺失或执行错误时使用内置版本；分区变更失败先核对实际完成状态。
        可选查询：tools/busybox sh unlock.sh --check
        执行：tools/busybox sh unlock.sh --execute
        工具按当前运行槽识别 abl_a / abl_b，再按所选目标写入，写前备份，逐槽回读校验，不切换活动槽位。
        新建efisp的在线后端为online-create.sh：自动准备RAM备份、保存应用内副本、继续执行。
        生成镜像与文字定制在分区操作前完成并固定SHA-256；payload-source.txt绑定实际来源槽位ABL及独立的ABL写入目标。
        创建顺序：1)校验备份；2)直接读取F2FS与checkpoint，计算实际末尾至下一已用分区前或GPT最后可用位置的连续空间，超过60MiB且镜像装得下时跳过再次缩容；
        3)不足时仅补足所需缩容量；按最终F2FS容量统一userdata GPT末端，在后方连续空闲创建efisp，保留super和其他分区；
        4)向所选槽位写入ABL并逐槽回读验证；5)最后写入PE并回读验证；6)假回锁提示刷写完成并可选进入Fastboot，仅解锁提示可选进入Recovery清空data。
        新建efisp采用${EfispProfile.ID}：名称${EfispProfile.LABEL}，类型EFI System（${EfispProfile.TYPE_CODE}），
        类型GUID=${EfispProfile.TYPE_GUID}，属性=${EfispProfile.NEW_ATTRIBUTES}（三台实测属性的按位交集）。
        PARTUUID每次新建时随机生成；分区编号、位置和逻辑扇区大小按当前设备计算，容量按实际释放的尾部空间确定。
        已有efisp沿用原有名称、类型、PARTUUID、属性和几何参数，核对EFI System类型和实际容量足以容纳镜像。
        镜像按所选文件原样写入；不添加MZ头，也不将efisp格式化为FAT。
        此处重建的是userdata分区条目，并非在已挂载data上mkfs。
        GPT重建在前。全部写入校验后才按模式提供手动重启入口，不自动重启。
        device-backup.tar包含准备阶段的GPT、来源及写入目标的原ABL、manifest及RAM任务；请保存到电脑或独立外置存储。
        未设置ramcache机型门槛；真实F2FS/加密及厂商缓存链的写入一致性尚未实机验证。
        ${if (BuildConfig.ALLOW_PARTITION_WRITES) "此构建已启用分区写入，仍须通过全部预检、备份与执行确认。" else "此只读调试构建已关闭分区写入。"}
        原unlock.sh支持原始、未映射、已卸载的ext4或已有efisp路径；已有efisp也按所选ABL目标在前、PE在后的顺序写入。
        ABL写完不代表已经解锁。随后PE失败属于部分完成，不提示重启；请保留备份和日志，先核对实际状态。
        保留 backups 和完整日志。GPT或分区表重读失败时先检查实际状态。
        使用原始镜像、hash与型号仍不能证明启动兼容或解锁成功。
    """.trimIndent()
}
