package net.hawyn.ablefisp.patch

/** Consumer/CFG driven discovery, independent of firmware identity and layout. */
internal object FakeLockPatcher {
    private data class Policy(val pc: Long, val register: Int, val start: Long, val end: Long)
    private data class Completion(val state: Long, val stateRegister: Int, val outgoing: Long, val facts: NormalBootStateFacts)
    private class Index(val image: PeImage) {
        val code = image.instructions().toList()
        val starts = code.filter { it.second in listOf(0xd503233f.toInt(), 0xd503237f.toInt()) }.map { it.first }
        private val flows = HashMap<Long, A64ValueOrigins>()
        private val consumers = HashMap<Long, Boolean>()
        fun bounds(pc: Long): Pair<Long, Long>? {
            val start = starts.lastOrNull { it <= pc } ?: return null
            val end = starts.firstOrNull { it > pc } ?: return null
            return start to end
        }
        private fun lowerBound(pc: Long): Int {
            var left = 0; var right = code.size
            while (left < right) {
                val middle = (left + right) ushr 1
                if (code[middle].first < pc) left = middle + 1 else right = middle
            }
            return left
        }
        fun instructions(start: Long, end: Long) = code.subList(lowerBound(start), lowerBound(end)).asSequence()
        fun flow(start: Long, end: Long) = flows.getOrPut(start) { A64ValueOrigins(image, start, end) }
        fun consumer(target: Long, identify: () -> Boolean) = consumers.getOrPut(target, identify)
    }

    fun analyze(image: PeImage, runtimeState: Long): SemanticPlan {
        val index = Index(image)
        val policies = policies(index, runtimeState)
        val completions = policies.flatMap { completion(index, it) }.distinct()
        if (completions.size != 1) throw PatchException(
            "假回锁：策略值来源、正常状态与消费字段未唯一匹配（策略 ${policies.size} 组，完成路径 ${completions.size} 组），本次未生成镜像。",
            if (completions.size > 1) PatchFailureKind.AMBIGUOUS_CORE_FEATURE else PatchFailureKind.INCOMPLETE_CORE_FEATURE,
        )
        val found = completions.single()
        val sink = requireNotNull(image.word(found.outgoing))
        return SemanticPlan(listOf(
            edit("正常启动状态报告 green", found.state, 0x52800000 or found.stateRegister),
            edit("Keymaster 输入 unlocked 字节", found.outgoing, (sink and -32) or 31),
            deviceStateProperty(index),
        ), listOf(
            "Freedom 三处分支保持原方案；Fastboot 使用原运行时解锁状态。",
            "通过 CFG 来源归并追踪策略的寄存器复制、栈保存/重载及寄存器复用。",
            "按分支值域和真实消费字段定位正常启动上报，不要求固定指令排列或固定栈偏移。",
            "AVB 允许错误的策略值保持，正常下游上报独立构造；厂商恢复由附加策略按条件处理，TEE 接受与真机开机尚未验证。",
        ), normalBootState = found.facts)
    }

    private fun edit(name: String, pc: Long, word: Int) =
        PlannedEdit(name, pc, ByteArray(4).also { it.putWord(0, word) })

    private fun policies(index: Index, global: Long): List<Policy> {
        val image = index.image
        val result = ArrayList<Policy>()
        for ((pc, word) in index.code) {
            val load = StateBranchEvaluator.access(word) ?: continue
            if (!load.load || load.width != 4 || load.rt == 31) continue
            val (start, end) = index.bounds(pc) ?: continue
            val source = A64.registerAddress(image, start, pc, load.rn)?.plus(load.offset) ?: continue
            if (source != global) continue
            val flow = index.flow(start, end)
            for ((comparePc, compare) in index.instructions(pc + 4, end)) {
                if (A64.cmpImmediate(compare) != (load.rt to 1)) continue
                if (!StraightLineLiveness.preserves(image, pc + 4, comparePc, setOf(load.rt))) continue
                for ((selectPc, select) in index.instructions(comparePc + 4, end)) {
                    if (select and 0xffe00c00.toInt() != 0x1a800000) continue
                    if (!StraightLineLiveness.preserves(image, comparePc + 4, selectPc, flags = true)) continue
                    val rn = (select ushr 5) and 31; val rm = (select ushr 16) and 31
                    val operand = when {
                        (select ushr 12) and 15 == 1 && rn == 31 -> rm
                        (select ushr 12) and 15 == 0 && rm == 31 -> rn
                        else -> continue
                    }
                    val initLoads = index.instructions(start, selectPc).filter { (at, raw) ->
                        val m = StateBranchEvaluator.access(raw) ?: return@filter false
                        m.load && m.width == 1 && m.rn != 31 &&
                            flow.derivesFrom(selectPc, operand, at, m.rt) &&
                            A64.registerAddress(image, start, at, m.rn)?.let { image.isWritable(it + m.offset, 1) } == true
                    }.toList()
                    if (initLoads.size == 1) result += Policy(selectPc, select and 31, start, end)
                }
            }
        }
        return result.distinct()
    }

    private fun completion(index: Index, policy: Policy): List<Completion> {
        val image = index.image
        val origins = index.flow(policy.start, policy.end)
        val cfg = CompletionFlow(image, policy.start, policy.end, objectAliases = origins::objectOffset)
        val instructions = index.instructions(policy.start, policy.end).toList()
        val calls = instructions.filter { (pc, w) -> A64.blTarget(pc, w)?.let { acceptsBootState(index, it) } == true }
        if (calls.isEmpty()) return emptyList()
        val result = ArrayList<Completion>()
        for ((testPc, test) in instructions) {
            val (zeroEntry, nonzeroEntry) = StateBranchEvaluator.booleanEdges(testPc, test) ?: continue
            val testedRegister = test and 31
            if (!origins.derivesFromOrZero(testPc, testedRegister, policy.pc, policy.register)) continue
            val unlocked = StateBranchEvaluator.evaluate(image, policy.start, policy.end, nonzeroEntry, testedRegister, true)
            val locked = StateBranchEvaluator.evaluate(image, policy.start, policy.end, zeroEntry, testedRegister, false)
            if (unlocked.isEmpty() || locked.isEmpty()) continue
            val value = unlocked.first()
            if (value.producer < 0 || value.register !in 0..30 || value.values.size != 1 || value.values.any { it !in 0..1 }) continue
            if (unlocked.any { it.base != value.base || it.offset != value.offset || it.producer != value.producer }) continue
            if (locked.any { it.base != value.base || it.offset != value.offset || it.values.any { n -> n !in setOf(0, 2) } }) continue
            val stateDefinitions = (unlocked + locked).map { it.store }.toSet()
            for ((loadPc, raw) in instructions) {
                val load = StateBranchEvaluator.access(raw) ?: continue
                if (!load.load || load.width != 4 || load.rn != value.base || load.offset != value.offset) continue
                if (!cfg.hasOnlyDefinitions(loadPc, stateDefinitions, load.rn, load.offset, 4)) continue
                val stateCopies = instructions.filter { (at, w) ->
                    val m = StateBranchEvaluator.access(w) ?: return@filter false
                    !m.load && m.width == 4 && origins.stackAddress(at, m.rn) != null &&
                        origins.derivesFrom(at, m.rt, loadPc, load.rt)
                }
                for ((statePc, stateWord) in stateCopies) {
                    val state = requireNotNull(StateBranchEvaluator.access(stateWord))
                    val stateOffset = requireNotNull(origins.stackAddress(statePc, state.rn)) + state.offset
                    val inputOffset = stateOffset - 4
                    if (inputOffset < 0) continue
                    val consumers = calls.filter { (pc, _) -> origins.stackAddress(pc, 0) == inputOffset }
                    if (consumers.size != 1) continue
                    val consumer = consumers.single().first
                    for ((bytePc, byteWord) in instructions) {
                        val byte = StateBranchEvaluator.access(byteWord) ?: continue
                        if (byte.load || byte.width != 1 ||
                            origins.stackAddress(bytePc, byte.rn)?.plus(byte.offset) != inputOffset) continue
                        if (byte.rt != 31 && !origins.derivesFromOrZero(bytePc, byte.rt, policy.pc, policy.register)) continue
                        val construction = minOf(loadPc, statePc, bytePc)
                        val inputFlow = CompletionFlow(image, construction, policy.end, setOf(value.base), origins::objectOffset)
                        if (!inputFlow.hasOnlyDefinition(consumer, statePc, 31, stateOffset, 4) ||
                            !inputFlow.hasOnlyDefinition(consumer, bytePc, 31, inputOffset, 1)) continue
                        result += Completion(value.producer, value.register, bytePc, NormalBootStateFacts(
                            policy.start, policy.end, policy.pc, policy.register, value.producer, value.register,
                            value.store, value.base, value.offset.toLong(),
                        ))
                    }
                }
            }
        }
        return result.distinct()
    }

    private fun acceptsBootState(index: Index, target: Long): Boolean {
        return index.consumer(target) { identifyBootState(index, target) }
    }

    private fun identifyBootState(index: Index, target: Long): Boolean {
        val (start, end) = index.bounds(target) ?: return false
        if (start != target) return false
        val code = index.instructions(start, end).toList()
        val flow = index.flow(start, end)
        return listOf(0 to 1, 4 to 4, 8 to 4, 16 to 8).all { (offset, width) ->
            code.any { (pc, w) -> StateBranchEvaluator.access(w)?.let {
                it.load && it.offset == offset && it.width == width && flow.derivesFromEntry(pc, it.rn, 0)
            } == true }
        }
    }

    private fun deviceStateProperty(index: Index): PlannedEdit {
        val image = index.image
        val propertyAddresses = image.bytes.occurrences("androidboot.vbmeta.device_state\u0000".toByteArray(Charsets.US_ASCII))
            .mapNotNull(image::offsetToRva).toSet()
        val locked = image.bytes.occurrences("locked\u0000".toByteArray(Charsets.US_ASCII)).mapNotNull(image::offsetToRva).toSet()
        val unlocked = image.bytes.occurrences("unlocked\u0000".toByteArray(Charsets.US_ASCII)).mapNotNull(image::offsetToRva).toSet()
        val found = ArrayList<PlannedEdit>()
        val functions = A64.references(image).filter { it.address in propertyAddresses }.mapNotNull { index.bounds(it.pc) }.distinct()
        for ((start, end) in functions) {
            val flow = index.flow(start, end)
            val code = index.instructions(start, end).toList()
            val consumers = code.filter { (pc, w) -> A64.blTarget(pc, w) != null && flow.globalAddress(pc, 1) in propertyAddresses }
            for ((pc, word) in code) {
                if (word and 0xffe00c00.toInt() != 0x9a800000.toInt()) continue
                val rd = word and 31; val rn = (word ushr 5) and 31; val rm = (word ushr 16) and 31
                val a = flow.globalAddress(pc, rn); val b = flow.globalAddress(pc, rm)
                if (a !in locked && b !in locked) continue
                if (a !in locked && a !in unlocked || b !in locked && b !in unlocked) continue
                if (consumers.count { (call, _) -> flow.derivesFrom(call, 2, pc, rd) } != 1) continue
                val selected = if (a in locked) rn else rm
                found += edit("Android 启动属性 device_state=locked", pc,
                    (word and 0xffe0fc1f.toInt()) or (selected shl 5) or (selected shl 16))
            }
        }
        return found.distinctBy { it.rva }.singleOrNull() ?: throw PatchException(
            "假回锁：Android device_state 属性与消费参数未唯一匹配，本次未生成镜像。",
            if (found.size > 1) PatchFailureKind.AMBIGUOUS_CORE_FEATURE else PatchFailureKind.INCOMPLETE_CORE_FEATURE,
        )
    }
}
