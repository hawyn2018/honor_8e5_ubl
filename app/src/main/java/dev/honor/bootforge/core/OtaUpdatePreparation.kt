package dev.honor.bootforge.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.math.BigInteger
import java.util.zip.ZipFile
import org.json.JSONObject

/** Installs the bundled OTA module; installation never starts an update or a reboot. */
internal object OtaUpdatePreparation {
    const val MODULE_ASSET = "modules/hnubl-ota.zip"
    const val HASH_ASSET = "modules/hnubl-ota.sha256"
    const val KSUD = "/data/adb/ksud"
    const val MODULE_ID = "hnubl_ota"
    const val LATEST_MESSAGE = "已安装最新版模块，无需更新"
    // Frame stdout rather than relying on grep/awk or executing module.prop as a
    // shell script. Installed and pending metadata are both inspected read-only.
    const val VERSION_QUERY_COMMAND = "printf 'HNUBL_MODULE_QUERY_V1\\n'; " +
        "for location in installed pending; do " +
        "if [ \"\$location\" = installed ]; then directory=/data/adb/modules/hnubl_ota; " +
        "else directory=/data/adb/modules_update/hnubl_ota; fi; " +
        "printf 'HNUBL_MODULE_BEGIN=%s\\n' \"\$location\"; " +
        "if [ -d \"\$directory\" ]; then " +
        "printf 'HNUBL_MODULE_STATE=present\\n'; " +
        "if [ ! -f \"\$directory/module.prop\" ] || [ ! -r \"\$directory/module.prop\" ]; then " +
        "printf 'Module version metadata is unavailable: %s\\n' \"\$location\" >&2; exit 1; fi; " +
        "cat \"\$directory/module.prop\" || exit 1; printf '\\n'; " +
        "elif [ -e \"\$directory\" ] || [ -L \"\$directory\" ]; then " +
        "printf 'Module directory is invalid: %s\\n' \"\$location\" >&2; exit 1; " +
        "else printf 'HNUBL_MODULE_STATE=absent\\n'; fi; " +
        "printf 'HNUBL_MODULE_END=%s\\n' \"\$location\"; done; " +
        "printf 'HNUBL_MODULE_QUERY_END\\n'"
    const val CHECK_COMMAND = "if [ -x /data/adb/ksud ]; then printf 'HNUBL_KSUD_READY=1\\n'; else printf 'KernelSU module installer not found\\n' >&2; exit 127; fi"
    const val ACTIVATION_ID_PREFIX = "HNUBL_ACTIVATION_ID="
    const val ACTIVATE_COMMAND = "if [ -f /data/adb/modules_update/hnubl_ota/service.sh ]; then " +
        "service=/data/adb/modules_update/hnubl_ota/service.sh; " +
        "elif [ -f /data/adb/modules/hnubl_ota/service.sh ]; then " +
        "service=/data/adb/modules/hnubl_ota/service.sh; " +
        "else printf 'Module service not found\\n' >&2; exit 127; fi; " +
        "/system/bin/sh \"\$service\" --activate >&2 || exit 1; " +
        "activation=\$(cat /data/adb/hnubl/activation.id) || exit 1; " +
        "case \"\$activation\" in *[!A-Za-z0-9-]*|'') printf 'Invalid activation identity\\n' >&2; exit 1;; esac; " +
        "printf 'HNUBL_ACTIVATION_ID=%s\\n' \"\$activation\"; " +
        "count=0; while [ \"\$count\" -lt 30 ]; do " +
        "if [ -s /data/adb/hnubl/status.json ]; then status=\$(cat /data/adb/hnubl/status.json) || status=; " +
        "if printf '%s' \"\$status\" | grep -Fq \"\$activation\" && " +
        "printf '%s' \"\$status\" | grep -Eq '\"controller_ready\"[[:space:]]*:[[:space:]]*true' && " +
        "printf '%s' \"\$status\" | grep -Eq '\"broker_ready\"[[:space:]]*:[[:space:]]*true' && " +
        "{ { printf '%s' \"\$status\" | grep -Eq '\"updater_running\"[[:space:]]*:[[:space:]]*true' && " +
        "printf '%s' \"\$status\" | grep -Eq '\"critical_ready\"[[:space:]]*:[[:space:]]*true' && " +
        "printf '%s' \"\$status\" | grep -Eq '\"hook_ready\"[[:space:]]*:[[:space:]]*true'; } || " +
        "{ printf '%s' \"\$status\" | grep -Eq '\"updater_running\"[[:space:]]*:[[:space:]]*false' && " +
        "printf '%s' \"\$status\" | grep -Eq '\"waiting_for_updater\"[[:space:]]*:[[:space:]]*true'; }; }; then " +
        "printf '%s\\n' \"\$status\"; exit 0; fi; fi; " +
        "count=\$((count + 1)); sleep 1; done; " +
        "cat /data/adb/hnubl/status.json 2>/dev/null; exit 1"

    data class Staged(val archive: File, val sha256: String)
    data class Result(val successful: Boolean, val message: String, val logs: List<String>)
    data class Activation(val active: Boolean, val message: String, val logs: List<String>)
    internal data class ActivationReadiness(val ready: Boolean, val idle: Boolean = false)
    data class ModuleVersion(val version: String, val versionCode: BigInteger) {
        val description: String get() = if (version.isBlank()) "versionCode=$versionCode"
            else "$version（versionCode=$versionCode）"
    }
    data class InstalledModule(val location: String, val metadata: ModuleVersion)

    fun stage(directory: File, openAsset: (String) -> InputStream): Staged {
        check(directory.isDirectory || directory.mkdirs()) { "创建模块安装目录失败" }
        val hash = openAsset(HASH_ASSET).bufferedReader().use { it.readText() }
            .trim().split(Regex("\\s+"), limit = 2).first().lowercase()
        require(Regex("[0-9a-f]{64}").matches(hash)) { "内置模块 SHA-256 清单无效" }
        val temporary = File.createTempFile(".hnubl-ota-", ".zip", directory)
        val archive = File(directory, "hnubl-ota.zip")
        try {
            openAsset(MODULE_ASSET).use { input -> temporary.outputStream().use { output ->
                input.copyTo(output)
                output.fd.sync()
            } }
            check(temporary.length() > 0 && sha256(temporary) == hash) { "内置模块 SHA-256 校验失败" }
            Files.move(temporary.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return Staged(archive, hash)
        } finally { temporary.delete() }
    }

    fun execute(
        logDirectory: File,
        moduleArchive: File,
        expectedSha256: String,
        checkRoot: (File) -> ShellResult = { RootShell.check(it) },
        runRoot: (String, File) -> ShellResult = { command, log ->
            if (command == ACTIVATE_COMMAND || command == VERSION_QUERY_COMMAND) {
                // Service logs must not be confused with the status document on stdout.
                val status = File(log.parentFile,
                    if (command == ACTIVATE_COMMAND) "module-status.json" else "module-versions.txt")
                val result = RootShell.capture(command, status, log, if (command == ACTIVATE_COMMAND) 45 else 20)
                ShellResult(result.exitCode, status.readText())
            } else RootShell.run(command, log, if (command == CHECK_COMMAND) 20 else 0)
        },
        activate: (File, (String, File) -> ShellResult) -> Activation = { directory, executor ->
            activateInstalled(directory, executor)
        },
        onLog: (String) -> Unit = {},
    ): Result {
        val logs = mutableListOf<String>()
        fun emit(message: String) {
            logs += message
            // A presentation observer never changes a completed install or starts a retry.
            try { onLog(message) } catch (_: Exception) { /* The retained log remains available. */ }
        }
        fun record(label: String, result: ShellResult) {
            emit("[更新模块] $label · 退出码 ${result.exitCode}")
            if (result.output.isNotBlank()) result.output.lines().forEach(::emit)
        }
        return try {
            check(logDirectory.isDirectory || logDirectory.mkdirs()) { "创建模块安装日志目录失败" }
            require(Regex("[0-9a-fA-F]{64}").matches(expectedSha256)) { "内置模块 SHA-256 清单无效" }
            check(moduleArchive.isFile && moduleArchive.length() > 0 &&
                sha256(moduleArchive).equals(expectedSha256, ignoreCase = true)) { "内置模块 SHA-256 校验失败" }
            emit("[更新模块] 内置 ZIP SHA-256=$expectedSha256")
            val bundled = readBundledVersion(moduleArchive)
            emit("[更新模块] 内置模块 ${bundled.description}")
            emit("[更新模块] 正在检查 Root 授权…")
            val root = checkRoot(File(logDirectory, "root-proof.log"))
            record("检查 Root", root)
            check(root.exitCode == 0 && root.output.lineSequence().any { it == "ROOT_UID=0" }) {
                "尚未获得 Root 权限，请先提权或在 Root 管理器中授权本应用"
            }
            emit("[更新模块] 正在读取已安装与待更新模块版本…")
            val queried = runRoot(VERSION_QUERY_COMMAND, File(logDirectory, "module-version-query.log"))
            record("读取已安装和待更新模块版本", queried)
            check(queried.exitCode == 0) { "模块版本读取失败，请查看日志；本次未安装或覆盖模块" }
            val existing = parseInstalledVersions(queried.output)
            for (item in existing) emit("[更新模块] ${locationName(item.location)} ${item.metadata.description}")
            val newest = existing.maxByOrNull { it.metadata.versionCode }
            if (newest != null && newest.metadata.versionCode >= bundled.versionCode) {
                emit("[更新模块] $LATEST_MESSAGE；保留${locationName(newest.location)}，不重复安装或降级")
                if (newest.location == "pending") emit("[更新模块] 较新或相同版本位于 KernelSU 待更新目录；本次未重新安装或启动它")
                val installedCode = existing.firstOrNull { it.location == "installed" }?.metadata?.versionCode
                val pendingCode = existing.firstOrNull { it.location == "pending" }?.metadata?.versionCode
                if (installedCode != null && pendingCode != null && pendingCode < installedCode)
                    emit("[更新模块] 注意：待更新目录中的版本低于已安装版本，请在 KernelSU 核对待更新状态，避免下次重启后降级")
                return Result(true, LATEST_MESSAGE, logs.toList())
            }
            emit(if (existing.isEmpty()) "[更新模块] 尚未安装模块，准备首次安装"
                else "[更新模块] 内置模块版本较新，准备安装更新")
            emit("[更新模块] 正在检查 KernelSU 模块安装器…")
            val installer = runRoot(CHECK_COMMAND, File(logDirectory, "installer-check.log"))
            record("检查 KernelSU 模块安装器", installer)
            check(installer.exitCode == 0 && installer.output.lineSequence().any { it == "HNUBL_KSUD_READY=1" }) {
                "当前 Root 环境没有可用的 KernelSU 模块安装器，请先在 KernelSU 管理器中完成环境安装"
            }
            val command = "$KSUD module install ${shellQuote(moduleArchive.canonicalPath)}"
            emit("[更新模块] $command")
            emit("[更新模块] 正在安装模块，请耐心等待安装器完成…")
            val installed = runRoot(command, File(logDirectory, "module-install.log"))
            record("安装模块", installed)
            check(installed.exitCode == 0) { "模块安装失败（${installed.exitCode}），请查看安装日志" }
            emit("[更新模块] 安装完成，正在即时启用并确认本次保护状态…")
            val activation = activate(logDirectory, runRoot)
            activation.logs.forEach(::emit)
            Result(activation.active, activation.message, logs.toList())
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            emit("[更新模块] $message")
            Result(false, message, logs.toList())
        }
    }

    private fun locationName(location: String) = if (location == "pending") "待更新模块" else "已安装模块"

    internal fun readBundledVersion(archive: File): ModuleVersion = ZipFile(archive).use { zip ->
        val entries = zip.entries().asSequence().filter { it.name == "module.prop" }.toList()
        check(entries.size == 1 && !entries.single().isDirectory) { "内置模块 ZIP 的 module.prop 缺失或重复" }
        val properties = zip.getInputStream(entries.single()).bufferedReader(Charsets.UTF_8).use { it.readText() }
        parseVersion(properties, "内置模块")
    }

    /** Parse only literal property values. No shell evaluation, guessing or app-version fallback. */
    internal fun parseVersion(properties: String, source: String): ModuleVersion {
        check(!properties.contains('\u0000')) { "$source 的 module.prop 含无效字符" }
        val relevant = setOf("id", "version", "versionCode")
        val values = mutableMapOf<String, String>()
        properties.removePrefix("\uFEFF").lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith('!')) return@forEach
            val separator = line.indexOf('=')
            if (separator < 0) return@forEach
            val key = line.substring(0, separator).trim()
            if (key !in relevant) return@forEach
            check(values.put(key, line.substring(separator + 1).trim()) == null) { "$source 的 $key 重复，版本信息无效" }
        }
        check(values["id"] == MODULE_ID) { "$source 的模块 ID 不匹配" }
        val code = values["versionCode"]
        check(code != null && code.matches(Regex("[0-9]+"))) { "$source 的 versionCode 缺失或无效，请核对 module.prop；本次未覆盖模块" }
        return ModuleVersion(values["version"].orEmpty(), BigInteger(code))
    }

    internal fun parseInstalledVersions(output: String): List<InstalledModule> {
        val lines = output.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var index = 0
        fun expect(expected: String) {
            check(index < lines.size && lines[index++] == expected) { "模块版本查询结果不完整或格式无效；本次未覆盖模块" }
        }
        expect("HNUBL_MODULE_QUERY_V1")
        val installed = mutableListOf<InstalledModule>()
        for (location in listOf("installed", "pending")) {
            expect("HNUBL_MODULE_BEGIN=$location")
            check(index < lines.size) { "模块版本查询结果不完整" }
            val state = lines[index++]
            val properties = mutableListOf<String>()
            val end = "HNUBL_MODULE_END=$location"
            while (index < lines.size && lines[index] != end) properties += lines[index++]
            expect(end)
            when (state) {
                "HNUBL_MODULE_STATE=absent" -> check(properties.all(String::isBlank)) { "模块不存在状态与返回内容冲突" }
                "HNUBL_MODULE_STATE=present" -> installed += InstalledModule(location,
                    parseVersion(properties.joinToString("\n"), locationName(location)))
                else -> error("模块版本查询状态无效；本次未覆盖模块")
            }
        }
        expect("HNUBL_MODULE_QUERY_END")
        check(lines.drop(index).all(String::isBlank)) { "模块版本查询结果包含未识别内容" }
        return installed.toList()
    }

    fun activateInstalled(
        logDirectory: File,
        runRoot: (String, File) -> ShellResult,
        readReadiness: (String) -> ActivationReadiness = ::parseActivationReadiness,
    ): Activation = try {
        val activationLog = File(logDirectory, "module-activate.log")
        val result = runRoot(ACTIVATE_COMMAND, activationLog)
        val observed = runCatching { readReadiness(result.output) }.getOrDefault(ActivationReadiness(false))
        val ready = result.exitCode == 0 && observed.ready
        Activation(ready,
            if (ready && observed.idle) "更新 KSU 模块已安装并即时启用，下次打开软件更新时生效，无需重启手机。"
            else if (ready) "更新 KSU 模块已安装并即时启用，软件更新保护已就绪，未重启手机。"
            else "更新 KSU 模块已安装，即时启用尚未就绪；请查看模块状态与激活日志。",
            buildList {
                add("[更新模块] 即时启用 · 退出码 ${result.exitCode} · ready=$ready · waiting_for_updater=${ready && observed.idle}")
                if (result.output.isNotBlank()) addAll(result.output.lines())
                if (activationLog.isFile) addAll(activationLog.readLines())
            })
    } catch (error: Exception) {
        Activation(false, "更新 KSU 模块已安装，即时启用失败：${error.message ?: error.javaClass.simpleName}",
            listOf("[更新模块] 即时启用失败：${error.message ?: error.javaClass.simpleName}"))
    }

    /** The shell returns its expected identity separately from the atomic status snapshot. */
    internal fun parseActivationReadiness(output: String): ActivationReadiness {
        val normalized = output.replace("\r\n", "\n")
        val header = normalized.substringBefore('\n')
        if (!header.startsWith(ACTIVATION_ID_PREFIX)) return ActivationReadiness(false)
        val expected = header.removePrefix(ACTIVATION_ID_PREFIX)
        if (!expected.matches(Regex("[A-Za-z0-9-]+"))) return ActivationReadiness(false)
        val status = JSONObject(normalized.substringAfter('\n', ""))
        if (status.opt("activation_id") != expected || status.opt("controller_ready") != true ||
            status.opt("broker_ready") != true || status.opt("error") !is String ||
            (status.opt("error") as String).isNotBlank()) return ActivationReadiness(false)
        val running = status.opt("updater_running") == true && status.opt("critical_ready") == true &&
            status.opt("hook_ready") == true && status.opt("waiting_for_updater") == false
        val idle = status.opt("updater_running") == false && status.opt("waiting_for_updater") == true &&
            status.opt("hook_ready") == false
        return ActivationReadiness(running || idle, idle)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
