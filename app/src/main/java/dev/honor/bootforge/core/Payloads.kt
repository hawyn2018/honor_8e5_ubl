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
        "efisp.img" to "c4b2a9027b0d03134a1d62ccd07c9ede5b3e40fd86d6264f95b3b2cf75b94c15",
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
            return ImageInfo(target.stagedName, if (target == ImageTarget.EFISP) 2015232L else 642136L,
                hashes.getValue(target.stagedName), false)
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

    fun stage(context: Context, useCustomImages: Boolean = true): File {
        val dir = stageRuntime(context)
        val overrides = ImageTarget.entries.filter { info(context, it, useCustomImages).custom }
            .associateWith { selectedFile(context, it) }
        PayloadFiles.stageImages(dir, overrides, useCustomImages) { context.assets.open("images/$it") }
        return dir
    }

    fun stageRuntime(context: Context): File {
        val dir = File(context.filesDir, "run").apply { mkdirs() }
        listOf("unlock.sh", "online-create.sh", "tool-dispatch.sh").forEach { script ->
            context.assets.open("recovery/$script").use { input ->
                File(dir, script).outputStream().use(input::copyTo)
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
        val names = listOf("unlock.sh", "online-create.sh", "tool-dispatch.sh", "fsprobe", "partition_worker", "gpttool")
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
    fun bundle(context: Context, preparedBackup: File? = null, useCustomImages: Boolean = true): File {
        val dir = stage(context, useCustomImages)
        val archive = File(context.cacheDir, "honor_ubl-Recovery.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            (listOf("unlock.sh", "online-create.sh", "tool-dispatch.sh", "fsprobe", "partition_worker", "gpttool", "payloads.sha256") + hashes.keys).forEach { name ->
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
            ImageTarget.entries.forEach { target ->
                val selected = info(context, target, useCustomImages)
                zip.write("${target.id}: ${selected.name}\ncustom=${selected.custom}\nbytes=${selected.bytes}\nsha256=${selected.sha256}\n\n".toByteArray(Charsets.UTF_8))
            }
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
        应用的自定义刷入页支持任意文件；efisp与ABL各自未选择时使用内置默认镜像。
        直接解锁使用两份内置镜像，自定义解锁使用各自选择的文件；切换模式保留已选文件。
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
        工具按当前运行槽识别 abl_a / abl_b，写前备份，写后回读校验。
        新建efisp的在线后端为online-create.sh：自动准备RAM备份、保存应用内副本、继续执行。
        创建顺序：1)校验备份；2)直接读取F2FS与checkpoint，尾部已释放超过60MiB时跳过再次缩容；
        3)按实际F2FS容量调整userdata GPT末端，在释放的整个尾部创建efisp，保留super；
        4)写入当前槽位ABL并回读验证；5)最后写入PE并回读验证；6)由用户选择重启Recovery清空data。
        新建efisp采用${EfispProfile.ID}：名称${EfispProfile.LABEL}，类型EFI System（${EfispProfile.TYPE_CODE}），
        类型GUID=${EfispProfile.TYPE_GUID}，属性=${EfispProfile.NEW_ATTRIBUTES}（三台实测属性的按位交集）。
        PARTUUID每次新建时随机生成；分区编号、位置和逻辑扇区大小按当前设备计算，容量按实际释放的尾部空间确定。
        已有efisp沿用原有名称、类型、PARTUUID、属性和几何参数，核对EFI System类型和实际容量足以容纳镜像。
        镜像按所选文件原样写入；不添加MZ头，也不将efisp格式化为FAT。
        此处重建的是userdata分区条目，并非在已挂载data上mkfs。
        GPT重建在前，真正格式化data在最终Recovery恢复出厂设置时完成。全部写入校验后才提供重启选择。
        device-backup.tar包含准备阶段的GPT、原ABL、manifest及RAM任务；请保存到电脑或独立外置存储。
        未设置ramcache机型门槛；真实F2FS/加密及厂商缓存链的写入一致性尚未实机验证。
        ${if (BuildConfig.ALLOW_PARTITION_WRITES) "此构建已启用分区写入，仍须通过全部预检、备份与执行确认。" else "此只读调试构建已关闭分区写入。"}
        原unlock.sh支持原始、未映射、已卸载的ext4或已有efisp路径；已有efisp也按当前ABL在前、PE在后的顺序写入。
        ABL写完不代表已经解锁。随后PE失败属于部分完成，不提示重启；请保留备份和日志，先核对实际状态。
        保留 backups 和完整日志。GPT或分区表重读失败时先检查实际状态。
        使用原始镜像、hash与型号仍不能证明启动兼容或解锁成功。
    """.trimIndent()
}
