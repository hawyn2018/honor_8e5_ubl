package net.hawyn.ablefisp.patch

import net.hawyn.ablefisp.api.AblEfisp
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/** Local firmware fixtures exercise the actual final pipeline. No device I/O. */
class FastbootRecoveryIntegrationTest {
    private fun workspace():File? = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it,"analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe").isFile }

    @Test fun twoFirmwareFamiliesBothModesRoundtripAndEmitInstructionFixtures() {
        val root=workspace(); assumeNotNull(root)
        val output=File(System.getProperty("user.dir"),"build/fastboot-recovery-fixtures").apply { mkdirs() }
        for((family,relative) in listOf("old" to "analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe",
            "new" to "analysis/ablnew-compat/ablnew-inner.pe")) {
            val path=File(root,relative);assumeNotNull(path.takeIf { it.isFile })
            val original=path.readBytes(); val sourceHash=original.sha256()
            for(mode in PatchMode.entries) {
                val base=PatchEngine.analyzeAndPatch(original,mode)
                val appLabel="device freedom (github hawyn2018)"
                val customized=AblEfisp.editText(base.output,mapOf("fastboot-unlocked" to appLabel),false)
                assertEquals(appLabel,AblEfisp.scanText(customized.output).entries.single { it.id=="fastboot-unlocked" }.text)
                val native=KblPatcher.patch(customized.output,BundledKblDriver.load()).bytes
                val saved=native.copyOf();val before=PeImage.parse(native)
                val facts=FastbootRecoveryFacts.analyze(before)
                assertTrue("$family: ${facts.prerequisites.note}",facts.prerequisites.earlyJumpProven)
                val slots=FastbootSlotRecoveryFacts.analyze(before)
                val failure=AutoBootFailureFacts.analyze(before,facts)
                val result=FastbootRecoveryPatcher.patch(native);val after=PeImage.parse(result.bytes)
                assertArrayEquals(saved,native);assertEquals(sourceHash,original.sha256())
                assertEquals(before.entryRva,after.entryRva);assertEquals(before.sections,after.sections.take(before.sections.size))
                assertArrayEquals(native,FastbootRecoveryPatcher.remove(result.bytes))
                assertArrayEquals(result.bytes,FastbootRecoveryPatcher.patch(result.bytes).bytes)
                assertArrayEquals(result.bytes,AblEfisp.patchWithKbl(result.bytes,mode).output)
                // Verify every original byte not described by a precise hook is
                // unchanged. This includes native retry accounting, command
                // bodies, shared reset services, harmless branches and KBL.
                val edited=BooleanArray(native.size)
                result.patches.forEach { p ->
                    val at=requireNotNull(before.rvaToOffset(p.rva,p.after.length/2))
                    repeat(p.after.length/2) { edited[at+it]=true }
                }
                for(n in before.headersSize until native.size) if(!edited[n])
                    assertEquals("Unowned byte changed at $family:$n",native[n],result.bytes[n])
                slots.retryDecrementWindows.forEach { w ->
                    assertEquals(before.word(w.mutation.storeRva),after.word(w.mutation.storeRva))
                    assertEquals(before.word(w.mutation.commitCallRva),after.word(w.mutation.commitCallRva))
                }
                failure.manualActionSites.forEach { action -> action.actionCallRvas.forEach { pc ->
                    assertEquals("manual ${action.command}",before.word(pc),after.word(pc))
                } }
                // Existing accepted modified-init_boot boot policy still has
                // its own successful RET; only its fatal tail is redirected.
                BootFailurePolicyPatcher.analyze(before).mapNotNull { it.existingStubRva }.distinct().forEach { stub ->
                    assertEquals(before.word(stub+12),after.word(stub+12))
                    assertEquals(0xd65f03c0.toInt(),after.word(stub+12))
                }
                val name="$family-${mode.name.lowercase()}"
                File(output,"$name-native.img").writeBytes(native)
                File(output,"$name.img").writeBytes(result.bytes)
                val metadata=linkedMapOf<String,Any?>(
                    "name" to name,"sourcePath" to path.absolutePath,"sourceSha256" to sourceHash,
                    "fastbootLabel" to appLabel,
                    "nativePath" to File(output,"$name-native.img").absolutePath,
                    "imagePath" to File(output,"$name.img").absolutePath,"outputSha256" to result.bytes.sha256(),
                    "imageBase" to after.imageBase,"entryRva" to after.entryRva,
                    "moduleRva" to result.metadata["rxRva"],"stateRva" to result.metadata["stateRva"],
                    "checkpointRva" to facts.prerequisites.checkpointRva,"fastbootSetupRva" to facts.fastbootSetupRva,
                    "recoveryEntryRva" to facts.prerequisites.recoveryEntryRva,
                    "preparationRoute" to facts.prerequisites.preparationRoute?.let { mapOf(
                        "entryRva" to it.entryRva,"successGateRva" to it.successGateRva,
                        "word" to it.originalSuccessWord.toLong().and(0xffffffffL),"statusRegister" to it.statusRegister,
                        "successDestinationRva" to it.successDestinationRva,
                        "globalRegisters" to it.afterPreparationGlobalRegisters,"constantRegisters" to it.afterPreparationConstants) },
                    "requestFlagBranchRva" to facts.requestFlagBranchRva,"fastbootGateRva" to facts.fastbootGateRva,
                    "lateRecoveryHookRva" to facts.normalBootRva,
                    "scopePrefix" to facts.scopePrefix?.let { mapOf("startRva" to it.startRva,
                        "words" to it.originalWords.map { w -> w.toLong().and(0xffffffffL) },
                        "loggerCallRva" to it.loggerCallRva,"loggerTargetRva" to it.loggerTargetRva,
                        "resumeRva" to it.resumeRva,"formatRva" to it.formatRva,"formatRegister" to it.formatRegister) },
                    "requestFlagRva" to facts.requestFlagRva,"continueFlagRva" to facts.continueFlow.flagRva,
                    "globalRegisters" to facts.prerequisites.materializableGlobalRegisters,
                    "constantRegisters" to facts.prerequisites.materializableConstants,
                    "canaryRva" to facts.canaryGlobalRva,"canaryBytes" to facts.canaryWidth,
                    "mainStart" to facts.main.start,"mainEnd" to facts.main.end,
                    "authTargetRva" to facts.continueFlow.authTargetRva,"bootLinuxRva" to facts.continueFlow.bootLinuxTargetRva,
                    "patches" to result.patches.map { mapOf("name" to it.name,"rva" to it.rva,"before" to it.before,"after" to it.after) },
                    "defaultSites" to slots.defaultInitializationSites.map { mapOf("hookRva" to it.decisionRva,
                        "word" to it.decisionOriginalWord.toLong().and(0xffffffffL),"errorRva" to it.nativeErrorBranchRva,
                        "defaultBranchTaken" to it.defaultBranchTaken,"nativeDefaultBranchRva" to it.nativeDefaultBranchRva,
                        "outputRegister" to it.outputRegisterAtDecision,"outputBytes" to it.outputBytes) },
                    "autoSites" to slots.automaticCallSites.map { mapOf("hookRva" to it.callRva,"kind" to it.kind.name,
                        "requiresDeferredCallback" to it.requiresDeferredCallback,"callbackNaturalResumeRva" to it.callbackNaturalResumeRva,
                        "callbackResultDeadRva" to it.callbackResultDeadRva,"callbackEntryRva" to it.callbackEntryRva,
                        "safeWaitResumeRvas" to it.safeWaitResumeRvas) },
                    "failureSites" to failure.sites.map { mapOf("hookRva" to it.hookRva,"word" to it.originalWord.toLong().and(0xffffffffL),
                        "targetRva" to it.targetCallee,"kind" to it.kind.name,"context" to it.executionContext.name,
                        "ownerStart" to it.ownerStart,"ownerEnd" to it.ownerEnd,"existingPolicyStubRva" to it.existingPolicyStubRva,
                        "normalContinueGuard" to it.normalContinueGuard,"safeWaitRva" to it.safeWaitResumeRva,
                        "callbackReturnAbiProven" to it.callbackReturnAbiProven) },
                    "hashPolicies" to BootFailurePolicyPatcher.analyze(before).map { mapOf(
                        "callRva" to it.callRva,"existingStubRva" to it.existingStubRva,"originalTarget" to it.originalTarget,
                        "allowRegister" to it.allowRegister,"errorRegister" to it.errorRegister) },
                    "safeWaits" to failure.safeWaitResumes.map { mapOf("hookRva" to it.hookRva,"word" to it.originalWord.toLong().and(0xffffffffL),
                        "cleanupRva" to it.cleanupRva,"eventSlotRva" to it.eventSlotRva) },
                    "deviceOperations" to false)
                File(output,"$name.json").writeText(json(metadata)+"\n")
            }
        }
    }

    private fun json(v:Any?):String = when(v) {
        null -> "null"
        is String -> "\""+v.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\""
        is Number,is Boolean -> v.toString()
        is Map<*,*> -> v.entries.joinToString(",","{","}") { json(it.key.toString())+":"+json(it.value) }
        is Iterable<*> -> v.joinToString(",","[","]") { json(it) }
        else -> error("Unsupported metadata type ${v.javaClass}")
    }
}
