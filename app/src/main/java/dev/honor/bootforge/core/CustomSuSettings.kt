package dev.honor.bootforge.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** The executable is one argv element, never a shell expression or a candidate list. */
data class CustomSuSettings(val enabled: Boolean = false, val path: String = "") {
    fun validationError(): String? = when {
        !enabled -> null
        path.isBlank() -> "请输入自定义 su 的绝对路径"
        !path.startsWith('/') -> "su 路径必须以 / 开头"
        '\u0000' in path -> "su 路径包含无效字符"
        else -> null
    }
}

/** Atomic whole-file replacement keeps readers in another process from seeing a partial save.
 * RootShell reloads before selecting a launcher; this does not rely on cached SharedPreferences. */
class CustomSuSettingsStore(private val file: File) {
    fun read(): CustomSuSettings {
        if (!file.exists()) return CustomSuSettings()
        val properties = Properties().also { p -> file.inputStream().use(p::load) }
        check(properties.getProperty("format") == "1") { "自定义 su 设置格式异常，请重新保存设置" }
        val enabled = when (properties.getProperty("enabled")) {
            "true" -> true
            "false" -> false
            else -> error("自定义 su 设置读取失败，请重新保存设置")
        }
        return CustomSuSettings(enabled, properties.getProperty("path", ""))
    }

    @Synchronized
    fun save(settings: CustomSuSettings) {
        require(settings.validationError() == null) { settings.validationError().orEmpty() }
        val directory = checkNotNull(file.absoluteFile.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "自定义 su 设置目录不可用" }
        val temporary = File.createTempFile("custom-su-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                Properties().apply {
                    setProperty("format", "1")
                    setProperty("enabled", settings.enabled.toString())
                    setProperty("path", settings.path)
                }.store(output, null)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    companion object {
        fun inDirectory(directory: File) = CustomSuSettingsStore(File(directory, "custom-su.properties"))
    }
}
