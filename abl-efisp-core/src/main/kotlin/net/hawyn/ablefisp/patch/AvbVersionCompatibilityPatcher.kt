package net.hawyn.ablefisp.patch

/**
 * Exception for proved libavb version policy guards, after native decoding.
 * A successful match bypasses only the version comparison. The following
 * reserved/header/auxiliary length/algorithm parsing stays in the original code.
 * Unknown parser layouts are reported, rather than manufacturing a parsed object.
 */
internal object AvbVersionCompatibilityPatcher {
    private const val IMAGE_MESSAGE = "Mismatch between image version and libavb version."
    private const val FOOTER_MESSAGE = "No support for footer version."
    private const val MAGIC_MESSAGE = "Footer magic is incorrect."
    private const val NOP = 0xd503201f.toInt()
    private data class Function(val start: Long, val end: Long)
    private data class Gate(val pc: Long, val target: Long)
    private data class Field(val root: String, val start: Long, val firstLoad: Long)

    fun analyze(image: PeImage): SemanticPlan {
        val index = Index(image)
        val header = imageVersionEdits(index)
        val footer = footerVersionEdits(index)
        val edits = (header + footer).distinctBy { it.rva }
        val notes = listOf(
            if (header.isEmpty()) "AVB 版本兼容：未证明内联 verifier 的 required major/minor 政策分支，保留原解析与错误恢复。"
            else "AVB 版本兼容：${header.size} 处 required major/minor 比较走已有真实解析继续路径，未伪造 verifier 成功。",
            if (footer.isEmpty()) "AVB 版本兼容：未证明 footer 版本政策与独立 magic 校验关系，保留原 footer 解析。"
            else "AVB 版本兼容：${footer.size} 处 footer major 版本比较走原生接受路径；独立 AVBf magic 校验及长度检查保持。",
            "AVB 版本兼容覆盖以实际证明的诊断、字段数据流和原生继续路径为准；其他独立 verifier 或未知布局不宣称已覆盖。",
        )
        return SemanticPlan(edits, notes)
    }

    private class Index(val image: PeImage) {
        val refs = A64.references(image)
        private val starts = image.instructions().filter {
            it.second == 0xd503233f.toInt() || it.second == 0xd503237f.toInt()
        }.map { it.first }.toList()
        fun function(pc: Long): Function? = starts.lastOrNull { it <= pc }?.let { start ->
            starts.firstOrNull { it > pc }?.let { Function(start, it) }
        }
        fun references(fragment: String): List<StringReference> {
            val targets = image.bytes.occurrences(fragment.toByteArray(Charsets.US_ASCII)).mapNotNull { offset ->
                var start = offset
                while (start > 0 && offset - start < 16 && (image.bytes[start - 1].toInt() and 255) in 32..126) start--
                val literal = image.bytes.asciiZ(start, fragment.length + 80) ?: return@mapNotNull null
                if (!literal.contains(fragment)) null else image.offsetToRva(start)
            }.toSet()
            return refs.filter { it.address in targets }
        }
        fun log(ref: StringReference, owner: Function): Long? =
            (ref.pc + 4..minOf(owner.end - 4, ref.pc + 96) step 4).firstOrNull { pc ->
                A64.blTarget(pc, image.word(pc) ?: 0) != null &&
                    A64.argumentState(image, ref.pc, pc).registers[ref.register] == A64.Value.Address(ref.address)
            }
    }

    private fun imageVersionEdits(index: Index): List<PlannedEdit> {
        val image = index.image
        val result = ArrayList<PlannedEdit>()
        for (ref in index.references(IMAGE_MESSAGE)) {
            val owner = index.function(ref.pc) ?: continue
            val log = index.log(ref, owner) ?: continue
            val matches = ArrayList<Gate>()
            for (pc in maxOf(owner.start, ref.pc - 160)..ref.pc - 4 step 4) {
                val gate = belowGate(image, pc) ?: continue
                if (gate.target <= log || gate.target >= owner.end || !linear(image, pc + 4, log)) continue
                // AVB_VBMETA_VERIFY_RESULT_UNSUPPORTED_VERSION must be the real
                // result on this diagnostic edge, not a size/algorithm error.
                val error = (log + 4..<minOf(gate.target, log + 28) step 4).firstOrNull {
                    A64.movWImmediate(image.word(it) ?: 0)?.second == 3
                } ?: continue
                val resultRegister = requireNotNull(A64.movWImmediate(requireNotNull(image.word(error)))).first
                val rejection = A64.bTarget(error + 4, image.word(error + 4) ?: 0) ?: continue
                if (rejection !in owner.start..<pc || resultRegister == 31) continue
                val ccmp = (maxOf(owner.start, pc - 24)..pc - 4 step 4).lastOrNull { at ->
                    isMinorCompare(image.word(at) ?: 0) &&
                        StraightLineLiveness.preserves(image, at + 4, pc, flags = true)
                } ?: continue
                val minor = (requireNotNull(image.word(ccmp)) ushr 5) and 31
                val majorCmp = (maxOf(owner.start, ccmp - 96)..ccmp - 4 step 4).lastOrNull { at ->
                    val raw = image.word(at) ?: return@lastOrNull false
                    raw >= 0 && A64.cmpImmediate(raw) != null &&
                        StraightLineLiveness.preserves(image, at + 4, ccmp, flags = true)
                } ?: continue
                val major = requireNotNull(A64.cmpImmediate(requireNotNull(image.word(majorCmp)))).first
                val start = (maxOf(owner.start, majorCmp - 256)..majorCmp - 4 step 4).lastOrNull {
                    A64.blTarget(it, image.word(it) ?: 0) != null
                }?.plus(4) ?: continue
                if (!linear(image, start, pc)) continue
                val data = DataFlow(image, start, pc)
                val first = data.field(majorCmp, major) ?: continue
                val second = data.field(ccmp, minor) ?: continue
                if (first.root != second.root || kotlin.math.abs(first.start - second.start) != 4L) continue
                // The native continuation must still do actual parsing, rather
                // than being a constant-success return inserted by another tool.
                val continuation = image.word(gate.target) ?: continue
                if (!isLoad(continuation) || !image.isCode(gate.target, 16)) continue
                matches += gate
            }
            matches.distinctBy { it.pc }.singleOrNull()?.let {
                result += edit("AVB 版本兼容：required major/minor 采用原生解析继续路径", it)
            }
        }
        return result.distinctBy { it.rva }
    }

    private fun footerVersionEdits(index: Index): List<PlannedEdit> {
        val image = index.image
        val result = ArrayList<PlannedEdit>()
        for (ref in index.references(FOOTER_MESSAGE)) {
            val owner = index.function(ref.pc) ?: continue
            val log = index.log(ref, owner) ?: continue
            val matches = ArrayList<Gate>()
            for (pc in maxOf(owner.start, ref.pc - 128)..ref.pc - 4 step 4) {
                val gate = belowGate(image, pc) ?: continue
                if (gate.target <= log || gate.target >= owner.end || !linear(image, pc + 4, log)) continue
                val success = A64.movWImmediate(image.word(gate.target) ?: 0) ?: continue
                if (success != (0 to 1)) continue
                val cleanup = A64.bTarget(gate.target + 4, image.word(gate.target + 4) ?: 0) ?: continue
                val zero = (log + 4..minOf(log + 16, gate.target - 4) step 4).firstOrNull {
                    A64.movWImmediate(image.word(it) ?: 0) == (0 to 0)
                } ?: continue
                if (cleanup != zero + 4) continue
                val cmp = (maxOf(owner.start, pc - 24)..pc - 4 step 4).lastOrNull { at ->
                    val raw = image.word(at) ?: return@lastOrNull false
                    raw >= 0 && A64.cmpImmediate(raw) != null &&
                        StraightLineLiveness.preserves(image, at + 4, pc, flags = true)
                } ?: continue
                val register = requireNotNull(A64.cmpImmediate(requireNotNull(image.word(cmp)))).first
                // The distinct bad-magic edge must retain both its literal and
                // the real AVBf/4-byte comparison. Its native equal branch is
                // the only entry used for version-field provenance below.
                val magic = index.references(MAGIC_MESSAGE).filter { it.pc in owner.start..<cmp }.mapNotNull { bad ->
                    val jump = (bad.pc + 4..minOf(bad.pc + 28, cmp - 4) step 4).firstOrNull {
                        A64.bTarget(it, image.word(it) ?: 0)?.let { target -> target in ref.pc + 4..log } == true
                    } ?: return@mapNotNull null
                    if (!linear(image, bad.pc, jump)) return@mapNotNull null
                    val equal = (maxOf(owner.start + 4, bad.pc - 160)..bad.pc - 4 step 4).lastOrNull { at ->
                        val target = A64.cbzW0(at, image.word(at) ?: 0) ?: return@lastOrNull false
                        target in jump + 4..cmp && A64.blTarget(at - 4, image.word(at - 4) ?: 0) != null
                    } ?: return@mapNotNull null
                    val call = equal - 4
                    val args = A64.argumentState(image, maxOf(owner.start, call - 192), call)
                    val address = (args.registers[1] as? A64.Value.Address)?.rva ?: return@mapNotNull null
                    if (args.registers[2] != A64.Value.Constant(4) || image.ascii(address) != "AVBf") return@mapNotNull null
                    requireNotNull(A64.cbzW0(equal, requireNotNull(image.word(equal))))
                }.distinct().singleOrNull() ?: continue
                if (!linear(image, magic, cmp)) continue
                val field = DataFlow(image, magic, cmp).field(cmp, register) ?: continue
                if (field.firstLoad < magic || field.start < 0) continue
                matches += gate
            }
            matches.distinctBy { it.pc }.singleOrNull()?.let {
                result += edit("AVB 版本兼容：Footer major 采用原生接受路径", it)
            }
        }
        return result.distinctBy { it.rva }
    }

    // CCMP Wn,#minor,#C,EQ: an unequal major version makes B.LO false;
    // an equal major version delegates to the actual decoded minor value.
    private fun isMinorCompare(w: Int) = w and 0xffe00c1f.toInt() == 0x7a400802
    private fun belowGate(image: PeImage, pc: Long): Gate? {
        val w = image.word(pc) ?: return null
        val target = if (w and 0xff00001f.toInt() == 0x54000003)
            pc + (signExtend(((w ushr 5) and 0x7ffff).toLong(), 19) shl 2)
        else A64.bTarget(pc, w) ?: return null
        return Gate(pc, target)
    }
    private fun edit(name: String, gate: Gate) = PlannedEdit(name, gate.pc,
        ByteArray(4).also { it.putWord(0, A64.branch(gate.pc, gate.target)) })
    private fun isLoad(w: Int) = w and 0x3b400000 == 0x39400000
    private fun linear(image: PeImage, start: Long, end: Long): Boolean = start <= end && (start..<end step 4).all { pc ->
        val w = image.word(pc) ?: return@all false
        A64.bTarget(pc, w) == null && w and 0xff000010.toInt() != 0x54000000 &&
            w and 0x7e000000 !in listOf(0x34000000, 0x36000000) &&
            w and 0xfffffc1f.toInt() !in listOf(0xd61f0000.toInt(), 0xd63f0000.toInt(), 0xd65f0000.toInt())
    }

    private data class Pointer(val root: String, val offset: Long)
    private data class ByteSource(val root: String, val offset: Long, val load: Long)
    /** Bit-level provenance only, never an evaluator of image contents. */
    private class DataFlow(private val image: PeImage, start: Long, end: Long) {
        private val states = HashMap<Long, List<List<Set<ByteSource>?>>>()
        private val gp = MutableList(32) { unknown(64) }
        private val vector = MutableList(32) { unknown(128) }
        private val pointers = MutableList<Pointer?>(32) { Pointer("entry:$it", 0) }
        init {
            pointers[31] = Pointer("sp", 0)
            for (pc in start..end step 4) {
                states[pc] = gp.map { it.toList() }
                if (pc < end) step(pc, image.word(pc) ?: break)
            }
        }
        fun field(pc: Long, register: Int): Field? {
            if (register == 31) return null
            val bits = states[pc]?.get(register)?.take(32) ?: return null
            if (bits.any { it == null || it.size != 1 }) return null
            val sources = bits.map { requireNotNull(it).single() }
            val grouped = sources.groupBy { it.root to it.offset }
            if (grouped.size != 4 || grouped.values.any { it.size != 8 }) return null
            val roots = sources.map { it.root }.distinct()
            val offsets = sources.map { it.offset }.distinct().sorted()
            if (roots.size != 1 || offsets.zipWithNext().any { (a, b) -> b != a + 1 }) return null
            return Field(roots.single(), offsets.first(), sources.minOf { it.load })
        }
        private fun bits(register: Int, width: Int) = if (register == 31) zero(width) else gp[register].take(width)
        private fun write(register: Int, value: List<Set<ByteSource>?>, width: Int, pointer: Pointer? = null) {
            if (register == 31) return
            gp[register] = value.take(width) + zero(64 - width)
            pointers[register] = pointer
        }
        private fun load(pointer: Pointer?, offset: Long, size: Int, pc: Long): List<Set<ByteSource>?> =
            if (pointer == null) unknown(size * 8) else List(size * 8) {
                setOf(ByteSource(pointer.root, pointer.offset + offset + it / 8, pc))
            }
        private fun step(pc: Long, w: Int) {
            val rd = w and 31; val rn = (w ushr 5) and 31; val rm = (w ushr 16) and 31
            val width = if (w < 0) 64 else 32
            when {
                A64.blTarget(pc, w) != null -> {
                    (0..18).forEach { write(it, unknown(64), 64) }
                    vector.indices.forEach { vector[it] = unknown(128) }
                }
                A64.adr(pc, w) != null -> A64.adr(pc, w)!!.let { write(it.first, unknown(64), 64, Pointer("image", it.second)) }
                A64.adrp(pc, w) != null -> A64.adrp(pc, w)!!.let { write(it.first, unknown(64), 64, Pointer("image", it.second)) }
                A64.add(w) != null -> A64.add(w)!!.let { a ->
                    val p = pointers[a.rn]?.let { Pointer(it.root, it.offset + a.immediate) }
                    if (a.rd == 31) pointers[31] = p else write(a.rd, unknown(64), 64, p)
                }
                isLoad(w) && w and 0x04000000 == 0 && (w ushr 22) and 3 == 1 -> {
                    val size = 1 shl (w ushr 30)
                    write(rd, load(pointers[rn], ((w ushr 10) and 0xfff).toLong() * size, size, pc) + zero(64 - size * 8), 64)
                }
                w and 0xffc00000.toInt() == 0x3dc00000 ->
                    vector[rd] = load(pointers[rn], ((w ushr 10) and 0xfff).toLong() * 16, 16, pc)
                w and 0xfffffc00.toInt() in listOf(0x1e260000, 0x9e660000.toInt()) ->
                    write(rd, vector[rn].take(width), width)
                // ORR shifted register, including the MOV alias. Unknown bit
                // overlaps stay unknown instead of accepting a similar opcode.
                w and 0x7f200000 == 0x2a000000 -> {
                    val shift = (w ushr 22) and 3; val amount = (w ushr 10) and 63
                    val lhs = bits(rn, width); val rhs = bits(rm, width)
                    val shifted = when (shift) {
                        0 -> List(width) { if (it < amount) emptySet() else rhs.getOrNull(it - amount) }
                        1 -> List(width) { if (it + amount >= width) emptySet() else rhs[it + amount] }
                        else -> unknown(width)
                    }
                    val value = List(width) { bit ->
                        val a = lhs[bit]; val b = shifted[bit]
                        if (a == null || b == null) null else a + b
                    }
                    val pointer = if (rn == 31 && shift == 0 && amount == 0 && width == 64) pointers[rm] else null
                    write(rd, value, width, pointer)
                }
                // UBFM: the normal LSL/LSR/UBFX aliases used by byte decoding.
                w and 0x7f800000 == 0x53000000 -> {
                    val r = (w ushr 16) and 63; val s = (w ushr 10) and 63; val source = bits(rn, width)
                    val value = when {
                        r >= width || s >= width -> unknown(width)
                        s >= r -> List(width) { if (it <= s - r) source[it + r] else emptySet() }
                        else -> List(width) { val at = it - (width - r); if (at in 0..s) source[at] else emptySet() }
                    }
                    write(rd, value, width)
                }
                w and 0x7ffffc00 in listOf(0x5ac00400, 0x5ac00800, 0x5ac00c00) -> {
                    val groupBytes = when (w and 0x00000c00) { 0x400 -> 2; 0x800 -> 4; else -> 8 }
                    val source = bits(rn, width)
                    write(rd, List(width) { bit ->
                        val byte = bit / 8; val group = byte / groupBytes * groupBytes
                        source[(group + groupBytes - 1 - byte % groupBytes) * 8 + bit % 8]
                    }, width)
                }
                A64.cmpImmediate(w) != null || isMinorCompare(w) || w == NOP -> Unit
                else -> {
                    // Conservative kill for every unmodelled GP definition.
                    // A missed decoder yields a skipped optional feature.
                    for (r in 0..30) if (StraightLineLiveness.mayWriteRegister(w, r)) write(r, unknown(64), 64)
                    // Unmodelled vector writes must not leave stale Q/S values
                    // that a later FMOV could incorrectly resurrect.
                    vector.indices.forEach { vector[it] = unknown(128) }
                }
            }
        }
        private fun unknown(width: Int): List<Set<ByteSource>?> = List(width) { null }
        private fun zero(width: Int): List<Set<ByteSource>?> = List(width) { emptySet() }
    }
}
