package net.hawyn.ablefisp.patch

/** Skip this ABL's TZ rollback-version update request through its existing no-update path.
 * The signed container version, stored indexes and unrelated SCM requests are untouched. */
internal object RollbackUpdatePatcher {
    private val commands = setOf(0x32000110L, 0x0200011eL)
    private data class Function(val start: Long, val end: Long)
    private sealed interface Value {
        data class Constant(val value: Long) : Value
        data class Stack(val offset: Long) : Value
        data class StackLoad(val offset: Long) : Value
        data class Method(val slot: Long, val offset: Long) : Value
        data class Result(val call: Long, val bits: Int = 64) : Value
    }
    private data class Call(val pc: Long, val command: Long?, val slot: Long, val request: Long, val response: Long)
    private data class Gate(val pc: Long, val join: Long, val predicate: Long)

    fun analyze(image: PeImage): SemanticPlan {
        val code = image.instructions().toList()
        val starts = code.filter { it.second in setOf(0xd503233f.toInt(), 0xd503237f.toInt()) }.map { it.first }
        fun function(pc: Long): Function? {
            val first = starts.lastOrNull { it <= pc } ?: return null
            val end = starts.firstOrNull { it > pc } ?: return null
            return Function(first, end)
        }
        val prepared = code.mapNotNull { (pc, raw) ->
            if (!isBlr(raw)) return@mapNotNull null
            val owner = function(pc) ?: return@mapNotNull null
            // Evidence extraction also sees a syntactic constructor whose
            // preparation was made unsafe by a new entry edge. It is used only
            // to reject an incomplete match, never to authorise a patch.
            val command = (localState(image, owner, pc, strict = false)[1] as? Value.Constant)?.value
            val candidate = call(image, owner, pc)
            if (command in commands && candidate == null) incomplete()
            candidate
        }
        val calls = prepared.filter { it.command in commands }
        val evidence = listOf("UpdateRollbackSyscall", "TZ_UPDATE_ROLLBACK_VERSION").any {
            image.bytes.occurrences(it.toByteArray(Charsets.US_ASCII)).isNotEmpty()
        }
        if (calls.isEmpty()) {
            if (evidence) incomplete()
            return SemanticPlan(emptyList(), listOf("未发现此类 TZ 回滚版本更新请求，没有添加该项修改。"))
        }
        val plans = ArrayList<PlannedEdit>()
        for ((owner, group) in calls.groupBy { requireNotNull(function(it.pc)) }) {
            val body = code.filter { it.first in owner.start..<owner.end }
            // An unmodelled BR could enter the update calls without visiting the gate.
            if (body.any { isBr(it.second) }) incomplete()
            if (group.map { Triple(it.slot, it.request, it.response) }.distinct().size != 1) incomplete()
            val joins = group.map { responseJoin(image, owner, it) ?: incomplete() }.distinct()
            if (joins.size != 1) incomplete()
            val join = joins.single()
            if (!joinFlagsIndependent(image, owner, join)) incomplete()
            val candidates = body.mapNotNull { (pc, raw) ->
                val target = A64.beqTarget(pc, raw) ?: A64.bTarget(pc, raw) ?: return@mapNotNull null
                if (target != join || pc >= group.minOf { it.pc }) return@mapNotNull null
                val predicate = predicate(image, owner, pc) ?: return@mapNotNull null
                val gate = Gate(pc, join, predicate)
                val graph = Graph(image, owner, gate)
                if (group.any { it.pc !in graph.reachable(owner.start) || it.pc !in graph.reachable(pc + 4, stop = join) }) return@mapNotNull null
                if (group.any { it.pc in graph.reachable(owner.start, stop = pc) }) return@mapNotNull null
                val regionFromGate = graph.reachable(pc + 4, stop = join)
                if (group.any { update -> graph.ancestors(update.pc, stop = join).any { at ->
                        at in regionFromGate && changesSp(requireNotNull(image.word(at))) } }) return@mapNotNull null
                // Existing private cleanup adapters can re-enter after their
                // selector. Reject an external entry only if it can bypass the
                // gate and actually reach one of the update requests.
                if (code.any { (from, word) -> from !in owner.start..<owner.end &&
                        directTarget(from, word)?.let { target -> target in owner.start + 4..<owner.end &&
                            group.any { it.pc in graph.reachable(target, stop = pc) } } == true }) return@mapNotNull null
                // A malformed constructor must not disappear when the other
                // request remains valid. Missing future fallback code is fine;
                // an unaccounted same-protocol/same-buffer request is not.
                val region = graph.reachable(pc + 4, stop = join)
                if (prepared.any { it.pc in owner.start..<owner.end && it.pc in region && it !in group &&
                        it.slot == group.first().slot && it.request == group.first().request && it.response == group.first().response })
                    return@mapNotNull null
                val primary = group.filter { it.command == 0x32000110L }
                val fallback = group.filter { it.command == 0x0200011eL }
                if (primary.isNotEmpty() && fallback.isNotEmpty() &&
                    fallback.any { legacy -> primary.none { modern -> unsupportedFallback(image, owner, graph, modern, legacy, join) } })
                    return@mapNotNull null
                gate
            }.distinct()
            if (candidates.size != 1) throw PatchException(
                "TZ 回滚版本更新请求的协议参数、成功汇合与启动门控未唯一匹配，本次未生成镜像。",
                if (candidates.size > 1) PatchFailureKind.AMBIGUOUS_CORE_FEATURE else PatchFailureKind.INCOMPLETE_CORE_FEATURE,
            )
            val gate = candidates.single()
            plans += PlannedEdit("阻止启动时请求上调回滚版本", gate.pc,
                ByteArray(4).also { it.putWord(0, A64.branch(gate.pc, gate.join)) })
        }
        return SemanticPlan(plans, listOf(
            "按 TZ 更新命令 ABI、协议方法、结果缓冲区、成功汇合与支配门控定位 ${plans.size} 处更新入口。",
            "保留原启动条件查询调用，随后进入既有不更新路径；本 ABL 不再执行已定位的回滚版本上调请求。",
            "没有清零设备回滚计数，没有改写 MBN 签名元数据；更早引导阶段和其他组件的独立更新策略仍在本修改范围之外。",
        ))
    }

    private fun incomplete(): Nothing = throw PatchException(
        "发现 TZ 回滚版本更新功能证据，但更新请求与自然跳过路径未完整匹配，本次未生成镜像。",
        PatchFailureKind.INCOMPLETE_CORE_FEATURE,
    )

    private fun call(image: PeImage, owner: Function, pc: Long): Call? {
        val state = localState(image, owner, pc)
        val method = state[(requireNotNull(image.word(pc)) ushr 5) and 31] as? Value.Method ?: return null
        if (method.offset != 0x38L || state[0] != Value.StackLoad(method.slot)) return null
        val command = (state[1] as? Value.Constant)?.value
        if (state[2] != Value.Constant(0)) return null
        val request = (state[3] as? Value.Stack)?.offset ?: return null
        val response = (state[4] as? Value.Stack)?.offset ?: return null
        if (request == response || request < 0 || response < 0) return null
        return Call(pc, command, method.slot, request, response)
    }

    /** Trace the call's zero transport status to the response == 1 success edge. */
    private fun responseJoin(image: PeImage, owner: Function, call: Call): Long? {
        var pc = call.pc + 4
        val state = hashMapOf<Int, Value>(0 to Value.Result(call.pc))
        var target: Long? = null
        while (pc < owner.end) {
            val word = image.word(pc) ?: return null
            if (changesSp(word)) return null
            val zero = cbz(pc, word)
            if (word ushr 31 == 1 && zero != null && state[word and 31] == Value.Result(call.pc)) { target = zero; break }
            if (isTransfer(pc, word)) return null
            execute(image, pc, word, state)
            pc += 4
        }
        pc = target ?: return null
        val response = hashMapOf<Int, Value>()
        while (pc < owner.end) {
            val word = image.word(pc) ?: return null
            if (changesSp(word)) return null
            val cmp = A64.cmpImmediate(word)
            if (cmp?.second == 1 && response[cmp.first] == Value.StackLoad(call.response)) {
                var branch = pc + 4
                while (image.word(branch) == 0xd503201f.toInt()) branch += 4
                return image.word(branch)?.let { A64.beqTarget(branch, it) }?.takeIf { it in owner.start..<owner.end }
            }
            if (isTransfer(pc, word)) return null
            execute(image, pc, word, response)
            pc += 4
        }
        return null
    }

    private fun predicate(image: PeImage, owner: Function, branch: Long): Long? {
        // The zero byte of a real predicate call controls the original no-update edge.
        var at = branch - 4
        while (at >= owner.start) {
            val word = image.word(at) ?: return null
            if (word and 0x7f80001f == 0x7200001f && logicalImmediate(word) == 0xffL) {
                if (!StraightLineLiveness.preserves(image, at + 4, branch, flags = true)) return null
                val source = localState(image, owner, at)[(word ushr 5) and 31] as? Value.Result ?: return null
                val target = image.word(source.call)?.let { A64.blTarget(source.call, it) } ?: return null
                return target.takeIf { image.isCode(it) }
            }
            if (isTransfer(at, word) || !StraightLineLiveness.preserves(image, at, at + 4, flags = true)) return null
            at -= 4
        }
        return null
    }

    private fun unsupportedFallback(image: PeImage, owner: Function, graph: Graph,
                                    primary: Call, fallback: Call, join: Long): Boolean {
        if (primary.pc >= fallback.pc || fallback.pc in graph.reachable(owner.start, stop = primary.pc)) return false
        val state = hashMapOf<Int, Value>(0 to Value.Result(primary.pc))
        var pc = primary.pc + 4
        while (pc < fallback.pc) {
            val word = image.word(pc) ?: return false
            // CMP Xresult,Xunsupported. The call ABI uses the 64-bit EFI status.
            if (word and 0xffe0fc1f.toInt() == 0xeb00001f.toInt()) {
                val a = state[(word ushr 5) and 31]; val b = state[(word ushr 16) and 31]
                if (setOf(a, b) == setOf(Value.Result(primary.pc), Value.Constant(0x8000000000000003uL.toLong()))) {
                    val branch = pc + 4
                    val raw = image.word(branch) ?: return false
                    if (raw and 0xff00001f.toInt() != 0x54000001) return false
                    val failed = conditionalTarget(branch, raw)
                    return fallback.pc !in graph.reachable(failed, stop = join) &&
                        fallback.pc in graph.reachable(branch + 4, stop = join) &&
                        fallback.pc !in graph.reachable(owner.start, stop = branch)
                }
            }
            if (A64.blTarget(pc, word) != null || isBlr(word) || A64.bTarget(pc, word) != null || isBr(word) || isReturn(word)) return false
            execute(image, pc, word, state)
            pc += 4
        }
        return false
    }

    /** A single predecessor-free preparation block; unknown writes erase facts. */
    private fun localState(image: PeImage, owner: Function, end: Long, strict: Boolean = true): HashMap<Int, Value> {
        var start = end
        var seed: Pair<Int, Value>? = null
        while (start > owner.start) {
            val pc = start - 4; val word = image.word(pc) ?: break
            if (isTransfer(pc, word) && (strict || A64.blTarget(pc, word) != null || isBlr(word) || isReturn(word) || isBr(word))) {
                if (A64.blTarget(pc, word) != null || isBlr(word)) seed = 0 to Value.Result(pc)
                break
            }
            start = pc
        }
        val state = hashMapOf<Int, Value>()
        seed?.let { state[it.first] = it.second }
        // A branch into the middle of a preparation block can skip a MOVK,
        // overwrite or pointer definition. Linear facts then have no proof.
        val incomingLow = if (seed == null) start + 4 else start
        if (strict) for (from in owner.start until owner.end step 4) {
            val raw = image.word(from) ?: continue
            val target = directTarget(from, raw) ?: continue
            if (from !in start..<end && target in incomingLow..end) return hashMapOf()
        }
        var pc = start
        while (pc < end) {
            val word = requireNotNull(image.word(pc))
            // The recognised ABI preparation has a stable frame and no stores.
            // Conservatively discard facts for other shapes, including a store
            // that could replace an already-loaded protocol slot.
            if (strict && (changesSp(word) || isStore(word))) return hashMapOf()
            execute(image, pc, word, state)
            pc += 4
        }
        return state
    }

    private fun execute(image: PeImage, pc: Long, word: Int, values: HashMap<Int, Value>) {
        val rd = word and 31
        fun put(value: Value?) { if (rd != 31) { if (value == null) values.remove(rd) else values[rd] = value } }
        if (changesSp(word)) { values.clear(); return }
        if (isStore(word)) {
            values.entries.removeIf { it.value is Value.StackLoad || it.value is Value.Method }
        }
        if (A64.blTarget(pc, word) != null || isBlr(word)) {
            (0..18).forEach(values::remove); values[0] = Value.Result(pc); return
        }
        val width = if (word ushr 31 == 0) 32 else 64
        val wide = word and 0x7f800000
        if (wide in setOf(0x52800000, 0x12800000, 0x72800000)) {
            val shift = ((word ushr 21) and 3) * 16
            if (shift >= width) { put(null); return }
            val field = ((word ushr 5) and 0xffff).toLong() shl shift
            val raw = when (wide) {
                0x52800000 -> field
                0x12800000 -> field.inv()
                else -> (values[rd] as? Value.Constant)?.value?.let { (it and (0xffffL shl shift).inv()) or field }
            }
            put(raw?.let { Value.Constant(if (width == 32) it and 0xffffffffL else it) }); return
        }
        if (word and 0x7fe0ffe0 == 0x2a0003e0) {
            val source = (word ushr 16) and 31
            val value = if (source == 31) Value.Constant(0) else values[source]
            put(if (width == 64) value else when (value) {
                is Value.Constant -> Value.Constant(value.value and 0xffffffffL)
                is Value.Result -> Value.Result(value.call, minOf(value.bits, 32))
                else -> null
            }); return
        }
        if (word and 0x7f8003e0 == 0x320003e0) { put(logicalImmediate(word)?.let { Value.Constant(it) }); return }
        A64.add(word)?.let { add ->
            val base = if (add.rn == 31) Value.Stack(0) else values[add.rn]
            put(when (base) {
                is Value.Stack -> Value.Stack(base.offset + add.immediate)
                is Value.Constant -> Value.Constant(base.value + add.immediate)
                else -> null
            }); return
        }
        if (word and 0xffc00000.toInt() == 0xf9400000.toInt()) {
            val rn = (word ushr 5) and 31; val offset = ((word ushr 10) and 0xfff) * 8L
            val base = if (rn == 31) Value.Stack(0) else values[rn]
            put(when (base) {
                is Value.Stack -> Value.StackLoad(base.offset + offset)
                is Value.StackLoad -> Value.Method(base.offset, offset)
                else -> null
            }); return
        }
        // Stores, compares and branches don't define a register, but all other
        // unrecognised possible writes kill the affected symbolic value.
        for (register in values.keys.toList()) if (StraightLineLiveness.mayWriteRegister(word, register)) values.remove(register)
    }

    private fun logicalImmediate(word: Int): Long? {
        val width = if (word ushr 31 == 0) 32 else 64
        val n = (word ushr 22) and 1; val s = (word ushr 10) and 63; val r = (word ushr 16) and 63
        val selector = (n shl 6) or (s.inv() and 63)
        if (selector <= 1) return null
        val size = 1 shl (31 - Integer.numberOfLeadingZeros(selector))
        if (size > width) return null
        val levels = size - 1; val ones = (s and levels) + 1; val rotation = r and levels
        if (ones == size) return null
        val mask = if (size == 64) -1L else (1L shl size) - 1
        var element = (1L shl ones) - 1
        if (rotation != 0) element = ((element ushr rotation) or (element shl (size - rotation))) and mask
        var value = 0L
        for (shift in 0 until width step size) value = value or (element shl shift)
        return value
    }

    /** The forced edge may carry different NZCV and predicate return X0.
     * Prove both are dead before any downstream use, including call arguments. */
    private fun joinFlagsIndependent(image: PeImage, owner: Function, join: Long): Boolean {
        data class State(val pc: Long, val flags: Boolean = true, val result: Boolean = true)
        val pending = arrayListOf(State(join)); val seen = hashSetOf<State>(); var index = 0
        while (index < pending.size) {
            val state = pending[index++]; val pc = state.pc
            if (pc !in owner.start..<owner.end || !image.isCode(pc)) return false
            if (!seen.add(state)) continue
            val word = requireNotNull(image.word(pc))
            if (A64.blTarget(pc, word) != null || isBlr(word) || isReturn(word)) {
                if (state.result) return false
                continue
            }
            if (state.flags && readsFlags(word)) return false
            if (state.result && readsReturnRegister(word) != false) return false
            val knownDefinition = word and 0x1f000000 in setOf(0x11000000, 0x0b000000) && word and 0x20000000 != 0 ||
                (word and 0x1f800000 == 0x12000000 || word and 0x1f000000 == 0x0a000000) && (word ushr 29) and 3 == 3
            val flags = state.flags && !knownDefinition
            val result = state.result && !StraightLineLiveness.mayWriteRegister(word, 0)
            if (!flags && !result) continue
            if (A64.bTarget(pc, word) != null || word and 0x7e000000 in setOf(0x34000000, 0x36000000)) {
                pending += successors(pc, word).map { State(it, flags, result) }; continue
            }
            if (flags && !StraightLineLiveness.preserves(image, pc, pc + 4, flags = true)) return false
            pending += State(pc + 4, flags, result)
        }
        return true
    }

    /** null means an unmodelled possible use, never proof that X0 is dead. */
    private fun readsReturnRegister(word: Int): Boolean? {
        if (word == 0xd503201f.toInt() || A64.adr(0, word) != null || A64.adrp(0, word) != null ||
            A64.bTarget(0, word) != null || word and 0xff000010.toInt() == 0x54000000) return false
        if (word and 0x7e000000 in setOf(0x34000000, 0x36000000)) return word and 31 == 0
        val wide = word and 0x7f800000
        if (wide in setOf(0x52800000, 0x12800000)) return false
        if (wide == 0x72800000) return word and 31 == 0 // MOVK reads preserved bits.
        if (word and 0x1f000000 in setOf(0x11000000, 0x12000000, 0x13000000)) return (word ushr 5) and 31 == 0
        if (word and 0x1f000000 in setOf(0x0a000000, 0x0b000000))
            return (word ushr 5) and 31 == 0 || (word ushr 16) and 31 == 0
        if (word and 0x3a000000 == 0x28000000) {
            if ((word ushr 5) and 31 == 0) return true
            return word and 0x04400000 == 0 && (word and 31 == 0 || (word ushr 10) and 31 == 0)
        }
        if (word and 0x3b000000 in setOf(0x39000000, 0x38000000)) {
            if ((word ushr 5) and 31 == 0) return true
            if (word and 0x00200000 != 0 && (word ushr 16) and 31 == 0) return true
            return word and 0x04000000 == 0 && (word ushr 22) and 3 == 0 && word and 31 == 0
        }
        return null
    }

    private fun readsFlags(word: Int): Boolean = word and 0xff000010.toInt() == 0x54000000 ||
        word and 0x1fe00000 in setOf(0x1a800000, 0x1a400000, 0x1a000000) ||
        word and 0xffffffe0.toInt() == 0xd53b4200.toInt() || word and 0x5f200c00 == 0x1e200c00

    private fun changesSp(word: Int): Boolean {
        if (word and 31 == 31 && word and 0x20000000 == 0 &&
            (word and 0x1f000000 == 0x11000000 || word and 0x1f200000 == 0x0b200000)) return true
        val base = (word ushr 5) and 31
        return base == 31 && (word and 0x3a000000 == 0x28000000 && (word ushr 23) and 3 in setOf(1, 3) ||
            word and 0x3b200000 == 0x38000000 && (word ushr 10) and 3 in setOf(1, 3))
    }

    private fun isStore(word: Int): Boolean {
        if (word and 0x3a400000 == 0x28000000) return true
        if (word and 0x3b000000 !in setOf(0x39000000, 0x38000000)) return false
        val op = (word ushr 22) and 3
        return if (word and 0x04000000 == 0) op == 0 else op in setOf(0, 2)
    }

    private class Graph(val image: PeImage, val owner: Function, val gate: Gate) {
        fun ancestors(target: Long, stop: Long? = null): Set<Long> {
            val parents = hashMapOf<Long, MutableList<Long>>()
            for (pc in owner.start until owner.end step 4) {
                if (pc == stop) continue
                val word = image.word(pc) ?: continue
                val next = if (pc == gate.pc) listOf(pc + 4, gate.join) else successors(pc, word)
                for (at in next) if (at in owner.start..<owner.end) parents.getOrPut(at) { arrayListOf() } += pc
            }
            val seen = hashSetOf<Long>(); val pending = arrayListOf(target); var index = 0
            while (index < pending.size) {
                val pc = pending[index++]
                if (pc == stop || !seen.add(pc)) continue
                pending += parents[pc].orEmpty()
            }
            return seen
        }
        fun reachable(start: Long, stop: Long? = null): Set<Long> {
            val visited = hashSetOf<Long>(); val pending = arrayListOf(start); var index = 0
            while (index < pending.size) {
                val pc = pending[index++]
                if (pc == stop || pc !in owner.start..<owner.end || !image.isCode(pc) || !visited.add(pc)) continue
                val word = requireNotNull(image.word(pc))
                val next = if (pc == gate.pc) listOf(pc + 4, gate.join) else successors(pc, word)
                pending += next.filter { it !in visited }
            }
            return visited
        }
    }

    private fun successors(pc: Long, word: Int): List<Long> {
        A64.bTarget(pc, word)?.let { return listOf(it) }
        if (isConditional(word)) return listOf(pc + 4, conditionalTarget(pc, word))
        if (isReturn(word) || isBr(word)) return emptyList()
        return listOf(pc + 4)
    }
    private fun cbz(pc: Long, word: Int): Long? = if (word and 0x7f000000 == 0x34000000) conditionalTarget(pc, word) else null
    private fun isBlr(word: Int) = word and 0xfffffc1f.toInt() == 0xd63f0000.toInt()
    private fun isBr(word: Int) = word and 0xfffffc1f.toInt() == 0xd61f0000.toInt()
    private fun isReturn(word: Int) = word and 0xfffffc1f.toInt() == 0xd65f0000.toInt() || word in setOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt())
    private fun isConditional(word: Int) = word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 in setOf(0x34000000, 0x36000000)
    private fun conditionalTarget(pc: Long, word: Int) = pc + (if (word and 0x7e000000 == 0x36000000)
        signExtend(((word ushr 5) and 0x3fff).toLong(), 14) else signExtend(((word ushr 5) and 0x7ffff).toLong(), 19)) * 4
    private fun isTransfer(pc: Long, word: Int) = A64.blTarget(pc, word) != null || A64.bTarget(pc, word) != null ||
        isConditional(word) || isBlr(word) || isBr(word) || isReturn(word)
    private fun directTarget(pc: Long, word: Int): Long? = A64.blTarget(pc, word) ?: A64.bTarget(pc, word) ?:
        if (isConditional(word)) conditionalTarget(pc, word) else null
}
