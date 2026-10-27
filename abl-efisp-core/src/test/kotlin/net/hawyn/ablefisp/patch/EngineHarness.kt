package net.hawyn.ablefisp.patch

import java.io.File

/** Core discovery contracts over independently generated relocation/corruption fixtures.
 * These original fixtures also contain deliberate unrelated tail references, so
 * they are not whole-image allocation fixtures for the later recovery adapter.
 * FreedomRecoveryTest covers the complete current PatchEngine separately. */
object EngineHarness {
    data class Outcome(val name: String, val passed: Boolean, val detail: String)

    @JvmStatic
    fun main(args: Array<String>) {
        val manifest = File(args.firstOrNull() ?: "analysis/ablpatcher-fixtures/manifest.json")
        val outcomes = run(manifest)
        outcomes.forEach { println("${if (it.passed) "PASS" else "FAIL"} ${it.name}: ${it.detail}") }
        check(outcomes.all { it.passed }) { "${outcomes.count { !it.passed }} fixture checks failed" }
        println("TOTAL ${outcomes.size} passed")
    }

    fun run(manifest: File): List<Outcome> {
        check(manifest.isFile) { "Generate analysis/ablpatcher-fixtures first" }
        val objects = Regex("""\{\s*"file"\s*:\s*"([^"]+)"([\s\S]*?)\n\s*\}""").findAll(manifest.readText()).toList()
        check(objects.isNotEmpty()) { "No fixture definitions" }
        return objects.map { match ->
            val name = match.groupValues[1].replace("\\\\", "/").replace('\\', '/')
            val body = match.groupValues[2]
            val expectation = Regex(""""expectation"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]
            val expectedRvas = Regex(""""branch_rvas"\s*:\s*\[([^]]*)]""").find(body)?.groupValues?.get(1)
                ?.let { array -> Regex("0x[0-9a-fA-F]+").findAll(array).map { it.value.substring(2).toLong(16) }.toList() }
                ?: emptyList()
            val expectedCount = Regex(""""patch_count"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toInt() ?: 4
            val source = File(manifest.parentFile, name).readBytes()
            val unchanged = source.copyOf()
            try {
                val result = patchCoreFixture(source)
                check(expectation != "reject") { "Expected rejection, but matched ${result.patches.map { it.rva }}" }
                check(result.patches.size == expectedCount) { "Expected $expectedCount core targets, got ${result.patches.size}" }
                check(result.alreadyPatched == (expectation == "already")) { "Unexpected core alreadyApplied flags" }
                check(result.patches.take(3).map { it.rva } == expectedRvas) { "Wrong relocated branch targets: ${result.patches.take(3).map { it.rva }}" }
                check(source.contentEquals(unchanged)) { "Source input was mutated" }
                val mapped = PeImage.parse(result.output)
                result.patches.forEach { patch ->
                    check(mapped.offsetToRva(patch.fileOffset) == patch.rva) { "RVA/raw mapping changed" }
                }
                val second = patchCoreFixture(result.output)
                check(second.alreadyPatched && second.output.contentEquals(result.output)) { "Patch is not idempotent" }
                Outcome(name, true, "${result.output.size} bytes; ${result.patches.count { !it.alreadyApplied }} edits; ${result.patches.take(3).map { "0x${it.rva.toString(16)}" }}")
            } catch (error: Exception) {
                if (expectation == "reject" && error is PatchException && source.contentEquals(unchanged)) {
                    Outcome(name, true, "rejected: ${error.message}")
                } else Outcome(name, false, "${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    private fun patchCoreFixture(input: ByteArray): PatchResult {
        val extracted = PatchEngine.extract(input)
        val image = PeImage.parse(extracted.output)
        val plan = SemanticPatcher.plan(image)
        val output = image.bytes.copyOf()
        val patches = plan.map { edit ->
            val offset = requireNotNull(image.rvaToOffset(edit.rva, edit.replacement.size))
            val before = image.bytes.copyOfRange(offset, offset + edit.replacement.size)
            edit.replacement.copyInto(output, offset)
            PatchMatch(edit.name, edit.rva, offset, before.hex(), edit.replacement.hex(), before.contentEquals(edit.replacement))
        }
        return PatchResult(output, extracted.sourceFormat, input.sha256(), output.sha256(), output.size,
            patches, emptyList(), patches.all { it.alreadyApplied })
    }
}
