package net.hawyn.ablefisp.patch

/** Remove only the charger qualification of SCAN_DOWN, not the other key cases. */
internal object FastbootKeyPolicyPatcher {
    private data class Function(val start: Long, val end: Long)
    private data class Load(val pc: Long, val access: StateBranchEvaluator.Access, val slot: Int)
    private data class Selector(val pc: Long, val result: Int, val qualification: Int?, val alreadyApplied: Boolean)
    private data class Match(val selector: Selector, val scanCompare: Long, val flagStore: Long, val consumer: Long)

    private class Index(val image: PeImage) {
        val code = image.instructions().toList()
        val refs = A64.references(image)
        private val starts = code.filter { it.second in setOf(0xd503233f.toInt(), 0xd503237f.toInt()) }.map { it.first }
        fun function(pc: Long): Function? {
            val start = starts.lastOrNull { it <= pc } ?: return null
            return Function(start, starts.firstOrNull { it > pc } ?: return null)
        }
        fun body(f: Function) = code.filter { it.first in f.start..<f.end }
        fun references(f: Function, predicate: (String) -> Boolean) = refs.filter {
            it.pc in f.start..<f.end && image.ascii(it.address)?.let(predicate) == true
        }
    }

    fun analyze(image: PeImage): SemanticPlan {
        val index = Index(image)
        val charger = index.refs.filter { image.ascii(it.address) == "GetChargerType" }
        val launches = index.refs.filter { image.ascii(it.address)?.startsWith("Launching fastboot") == true }
        if (charger.isEmpty() && launches.isEmpty()) return SemanticPlan(emptyList(), listOf(
            "未发现按键/charger 资格的 Fastboot 入口关系，保留原按键流程。"))
        val keyReaders = index.refs.filter { image.ascii(it.address)?.startsWith("ScanCode =") == true }
            .mapNotNull { index.function(it.pc) }.distinct().filter { f ->
                index.references(f) { it.startsWith("Key Stroke Read") }.isNotEmpty()
            }.map { it.start }.toSet()
        val matches = ArrayList<Match>()
        val functions = charger.mapNotNull { index.function(it.pc) }.distinct()
        for (function in functions) {
            val launchRefs = launches.filter { it.pc in function.start..<function.end }
            if (launchRefs.isEmpty()) continue
            val body = index.body(function)
            // Only address queries are needed. Do not build provenance equations for
            // the giant entry function's unrelated normal-boot branches.
            val flow = A64ValueOrigins(image, function.start, function.end)
            val chargerSlots = charger.filter { it.pc in function.start..<function.end }.flatMap { ref ->
                body.filter { it.first in ref.pc..minOf(function.end - 4, ref.pc + 96) }.mapNotNull { (pc, word) ->
                    val access = StateBranchEvaluator.access(word) ?: return@mapNotNull null
                    if (access.load || access.width != 4 || access.rt != 31) return@mapNotNull null
                    val slot = stackSlot(flow, pc, access) ?: return@mapNotNull null
                    val qualified = body.any { (storePc, storeWord) ->
                        if (storePc !in maxOf(function.start, ref.pc - 128)..<ref.pc) false
                        else {
                            val store = StateBranchEvaluator.access(storeWord)
                            store != null && !store.load && store.width == 4 && store.rt != 31 &&
                                stackSlot(flow, storePc, store) == slot &&
                                body.any { (producerPc, producerWord) ->
                                    producerPc in maxOf(function.start, storePc - 32)..<storePc &&
                                        isCset(producerWord, 3) &&
                                        fromDefinition(image, producerPc, producerWord and 31, storePc, store.rt) &&
                                        body.any { (comparePc, compareWord) ->
                                            comparePc in maxOf(function.start, producerPc - 32)..<producerPc &&
                                                A64.cmpImmediate(compareWord)?.second == 2 &&
                                                StraightLineLiveness.preserves(image, comparePc + 4, producerPc, flags = true)
                                        }
                                }
                        }
                    }
                    if (qualified) pc to slot else null
                }
            }.distinct()
            for ((comparePc, compareWord) in body) {
                val compare = A64.cmpImmediate(compareWord) ?: continue
                if (compare.second != 2 || compare.first == 31 || compareWord ushr 31 != 0) continue
                val keyLoad = loadBefore(image, flow, function, comparePc, compare.first) ?: continue
                if (!flow.hasOnlyLinearEntry(keyLoad.pc, comparePc)) continue
                val readCalls = body.filter { (pc, word) ->
                    pc in maxOf(function.start, keyLoad.pc - 128)..<keyLoad.pc &&
                        A64.blTarget(pc, word) in keyReaders && flow.stackAddress(pc, 0) == keyLoad.slot
                }
                if (readCalls.size != 1) continue
                val selectors = body.filter { it.first in comparePc + 4..minOf(function.end - 4, comparePc + 32) }
                    .mapNotNull { (pc, word) -> selector(word)?.takeIf {
                        flow.hasOnlyLinearEntry(comparePc, pc) &&
                            StraightLineLiveness.preserves(image, comparePc + 4, pc, flags = true)
                    }?.let { pc to it } }
                for ((selectorPc, unplaced) in selectors) {
                    val selected = unplaced.copy(pc = selectorPc)
                    val store = guardedFlagStore(image, body, selected) ?: continue
                    val companion = body.filter { it.first in store + 4..minOf(function.end - 4, store + 128) }
                        .mapNotNull { (pc, word) ->
                            val cmp = A64.cmpImmediate(word) ?: return@mapNotNull null
                            if (word ushr 31 != 0 || cmp.second != 1) return@mapNotNull null
                            val load = loadBefore(image, flow, function, pc, cmp.first) ?: return@mapNotNull null
                            if (load.slot != keyLoad.slot) return@mapNotNull null
                            body.filter { it.first in pc + 4..minOf(function.end - 4, pc + 24) }.mapNotNull { (at, raw) ->
                                selector(raw)?.takeIf { !it.alreadyApplied &&
                                    StraightLineLiveness.preserves(image, pc + 4, at, flags = true) }
                                    ?.let { it.copy(pc = at) }
                            }.singleOrNull()
                        }.distinct()
                    if (companion.size != 1) continue
                    val qualification = selected.qualification ?: companion.single().qualification ?: continue
                    val qualificationLoad = loadBefore(image, flow, function, selectorPc, qualification) ?: continue
                    val otherQualification = loadBefore(image, flow, function, companion.single().pc,
                        requireNotNull(companion.single().qualification)) ?: continue
                    if (qualificationLoad.slot != otherQualification.slot) continue
                    if (chargerSlots.none { (zeroPc, slot) -> slot == qualificationLoad.slot &&
                            stableStack(image, zeroPc, qualificationLoad.pc) }) continue
                    val consumers = flagConsumers(image, flow, body, store, launchRefs)
                    if (consumers.size == 1) matches += Match(selected, comparePc, store, consumers.single())
                }
            }
        }
        val unique = matches.distinctBy { it.selector.pc }
        if (unique.size != 1) throw PatchException(
            "音量下 Fastboot：按键读出、SCAN_DOWN、charger 资格与 Fastboot 标志关系未唯一匹配，本次未生成镜像。",
            if (unique.size > 1) PatchFailureKind.AMBIGUOUS_CORE_FEATURE else PatchFailureKind.INCOMPLETE_CORE_FEATURE)
        val found = unique.single()
        val replacement = ByteArray(4).also { it.putWord(0, 0x1a9f17e0 or found.selector.result) }
        return SemanticPlan(listOf(PlannedEdit("音量下进入 Fastboot 不要求插线/charger 资格", found.selector.pc, replacement)), listOf(
            "按按键读出缓冲、SCAN_DOWN=2 比较、GetChargerType 资格字段和 Fastboot 标志消费者定位。",
            "仅把音量下选择器改为按键相等时写 1；其他按键和原 Fastboot 标志/入口保持。",
            if (found.selector.alreadyApplied) "原成品已有同一音量下修补，已重新核对数据流。" else "已解除音量下进入 Fastboot 的 charger 资格。"))
    }

    private fun isCset(word: Int, condition: Int): Boolean =
        word and 0xffff0fe0.toInt() == 0x1a9f07e0 && ((word ushr 12) and 15) == (condition xor 1)

    private fun selector(word: Int): Selector? {
        val rd = word and 31
        if (rd == 31) return null
        if (isCset(word, 0)) return Selector(0, rd, null, true)
        if (word and 0xffe00c00.toInt() != 0x1a800000) return null
        val rn = (word ushr 5) and 31; val rm = (word ushr 16) and 31
        val qualification = when ((word ushr 12) and 15) {
            0 -> if (rm == 31 && rn != 31) rn else return null
            1 -> if (rn == 31 && rm != 31) rm else return null
            else -> return null
        }
        return Selector(0, rd, qualification, false)
    }

    private fun stackSlot(flow: A64ValueOrigins, pc: Long, access: StateBranchEvaluator.Access): Int? =
        flow.stackAddress(pc, access.rn)?.let { base ->
            (base.toLong() + access.offset).takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        }

    private fun loadBefore(image: PeImage, flow: A64ValueOrigins, function: Function, pc: Long, register: Int): Load? {
        var reg = register
        for (at in pc - 4 downTo maxOf(function.start, pc - 128) step 4) {
            val word = image.word(at) ?: return null
            val copy = move(word, reg)
            if (copy != null) { reg = copy; continue }
            val memory = StateBranchEvaluator.access(word)
            if (memory?.load == true && memory.width == 4 && memory.rt == reg) {
                val slot = stackSlot(flow, at, memory) ?: return null
                return Load(at, memory, slot)
            }
            if (A64.blTarget(at, word) != null || word and 0xfffffc1f.toInt() == 0xd63f0000.toInt() ||
                StraightLineLiveness.mayWriteRegister(word, reg)) return null
        }
        return null
    }

    private fun move(word: Int, destination: Int): Int? = if (word and 0xffe0ffe0.toInt() in
        listOf(0x2a0003e0, 0xaa0003e0.toInt()) && word and 31 == destination) (word ushr 16) and 31 else null

    private fun fromDefinition(image: PeImage, definition: Long, defined: Int, use: Long, register: Int): Boolean {
        var reg = register
        for (pc in use - 4 downTo definition + 4 step 4) {
            val word = image.word(pc) ?: return false
            move(word, reg)?.let { reg = it; continue }
            if (A64.blTarget(pc, word) != null || word and 0xfffffc1f.toInt() == 0xd63f0000.toInt() ||
                StraightLineLiveness.mayWriteRegister(word, reg)) return false
        }
        return reg == defined
    }

    private fun guardedFlagStore(image: PeImage, body: List<Pair<Long, Int>>, selector: Selector): Long? {
        val result = ArrayList<Long>()
        for ((pc, word) in body.filter { it.first in selector.pc + 4..selector.pc + 40 }) {
            val cmp = A64.cmpImmediate(word) ?: continue
            if (cmp.second != 1 || word ushr 31 != 0 || !fromDefinition(image, selector.pc, selector.result, pc, cmp.first)) continue
            for ((branch, raw) in body.filter { it.first in pc + 4..pc + 16 }) {
                if (raw and 0xff00001f.toInt() != 0x54000001 ||
                    !StraightLineLiveness.preserves(image, pc + 4, branch, flags = true)) continue
                val skip = branch + (signExtend(((raw ushr 5) and 0x7ffff).toLong(), 19) shl 2)
                for ((store, stored) in body.filter { it.first in branch + 4..<minOf(skip, branch + 24) }) {
                    val access = StateBranchEvaluator.access(stored) ?: continue
                    if (access.load || access.width != 1 || access.rn == 31 ||
                        !fromDefinition(image, selector.pc, selector.result, store, access.rt)) continue
                    if (!StraightLineLiveness.preserves(image, branch + 4, store, setOf(access.rt))) continue
                    result += store
                }
            }
        }
        return result.distinct().singleOrNull()
    }

    private fun flagConsumers(image: PeImage, flow: A64ValueOrigins, body: List<Pair<Long, Int>>,
                              store: Long, launches: List<StringReference>): List<Long> {
        val written = requireNotNull(StateBranchEvaluator.access(requireNotNull(image.word(store))))
        val writtenBase = flow.globalAddress(store, written.rn)
        return body.mapNotNull { (pc, word) ->
            val load = StateBranchEvaluator.access(word) ?: return@mapNotNull null
            if (pc <= store || !load.load || load.width != 1 || load.rn == 31 || load.offset != written.offset) return@mapNotNull null
            val base = flow.globalAddress(pc, load.rn)
            if (base != null && writtenBase != null && base != writtenBase) return@mapNotNull null
            val tests = body.filter { it.first in pc + 4..pc + 16 }.filter { (branch, raw) ->
                raw and 31 == load.rt && StraightLineLiveness.preserves(image, pc + 4, branch, setOf(load.rt)) &&
                    StateBranchEvaluator.booleanEdges(branch, raw)?.second?.let { fastboot ->
                        launches.any { it.pc in fastboot..fastboot + 64 }
                    } == true
            }
            pc.takeIf { tests.size == 1 }
        }.distinct()
    }

    /** Stack offsets in the charger and key-output accesses must use the same frame. */
    private fun stableStack(image: PeImage, first: Long, last: Long): Boolean {
        if (first >= last) return false
        for (pc in first + 4..<last step 4) {
            val word = image.word(pc) ?: return false
            if (word and 0x9f00001f.toInt() == 0x9100001f.toInt() && word and 0x20000000 == 0) return false
            if (word and 0x3a000000 == 0x28000000 && (word ushr 5) and 31 == 31 &&
                (word ushr 23) and 3 in listOf(1, 3)) return false
            if (word and 0x3b200000 == 0x38000000 && (word ushr 5) and 31 == 31 &&
                (word ushr 10) and 3 in listOf(1, 3)) return false
        }
        return true
    }
}
