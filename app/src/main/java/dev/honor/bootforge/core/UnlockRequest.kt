package dev.honor.bootforge.core

enum class UnlockMode(val label: String) {
    FAKE_LOCK("假回锁解锁"), DIRECT("仅解锁"), CUSTOM("自定义刷写")
}
enum class AblWriteSource(val label: String) {
    UPLOADED("使用上传版本"), BUILTIN("使用内置版本")
}

enum class AblWriteTarget(val label: String) {
    CURRENT("当槽刷写"), OTHER("对槽刷写"), BOTH("双槽刷写");

    /** The running slot must be known before even a dual-slot selection is resolved. */
    fun partitionNames(slot: String): List<String> {
        val current = when (slot) {
            "a", "_a" -> "a"
            "b", "_b" -> "b"
            else -> return emptyList()
        }
        val other = if (current == "a") "b" else "a"
        return when (this) {
            CURRENT -> listOf("abl_$current")
            OTHER -> listOf("abl_$other")
            BOTH -> listOf("abl_$current", "abl_$other")
        }
    }
}
enum class EfispSource(val label: String) {
    UPLOADED_EFISP("刷入上传版本的 efisp"),
    UPLOADED_ABL("将上传版本的 ABL 修补为 efisp"),
    CURRENT_SLOT_ABL("将当前槽位的 ABL 修补为 efisp"),
    OTHER_SLOT_ABL("将对槽的 ABL 修补为 efisp"),
    BUILTIN_ABL("将应用内置的 ABL 修补为 efisp")
}

/** Resolve the extraction source from fresh running-slot evidence, never from the write target. */
internal fun ablSnapshotSourceSlot(runningSlot: String, useOtherSlot: Boolean): String {
    val current = when (runningSlot) {
        "a", "_a" -> "a"
        "b", "_b" -> "b"
        else -> throw IllegalArgumentException("运行槽位尚未形成有效 A/B 身份")
    }
    return if (useOtherSlot) { if (current == "a") "b" else "a" } else current
}
enum class EfispPatchMode(val label: String) {
    FAKE_LOCK("假回锁模式"), FREEDOM("仅解锁模式")
}

/** The explicit mode also determines the completion action for an uploaded EFI. */
enum class UnlockCompletion(val destination: String, val rebootCommand: String) {
    BOOTLOADER("Fastboot", "reboot bootloader"),
    RECOVERY("Recovery", "reboot recovery");

    val requiresRecoveryFormat: Boolean get() = this == RECOVERY

    fun commandIfReady(canReboot: Boolean, writesAllowed: Boolean, busy: Boolean): String? =
        rebootCommand.takeIf { canReboot && writesAllowed && !busy }
}

/** Immutable selection. Automatic modes always use built-in ABL and current-slot writes. */
data class UnlockRequest(
    val mode: UnlockMode = UnlockMode.FAKE_LOCK,
    val ablSource: AblWriteSource = AblWriteSource.BUILTIN,
    val efispSource: EfispSource = EfispSource.CURRENT_SLOT_ABL,
    val patchMode: EfispPatchMode = EfispPatchMode.FAKE_LOCK,
    val ablTarget: AblWriteTarget = AblWriteTarget.CURRENT,
) {
    val completion: UnlockCompletion get() =
        if (resolved().patchMode == EfispPatchMode.FAKE_LOCK) UnlockCompletion.BOOTLOADER else UnlockCompletion.RECOVERY

    fun resolved(): UnlockRequest = when (mode) {
        UnlockMode.CUSTOM -> this
        UnlockMode.FAKE_LOCK -> copy(ablSource = AblWriteSource.BUILTIN,
            efispSource = EfispSource.CURRENT_SLOT_ABL, patchMode = EfispPatchMode.FAKE_LOCK,
            ablTarget = AblWriteTarget.CURRENT)
        UnlockMode.DIRECT -> copy(ablSource = AblWriteSource.BUILTIN,
            efispSource = EfispSource.CURRENT_SLOT_ABL, patchMode = EfispPatchMode.FREEDOM,
            ablTarget = AblWriteTarget.CURRENT)
    }

    fun encode(): String = listOf(mode.name, ablSource.name, efispSource.name, patchMode.name, ablTarget.name).joinToString(":")

    /** Old execution bundles without a target field retain their original current-slot behavior. */
    fun matchesPayloadBinding(binding: Map<String, String>): Boolean {
        val selected = resolved()
        return binding["REQUEST_MODE"] == selected.mode.name && binding["EFI_SOURCE"] == selected.efispSource.name &&
            binding["PATCH_MODE"] == selected.patchMode.name &&
            (binding["ABL_WRITE_TARGET"] ?: AblWriteTarget.CURRENT.name) == selected.ablTarget.name
    }

    companion object {
        fun decode(value: String): UnlockRequest {
            val parts = value.split(':')
            require(parts.size in 4..5) { "解锁选项记录无效" }
            return UnlockRequest(UnlockMode.valueOf(parts[0]), AblWriteSource.valueOf(parts[1]),
                EfispSource.valueOf(parts[2]), EfispPatchMode.valueOf(parts[3]),
                if (parts.size == 5) AblWriteTarget.valueOf(parts[4]) else AblWriteTarget.CURRENT)
        }
    }
}
