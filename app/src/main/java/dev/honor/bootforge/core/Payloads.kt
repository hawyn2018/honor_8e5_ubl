package dev.honor.bootforge.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
        "abl.img" to "509e8f9655184f69c147c494f8cfabe795f2cacb4e5e6b7a1fb0a48ecc08376c",
    )
    private fun selectedFile(context: Context, target: ImageTarget): File {
        val name = context.getSharedPreferences("custom_images", 0)
            .getString("${target.id}_file", "${target.id}.bin")!!
        check(name == "${target.id}.bin" || Regex("${target.id}-[a-f0-9-]{36}\\.bin").matches(name)) { "镜像保存路径无效" }
        return File(context.filesDir, "selected/$name")
    }

    fun info(context: Context, target: ImageTarget, useCustomImages: Boolean = true): ImageInfo {
        val prefs = context.getSharedPreferences("custom_images", 0)
        if (!useCustomImages || !prefs.getBoolean("${target.id}_selected", false)) {
            return if (target == ImageTarget.EFISP) ImageInfo("efisp.img · 运行时生成", 0, "", false)
                else ImageInfo(target.stagedName, 642136L, hashes.getValue(target.stagedName), false)
        }
        val file = selectedFile(context, target)
        check(file.isFile && file.length() > 0) { "${target.id} 所选文件缺失，请重新选择或恢复默认" }
        return ImageInfo(prefs.getString("${target.id}_name", target.stagedName)!!,
            file.length(), prefs.getString("${target.id}_sha256", "").orEmpty(), true)
    }

    fun importImage(context: Context, target: ImageTarget, uri: Uri): ImageInfo {
        val previous = selectedFile(context, target)
        val destination = File(context.filesDir, "selected/${target.id}-${UUID.randomUUID()}.bin")
        destination.parentFile!!.mkdirs()
        val temp = File.createTempFile(".${target.id}-", ".import", destination.parentFile)
        try {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()?.replace(Regex("[\\r\\n\\t]"), " ")?.take(256)?.ifBlank { null } ?: "${target.id} 自定义文件"
            context.contentResolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "文件读取失败" }
                temp.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
            }
            check(temp.length() > 0) { "所选文件为空" }
            val result = ImageInfo(name, temp.length(), sha256(temp), true)
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            check(context.getSharedPreferences("custom_images", 0).edit()
                .putBoolean("${target.id}_selected", true).putString("${target.id}_name", name)
                .putString("${target.id}_file", destination.name)
                .putString("${target.id}_sha256", result.sha256).commit()) { "文件选择保存失败" }
            previous.delete()
            return result
        } finally { temp.delete() }
    }

    fun resetImage(context: Context, target: ImageTarget) {
        check(context.getSharedPreferences("custom_images", 0).edit()
            .putBoolean("${target.id}_selected", false).commit()) { "默认镜像设置保存失败" }
    }

    fun stage(context: Context, request: UnlockRequest = UnlockRequest(), onLog: (String) -> Unit = {}): File {
        val selection = request.resolved()
        val dir = stageRuntime(context)
        val pending = File(context.cacheDir, "payload-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val sourceLines = mutableListOf("REQUEST_MODE=${selection.mode.name}",
            "EFI_SOURCE=${selection.efispSource.name}", "PATCH_MODE=${selection.patchMode.name}",
            "ABL_WRITE_TARGET=${selection.ablTarget.name}")
        val details = mutableListOf<String>()
        fun log(message: String) {
            details += message.lines()
            runCatching { onLog(message) }
        }
        fun uploaded(target: ImageTarget): File {
            val image = info(context, target)
            check(image.custom) { "请先选择上传的 ${target.id} 文件" }
            return selectedFile(context, target).also {
                check(sha256(it) == image.sha256) { "${target.id} 上传文件已变化，请重新选择" }
            }
        }
        try {
            log("[镜像准备] 解锁方式：${selection.mode.label}；ABL：${selection.ablSource.label}；ABL 槽位：${selection.ablTarget.label}；efisp：${selection.efispSource.label}；修补：${selection.patchMode.label}")
            val efisp: File
            val summary: String
            if (selection.efispSource == EfispSource.UPLOADED_EFISP) {
                efisp = uploaded(ImageTarget.EFISP)
                summary = "使用上传的 efisp，保持原始字节\n"
                log("[镜像准备] 使用上传的 efisp 原始字节，跳过 ABL 修补")
            } else {
                val source = when (selection.efispSource) {
                    EfispSource.UPLOADED_ABL -> uploaded(ImageTarget.ABL)
                    EfispSource.BUILTIN_ABL -> File(pending, "builtin-abl.img").also { destination ->
                        context.assets.open("images/abl.img").use { input -> destination.outputStream().use(input::copyTo) }
                        check(sha256(destination) == hashes.getValue("abl.img")) { "内置 ABL SHA-256 校验失败" }
                    }
                    EfispSource.CURRENT_SLOT_ABL, EfispSource.OTHER_SLOT_ABL -> {
                        val otherSlot = selection.efispSource == EfispSource.OTHER_SLOT_ABL
                        log("[ABL提取] 正在直接读取${if (otherSlot) "对槽" else "当前槽位"} ABL")
                        val snapshot = CurrentAblSnapshot.extract(context, dir,
                            File(pending, if (otherSlot) "other-abl.img" else "current-abl.img"), useOtherSlot = otherSlot)
                        sourceLines += listOf("SOURCE_SLOT=${snapshot.identity.slot}",
                            "SOURCE_ABL_DEVICE=${snapshot.identity.device}", "SOURCE_ABL_MM=${snapshot.identity.majorMinor}",
                            "SOURCE_ABL_CAPACITY=${snapshot.identity.bytes}")
                        log("[ABL提取] 已完成 · 槽位=${snapshot.identity.slot} · 设备=${snapshot.identity.device} · 主次设备号=${snapshot.identity.majorMinor} · ${snapshot.identity.bytes} 字节")
                        log("[ABL提取] 源 SHA-256=${snapshot.sha256} · 提取前后身份核对通过")
                        snapshot.file
                    }
                    EfispSource.UPLOADED_EFISP -> error("上传 EFI 不经过修补")
                }
                efisp = File(pending, "efisp.img")
                val patched = EfispImagePatcher.patch(source, efisp, selection.patchMode == EfispPatchMode.FAKE_LOCK, onLog = ::log)
                sourceLines += "SOURCE_ABL_SHA256=${patched.sourceSha256}"
                summary = "模式=${selection.patchMode.label}\n来源=${selection.efispSource.label}\n" +
                    "源 SHA-256=${patched.sourceSha256}\nEFI SHA-256=${patched.sha256}\nEFI 字节=${patched.bytes}\n" +
                    "输入格式=${patched.sourceFormat}\n核心修补项=${patched.patchCount}\n文字定制项=${patched.textChanges}\n"
            }
            val overrides = mutableMapOf(ImageTarget.EFISP to efisp)
            if (selection.ablSource == AblWriteSource.UPLOADED) overrides[ImageTarget.ABL] = uploaded(ImageTarget.ABL)
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
        工具不设置机型或镜像格式白名单，使用者自行判断所选文件。
        自定义页的ABL写入文件、写入槽位与efisp生成来源独立，上传来源必须先选择文件。
        efisp可独立选择从当前槽位或对槽的ABL直接读取并修补；来源槽位不随ABL写入目标改变。
        ABL槽位提供当槽刷写、对槽刷写、双槽刷写，默认当槽刷写；双槽使用同一份所选ABL镜像。
        假回锁优先；非自定义模式从当前槽位ABL生成efisp，并只向当前槽位ABL分区写入内置abl.img。自定义仅在明确选择上传ABL写入时改用上传文件。上传EFI保持原样，所选模式决定完成提示和重启入口。
        开始前仅检查所需工具可用性；执行中读取槽位和分区地址、确认容量及写后结果。
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
