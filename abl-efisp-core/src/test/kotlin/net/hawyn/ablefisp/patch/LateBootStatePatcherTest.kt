package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LateBootStatePatcherTest {
    private fun root(): File {
        val result = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6).firstOrNull { File(it, "ablnew.img").isFile }
        assumeTrue("Local firmware samples", result != null)
        return result!!
    }
    private fun image(path: String): PeImage = PeImage.parse(File(root(), path).readBytes())
    private fun facts(image: PeImage): NormalBootStateFacts = requireNotNull(
        FakeLockPatcher.analyze(image, requireNotNull(SemanticPatcher.analyze(image).stateGlobal)).normalBootState,
    )
    private fun replace(image: PeImage, pc: Long, word: Int): PeImage = PeImage.parse(image.bytes.copyOf().also {
        it.putWord(requireNotNull(image.rvaToOffset(pc, 4)), word)
    })

    @Test fun oldNewAndOtaUseTheSameStateAndPolicyRelations() {
        for (path in listOf("analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe",
            "analysis/ablnew-compat/ablnew-inner.pe", "analysis/locked-boot-feasibility/fastboot-ota-abl.pe")) {
            val source = image(path); val before = source.bytes.copyOf()
            val plan = LateBootStatePatcher.analyze(source, facts(source)).single()
            assertNull(plan.existingStubRva)
            assertEquals(plan.originalSelector, source.word(plan.selectorRva))
            assertNotEquals(plan.stateRegister, plan.errorRegister)
            assertEquals(4, requireNotNull(StateBranchEvaluator.access(requireNotNull(source.word(plan.storeRva)))).width)
            assertArrayEquals(before, source.bytes)
        }
    }

    @Test fun emittedInstructionsOnlyChangeSuccessfulAllowedState() {
        val selector = 0x4000L; val stub = 0x5000L
        val original = 0x1a9f0400 or (9 shl 5) or 7 // CSINC W7,W9,WZR,EQ
        val plan = LateBootStatePatcher.Plan(selector, original, selector + 4, 25, 7, 20)
        val edits = LateBootStatePatcher.edits(plan, stub)
        assertEquals(stub, A64.bTarget(selector, edits[0].replacement.word(0)))
        val words = (0 until 5).associate { stub + it * 4 to edits[1].replacement.word(it * 4) }
        for (allow in 0..1) for (pending in listOf(0L, 1L, Long.MIN_VALUE)) for (state in 0..3) {
            val registers = LongArray(31) { 0x120000L + it }; registers[9] = state.toLong(); registers[25] = pending
            val before = registers.copyOf(); val zero = allow == 0; var pc = stub
            repeat(5) {
                if (pc == selector + 4) return@repeat
                val word = requireNotNull(words[pc])
                val branch = A64.bTarget(pc, word)
                if (branch != null) pc = branch
                else if (word and 0xff000000.toInt() == 0xb5000000.toInt()) {
                    pc = if (registers[word and 31] != 0L) pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2) else pc + 4
                } else {
                    assertEquals(0x1a9f0000, word and 0xfffff800.toInt())
                    val left = registers[(word ushr 5) and 31]
                    registers[word and 31] = if (zero) left and 0xffffffffL else if (word and 0x400 != 0) 1 else 0
                    pc += 4
                }
            }
            assertEquals(selector + 4, pc)
            val expected = if (pending == 0L && allow != 0) 0 else if (allow == 0) state else 1
            assertEquals(expected.toLong(), registers[7])
            assertTrue(before.indices.filter { it != 7 }.all { before[it] == registers[it] })
        }
    }

    @Test fun existingDetourIsDecodedAndReprovedWithoutAnotherAllocation() {
        val source = image("analysis/ablnew-compat/ablnew-inner.pe"); val facts = facts(source)
        val first = LateBootStatePatcher.analyze(source, facts).single()
        val section = source.sections.first { it.executable }
        val stub = section.rva + section.rawSize - 0x100
        val output = source.bytes.copyOf()
        for (edit in LateBootStatePatcher.edits(first, stub))
            edit.replacement.copyInto(output, requireNotNull(source.rvaToOffset(edit.rva, edit.replacement.size)))
        val repeated = LateBootStatePatcher.analyze(PeImage.parse(output), facts).single()
        assertEquals(first.selectorRva, repeated.selectorRva)
        assertEquals(stub, repeated.existingStubRva)
        assertTrue(LateBootStatePatcher.edits(repeated, stub).all { edit ->
            output.copyOfRange(requireNotNull(source.rvaToOffset(edit.rva, edit.replacement.size)),
                requireNotNull(source.rvaToOffset(edit.rva, edit.replacement.size)) + edit.replacement.size).contentEquals(edit.replacement)
        })
    }

    @Test fun unrelatedPolicyAndReversedFailureBranchAreRejected() {
        val source = image("analysis/ablnew-compat/ablnew-inner.pe"); val facts = facts(source)
        val plan = LateBootStatePatcher.analyze(source, facts).single()
        val cmp = requireNotNull(source.word(plan.selectorRva - 4))
        val wrongPolicy = replace(source, plan.selectorRva - 4, (cmp and 0xfffffc1f.toInt()) or (17 shl 5))
        assertThrows(PatchException::class.java) { LateBootStatePatcher.analyze(wrongPolicy, facts) }
        val branch = plan.storeRva + 4
        val reversed = replace(source, branch, requireNotNull(source.word(branch)) xor 0x01000000)
        assertThrows(PatchException::class.java) { LateBootStatePatcher.analyze(reversed, facts) }
        val truncatedError = replace(source, branch, requireNotNull(source.word(branch)) and 0x7fffffff)
        assertThrows(PatchException::class.java) { LateBootStatePatcher.analyze(truncatedError, facts) }
        assertThrows(PatchException::class.java) { LateBootStatePatcher.analyze(source, facts.copy(contextFieldOffset = facts.contextFieldOffset + 4)) }
    }

    @Test fun overwritingThePrivatePolicyReloadIsRejected() {
        val source = image("analysis/ablnew-compat/ablnew-inner.pe"); val facts = facts(source)
        val output = source.bytes.copyOf()
        // A nearby scalar slot is not assumed to retain the normal AVB policy
        // after an explicit overwrite on the successful cleanup route.
        for ((pc, word) in listOf(0x49d14L to 0x910173f0.toInt(), // ADD X16,SP,#5C
            0x49d18L to 0x52800051, // MOV W17,#2: unrelated nonzero source
            0x49d1cL to 0xb9000211.toInt(), // STR W17,[X16]
            0x49d20L to 0xb9400217.toInt())) // LDR W23,[X16]
            output.putWord(requireNotNull(source.rvaToOffset(pc, 4)), word)
        assertThrows(PatchException::class.java) { LateBootStatePatcher.analyze(PeImage.parse(output), facts) }
    }

    @Test fun aDamagedExistingDetourDoesNotSilentlyProduceAnotherPatch() {
        val source = image("analysis/ablnew-compat/ablnew-inner.pe"); val facts = facts(source)
        val plan = LateBootStatePatcher.analyze(source, facts).single()
        val section = source.sections.first { it.executable }; val stub = section.rva + section.rawSize - 0x100
        val output = source.bytes.copyOf()
        for (edit in LateBootStatePatcher.edits(plan, stub))
            edit.replacement.copyInto(output, requireNotNull(source.rvaToOffset(edit.rva, edit.replacement.size)))
        output.putWord(requireNotNull(source.rvaToOffset(stub + 16, 4)), A64.branch(stub + 16, plan.selectorRva + 8))
        assertThrows(PatchException::class.java) { LateBootStatePatcher.analyze(PeImage.parse(output), facts) }
    }

    @Test fun pendingValueMustSurviveBetweenSelectorAndStore() {
        val source = image("analysis/ablnew-compat/ablnew-inner.pe"); val facts = facts(source)
        val plan = LateBootStatePatcher.analyze(source, facts).single()
        val branch = requireNotNull(source.word(plan.storeRva + 4))
        val failed = plan.storeRva + 4 + (signExtend(((branch ushr 5) and 0x7ffff).toLong(), 19) shl 2)
        val success = requireNotNull(A64.bTarget(plan.storeRva + 8, requireNotNull(source.word(plan.storeRva + 8))))
        val output = source.bytes.copyOf()
        for ((pc, word) in listOf(
            plan.storeRva to (0xd2800020.toInt() or plan.errorRegister), // redefine pending between selector and original store
            plan.storeRva + 4 to requireNotNull(source.word(plan.storeRva)),
            plan.storeRva + 8 to (0xb5000000.toInt() or plan.errorRegister or
                (((((failed - (plan.storeRva + 8)) shr 2) and 0x7ffff).toInt()) shl 5)),
            plan.storeRva + 12 to A64.branch(plan.storeRva + 12, success),
        )) output.putWord(requireNotNull(source.rvaToOffset(pc, 4)), word)
        assertThrows(PatchException::class.java) { LateBootStatePatcher.analyze(PeImage.parse(output), facts) }
    }

    @Test fun indexedAndVectorStoresCannotClobberThePrivatePolicySpill() {
        val source = image("analysis/ablnew-compat/ablnew-inner.pe"); val facts = facts(source)
        for (store in listOf(0xb82f6a11.toInt(), // STR W17,[X16,X15]
            0xb8000211.toInt(), // STUR W17,[X16]
            0xb8000e11.toInt(), // STR W17,[X16,#0]!
            0xb8000611.toInt(), // STR W17,[X16],#0
            0x3d800200, // STR Q0,[X16]
            0x3c800200, // STUR Q0,[X16]
        )) {
            val output = source.bytes.copyOf()
            for ((pc, word) in listOf(0x49d14L to 0x910173f0.toInt(),
                0x49d18L to 0x52800051, 0x49d1cL to 0xaa1f03ef.toInt(),
                0x49d20L to store, 0x49d24L to 0xb9400217.toInt()))
                output.putWord(requireNotNull(source.rvaToOffset(pc, 4)), word)
            assertThrows("store ${store.toUInt().toString(16)}", PatchException::class.java) {
                LateBootStatePatcher.analyze(PeImage.parse(output), facts)
            }
        }
    }

    @Test fun aSpinLoopAfterAnEarlyReturnEdgeIsNotNonReturning() {
        val source = image("analysis/ablnew-compat/ablnew-inner.pe")
        val at = source.sections.first { it.executable }.let { it.rva + it.rawSize - 0x100 }
        val output = source.bytes.copyOf()
        val words = intArrayOf(0xd503233f.toInt(), 0xd503201f.toInt(), 0x52800028,
            0xb9000fe8.toInt(), 0xb9400fe8.toInt(), 0x35ffffe8, 0xd50323bf.toInt(),
            0xd65f03c0.toInt(), 0xd503233f.toInt())
        for ((index, word) in words.withIndex()) output.putWord(requireNotNull(source.rvaToOffset(at + index * 4, 4)), word)
        val method = LateBootStatePatcher::class.java.getDeclaredMethod("privateSpinDoesNotReturn",
            PeImage::class.java, Long::class.javaPrimitiveType, List::class.java).also { it.isAccessible = true }
        assertEquals(true, method.invoke(LateBootStatePatcher, PeImage.parse(output), at, listOf(at, at + 32)))
        output.putWord(requireNotNull(source.rvaToOffset(at + 4, 4)), 0xb40000a0.toInt()) // CBZ X0, early epilogue
        assertEquals(false, method.invoke(LateBootStatePatcher, PeImage.parse(output), at, listOf(at, at + 32)))
    }

    @Test fun independentRegisterPermutationsKeepTheSameMeaning() {
        val directory = File(root(), "analysis/fakebl-repair-20261026/semantic-fixtures")
        val files = directory.listFiles { file -> file.extension == "pe" }.orEmpty()
        assumeTrue("Independent whole-program register permutations", files.isNotEmpty())
        for (file in files) {
            val source = PeImage.parse(file.readBytes())
            assertEquals(file.name, 1, LateBootStatePatcher.analyze(source, facts(source)).size)
        }
    }
}
