package net.hawyn.ablefisp.patch

/**
 * Repair an OEM hash-error recovery call without changing the AVB result itself.
 *
 * The report is also reached by invalid metadata on some loaders, so the injected
 * leaf checks both the actual allow-error value and the actual result == 3. It
 * never changes a global recovery switch or the original recovery function.
 */
internal object BootFailurePolicyPatcher {
    const val STUB_BYTES = 20

    data class Plan(
        val callRva: Long,
        val originalTarget: Long,
        val allowRegister: Int,
        val errorRegister: Int,
        val existingStubRva: Long? = null,
    )

    private data class Function(val start: Long, val end: Long)
    private data class Existing(val target: Long, val allow: Int, val error: Int)

    private class Index(val image: PeImage) {
        val code = image.instructions().toList()
        val starts = code.filter { it.second == 0xd503233f.toInt() || it.second == 0xd503237f.toInt() }.map { it.first }
        val refs = A64.references(image)
        private val flows = HashMap<Function, A64ValueOrigins>()
        private val jumps = HashMap<Function, Map<Long, List<Long>>>()
        fun function(pc: Long): Function? {
            val start = starts.lastOrNull { it <= pc } ?: return null
            val end = starts.firstOrNull { it > pc } ?: return null
            return Function(start, end)
        }
        fun words(function: Function) = code.asSequence().filter { it.first in function.start..<function.end }
        fun jumps(function: Function) = jumps.getOrPut(function) { boundedJumpTables(this, function) }
        fun flow(function: Function) = flows.getOrPut(function) {
            A64ValueOrigins(image, function.start, function.end, jumps(function))
        }
    }

    /** No firmware identity, offsets, physical device or register-number table. */
    fun analyze(image: PeImage): List<Plan> {
        val format = image.bytes.occurrences("%a verify hash fail\u0000".toByteArray(Charsets.US_ASCII))
            .mapNotNull(image::offsetToRva).toSet()
        if (format.isEmpty()) return emptyList()
        val index = Index(image)
        val property = image.bytes.occurrences("androidboot.vbmeta.device_state\u0000".toByteArray(Charsets.US_ASCII))
            .mapNotNull(image::offsetToRva).toSet()
        val optionFunctions = index.refs.filter { it.address in property }.mapNotNull { index.function(it.pc)?.start }.toSet()
        val results = ArrayList<Plan>()
        val relevant = index.refs.filter { it.address in format }.mapNotNull { index.function(it.pc) }.distinct()
        for (function in relevant) {
            val words = index.words(function).toList()
            // An unmodelled indirect transfer could introduce an unseen definition.
            if (words.any { (pc, raw) -> raw and 0xfffffc1f.toInt() == 0xd61f0000.toInt() && pc !in index.jumps(function) }) continue
            val flow = index.flow(function)
            for ((formatterCall, raw) in words) {
                if (A64.blTarget(formatterCall, raw) == null) continue
                val args = A64.argumentState(image, maxOf(function.start, formatterCall - 96), formatterCall)
                val text = args.registers[2] as? A64.Value.Address ?: continue
                if (text.rva !in format) continue
                val buffer = args.registers[0] as? A64.Value.Stack ?: continue
                val capacity = args.registers[1] as? A64.Value.Constant ?: continue
                if (capacity.value !in 1..4096) continue
                // The formatter's output must be the detail argument of this call.
                for ((reportCall, reportWord) in words.filter { it.first in formatterCall + 4..formatterCall + 64 }) {
                    val target = A64.blTarget(reportCall, reportWord) ?: continue
                    val state = A64.argumentState(image, formatterCall + 4, reportCall)
                    if (state.registers[0] != A64.Value.Constant(0) || state.registers[1] != buffer) continue
                    val existing = decodeStub(image, target)
                    val original = existing?.target ?: target
                    if (!isBootFailureReporter(index, original)) continue
                    if (!returnValueIsDead(index, function, reportCall)) continue
                    val error = errorRegister(index, function, reportCall) ?: continue
                    val allowArguments = allowArguments(index, function, optionFunctions)
                    for (argument in allowArguments) {
                        val registers = (2..30).filter { flow.derivesFromEntry(reportCall, it, argument) }
                        // At least one live copy is sufficient. Every accepted copy has
                        // the same single entry-argument leaf, not equal value ranges.
                        val allow = registers.minOrNull() ?: continue
                        if (!hasFullPartitionSizeSelection(index, function, argument)) continue
                        if (allow == error) continue
                        if (existing != null && (existing.allow != allow || existing.error != error)) continue
                        results += Plan(reportCall, original, allow, error, existing?.let { target })
                    }
                }
            }
        }
        val distinct = results.distinctBy { listOf(it.callRva, it.originalTarget, it.allowRegister.toLong(), it.errorRegister.toLong()) }
        if (distinct.size != relevant.size || distinct.map { it.callRva }.distinct().size != distinct.size) {
            throw PatchException(
                "假回锁：AVB hash 故障报告、允许错误值及错误码来源未唯一匹配，本次未生成镜像。",
                if (distinct.size > relevant.size) PatchFailureKind.AMBIGUOUS_CORE_FEATURE else PatchFailureKind.INCOMPLETE_CORE_FEATURE,
            )
        }
        return distinct
    }

    /** The caller must allocate mapped RX space; this method never chooses padding. */
    fun edits(plan: Plan, stubRva: Long): List<PlannedEdit> {
        require(plan.allowRegister in 2..30 && plan.errorRegister in 2..30 && plan.allowRegister != plan.errorRegister)
        val code = ByteArray(STUB_BYTES)
        // allow==0 -> original; otherwise only ERROR_VERIFICATION(3) skips it.
        code.putWord(0, 0x34000080 or plan.allowRegister)
        code.putWord(4, 0x71000c1f or (plan.errorRegister shl 5))
        code.putWord(8, 0x54000041)
        code.putWord(12, 0xd65f03c0.toInt())
        code.putWord(16, A64.branch(stubRva + 16, plan.originalTarget))
        val call = ByteArray(4).also { it.putWord(0, A64.branch(plan.callRva, stubRva) or 0x80000000.toInt()) }
        return listOf(
            PlannedEdit("AVB hash 错误的条件恢复调用", plan.callRva, call),
            PlannedEdit("仅允许验签错误时跳过厂商恢复", stubRva, code),
        )
    }

    private fun decodeStub(image: PeImage, target: Long): Existing? {
        val a = image.word(target) ?: return null
        val b = image.word(target + 4) ?: return null
        if (!image.isCode(target, STUB_BYTES) || a and -32 != 0x34000080 ||
            b and 0xfffffc1f.toInt() != 0x71000c1f || image.word(target + 8) != 0x54000041 ||
            image.word(target + 12) != 0xd65f03c0.toInt()) return null
        val original = A64.bTarget(target + 16, image.word(target + 16) ?: return null) ?: return null
        if (!image.isCode(original)) return null
        return Existing(original, a and 31, (b ushr 5) and 31)
    }

    private fun isBootFailureReporter(index: Index, target: Long): Boolean {
        val function = index.function(target) ?: return false
        if (function.start != target) return false
        return index.refs.any { it.pc in function.start..<function.end && index.image.ascii(it.address)?.let { s ->
            "bootfail_errno" in s && "suggest_method" in s
        } == true }
    }

    /** Discover the result variable from the post-report accepted 3..5 range. */
    private fun errorRegister(index: Index, function: Function, call: Long): Int? {
        val image = index.image
        val candidates = ArrayList<Int>()
        for ((pc, word) in index.words(function).filter { it.first in call + 4..call + 64 }) {
            if (word and 0xffc00000.toInt() != 0x51000000 || ((word ushr 10) and 0xfff) != 3) continue
            val result = word and 31; var source = (word ushr 5) and 31
            val compare = index.words(function).firstOrNull { (at, raw) ->
                at in pc + 4..pc + 32 && A64.cmpImmediate(raw) == (result to 2) &&
                    StraightLineLiveness.preserves(image, pc + 4, at, setOf(result))
            } ?: continue
            val branch = image.word(compare.first + 4) ?: continue
            if (branch and 0xff00001f.toInt() != 0x54000008) continue // unsigned HI
            // Reverse MOV copies between the call and SUB. No guessed register.
            for (at in pc - 4 downTo call + 4 step 4) {
                val raw = image.word(at) ?: break
                if (raw and 0xffe0ffe0.toInt() in listOf(0x2a0003e0, 0xaa0003e0.toInt()) && raw and 31 == source) {
                    source = (raw ushr 16) and 31
                }
            }
            if (source !in 2..30 || !StraightLineLiveness.preserves(image, call + 4, pc, setOf(source))) continue
            if (!verificationResultReaches(index, function, call, source)) continue
            candidates += source
        }
        return candidates.distinct().singleOrNull()
    }

    /**
     * At least one exact ERROR_VERIFICATION definition must reach the formatter
     * without redefining this result. Other error paths may have service-return
     * origins; their numeric result is checked at runtime rather than assumed.
     */
    private fun verificationResultReaches(index: Index, function: Function, call: Long, register: Int): Boolean {
        val pending = ArrayDeque<Long>()
        index.words(function).filter { (_, raw) -> A64.movWImmediate(raw) == (register to 3) }
            .forEach { pending.add(it.first + 4) }
        val visited = HashSet<Long>()
        while (pending.isNotEmpty()) {
            val pc = pending.removeFirst()
            if (pc == call) return true
            if (!visited.add(pc) || pc !in function.start..<function.end) continue
            val raw = index.image.word(pc) ?: continue
            if (A64.blTarget(pc, raw) != null || raw and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) {
                if (register in 19..29) pending.add(pc + 4)
                continue
            }
            if (raw and 0xfffffc1f.toInt() == 0xd61f0000.toInt()) {
                pending.addAll(index.jumps(function)[pc].orEmpty())
                continue
            }
            if (raw and 0xfffffc1f.toInt() == 0xd65f0000.toInt() || raw in listOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt())) continue
            val target = directTarget(pc, raw)
            if (target != null) {
                pending.add(target)
                if (A64.bTarget(pc, raw) == null) pending.add(pc + 4)
                continue
            }
            if (raw and 0x0ff80000 == 0x0f000000 || // SIMD modified immediate, no GP output
                !StraightLineLiveness.mayWriteRegister(raw, register)) pending.add(pc + 4)
        }
        return false
    }

    /** Skipping a void report must not manufacture a consumed service return. */
    private fun returnValueIsDead(index: Index, function: Function, call: Long): Boolean {
        val pending = ArrayDeque<Long>().also { it.add(call + 4) }
        val visited = HashSet<Long>()
        while (pending.isNotEmpty()) {
            val pc = pending.removeFirst()
            if (!visited.add(pc)) continue
            if (visited.size > 512 || pc !in function.start..<function.end) return false
            val word = index.image.word(pc) ?: return false
            if (A64.blTarget(pc, word) != null || word and 0xfffffc1f.toInt() in listOf(
                    0xd63f0000.toInt(), 0xd65f0000.toInt()) || word in listOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt())) return false
            val reads = gpReads(word) ?: return false
            if (0 in reads) return false
            if (gpWritesZero(word)) continue
            if (word and 0xfffffc1f.toInt() == 0xd61f0000.toInt()) {
                val targets = index.jumps(function)[pc] ?: return false
                pending.addAll(targets)
                continue
            }
            directTarget(pc, word)?.let { target ->
                pending.add(target)
                if (A64.bTarget(pc, word) == null) pending.add(pc + 4)
            } ?: pending.add(pc + 4)
        }
        return true
    }

    /** Only classes needed for a bounded dead-return proof are accepted. */
    private fun gpReads(word: Int): Set<Int>? {
        fun registers(vararg values: Int) = values.filter { it != 31 }.toSet()
        val rd = word and 31; val rn = (word ushr 5) and 31; val rm = (word ushr 16) and 31
        if (A64.adr(0, word) != null || A64.adrp(0, word) != null || A64.bTarget(0, word) != null ||
            word and 0xff000010.toInt() == 0x54000000 || word and 0x0ff80000 == 0x0f000000 ||
            word and 0xfffff01f.toInt() == 0xd503201f.toInt()) return emptySet()
        if (word and 0x7e000000 in listOf(0x34000000, 0x36000000)) return registers(rd)
        if (word and 0xfffffc1f.toInt() == 0xd61f0000.toInt()) return registers(rn)
        if (word and 0x1f800000 == 0x12800000) return if (word and 0x60000000 == 0x60000000) registers(rd) else emptySet()
        if (word and 0x3a000000 == 0x28000000) {
            val store = word and 0x00400000 == 0 && word and 0x04000000 == 0
            return registers(rn, if (store) rd else 31, if (store) (word ushr 10) and 31 else 31)
        }
        if (word and 0x3b000000 in listOf(0x39000000, 0x38000000)) {
            val indexed = word and 0x3b200000 == 0x38200000
            val store = word and 0x00c00000 == 0 && word and 0x04000000 == 0
            return registers(rn, if (indexed) rm else 31, if (store) rd else 31)
        }
        if (word and 0x3b000000 == 0x18000000) return emptySet()
        if (word and 0x1f000000 == 0x11000000 || word and 0x1f800000 in listOf(0x12000000, 0x13000000)) return registers(rn)
        if (word and 0x1f000000 in listOf(0x0a000000, 0x0b000000) || word and 0x1fe00000 == 0x1a800000) return registers(rn, rm)
        if (word and 0x7fe08000 == 0x1b000000) return registers(rn, rm, (word ushr 10) and 31)
        return null
    }

    private fun gpWritesZero(word: Int): Boolean {
        val rd = word and 31; val rn = (word ushr 5) and 31
        if (A64.bTarget(0, word) != null || word and 0xff000010.toInt() == 0x54000000 ||
            word and 0x7e000000 in listOf(0x34000000, 0x36000000) ||
            word and 0xfffffc1f.toInt() == 0xd61f0000.toInt() ||
            word and 0x0ff80000 == 0x0f000000 || word and 0xfffff01f.toInt() == 0xd503201f.toInt()) return false
        if (word and 0x3a000000 == 0x28000000) return (word ushr 23) and 3 in listOf(1, 3) && rn == 0 ||
                word and 0x00400000 != 0 && word and 0x04000000 == 0 && (rd == 0 || (word ushr 10) and 31 == 0)
        if (word and 0x3b000000 in listOf(0x39000000, 0x38000000)) {
            val indexed = word and 0x3b200000 == 0x38200000
            val writeBack = word and 0x3b000000 == 0x38000000 && !indexed && (word ushr 10) and 3 in listOf(1, 3)
            val operation = (word ushr 22) and 3
            val integerLoad = word and 0x04000000 == 0 && operation != 0 && !(word ushr 30 == 3 && operation == 2)
            return writeBack && rn == 0 || integerLoad && rd == 0
        }
        if (word and 0x3b000000 == 0x18000000) return word and 0x04000000 == 0 && word ushr 30 != 3 && rd == 0
        return rd == 0 // All remaining accepted classes have one scalar Rd.
    }

    /**
     * Connect the helper's discovered boolean argument to the bit-zero AVB flag
     * in a caller that also invokes the device_state option producer. Parameter
     * numbers are found from call values; neither helper nor caller ABI is fixed.
     */
    private fun allowArguments(index: Index, helper: Function, optionFunctions: Set<Long>): Set<Int> {
        val image = index.image
        val result = HashSet<Int>()
        val calls = index.code.filter { (pc, raw) -> A64.blTarget(pc, raw) == helper.start && pc !in helper.start..<helper.end }
        for ((call, _) in calls) {
            val caller = index.function(call) ?: continue
            val body = index.words(caller).toList()
            if (caller.start !in optionFunctions && body.none { (at, raw) -> A64.blTarget(at, raw)?.let { it in optionFunctions } == true }) continue
            val flow = index.flow(caller)
            for ((pc, word) in body) {
                val source = bitZeroCopy(word) ?: continue
                if ((0..7).none { flow.derivesFromEntry(pc, source.second, it) }) continue
                for (argument in 0..7) {
                    if (flow.derivesFrom(call, argument, pc, source.first)) result += argument
                }
            }
        }
        return result
    }

    /** AND #1 or UBFX #0,#1, including an X-register result. */
    private fun bitZeroCopy(word: Int): Pair<Int, Int>? {
        val mask = word and 0xfffffc00.toInt()
        if (mask !in listOf(0x12000000, 0x92400000.toInt(), 0x53000000, 0xd3400000.toInt())) return null
        return (word and 31) to ((word ushr 5) and 31)
    }

    /**
     * Recover a compiler's range-checked relative switch, not guessed destinations.
     * CMP index,N / B.HI default bounds the unsigned table index. Each read-only
     * byte/halfword/word entry is added to an ADR code base with scale four.
     * Register definitions may be scheduled within the bounded dispatch window.
     * The ordinary provenance engine then handles copies, joins and stack spills.
     */
    private fun boundedJumpTables(index: Index, function: Function): Map<Long, List<Long>> {
        val image = index.image
        val body = index.words(function).toList()
        val result = HashMap<Long, List<Long>>()
        for ((branch, raw) in body) {
            if (raw and 0xfffffc1f.toInt() != 0xd61f0000.toInt()) continue
            val branchRegister = (raw ushr 5) and 31
            val add = body.lastOrNull { (pc, word) -> pc in branch - 16..<branch &&
                word and 0xffe0fc00.toInt() == 0x8b000800.toInt() && word and 31 == branchRegister &&
                StraightLineLiveness.preserves(image, pc + 4, branch, setOf(branchRegister))
            } ?: continue
            val codeRegister = (add.second ushr 5) and 31
            val entryRegister = (add.second ushr 16) and 31
            val load = body.lastOrNull { (pc, word) -> pc in add.first - 24..<add.first &&
                // Unsigned integer register-offset load, no sign extension.
                word and 0x3fe00c00 == 0x38600800 && word and 31 == entryRegister &&
                (word ushr 13) and 7 in listOf(2, 3) &&
                StraightLineLiveness.preserves(image, pc + 4, add.first, setOf(entryRegister))
            } ?: continue
            val size = 1 shl (load.second ushr 30)
            if (size !in listOf(1, 2, 4) || size > 1 && load.second and 0x1000 == 0) continue
            val tableRegister = (load.second ushr 5) and 31
            val indexRegister = (load.second ushr 16) and 31
            val guard = body.lastOrNull { (pc, word) -> pc in load.first - 64..<load.first &&
                word and 0xff00001f.toInt() == 0x54000008 &&
                image.word(pc - 4)?.let(A64::cmpImmediate)?.let { it.first == indexRegister && it.second in 0..255 } == true &&
                StraightLineLiveness.preserves(image, pc + 4, load.first, setOf(indexRegister))
            } ?: continue
            val compare = requireNotNull(image.word(guard.first - 4))
            // CMP Wn bounds only the low word. An X-indexed table access also
            // needs a proved W definition, or unconstrained high bits remain.
            if (compare ushr 31 == 0 && (load.second ushr 13) and 7 == 3 &&
                !hasZeroExtendedIndex(index, function, guard.first - 4, indexRegister)) continue
            val default = guard.first + (signExtend(((guard.second ushr 5) and 0x7ffff).toLong(), 19) shl 2)
            if (default in guard.first + 4..branch) continue
            // This straight dispatch must have no alternate direct entry after
            // the range check. Otherwise the bound would not dominate the read.
            if (index.code.any { (pc, word) ->
                val target = directTarget(pc, word) ?: A64.blTarget(pc, word)
                target != null && target in guard.first + 4..branch && pc != target - 4
            }) continue
            val count = requireNotNull(image.word(guard.first - 4)?.let(A64::cmpImmediate)).second + 1
            val codeBase = body.mapNotNull { (pc, word) ->
                A64.adr(pc, word)?.takeIf { it.first == codeRegister && pc in guard.first + 4..<add.first &&
                    StraightLineLiveness.preserves(image, pc + 4, add.first, setOf(codeRegister)) }
            }.singleOrNull()?.second ?: continue
            val table = index.refs.filter { it.register == tableRegister && it.pc in guard.first + 4..<load.first }
                .mapNotNull { ref -> StraightLineLiveness.referenceEnd(image, ref)?.takeIf { end ->
                    end <= load.first && StraightLineLiveness.preserves(image, end, load.first, setOf(tableRegister))
                }?.let { ref.address } }.distinct().singleOrNull() ?: continue
            val offset = image.rvaToOffset(table, count * size) ?: continue
            if (image.isWritable(table, count * size)) continue
            val targets = (0 until count).map { n ->
                var entry = 0L
                for (byte in 0 until size) entry = entry or ((image.bytes[offset + n * size + byte].toLong() and 255) shl (byte * 8))
                codeBase + entry * 4
            }
            if (targets.any { it !in function.start..<function.end || !image.isCode(it) || it in guard.first + 4..branch }) continue
            result[branch] = targets.distinct()
        }
        return result
    }

    private fun hasZeroExtendedIndex(index: Index, function: Function, compare: Long, register: Int): Boolean {
        var pc = compare - 4
        while (pc >= function.start && compare - pc <= 128) {
            val word = index.image.word(pc) ?: return false
            if (directTarget(pc, word) != null || A64.blTarget(pc, word) != null ||
                word and 0xfffffc1f.toInt() in listOf(0xd61f0000.toInt(), 0xd63f0000.toInt(), 0xd65f0000.toInt())) return false
            if (word and 0x0ff80000 != 0x0f000000 && StraightLineLiveness.mayWriteRegister(word, register)) {
                if (word and 31 == register && word ushr 31 == 0 &&
                    (word and 0x1f000000 in listOf(0x11000000, 0x0a000000, 0x0b000000) ||
                        word and 0x1f800000 in listOf(0x12000000, 0x12800000, 0x13000000) ||
                        word and 0x1fe00000 == 0x1a800000 || word and 0x7fe08000 == 0x1b000000)) return true
                if (word and 0x3b000000 in listOf(0x39000000, 0x38000000) && word and 31 == register &&
                    word and 0x04000000 == 0) {
                    val operation = (word ushr 22) and 3
                    val size = word ushr 30
                    val indexed = word and 0x3b200000 == 0x38200000
                    val writeBack = word and 0x3b000000 == 0x38000000 && !indexed && (word ushr 10) and 3 in listOf(1, 3)
                    if (writeBack && (word ushr 5) and 31 == register) return false
                    return operation == 1 && size <= 2 || operation == 3 && size <= 1
                }
                if (word and 0xffc00000.toInt() == 0x29400000) {
                    return word and 31 == register || (word ushr 10) and 31 == register
                }
                if (word and 0xff000000.toInt() == 0x18000000) return word and 31 == register
                return false
            }
            pc -= 4
        }
        return false
    }

    private fun directTarget(pc: Long, word: Int): Long? {
        A64.bTarget(pc, word)?.let { return it }
        if (word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 == 0x34000000) {
            return pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
        }
        if (word and 0x7e000000 == 0x36000000) return pc + (signExtend(((word ushr 5) and 0x3fff).toLong(), 14) shl 2)
        return null
    }

    /** Relaxed verification loads the whole partition instead of descriptor length. */
    private fun hasFullPartitionSizeSelection(index: Index, function: Function, argument: Int): Boolean {
        val image = index.image
        val body = index.words(function).toList()
        val flow = index.flow(function)
        for ((pc, word) in body) {
            val tested = when {
                // TST Wn,#0xff followed by EQ/NE, or CBZ/CBNZ of the value.
                word and 0xfffffc1f.toInt() == 0x72001c1f -> (word ushr 5) and 31
                StateBranchEvaluator.booleanEdges(pc, word) != null -> word and 31
                else -> continue
            }
            if (!flow.derivesFromEntry(pc, tested, argument)) continue
            val branchPc = if (word and 0xfffffc1f.toInt() == 0x72001c1f) pc + 8 else pc
            // Scheduling can put a descriptor-size store between TST and B.eq.
            val branch = if (branchPc != pc) (pc + 4..pc + 16 step 4).firstOrNull { at ->
                image.word(at)?.let { it and 0xff00001f.toInt() in listOf(0x54000000, 0x54000001) } == true
            } else pc
            if (branch == null) continue
            val branchWord = image.word(branch) ?: continue
            val target = if (branchWord and 0xff000010.toInt() == 0x54000000) {
                branch + (signExtend(((branchWord ushr 5) and 0x7ffff).toLong(), 19) shl 2)
            } else StateBranchEvaluator.booleanEdges(branch, branchWord)?.first ?: continue
            if (target <= branch || target > function.end) continue
            val indirect = body.firstOrNull { (at, raw) -> at in branch + 4..<minOf(target, branch + 40) && raw and 0xfffffc1f.toInt() == 0xd63f0000.toInt() } ?: continue
            val state = A64.argumentState(image, branch + 4, indirect.first)
            if (state.registers[1] is A64.Value.Stack && state.registers[2] is A64.Value.Stack) return true
        }
        return false
    }
}
