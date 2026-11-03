package net.hawyn.ablefisp.patch

/**
 * Remove proved policy penalties after the native input has already been read.
 * This is deliberately separate from parsers: a policy exception never invents
 * an output object, a DTB pointer, a protocol method, or an OS-version value.
 */
internal object BootCompatibilityPatcher {
    private data class Function(val start: Long, val end: Long)
    private data class Memory(val register: Int, val base: Int, val offset: Int, val load: Boolean)
    private data class Increment(val pc: Long, val address: Long)
    private data class ZeroGate(val pc: Long, val target: Long, val register: Int, val nonzero: Boolean, val bits: Int)
    private const val ROLLBACK_MESSAGE = "Image rollback index is less than the stored rollback index."
    private val versionMessages = listOf(
        "oeminfo base version different from vbmeta base version!",
        "bringupAuthFile base version different from oeminfo base version!",
        "bringupAuthFile preload version different from oeminfo version!",
        "bringupAuthFile cust version different from oeminfo version!",
        "vbmeta base version different from other image base version!",
    )
    private const val AUTH_MESSAGE = "not allow update CT01 or CR01 version:"
    private const val NOP = 0xd503201f.toInt()

    fun analyze(image: PeImage): SemanticPlan {
        val index = Index(image)
        val edits = ArrayList<PlannedEdit>()
        val notes = ArrayList<String>()
        val rollback = rollbackEdits(index)
        edits += rollback
        notes += if (rollback.isEmpty()) "兼容策略：未证明可独立移除的 AVB 低索引判定，保留原解析及错误恢复。"
            else "兼容策略：${rollback.size} 处 AVB 索引比较采用已有继续路径；已存索引、解析和输出对象保持。"
        val version = versionEdits(index)
        edits += version
        notes += if (version.isEmpty()) "兼容策略：未证明 OEM 版本一致性报告计数，保留原流程。"
            else "兼容策略：${version.size} 处 OEM 版本差异不再累加拒绝计数；原读取、比较、诊断与清理继续执行。"
        val auth = authEdits(index)
        edits += auth
        notes += if (auth.isEmpty()) "兼容策略：未证明 BRINGUPAUTH 的版本字符串政策分支，保留原认证数据流程。"
            else "兼容策略：BRINGUPAUTH 的 CT01/CR01 字符串政策走已有无匹配路径；数据解析、签名验证和实际写入结果保持。"
        notes += "未知结构解析失败、缺少有效 DTB/DTBO、协议方法缺失不伪造成成功，由 EFISP 原生故障恢复处理。"
        val unique = edits.distinctBy { it.rva }
        if (edits.groupBy { it.rva }.any { (_, group) -> group.map { it.replacement.hex() }.distinct().size != 1 })
            throw PatchException("兼容策略的独立功能目标发生重叠。")
        return SemanticPlan(unique, notes)
    }

    private class Index(val image: PeImage) {
        val code = image.instructions().toList()
        val references = A64.references(image)
        private val starts = code.filter { it.second == 0xd503233f.toInt() || it.second == 0xd503237f.toInt() }.map { it.first }
        private val flows = HashMap<Long, A64ValueOrigins>()
        fun function(pc: Long): Function? {
            val start = starts.lastOrNull { it <= pc } ?: return null
            return Function(start, starts.firstOrNull { it > pc } ?: return null)
        }
        fun flow(owner: Function) = flows.getOrPut(owner.start) { A64ValueOrigins(image, owner.start, owner.end) }
        fun refs(fragment: String): List<StringReference> {
            val addresses = image.bytes.occurrences(fragment.toByteArray(Charsets.US_ASCII)).mapNotNull { offset ->
                var start = offset
                // Prefixes such as "%a: " and ": " belong to the real literal.
                while (start > 0 && offset - start < 16) {
                    val previous = image.bytes[start - 1].toInt() and 255
                    if (previous !in 32..126 && previous !in listOf(9, 10, 13)) break
                    start--
                }
                val text = image.bytes.asciiZ(start, fragment.length + 96) ?: return@mapNotNull null
                if (!text.contains(fragment)) return@mapNotNull null
                image.offsetToRva(start)
            }.toSet()
            return references.filter { it.address in addresses }
        }
        fun exact(value: String) = references.filter { image.ascii(it.address) == value }
        fun logCall(ref: StringReference, owner: Function): Long? =
            (ref.pc + 4..minOf(owner.end - 4, ref.pc + 96) step 4).firstOrNull { pc ->
                val word = image.word(pc) ?: return@firstOrNull false
                A64.blTarget(pc, word) != null &&
                    A64.argumentState(image, ref.pc, pc).registers[ref.register] == A64.Value.Address(ref.address)
            }
    }

    private fun rollbackEdits(index: Index): List<PlannedEdit> {
        val image = index.image
        val result = ArrayList<PlannedEdit>()
        for (ref in index.refs(ROLLBACK_MESSAGE)) {
            val owner = index.function(ref.pc) ?: continue
            val log = index.logCall(ref, owner) ?: continue
            val choices = ArrayList<Pair<Long, Long>>()
            for (pc in maxOf(owner.start + 4, ref.pc - 128)..ref.pc - 4 step 4) {
                val raw = image.word(pc) ?: continue
                val conditional = raw and 0xff00001f.toInt() == 0x54000002 // B.HS: image >= stored.
                val target = if (conditional) conditionalTarget(pc, raw) else A64.bTarget(pc, raw) ?: continue
                if (target <= log || target >= owner.end || !linear(image, pc + 4, log)) continue
                val compare = image.word(pc - 4) ?: continue
                // A real unsigned 64-bit image/stored-index comparison, not a
                // version string, null check or a metadata-format decision.
                if (compare and 0xffe0fc1f.toInt() != 0xeb00001f.toInt()) continue
                if ((compare ushr 5) and 31 == 31 || (compare ushr 16) and 31 == 31) continue
                val four = (log + 4..<target step 4).filter { at ->
                    A64.movWImmediate(image.word(at) ?: 0)?.second == 4
                }.singleOrNull() ?: continue
                if (!linear(image, log + 4, four)) continue
                // The original error is accepted only by an allow-error gate.
                // Its presence proves this is the recoverable AVB result path.
                val flag = (four + 4..<target step 4).any { at ->
                    val w = image.word(at) ?: return@any false
                    w and 0xff00001f.toInt() == 0x54000000 || zeroGate(at, w) != null
                }
                if (!flag) continue
                choices += pc to target
            }
            choices.distinct().singleOrNull()?.let { (pc, target) ->
                result += edit("AVB 低索引采用原生继续路径", pc, A64.branch(pc, target))
            }
        }
        return result.distinctBy { it.rva }
    }

    private fun versionEdits(index: Index): List<PlannedEdit> {
        val image = index.image
        val matches = ArrayList<Pair<StringReference, Increment>>()
        for (message in versionMessages) for (ref in index.refs(message)) {
            val owner = index.function(ref.pc) ?: continue
            val flow = index.flow(owner)
            val candidates = ArrayList<Increment>()
            for (pc in maxOf(owner.start + 4, ref.pc - 128)..ref.pc - 4 step 4) {
                val add = image.word(pc) ?: continue
                if (add and 0xff800000.toInt() != 0x11000000) continue // ADD Wd,Wn,#imm, without flags.
                val delta = ((add ushr 10) and 0xfff) shl if (add and 0x00400000 != 0) 12 else 0
                if (delta !in 0..255) continue
                val destination = add and 31; val source = (add ushr 5) and 31
                if (destination == 31 || source == 31) continue
                val loadPc = (pc - 4 downTo maxOf(owner.start, pc - 24) step 4).firstOrNull { at ->
                    memory(image.word(at) ?: 0)?.let { it.load && it.register == source && it.base != 31 } == true
                } ?: continue
                val load = requireNotNull(memory(requireNotNull(image.word(loadPc))))
                if (!StraightLineLiveness.preserves(image, loadPc + 4, pc, setOf(source))) continue
                val storePc = (pc + 4..minOf(ref.pc - 4, pc + 24) step 4).firstOrNull { at ->
                    memory(image.word(at) ?: 0)?.let { !it.load && it.register == destination && it.base != 31 } == true
                } ?: continue
                val store = requireNotNull(memory(requireNotNull(image.word(storePc))))
                if (!StraightLineLiveness.preserves(image, pc + 4, storePc, setOf(destination))) continue
                val address = flow.globalAddress(loadPc, load.base)?.plus(load.offset) ?: continue
                if (flow.globalAddress(storePc, store.base)?.plus(store.offset) != address || !image.isWritable(address, 4)) continue
                if (!linear(image, storePc + 4, ref.pc)) continue
                candidates += Increment(pc, address)
            }
            candidates.distinct().singleOrNull()?.let { matches += ref to it }
        }
        // Multiple independently named consistency checks share a report sink.
        // This excludes an unrelated local length/format accumulator.
        val accepted = matches.groupBy { it.second.address }.values.filter { group ->
            group.map { it.first.address }.distinct().size >= 2
        }.flatten()
        return accepted.map { (_, candidate) ->
            val raw = requireNotNull(image.word(candidate.pc))
            edit("OEM 版本差异不累加拒绝计数", candidate.pc, raw and 0x007ffc00.inv())
        }.distinctBy { it.rva }
    }

    private data class Search(val ref: StringReference, val call: Long, val helper: Long, val gate: ZeroGate)

    private fun authEdits(index: Index): List<PlannedEdit> {
        val image = index.image
        val result = ArrayList<PlannedEdit>()
        for (diagnostic in index.refs(AUTH_MESSAGE)) {
            val owner = index.function(diagnostic.pc) ?: continue
            val tag = index.exact("BRINGUPAUTH").any { it.pc in owner.start..<diagnostic.pc }
            if (!tag) continue
            fun searches(needle: String): List<Search> = index.exact(needle).filter { it.pc in owner.start..<diagnostic.pc }.mapNotNull { ref ->
                val call = (ref.pc + 4..minOf(ref.pc + 40, diagnostic.pc - 4) step 4).firstOrNull { at ->
                    val helper = A64.blTarget(at, image.word(at) ?: 0) ?: return@firstOrNull false
                    image.isCode(helper) && A64.argumentState(image, ref.pc, at).registers[1] == A64.Value.Address(ref.address)
                } ?: return@mapNotNull null
                val raw = image.word(call + 4) ?: return@mapNotNull null
                val gate = zeroGate(call + 4, raw) ?: when {
                    needle == "CT01" && raw == NOP -> ZeroGate(call + 4, diagnostic.pc, 0, true, 64)
                    needle == "CR01" -> A64.bTarget(call + 4, raw)?.let { ZeroGate(call + 4, it, 0, false, 64) }
                    else -> null
                } ?: return@mapNotNull null
                if (gate.bits != 64 || gate.register != 0) return@mapNotNull null
                Search(ref, call, requireNotNull(A64.blTarget(call, requireNotNull(image.word(call)))), gate)
            }
            val pairs = searches("CT01").flatMap { first -> searches("CR01").mapNotNull { second ->
                if (first.call >= second.ref.pc || second.call - first.call > 128 || first.helper != second.helper ||
                    !first.gate.nonzero || second.gate.nonzero || first.gate.target > diagnostic.pc ||
                    first.gate.target < second.gate.pc + 4 || second.gate.target <= diagnostic.pc ||
                    second.gate.target >= owner.end || !linear(image, first.gate.target, diagnostic.pc) ||
                    !linear(image, first.gate.pc + 4, second.call) ||
                    !index.flow(owner).sameValue(first.call, 0, second.call, 0)) null else first to second
            } }
            val (first, second) = pairs.distinctBy { it.first.call to it.second.call }.singleOrNull() ?: continue
            // Keep both real searches and their ABI/side effects. Only their
            // policy gates change; normal construction at the old join remains.
            result += edit("BRINGUPAUTH CT01 政策采用原生继续路径", first.gate.pc, NOP)
            result += edit("BRINGUPAUTH CR01 政策采用原生继续路径", second.gate.pc, A64.branch(second.gate.pc, second.gate.target))
        }
        return result.distinctBy { it.rva }
    }

    private fun edit(name: String, pc: Long, word: Int) = PlannedEdit(name, pc, ByteArray(4).also { it.putWord(0, word) })
    private fun memory(word: Int): Memory? {
        val op = word and 0xffc00000.toInt()
        if (op != 0xb9400000.toInt() && op != 0xb9000000.toInt()) return null
        return Memory(word and 31, (word ushr 5) and 31, ((word ushr 10) and 0xfff) * 4, op == 0xb9400000.toInt())
    }
    private fun zeroGate(pc: Long, word: Int): ZeroGate? {
        if (word and 0x7e000000 != 0x34000000) return null
        return ZeroGate(pc, conditionalTarget(pc, word), word and 31, word and 0x01000000 != 0, if (word < 0) 64 else 32)
    }
    private fun conditionalTarget(pc: Long, word: Int): Long = pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
    private fun linear(image: PeImage, start: Long, end: Long): Boolean = start <= end && (start..<end step 4).all { pc ->
        val w = image.word(pc) ?: return@all false
        A64.bTarget(pc, w) == null && w and 0xff000010.toInt() != 0x54000000 &&
            w and 0x7e000000 !in listOf(0x34000000, 0x36000000) &&
            w and 0xfffffc1f.toInt() !in listOf(0xd61f0000.toInt(), 0xd65f0000.toInt())
    }
}
