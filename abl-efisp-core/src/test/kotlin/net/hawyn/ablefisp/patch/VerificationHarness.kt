package net.hawyn.ablefisp.patch

import java.io.File

/** Host-only evidence exporter, excluded from production source sets. */
object VerificationHarness {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 4) { "Usage: core|structure sourcePe outputPe verification.json" }
        val sourcePath = File(args[1]); val outputPath = File(args[2]); val destination = File(args[3])
        val sourceInput = sourcePath.readBytes()
        val source = PatchEngine.extract(sourceInput).output
        val output = outputPath.readBytes()
        val result = when (args[0]) {
            "core" -> OutputVerifier.verifyCore(source, output, PatchEngine.analyzeAndPatch(source).patches)
            "structure" -> OutputVerifier.verifyStructure(source, output)
            else -> error("Expected core or structure mode")
        }
        fun q(value: String): String = "\"" + buildString {
            value.forEach { c -> when (c) {
                '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
            } }
        } + "\""
        val json = buildString {
            append("{\n  \"mode\": ${q(args[0])},\n")
            append("  \"source\": ${q(sourcePath.absolutePath)},\n  \"output\": ${q(outputPath.absolutePath)},\n")
            append("  \"sourcePeSha256\": ${q(source.sha256())},\n  \"outputPeSha256\": ${q(output.sha256())},\n")
            append("  \"allPassed\": ${result.allPassed},\n  \"hardwareBootTested\": false,\n")
            append("  \"summary\": ${q(result.summary)},\n  \"checks\": [\n")
            result.checks.forEachIndexed { index, check ->
                if (index > 0) append(",\n")
                append("    {\"name\": ${q(check.name)}, \"passed\": ${check.passed}, \"detail\": ${q(check.detail)}}")
            }
            append("\n  ]\n}\n")
        }
        destination.parentFile?.mkdirs()
        destination.writeText(json, Charsets.UTF_8)
        println("${if (result.allPassed) "PASS" else "FAIL"} ${args[0]}: ${result.checks.count { it.passed }}/${result.checks.size}; ${destination.absolutePath}")
        check(result.allPassed) { "Delivery-time static verification failed; see the report" }
    }
}
