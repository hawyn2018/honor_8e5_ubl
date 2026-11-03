package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FastbootSlotRecoveryFactsTest {
    private val samples = listOf(
        "analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe",
        "analysis/ablnew-compat/ablnew-inner.pe",
    )

    private fun sample(path: String): PeImage {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(7).firstOrNull { File(it, path).isFile }
        assumeTrue("Local native firmware fixture: $path", root != null)
        return PeImage.parse(File(root!!, path).readBytes())
    }

    private val singleMarkSample = "analysis/bkqan90-abl-patch-failure-20261102/fakebl138/input-inner.pe"

    private fun markSite(image: PeImage, plan: FastbootSlotRecoveryFacts.Plan): Pair<FastbootSlotRecoveryFacts.Mutation, Long> {
        val mutations = plan.automaticCallSites.first {
            it.kind == FastbootSlotRecoveryFacts.Kind.HANDLE_ACTIVE_UNBOOTABLE
        }.nativeMutationEvidence
        return mutations.firstNotNullOf { mutation ->
            (mutation.pairedReadRva + 4..<mutation.storeRva step 4).firstOrNull { pc ->
                image.word(pc)!! and 0x7f800000 == 0x32000000
            }?.let { mutation to it }
        }
    }

    @Test fun aSingleNativeMarkCommitAndAnOptionalAlternateClearProveTheSameTransaction() {
        for (path in samples + singleMarkSample) {
            val image = sample(path)
            val plan = FastbootSlotRecoveryFacts.analyze(image)
            val handlers = plan.automaticCallSites.filter { it.kind == FastbootSlotRecoveryFacts.Kind.HANDLE_ACTIVE_UNBOOTABLE }
            assertTrue(handlers.isNotEmpty())
            if (path == singleMarkSample) assertTrue(handlers.all { it.nativeMutationEvidence.size == 1 })
            else assertTrue(handlers.all { it.nativeMutationEvidence.size >= 2 })
            assertTrue(requireNotNull(plan.evidence["handlerMarkMutations"]) >= 1)
            val automatic = requireNotNull(plan.evidence["handlerSetActiveCall"])
            val reset = requireNotNull(plan.evidence["handlerResetCall"])
            assertEquals(plan.sharedSetActiveRva, A64.blTarget(automatic, image.word(automatic)!!))
            assertTrue(reset > automatic)
            assertEquals(0xd63f0000.toInt(), image.word(reset)!! and 0xfffffc1f.toInt())
            assertTrue(handlers.all { it.nativeMutationEvidence.any { m -> m.commitCallRva < automatic } })
            assertTrue(plan.automaticCallSites.none { it.callRva in plan.manualSetActiveCalls })
        }
    }

    @Test fun changingTheNativeBitOperationDoesNotHideTheAutomaticSlotTransaction() {
        val image = sample(singleMarkSample)
        val plan = FastbootSlotRecoveryFacts.analyze(image)
        val (_, mark) = markSite(image, plan)
        val changed = image.bytes.copyOf()
        changed.putWord(requireNotNull(image.rvaToOffset(mark, 4)), 0xd503201f.toInt())
        val found = FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed))
        assertEquals(plan.automaticCallSites.map { it.callRva }, found.automaticCallSites.map { it.callRva })
        assertEquals(plan.evidence["handlerResetCall"], found.evidence["handlerResetCall"])
    }

    @Test fun noCommittedNativeAttributeMutationDoesNotProveTheHandler() {
        val image = sample(singleMarkSample)
        val plan = FastbootSlotRecoveryFacts.analyze(image)
        val changed = image.bytes.copyOf()
        plan.automaticCallSites.first {
            it.kind == FastbootSlotRecoveryFacts.Kind.HANDLE_ACTIVE_UNBOOTABLE
        }.nativeMutationEvidence.map { it.commitCallRva }.distinct().forEach { call ->
            changed.putWord(requireNotNull(image.rvaToOffset(call, 4)), 0xd503201f.toInt())
        }
        val error = assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)) }
        assertTrue(error.message.orEmpty().contains("unbootable 标记"))
    }

    @Test fun markingAndSetActiveWithoutTheRealResetConsumerDoNotProveTheWholeHandler() {
        val image = sample(singleMarkSample)
        val plan = FastbootSlotRecoveryFacts.analyze(image)
        val reset = requireNotNull(plan.evidence["handlerResetCall"])
        val changed = image.bytes.copyOf()
        changed.putWord(requireNotNull(image.rvaToOffset(reset, 4)), 0xd63f0000.toInt() or (9 shl 5))
        val error = assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)) }
        assertTrue(error.message.orEmpty().contains("unbootable 标记"))
    }

    @Test fun attributeReadMarkAndStoreMayUseARenamedRegister() {
        val image = sample(singleMarkSample)
        val before = FastbootSlotRecoveryFacts.analyze(image)
        val (mutation, mark) = markSite(image, before)
        val read = image.word(mutation.pairedReadRva)!!
        val store = image.word(mutation.storeRva)!!
        val operation = image.word(mark)!!
        val old = operation and 31
        val fieldShift = if (store and 31 == old) 0 else 10
        assertEquals(old, (read ushr fieldShift) and 31)
        val changed = image.bytes.copyOf()
        fun word(pc: Long, value: Int) { changed.putWord(requireNotNull(image.rvaToOffset(pc, 4)), value) }
        word(mutation.pairedReadRva, (read and (31 shl fieldShift).inv()) or (12 shl fieldShift))
        word(mark, (operation and (31 or (31 shl 5)).inv()) or 12 or (12 shl 5))
        word(mutation.storeRva, (store and (31 shl fieldShift).inv()) or (12 shl fieldShift))
        val after = FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed))
        assertEquals(before.evidence["handlerSetActiveCall"], after.evidence["handlerSetActiveCall"])
        assertEquals(before.evidence["handlerResetCall"], after.evidence["handlerResetCall"])
        assertEquals(before.automaticCallSites.map { it.callRva }, after.automaticCallSites.map { it.callRva })
    }

    @Test fun discoversOldAndNewAutomaticSitesFromTheSameNativeRelations() {
        for (path in samples) {
            val image = sample(path)
            val before = image.bytes.copyOf()
            val plan = FastbootSlotRecoveryFacts.analyze(image)
            assertTrue(path, plan.enabled)
            assertEquals(path, 6, plan.automaticCallSites.size)
            assertEquals(1, plan.automaticCallSites.count { it.kind == FastbootSlotRecoveryFacts.Kind.MAIN_ROLLBACK_SET_ACTIVE })
            assertEquals(1, plan.automaticCallSites.count { it.kind == FastbootSlotRecoveryFacts.Kind.BOOT_DETECTOR_SET_ACTIVE })
            assertEquals(4, plan.automaticCallSites.count { it.kind == FastbootSlotRecoveryFacts.Kind.HANDLE_ACTIVE_UNBOOTABLE })
            assertEquals(1, plan.manualSetActiveCalls.size)
            assertEquals(1, plan.retryDecrementWindows.size)
            assertArrayEquals(before, image.bytes)
        }
    }

    @Test fun everyHookPrecedesNativeMutationAndKeepsManualAndSharedFunctionsUntouched() {
        for (path in samples) {
            val image = sample(path)
            val plan = FastbootSlotRecoveryFacts.analyze(image)
            val manual = plan.manualSetActiveCalls.single()
            assertEquals(plan.sharedSetActiveRva, A64.blTarget(manual, image.word(manual)!!))
            assertTrue(plan.automaticCallSites.none { it.callRva in plan.manualSetActiveCalls })
            assertTrue(plan.automaticCallSites.none { it.ownerStartRva == plan.sharedSetActiveRva })
            for (site in plan.automaticCallSites) {
                assertEquals(site.nativeEntryRva, A64.blTarget(site.callRva, site.originalWord))
                assertEquals(site.originalWord, image.word(site.callRva))
                assertTrue(site.hookBeforeNativeEntry)
                assertTrue(site.requiresRecoveryRequest)
                assertTrue(site.nativeMutationEvidence.size >= 2)
                assertTrue(site.nativeMutationEvidence.all { it.storeRva >= site.nativeEntryRva })
                assertTrue(site.nativeMutationEvidence.all { mutation ->
                    A64.stpW(image.word(mutation.storeRva)!!) != null &&
                        A64.blTarget(mutation.commitCallRva, image.word(mutation.commitCallRva)!!) == mutation.commitRva
                })
            }
        }
    }

    @Test fun normalRetryAccountingIsReportedWithoutBecomingAnAutomaticSwitchHook() {
        val image = sample(samples[1])
        val plan = FastbootSlotRecoveryFacts.analyze(image)
        val window = plan.retryDecrementWindows.single()
        assertTrue(window.preserveNormalAttemptAccounting)
        assertTrue(window.mutation.storeRva > window.referenceRva)
        assertTrue(plan.automaticCallSites.none { it.callRva == window.mutation.commitCallRva })
    }

    @Test fun indirectUefiNotificationHandlerDefersBeforeAnySlotMutation() {
        for (path in samples) {
            val image = sample(path)
            val plan = FastbootSlotRecoveryFacts.analyze(image)
            val callback = plan.automaticCallSites.single { it.requiresDeferredCallback }
            assertEquals(FastbootSlotRecoveryFacts.Kind.HANDLE_ACTIVE_UNBOOTABLE, callback.kind)
            assertEquals(callback.callRva + 4, callback.callbackNaturalResumeRva)
            assertTrue(requireNotNull(callback.callbackResultDeadRva) > callback.callRva)
            assertNotNull(callback.callbackEntryRva)
            assertNotNull(callback.callbackRegistrationRva)
            assertNotNull(callback.callbackDispatchCallRva)
            assertFalse(callback.callbackFunctionPointerRvas.isEmpty())
            assertFalse(callback.safeWaitResumeRvas.isEmpty())
            val base = requireNotNull(callback.callbackTableRva)
            val stride = requireNotNull(callback.callbackTableStride)
            val column = requireNotNull(callback.callbackTableColumn)
            callback.callbackFunctionPointerRvas.forEach { slot ->
                assertEquals(column, (slot - base) % stride)
                val at = requireNotNull(image.rvaToOffset(slot, 8))
                assertTrue(image.bytes.u64(at) in setOf(callback.ownerStartRva, image.imageBase + callback.ownerStartRva))
            }
            assertEquals(5, plan.automaticCallSites.count { !it.requiresDeferredCallback })
            assertTrue(plan.automaticCallSites.filter { !it.requiresDeferredCallback }.all { it.callbackNaturalResumeRva == null })
        }
    }

    @Test fun aUsedCallbackReturnCannotBeReplacedWithAnInventedStatus() {
        for (path in samples) {
            val image = sample(path)
            val callback = FastbootSlotRecoveryFacts.analyze(image).automaticCallSites.single { it.requiresDeferredCallback }
            val kill = requireNotNull(callback.callbackResultDeadRva)
            val word = image.word(kill)!!
            assertEquals(0, word and 31)
            val changed = image.bytes.copyOf()
            changed.putWord(requireNotNull(image.rvaToOffset(kill, 4)), (word and -32) or 1)
            val error = assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)) }
            assertTrue(error.message.orEmpty().contains("返回值仍被使用"))
        }
    }

    @Test fun removingTheNativeIndirectReferenceRemovesOnlyThatCallbackClassification() {
        val image = sample(samples[1])
        val callback = FastbootSlotRecoveryFacts.analyze(image).automaticCallSites.single { it.requiresDeferredCallback }
        val changed = image.bytes.copyOf()
        callback.callbackFunctionPointerRvas.forEach { slot ->
            val at = requireNotNull(image.rvaToOffset(slot, 8))
            repeat(8) { changed[at + it] = 0 }
        }
        val found = FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed))
        assertEquals(6, found.automaticCallSites.size)
        assertTrue(found.automaticCallSites.none { it.requiresDeferredCallback })
        assertEquals(1, found.manualSetActiveCalls.size)
    }

    @Test fun unknownIndirectTableFormDoesNotSilentlyBecomeASynchronousRequest() {
        for (path in samples) {
            val image = sample(path)
            val callback = FastbootSlotRecoveryFacts.analyze(image).automaticCallSites.single { it.requiresDeferredCallback }
            val changed = image.bytes.copyOf()
            val start = requireNotNull(callback.callbackEntryRva)
            val end = requireNotNull(callback.callbackDispatchCallRva)
            val multiply = image.instructions().filter { (pc, word) ->
                pc in start..<end && word and 0xffe08000.toInt() == 0x9ba00000.toInt()
            }.toList()
            assertTrue(multiply.isNotEmpty())
            multiply.forEach { (pc, word) ->
                // A valid signed-index form is deliberately not yet proved by
                // this unsigned-table decoder. Stored owner pointers remain.
                changed.putWord(requireNotNull(image.rvaToOffset(pc, 4)), word xor 0x00800000)
            }
            callback.callbackFunctionPointerRvas.forEach { slot ->
                val at = requireNotNull(image.rvaToOffset(slot, 8))
                assertEquals(image.bytes.u64(at), changed.u64(at))
            }
            val error = assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)) }
            assertTrue(error.message.orEmpty().contains("address-taken"))
        }
    }

    @Test fun directlyRegisteredCallbackOwnerIsNotMistakenForSynchronousCode() {
        val image = sample(samples[1])
        val plan = FastbootSlotRecoveryFacts.analyze(image)
        val callback = plan.automaticCallSites.single { it.requiresDeferredCallback }
        val owner = requireNotNull(callback.callbackEntryRva)
        val firstCall = image.instructions().first { (pc, word) ->
            pc in owner..<requireNotNull(callback.callbackDispatchCallRva) && A64.blTarget(pc, word) != null
        }.first
        val changed = image.bytes.copyOf()
        changed.putWord(requireNotNull(image.rvaToOffset(firstCall, 4)),
            A64.branch(firstCall, requireNotNull(plan.unbootableHandlerRva)) or 0x80000000.toInt())
        val error = assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)) }
        assertTrue(error.message.orEmpty().contains("CreateEvent 直接注册 callback"))
    }

    @Test fun mainSuccessJoinSkipsItsWholeAutomaticResetRatherThanReturningZeroToIt() {
        for (path in samples) {
            val image = sample(path)
            val plan = FastbootSlotRecoveryFacts.analyze(image)
            val main = plan.automaticCallSites.single {
                it.kind == FastbootSlotRecoveryFacts.Kind.MAIN_ROLLBACK_SET_ACTIVE
            }
            val reset = requireNotNull(main.automaticResetCallRva)
            assertEquals(0L, main.nativeSuccessValue)
            assertNotNull(main.successGateRva)
            assertEquals(reset + 4, main.naturalSuccessJoinRva)
            val load = image.word(requireNotNull(main.resetSystemFieldLoadRva))!!
            assertEquals(0xf9400000.toInt(), load and 0xffc00000.toInt())
            assertEquals(0x68, ((load ushr 10) and 0xfff) * 8)
            assertEquals(0xd63f0000.toInt(), image.word(reset)!! and 0xfffffc1f.toInt())
            val detector = plan.automaticCallSites.single {
                it.kind == FastbootSlotRecoveryFacts.Kind.BOOT_DETECTOR_SET_ACTIVE
            }
            assertEquals(0L, detector.nativeSuccessValue)
            assertNotNull(detector.naturalSuccessJoinRva)
            assertNull(detector.automaticResetCallRva)
        }
    }

    @Test fun resetSystemFieldConsumerCanUseARenamedRegister() {
        val image = sample(samples[1])
        val site = FastbootSlotRecoveryFacts.analyze(image).automaticCallSites.single {
            it.kind == FastbootSlotRecoveryFacts.Kind.MAIN_ROLLBACK_SET_ACTIVE
        }
        val load = requireNotNull(site.resetSystemFieldLoadRva)
        val call = requireNotNull(site.automaticResetCallRva)
        val changed = image.bytes.copyOf()
        changed.putWord(requireNotNull(image.rvaToOffset(load, 4)), (image.word(load)!! and -32) or 9)
        changed.putWord(requireNotNull(image.rvaToOffset(call, 4)), 0xd63f0000.toInt() or (9 shl 5))
        val found = FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)).automaticCallSites.single {
            it.kind == FastbootSlotRecoveryFacts.Kind.MAIN_ROLLBACK_SET_ACTIVE
        }
        assertEquals(load, found.resetSystemFieldLoadRva)
        assertEquals(site.naturalSuccessJoinRva, found.naturalSuccessJoinRva)
    }

    @Test fun anOutlinedResetFieldProducerIsFollowedThroughItsRealExportedRegister() {
        val image = sample(samples[1])
        val site = FastbootSlotRecoveryFacts.analyze(image).automaticCallSites.single {
            it.kind == FastbootSlotRecoveryFacts.Kind.MAIN_ROLLBACK_SET_ACTIVE
        }
        val load = requireNotNull(site.resetSystemFieldLoadRva)
        val section = image.sections.first { it.executable }
        val helper = (section.rva + section.rawSize - 0x100) and -16L
        val changed = image.bytes.copyOf()
        changed.putWord(requireNotNull(image.rvaToOffset(helper, 4)), 0xd503245f.toInt()) // BTI C
        changed.putWord(requireNotNull(image.rvaToOffset(helper + 4, 4)), image.word(load)!!)
        changed.putWord(requireNotNull(image.rvaToOffset(helper + 8, 4)), 0xd65f03c0.toInt())
        changed.putWord(requireNotNull(image.rvaToOffset(load, 4)), A64.branch(load, helper) or 0x80000000.toInt())
        val found = FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)).automaticCallSites.single {
            it.kind == FastbootSlotRecoveryFacts.Kind.MAIN_ROLLBACK_SET_ACTIVE
        }
        assertEquals(helper + 4, found.resetSystemFieldLoadRva)
        assertEquals(site.naturalSuccessJoinRva, found.naturalSuccessJoinRva)
    }

    @Test fun aNearbyFieldLoadWithoutAConsumerDoesNotProveResetSystem() {
        val image = sample(samples[1])
        val site = FastbootSlotRecoveryFacts.analyze(image).automaticCallSites.single {
            it.kind == FastbootSlotRecoveryFacts.Kind.MAIN_ROLLBACK_SET_ACTIVE
        }
        val call = requireNotNull(site.automaticResetCallRva)
        val changed = image.bytes.copyOf()
        changed.putWord(requireNotNull(image.rvaToOffset(call, 4)), 0xd63f0000.toInt() or (9 shl 5))
        assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)) }
    }

    @Test fun defaultInitializationUsesTheNativeNoActiveErrorBeforeItsFirstAttributeWrite() {
        for (path in samples) {
            val image = sample(path)
            val plan = FastbootSlotRecoveryFacts.analyze(image)
            val site = plan.defaultInitializationSites.single()
            assertEquals(plan.manualSetActiveEntryRva, site.manualHandlerRva)
            assertEquals(site.functionStartRva,
                A64.blTarget(site.manualSharedQueryCallRva, image.word(site.manualSharedQueryCallRva)!!))
            assertEquals(0, site.outputArgument)
            assertEquals(6, site.outputBytes)
            assertTrue(site.clearOutputWhenBlocked)
            assertTrue(site.decisionRva < site.firstMutationRva)
            assertEquals(site.decisionOriginalWord, image.word(site.decisionRva))
            assertTrue(site.nativeErrorValue < 0)
            assertEquals(Long.MIN_VALUE or 14L, site.nativeErrorValue)
            assertTrue(site.nativeErrorDefinitions.isNotEmpty())
            assertTrue(site.nativeErrorDefinitions.all { it.originalWord and 31 == site.nativeErrorReturnRegister })
            assertEquals(site.nativeCommitRva,
                A64.blTarget(site.nativeCommitCallRva, image.word(site.nativeCommitCallRva)!!))
            val word = site.decisionOriginalWord
            val relative = (((word ushr 5) and 0x7ffff).toLong() shl 45) shr 43
            val taken = site.decisionRva + relative
            assertEquals(site.nativeDefaultBranchRva,
                if (site.defaultBranchTaken) taken else site.decisionRva + 4)
            assertEquals(site.nativeErrorBranchRva,
                if (site.defaultBranchTaken) site.decisionRva + 4 else taken)
        }
    }

    @Test fun removingTheDefaultDecisionDoesNotLeaveAnApparentlyGuardedNativeWrite() {
        val image = sample(samples[1])
        val site = FastbootSlotRecoveryFacts.analyze(image).defaultInitializationSites.single()
        val changed = image.bytes.copyOf()
        changed.putWord(requireNotNull(image.rvaToOffset(site.decisionRva, 4)),
            A64.branch(site.decisionRva, site.nativeDefaultBranchRva))
        assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)) }
    }

    @Test fun nativeErrorCodeChangesAreDiscoveredRatherThanReplacedWithAnInventedConstant() {
        val image = sample(samples[1])
        val site = FastbootSlotRecoveryFacts.analyze(image).defaultInitializationSites.single()
        val definition = site.nativeErrorDefinitions.first()
        val changed = image.bytes.copyOf()
        // Keep its MOVZ destination/shift; change only the native low error value.
        changed.putWord(requireNotNull(image.rvaToOffset(definition.rva, 4)),
            (definition.originalWord and 0xffe0001f.toInt()) or (15 shl 5))
        val found = FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)).defaultInitializationSites.single()
        assertEquals(Long.MIN_VALUE or 15L, found.nativeErrorValue)
        assertEquals(site.nativeErrorBranchRva, found.nativeErrorBranchRva)
        assertEquals(site.nativeErrorEpilogueRva, found.nativeErrorEpilogueRva)
    }

    @Test fun aNativeErrorPathWithoutItsErrorBitIsRejected() {
        val image = sample(samples[1])
        val site = FastbootSlotRecoveryFacts.analyze(image).defaultInitializationSites.single()
        val definition = site.nativeErrorDefinitions.last()
        assertTrue(site.nativeErrorDefinitions.size > 1)
        val changed = image.bytes.copyOf()
        changed.putWord(requireNotNull(image.rvaToOffset(definition.rva, 4)), 0xd503201f.toInt())
        assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)) }
    }

    @Test fun defaultOutputCapacityMustBeProvedBeforeAnySuffixClearIsDescribed() {
        val image = sample(samples[1])
        val site = FastbootSlotRecoveryFacts.analyze(image).defaultInitializationSites.single()
        val changed = replaceArgument(image, site.nativeOutputCopyCallRva, 1, site.outputBytes / 2, 0)
        assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(changed) }
    }

    @Test fun automaticUnknownBooleanIsRejectedRatherThanTreatedAsManualOrIgnored() {
        val image = sample(samples[1])
        val plan = FastbootSlotRecoveryFacts.analyze(image)
        val site = plan.automaticCallSites.single { it.kind == FastbootSlotRecoveryFacts.Kind.MAIN_ROLLBACK_SET_ACTIVE }
        val changed = replaceArgument(image, site.callRva, requireNotNull(plan.manualBooleanArgument), 0, 2)
        assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(changed) }
    }

    @Test fun aManualCallWithoutItsProvedTrueArgumentIsRejected() {
        val image = sample(samples[1])
        val plan = FastbootSlotRecoveryFacts.analyze(image)
        val changed = replaceArgument(image, plan.manualSetActiveCalls.single(),
            requireNotNull(plan.manualBooleanArgument), 1, 0)
        assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(changed) }
    }

    @Test fun aCommandNameOutsideACompleteNativeCommandTableDoesNotProveTheManualEntry() {
        val image = sample(samples[1])
        val plan = FastbootSlotRecoveryFacts.analyze(image)
        val changed = image.bytes.copyOf()
        val records = records(image, plan)
        val selected = records.single { it.second == "set_active" }
        val other = records.single { it.second == "flash:" }
        putQ(changed, requireNotNull(image.rvaToOffset(selected.first, 8)),
            image.bytes.u64(requireNotNull(image.rvaToOffset(other.first, 8))))
        // The original set_active string still exists, but no valid record names it.
        assertTrue(changed.occurrences("set_active\u0000".toByteArray()).isNotEmpty())
        assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)) }
    }

    @Test fun conflictingSetActiveRecordsAreNotResolvedByPickingTheFirst() {
        val image = sample(samples[1])
        val plan = FastbootSlotRecoveryFacts.analyze(image)
        val changed = image.bytes.copyOf()
        val records = records(image, plan)
        val selected = records.single { it.second == "set_active" }
        val other = records.first { it.second !in setOf("set_active", "flash:", "erase:", "getvar:", "download:") }
        putQ(changed, requireNotNull(image.rvaToOffset(other.first, 8)),
            image.bytes.u64(requireNotNull(image.rvaToOffset(selected.first, 8))))
        assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)) }
    }

    @Test fun missingNativeAttributeStoresPreventAClaimThatTheMutationWasIdentified() {
        val image = sample(samples[1])
        val plan = FastbootSlotRecoveryFacts.analyze(image)
        val nativeWrites = plan.automaticCallSites.first {
            it.kind == FastbootSlotRecoveryFacts.Kind.MAIN_ROLLBACK_SET_ACTIVE
        }.nativeMutationEvidence
        val changed = image.bytes.copyOf()
        nativeWrites.drop(1).forEach { changed.putWord(requireNotNull(image.rvaToOffset(it.storeRva, 4)), 0xd503201f.toInt()) }
        assertThrows(PatchException::class.java) { FastbootSlotRecoveryFacts.analyze(PeImage.parse(changed)) }
    }

    @Test fun aProvedManualCommandWithoutAnySlotBackendReturnsDisabledNotEnabled() {
        val image = singleSlotFixture()
        val before = image.bytes.copyOf()
        val plan = FastbootSlotRecoveryFacts.analyze(image)
        assertEquals(0x1000L, plan.manualSetActiveEntryRva)
        assertFalse(plan.enabled)
        assertNull(plan.sharedSetActiveRva)
        assertTrue(plan.automaticCallSites.isEmpty())
        assertArrayEquals(before, image.bytes)
    }

    @Test fun commandTableDiscoveryFollowsMovedCodeAndData() {
        val plan = FastbootSlotRecoveryFacts.analyze(singleSlotFixture(0x9000, 0x37000))
        assertEquals(0x9000L, plan.manualSetActiveEntryRva)
        assertEquals(0x37000L, plan.commandTableStartRva)
        assertFalse(plan.enabled)
    }

    @Test fun incompleteSlotLogicIsNotSilentlyReportedAsSingleSlot() {
        assertThrows(PatchException::class.java) {
            FastbootSlotRecoveryFacts.analyze(singleSlotFixture(slotMarker = true))
        }
    }

    private fun replaceArgument(image: PeImage, call: Long, argument: Int, before: Int, after: Int): PeImage {
        val definition = (call - 4 downTo call - 32 step 4).firstOrNull { pc ->
            image.word(pc)?.let(A64::movWImmediate) == (argument to before)
        }
        assertNotNull("Fresh argument definition before dynamically discovered call", definition)
        return PeImage.parse(image.bytes.copyOf().also {
            it.putWord(requireNotNull(image.rvaToOffset(definition!!, 4)), 0x52800000 or (after shl 5) or argument)
        })
    }

    private fun records(image: PeImage, plan: FastbootSlotRecoveryFacts.Plan) =
        (plan.commandTableStartRva until plan.commandTableEndRva step 16).map { pc ->
            val at = requireNotNull(image.rvaToOffset(pc, 16))
            val pointer = image.bytes.u64(at)
            val nameRva = if (image.rvaToOffset(pointer - image.imageBase) != null) pointer - image.imageBase else pointer
            pc to requireNotNull(image.ascii(nameRva, 96))
        }

    private fun singleSlotFixture(code: Long = 0x1000, data: Long = 0x5000, slotMarker: Boolean = false): PeImage {
        val bytes = ByteArray(0x2400)
        bytes[0] = 77; bytes[1] = 90
        bytes.putWord(0x3c, 0x80); bytes.putWord(0x80, 0x4550)
        bytes.putWord(0x84, 0x0002aa64); bytes.putWord(0x94, 0x202200f0); bytes.putWord(0x98, 0x20b)
        bytes.putWord(0xa8, code.toInt()); bytes.putWord(0xb8, 0x1000); bytes.putWord(0xbc, 0x200)
        bytes.putWord(0xd0, (data + 0x1000).toInt()); bytes.putWord(0xd4, 0x400); bytes.putWord(0x104, 16)
        repeat(2) { index ->
            val at = 0x188 + index * 40
            (if (index == 0) ".text" else ".data").toByteArray().copyInto(bytes, at)
            bytes.putWord(at + 8, 0x1000); bytes.putWord(at + 12, (if (index == 0) code else data).toInt())
            bytes.putWord(at + 16, 0x1000); bytes.putWord(at + 20, 0x400 + index * 0x1000)
            bytes.putWord(at + 36, if (index == 0) 0x60000020 else 0xc0000040.toInt())
        }
        var stringOffset = 0x1600
        for ((index, command) in listOf("set_active", "flash:", "erase:", "getvar:", "download:").withIndex()) {
            val string = command.toByteArray() + byteArrayOf(0)
            string.copyInto(bytes, stringOffset)
            putQ(bytes, 0x1400 + index * 16, data + stringOffset - 0x1400)
            putQ(bytes, 0x1408 + index * 16, code + index * 0x40)
            bytes.putWord(0x400 + index * 0x40, 0xd503233f.toInt())
            bytes.putWord(0x404 + index * 0x40, 0xd65f03c0.toInt())
            stringOffset += string.size
        }
        bytes.putWord(0x540, 0xd503233f.toInt())
        bytes.putWord(0x544, 0xd65f03c0.toInt())
        if (slotMarker) {
            val text = "HandleActiveSlotUnbootable: Rebooting\u0000".toByteArray()
            text.copyInto(bytes, 0x1900)
            val target = data + 0x500
            val displacement = target - (code + 0x44)
            bytes.putWord(0x444, 0x10000001 or (((displacement and 3).toInt()) shl 29) or
                ((((displacement shr 2) and 0x7ffff).toInt()) shl 5))
            bytes.putWord(0x448, A64.branch(code + 0x48, code + 0x100) or 0x80000000.toInt())
            bytes.putWord(0x44c, 0xd65f03c0.toInt())
        }
        return PeImage.parse(bytes)
    }

    private fun putQ(bytes: ByteArray, at: Int, value: Long) {
        bytes.putWord(at, value.toInt()); bytes.putWord(at + 4, (value ushr 32).toInt())
    }
}
