package net.hawyn.ablefisp.api

import net.hawyn.ablefisp.io.ImageIo
import net.hawyn.ablefisp.patch.ExtractedImage
import net.hawyn.ablefisp.patch.PatchEngine
import net.hawyn.ablefisp.patch.PatchException
import net.hawyn.ablefisp.patch.PatchFailureKind
import net.hawyn.ablefisp.patch.PatchResult
import net.hawyn.ablefisp.patch.PatchMode
import net.hawyn.ablefisp.text.TextEngine
import net.hawyn.ablefisp.text.TextEntry
import net.hawyn.ablefisp.text.TextPatchResult
import net.hawyn.ablefisp.text.TextScanResult
import java.util.Collections

/**
 * An extracted/prepared PE and its editable text catalogue.
 *
 * [output] is a new, caller-owned mutable byte array, independent of the input.
 * When [patchResult] is present, its output is the same array as [output]. Treat
 * it as read-only while using the hashes/catalogue; editing the array manually
 * invalidates that metadata. Pass it to [AblEfisp.editText] for another new image.
 * Collection properties are immutable snapshots; entries and patch metadata use
 * read-only value models. The core retains no references to this result.
 */
data class PreparedTextImage(
    val output: ByteArray,
    val sourceFormat: String,
    val sourceSha256: String,
    val outputSha256: String,
    val patchResult: PatchResult?,
    val entries: List<TextEntry>,
    val notes: List<String>,
)

/**
 * Synchronous, platform-independent entry points for embedding in a host process.
 *
 * No method accesses Android, Root, files, partitions, UI or a second application.
 * Run long operations on a host-managed worker thread. Each call snapshots its
 * input bytes and edit map; do not modify those inputs concurrently while the
 * snapshot is being taken. Returned byte arrays belong to the caller and never
 * alias the supplied input or a result from another invocation. Exceptions retain
 * the existing [PatchException] / text engine error types.
 */
object AblEfisp {
    /** Extract the inner ARM64 PE; does not apply the three core modifications. */
    @JvmStatic
    fun extract(input: ByteArray): ExtractedImage =
        PatchEngine.extract(input.copyOf()).let { it.copy(notes = immutable(it.notes)) }

    /** Locate and apply the three core functions and any uniquely proven rename. */
    @JvmStatic
    fun patch(input: ByteArray): PatchResult = patchSnapshot(input.copyOf())

    @JvmStatic
    fun patch(input: ByteArray, mode: PatchMode): PatchResult = patchSnapshot(input.copyOf(), mode)

    /**
     * Prepare a new import for text editing, matching the standalone UI workflow.
     * First try core patching for every input, including bare PE. Only a bare MZ
     * image with [PatchFailureKind.NO_CORE_FEATURE] falls back to direct text
     * editing. Incomplete/ambiguous core matches and malformed containers propagate
     * their error. Scan errors also propagate; [patch] and [scanText] are available
     * separately when a host needs to keep the generated PE despite a scan error.
     */
    @JvmStatic
    fun prepareTextEditing(input: ByteArray): PreparedTextImage = prepareTextEditing(input, PatchMode.FREEDOM)

    @JvmStatic
    fun prepareTextEditing(input: ByteArray, mode: PatchMode): PreparedTextImage {
        val snapshot = input.copyOf()
        val rawPe = snapshot.size >= 2 && snapshot[0] == 0x4d.toByte() && snapshot[1] == 0x5a.toByte()
        val patched = try {
            patchSnapshot(snapshot, mode)
        } catch (error: PatchException) {
            if (!rawPe || error.kind != PatchFailureKind.NO_CORE_FEATURE) throw error
            null
        }
        val extracted = if (patched == null) PatchEngine.extract(snapshot) else null
        val output = patched?.output ?: requireNotNull(extracted).output
        val scan = scanSnapshot(output)
        return PreparedTextImage(
            output = output,
            sourceFormat = patched?.sourceFormat ?: requireNotNull(extracted).sourceFormat,
            sourceSha256 = patched?.sourceSha256 ?: ImageIo.sha256(snapshot),
            outputSha256 = patched?.outputSha256 ?: ImageIo.sha256(output),
            patchResult = patched,
            entries = scan.entries,
            notes = immutable(((patched?.notes ?: extracted?.notes.orEmpty()) + scan.notes).distinct()),
        )
    }

    /** Scan an already prepared PE. This method does not apply core modifications. */
    @JvmStatic
    fun scanText(pe: ByteArray): TextScanResult = scanSnapshot(pe.copyOf())

    /** Apply text edits to a prepared PE, returning a new caller-owned output. */
    @JvmStatic
    fun editText(pe: ByteArray, edits: Map<String, String>): TextPatchResult =
        editText(pe, edits, autoWrapFastboot = true)

    /** Preserve caller-supplied Fastboot line breaks when automatic wrapping is disabled. */
    @JvmStatic
    fun editText(pe: ByteArray, edits: Map<String, String>, autoWrapFastboot: Boolean): TextPatchResult {
        val snapshot = pe.copyOf()
        val requested = LinkedHashMap(edits)
        return TextEngine.apply(snapshot, requested, autoWrapFastboot).let {
            it.copy(changes = immutable(it.changes), notes = immutable(it.notes))
        }
    }

    private fun patchSnapshot(input: ByteArray, mode: PatchMode = PatchMode.FREEDOM): PatchResult =
        PatchEngine.analyzeAndPatch(input, mode).let {
            it.copy(patches = immutable(it.patches), notes = immutable(it.notes))
        }

    private fun scanSnapshot(pe: ByteArray): TextScanResult =
        TextEngine.scan(pe).let { it.copy(entries = immutable(it.entries), notes = immutable(it.notes)) }

    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
