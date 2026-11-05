package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp

/** Existing per-feature golden contracts predate independent VM/command/key policies.
 * Restore only the explicitly reported new feature spans in a test-owned copy.
 * The real default API output is tested independently by FlashingUnlockPatcherTest
 * and the instruction-execution matrix; this helper is excluded from the App. */
internal fun PatchResult.preVmHistoricalView(): PatchResult {
    // The receipt owns its full immutable RX mapping and native hook. Validate
    // and remove this outer layer before touching any byte covered by its hash.
    // VM production output, runtime and text/reimport are tested separately by
    // VmDtboCompatTest and the actual emitted ARM64 selector-thunk suite.
    val withoutVm = VmDtboCompatPatcher.remove(output)
    val vm = patches.filter { it.name.startsWith("VM DTBO：") }
    check(vm.isNotEmpty() == !withoutVm.contentEquals(output)) { "VM receipt/report ownership disagrees" }
    val keys = patches.filter { it.name == "音量下进入 Fastboot 不要求插线/charger 资格" }
    check(keys.size <= 1 && keys.all { it.after.split(' ').size == 4 }) { "Unexpected independent key policy span" }
    val restored = withoutVm.copyOf()
    for(patch in keys) {
        val before=patch.before.split(' ').map { it.toInt(16).toByte() }.toByteArray()
        val after=patch.after.split(' ').map { it.toInt(16).toByte() }.toByteArray()
        check(before.size==4&&after.size==4&&restored.matches(patch.fileOffset,after)) { "Key report does not describe actual output bytes" }
        before.copyInto(restored,patch.fileOffset)
    }
    val retained=patches.filterNot { it in vm || it in keys }
    return copy(output=restored,outputSha256=restored.sha256(),imageSize=restored.size,patches=retained,
        alreadyPatched=retained.all { it.alreadyApplied })
}

private fun PatchResult.legacyView(): PatchResult {
    val view=preVmHistoricalView()
    val patches=view.patches
    val flashing = patches.filter { it.name.startsWith("flashing unlock：") }
    val independent = patches.filter { it.name.startsWith("oem superunlock：") }
    val dataHash = patches.filter { it.name.startsWith("AVB HashDescriptor") }
    // Historical golden contracts describe pre-PSS output. The real new policy
    // and complete API output are asserted in PssVerificationPatcherTest.
    val pss = patches.filter { it.name == "共用 RSA-PSS 验证入口固定成功" }
    val policy = patches.filter { it.name.startsWith("AVB 低索引") || it.name.startsWith("OEM 版本") ||
        it.name.startsWith("BRINGUPAUTH") || it.name.startsWith("AVB 版本兼容") }
    val added = flashing + independent + dataHash + policy + pss
    if (added.isEmpty()) return view
    if (flashing.isNotEmpty() || independent.isNotEmpty()) check(flashing.size == 5 && independent.size >= 2) { "Incomplete command feature report" }
    check(dataHash.all { it.after.split(' ').size == 4 }) { "Unexpected data-hash edit span" }
    check(pss.size <= 1 && pss.all { it.after.split(' ').size in setOf(8, 12) }) { "Unexpected PSS policy edit span" }
    val restored = view.output.copyOf()
    for (patch in added) {
        val before = patch.before.split(' ').map { it.toInt(16).toByte() }.toByteArray()
        check(before.size == patch.after.split(' ').size)
        before.copyInto(restored, patch.fileOffset)
    }
    val retained = patches.filterNot { it in added }
    // Restore the specifically reported new mapping fields first, then discard
    // the now-unmapped appended section for historical baseline-only checks.
    // Expanded production output and reimport are tested separately.
    val originalMapping = PeImage.parse(restored).bytes
    return view.copy(output = originalMapping, outputSha256 = originalMapping.sha256(),imageSize=originalMapping.size, patches = retained,
        alreadyPatched = retained.all { it.alreadyApplied })
}

internal fun legacyPatch(input: ByteArray, mode: PatchMode = PatchMode.FREEDOM): PatchResult =
    AblEfisp.patch(input, mode).legacyView()

internal fun legacyEnginePatch(input: ByteArray, mode: PatchMode = PatchMode.FREEDOM): PatchResult =
    PatchEngine.analyzeAndPatch(input, mode).legacyView()
