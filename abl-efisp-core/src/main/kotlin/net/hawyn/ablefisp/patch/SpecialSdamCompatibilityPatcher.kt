package net.hawyn.ablefisp.patch

/** Optional, child-local special-SDAM transaction policy. Native cleanup and
 * ordinary VADJ/xvdd work remain native; this never patches a reset service. */
internal object SpecialSdamCompatibilityPatcher {
    private val SDAM_GUID = byteArrayOf(0x42, 0x57, 0xbf.toByte(), 0x66, 0x38, 0x30, 0x49, 0x40,
        0x8e.toByte(), 0x9a.toByte(), 0x42, 0x36, 0xdb.toByte(), 0x77, 0x06, 0xdd.toByte())
    private const val NOP = 0xd503201f.toInt()
    private data class Function(val start: Long, val end: Long)
    private data class Gate(val pc: Long, val target: Long)
    private data class Facts(val gate: Gate, val slot: Long, val tuple: List<Long>, val reads: List<Long>,
        val write: Long, val flag: Long, val caller: Long, val reset: Long)
    private sealed interface Value {
        data object Unknown : Value
        data class Input(val register: Int) : Value
        data class Constant(val number: Long) : Value
        data class Address(val rva: Long) : Value
        data class Stack(val offset: Long) : Value
        data class Load(val pc: Long, val base: Value, val offset: Long, val width: Int) : Value
        data class Result(val call: Long) : Value
        data class Calculation(val inputs: List<Value>) : Value
    }
    private data class Method(val slot: Long, val offset: Long)
    private data class Call(val pc: Long, val method: Method, val args: List<Value>)

    fun analyze(image: PeImage): SemanticPlan {
        val index = Index(image)
        val anchors = index.refs.filter { ref -> image.ascii(ref.address)?.let {
            it.contains("special", true) && it.contains("sdam", true)
        } == true }
        if (anchors.isEmpty()) return skipped("未发现 special SDAM 更新功能")
        val environment = try { KblEnvironmentFacts.analyze(image) } catch (_: PatchException) {
            return skipped("special SDAM 的 EFI 协议环境来源尚未证明")
        }
        val boundSlots = protocolSlots(index, environment.bootServicesGlobal)
        if (boundSlots.isEmpty()) return skipped("special SDAM 的 GUID、LocateProtocol 与输出接口槽未关联")
        val matches = anchors.mapNotNull { index.owner(it.pc) }.distinct().flatMap { function ->
            discover(index, function, boundSlots, environment.runtimeServicesGlobal)
        }.distinct()
        if (matches.size != 1) return skipped(if (matches.isEmpty())
            "special SDAM 的读写、输出标志、不更新清理与直接复位消费链未完整证明"
            else "special SDAM 更新功能存在多个完整候选")
        val facts = matches.single()
        val replacement = ByteArray(4).also { it.putWord(0, A64.branch(facts.gate.pc, facts.gate.target)) }
        return SemanticPlan(listOf(PlannedEdit("special SDAM 更新事务采用原生不更新路径", facts.gate.pc, replacement)), listOf(
            "按 special 诊断、SDAM GUID/协议槽、同参数读写、成功输出标志及真实 ResetSystem 消费定位更新事务。",
            "在更新 helper 的全部事务 I/O 之前进入原生不更新成功路径，保留入口已清零的输出标志及原清理；未在 PMIC 写入后吞掉复位。",
            "动态参数 PMIC=${facts.tuple[0]}、bank=${facts.tuple[1]}、offset=${facts.tuple[2]}、length=${facts.tuple[3]}；参数是识别结果，不是机型或版本表。",
            "保留普通 SDAM/VADJ/xvdd 自身写入与策略复位、Rawdump 状态记录、独立只读 special 诊断和手动 reboot/Fastboot；本项不承诺整个 main 无 SDAM 访问或无复位。",
        ))
    }

    private fun skipped(reason: String) = SemanticPlan(emptyList(), listOf("已跳过可选 special SDAM 兼容：$reason；保留该项原生代码。"))

    private class Index(val image: PeImage) {
        val code = image.instructions().toList()
        val refs = A64.references(image)
        private val starts = code.filter { it.second in PAC }.map { it.first }
        private val flows = HashMap<Function, Flow>()
        fun owner(pc: Long): Function? {
            val n = starts.binarySearch(pc).let { if (it >= 0) it else -it - 2 }
            return if (n >= 0 && n + 1 < starts.size) Function(starts[n], starts[n + 1]) else null
        }
        fun body(function: Function) = code.filter { it.first in function.start..<function.end }
        fun flow(function: Function) = flows.getOrPut(function) { Flow(image, function) }
    }

    private fun protocolSlots(index: Index, bootServices: Long): Set<Long> {
        val image = index.image
        val guids = image.bytes.occurrences(SDAM_GUID).mapNotNull(image::offsetToRva).toSet()
        return index.refs.filter { it.address in guids }.mapNotNull { index.owner(it.pc) }.distinct().flatMap { owner ->
            val flow = index.flow(owner)
            index.body(owner).mapNotNull { (pc, _) ->
                val call = flow.call(pc) ?: return@mapNotNull null
                if (call.method != Method(bootServices, 0x140) || call.args[0] !in guids.map { Value.Address(it) } ||
                    call.args[1] != Value.Constant(0)) return@mapNotNull null
                (call.args[2] as? Value.Address)?.rva?.takeIf { image.isWritable(it, 8) }
            }
        }.toSet()
    }

    private fun discover(index: Index, function: Function, slots: Set<Long>, runtimeServices: Long): List<Facts> {
        val source = index.image
        val body = index.body(function)
        val firstIndirect = body.firstOrNull { isBlr(it.second) }?.first ?: return emptyList()
        // A pre-I/O branch must already name a native success/no-update block.
        // An owned B has the same target; analyze its retained update body by
        // replacing only that instruction with NOP in a private snapshot.
        val gates = body.mapNotNull { (pc, word) ->
            val target = branch(pc, word) ?: return@mapNotNull null
            if (pc >= firstIndirect || target !in function.start..<function.end ||
                !isZero(source.word(target) ?: 0, 0)) null else Gate(pc, target)
        }
        return gates.mapNotNull { gate ->
            val bytes = source.bytes.copyOf()
            bytes.putWord(requireNotNull(source.rvaToOffset(gate.pc, 4)), NOP)
            val image = PeImage.parse(bytes)
            val flow = Flow(image, function)
            val calls = body.mapNotNull { flow.call(it.first) }.filter { it.method.slot in slots && it.method.offset in setOf(8L, 16L) }
            val reads = calls.filter { it.method.offset == 8L }
            val writes = calls.filter { it.method.offset == 16L }
            val write = writes.singleOrNull() ?: return@mapNotNull null
            if (reads.isEmpty() || calls.any { it.pc <= gate.pc || it.method.slot != write.method.slot }) return@mapNotNull null
            val tuple = write.args.take(4).map { (it as? Value.Constant)?.number ?: return@mapNotNull null }
            if (tuple[3] != 1L || reads.any { it.args.take(4) != write.args.take(4) }) return@mapNotNull null
            val buffer = write.args[4] as? Value.Stack ?: return@mapNotNull null
            val read = reads.singleOrNull { it.args[4] == buffer } ?: return@mapNotNull null
            if (read.pc >= write.pc) return@mapNotNull null
            val stores = body.mapNotNull { (pc, word) ->
                val access = StateBranchEvaluator.access(word) ?: return@mapNotNull null
                if (access.load || access.width != 1 || access.offset != 0 ||
                    !flow.origins.addressDerivesFromEntry(pc, access.rn, 0)) null
                else pc to flow.value(pc, access.rt)
            }
            val clear = stores.singleOrNull { it.second == Value.Constant(0) && it.first < gate.pc } ?: return@mapNotNull null
            val set = stores.singleOrNull { it.second == Value.Constant(1) } ?: return@mapNotNull null
            if (stores.size != 2 || flow.graph.reaches(function.start, gate.pc, setOf(clear.first)) ||
                flow.graph.reaches(function.start, set.first, setOf(write.pc))) return@mapNotNull null
            if (!writeSuccess(flow, write.pc, set.first)) return@mapNotNull null
            val bufferStores = body.filter { (pc, word) ->
                val a = StateBranchEvaluator.access(word)
                pc in read.pc + 4..<write.pc && a != null && !a.load && a.width == 1 &&
                    flow.stack(pc, a.rn)?.plus(a.offset) == buffer.offset &&
                    hasRead(flow.value(pc, a.rt), read.pc, buffer.offset)
            }
            if (bufferStores.size != 1) return@mapNotNull null
            val noUpdate = Flow(source, Function(gate.target, function.end))
            val noUpdateNodes = noUpdate.graph.reachable(gate.target)
            val returns = noUpdateNodes.filter { isRet(source.word(it) ?: 0) }
            if (returns.isEmpty() || returns.any { noUpdate.value(it, 0) != Value.Constant(0) } ||
                calls.any { it.pc in noUpdateNodes } || set.first in noUpdateNodes) return@mapNotNull null
            val patched = Graph(source, function, gate.pc to gate.target)
            if (calls.any { it.pc in patched.reachable(function.start) }) return@mapNotNull null
            // No alternate direct entry may bypass the pre-transaction gate.
            if (index.code.any { (from, word) -> from !in function.start..<function.end &&
                    (A64.blTarget(from, word) ?: branch(from, word))?.let { it in function.start + 4..<function.end } == true }) return@mapNotNull null
            val consumer = consumer(index, function, runtimeServices) ?: return@mapNotNull null
            Facts(gate, write.method.slot, tuple, reads.map { it.pc }, write.pc, set.first, consumer.first, consumer.second)
        }
    }

    private fun hasRead(value: Value, call: Long, buffer: Long): Boolean = when (value) {
        is Value.Load -> value.pc > call && value.width == 1 && value.base is Value.Stack &&
            value.base.offset + value.offset == buffer
        is Value.Calculation -> value.inputs.any { hasRead(it, call, buffer) }
        else -> false
    }

    private fun writeSuccess(flow: Flow, call: Long, flag: Long): Boolean = flow.graph.nodes.any { pc ->
        val word = flow.image.word(pc) ?: return@any false
        val split = statusEdges(pc, word) ?: return@any false
        flow.value(pc, word and 31) == Value.Result(call) &&
            flow.graph.reaches(call + 4, pc) && flow.graph.reaches(split.first, flag) &&
            !flow.graph.reaches(split.second, flag)
    }

    /** At least one real flag consumer must select the update/reset chain.
     * Other callers may still perform independent ordinary VADJ resets. */
    private fun consumer(index: Index, helper: Function, runtimeServices: Long): Pair<Long, Long>? {
        for ((call, word) in index.code) {
            if (A64.blTarget(call, word) != helper.start) continue
            val owner = index.owner(call) ?: continue
            val flow = index.flow(owner)
            val buffer = flow.value(call, 0) as? Value.Stack ?: continue
            val body = index.body(owner)
            for ((test, raw) in body) {
                val edges = booleanEdges(index.image, owner, test, raw) ?: continue
                val loaded = flow.value(test, edges.register) as? Value.Load ?: continue
                if (loaded.pc <= call || loaded.width != 1 || loaded.base !is Value.Stack ||
                    loaded.base.offset + loaded.offset != buffer.offset || !flow.graph.reaches(call + 4, test)) continue
                for ((reset, branchWord) in body) {
                    val target = A64.blTarget(reset, branchWord) ?: continue
                    if (!flow.graph.reaches(edges.nonzero, reset, setOf(test, edges.zero)) ||
                        flow.graph.reaches(edges.zero, reset, setOf(test))) continue
                    if (resetDecision(index, target, runtimeServices)) return call to reset
                }
            }
        }
        return null
    }

    private fun resetDecision(index: Index, target: Long, runtimeServices: Long): Boolean {
        val owner = index.owner(target)?.takeIf { it.start == target } ?: return false
        val flow = index.flow(owner)
        return index.body(owner).any { (call, word) ->
            val wrapper = A64.blTarget(call, word) ?: return@any false
            val wrapperOwner = index.owner(wrapper)?.takeIf { it.start == wrapper } ?: return@any false
            val resetFlow = index.flow(wrapperOwner)
            val resetCalls = index.body(wrapperOwner).mapNotNull { resetFlow.call(it.first) }
                .filter { it.method == Method(runtimeServices, 0x68) }
            if (resetCalls.isEmpty()) return@any false
            // The wrapper call itself must be conditional on the preceding
            // native decision result, with a genuine no-reset continuation.
            index.body(owner).any { (test, raw) ->
                val edges = booleanEdges(index.image, owner, test, raw) ?: return@any false
                val result = flow.value(test, edges.register)
                result is Value.Result && result.call < test &&
                    flow.graph.reaches(edges.nonzero, call, setOf(edges.zero)) &&
                    !flow.graph.reaches(edges.zero, call, setOf(test))
            }
        }
    }

    private class Flow(val image: PeImage, val function: Function) {
        val graph = Graph(image, function)
        val origins = A64ValueOrigins(image, function.start, function.end)
        private val cache = HashMap<Pair<Long, Int>, Value>()
        private val active = HashSet<Pair<Long, Int>>()
        private val leaves = HashMap<Long, List<Value>?>()
        fun stack(pc: Long, register: Int): Long? {
            val relative = origins.stackAddress(pc, register) ?: return null
            val sp = origins.stackPointerOffset(pc) ?: return null
            return relative.toLong() + sp.toLong()
        }
        fun value(pc: Long, register: Int): Value {
            if (register == 31) return Value.Constant(0)
            val key = pc to register
            cache[key]?.let { return it }
            if (cache.size > 50000 || !active.add(key)) return Value.Unknown
            val answer = origins.globalAddress(pc, register)?.let { Value.Address(it) }
                ?: stack(pc, register)?.let { Value.Stack(it) } ?: run {
                    val definitions = graph.definitions(pc, register, ::writes)
                    val values = definitions.map { if (it == function.start - 4) Value.Input(register) else defined(it, register) }.distinct()
                    values.singleOrNull() ?: Value.Unknown
                }
            active.remove(key)
            cache[key] = answer
            return answer
        }
        fun call(pc: Long): Call? {
            val word = image.word(pc) ?: return null
            if (!isBlr(word)) return null
            val method = value(pc, (word ushr 5) and 31) as? Value.Load ?: return null
            val pointer = method.base as? Value.Load ?: return null
            val global = pointer.base as? Value.Address ?: return null
            if (method.width != 8 || pointer.width != 8) return null
            return Call(pc, Method(global.rva + pointer.offset, method.offset), (0..4).map { value(pc, it) })
        }
        private fun writes(pc: Long, register: Int): Boolean {
            val word = requireNotNull(image.word(pc))
            A64.blTarget(pc, word)?.let { target ->
                val summary = summary(target)
                return if (summary != null) summary[register] != Value.Input(register)
                    else register !in 19..29
            }
            if (isBlr(word)) return register !in 19..29
            return writesGp(word, register)
        }
        private fun defined(pc: Long, register: Int): Value {
            val word = image.word(pc) ?: return Value.Unknown
            A64.blTarget(pc, word)?.let { target ->
                return when (val result = summary(target)?.get(register)) {
                    is Value.Input -> value(pc, result.register)
                    null -> if (register == 0) Value.Result(pc) else Value.Unknown
                    else -> result
                }
            }
            if (isBlr(word)) return if (register == 0) Value.Result(pc) else Value.Unknown
            constant(word)?.let { return Value.Constant(it) }
            copy(word)?.let { return value(pc, it) }
            val access = StateBranchEvaluator.access(word)
            if (access?.load == true && access.rt == register) {
                val base = stack(pc, access.rn)?.let { Value.Stack(it) } ?: value(pc, access.rn)
                return Value.Load(pc, base, access.offset.toLong(), access.width)
            }
            // Integer data operations preserve dependency, not inferred values.
            val rn = (word ushr 5) and 31
            if (word and 0x0e000000 == 0x0a000000)
                return Value.Calculation(listOf(value(pc, rn), value(pc, (word ushr 16) and 31)))
            if (word and 0x1f800000 in setOf(0x13000000, 0x12000000)) {
                val inputs = arrayListOf(value(pc, rn))
                if (word and 0x60000000 == 0x20000000) inputs += value(pc, register) // BFM/BFXIL keeps old destination bits.
                return Value.Calculation(inputs)
            }
            return Value.Unknown
        }
        private fun summary(target: Long): List<Value>? {
            if (leaves.containsKey(target)) return leaves[target]
            leaves[target] = null
            val registers = MutableList<Value>(31) { Value.Input(it) }
            var pc = target
            val end = image.sections.singleOrNull { it.executable && target in it.rva..<it.rva + it.rawSize }?.let { it.rva + it.rawSize } ?: return null
            while (pc < end) {
                val word = image.word(pc)?.takeIf { image.isCode(pc) } ?: return null
                if (isRet(word)) return registers.toList().also { leaves[target] = it }
                if (branch(pc, word) != null || A64.blTarget(pc, word) != null || isBlr(word) || isBr(word)) return null
                val destination = word and 31
                val preserved = copy(word)?.let { if (it == 31) Value.Constant(0) else registers[it] }
                val immediate = constant(word)?.let { Value.Constant(it) }
                for (r in registers.indices) if (writesGp(word, r)) registers[r] = Value.Unknown
                if (destination != 31) {
                    if (immediate != null) registers[destination] = immediate
                    else if (preserved != null) registers[destination] = preserved
                }
                pc += 4
            }
            return null
        }
    }

    private class Graph(private val image: PeImage, private val function: Function, private val edgeOverride: Pair<Long, Long>? = null) {
        val nodes = HashSet<Long>()
        private val predecessors = HashMap<Long, MutableList<Long>>()
        init {
            val pending = arrayListOf(function.start); var n = 0
            while (n < pending.size) {
                val pc = pending[n++]
                if (pc !in function.start..<function.end || !image.isCode(pc) || !nodes.add(pc)) continue
                for (next in successors(pc)) if (next in function.start..<function.end) {
                    predecessors.getOrPut(next) { arrayListOf() }.add(pc)
                    if (next !in nodes) pending += next
                }
            }
        }
        fun reachable(start: Long, stop: Set<Long> = emptySet()): Set<Long> {
            val pending = arrayListOf(start); val seen = HashSet<Long>(); var n = 0
            while (n < pending.size) {
                val pc = pending[n++]
                if (pc in stop || pc !in function.start..<function.end || !nodes.contains(pc) || !seen.add(pc)) continue
                pending += successors(pc)
            }
            return seen
        }
        fun reaches(start: Long, target: Long, stop: Set<Long> = emptySet()) = target in reachable(start, stop)
        fun definitions(pc: Long, register: Int, writes: (Long, Int) -> Boolean): Set<Long> {
            val pending = ArrayList(predecessors[pc].orEmpty()); val seen = HashSet<Long>(); val result = HashSet<Long>(); var n = 0
            if (pc == function.start) return setOf(function.start - 4)
            while (n < pending.size) {
                val at = pending[n++]
                if (!seen.add(at)) continue
                if (writes(at, register)) result += at
                else if (at == function.start) result += function.start - 4
                else pending += predecessors[at].orEmpty()
            }
            return result
        }
        private fun successors(pc: Long): List<Long> {
            edgeOverride?.takeIf { it.first == pc }?.let { return listOf(it.second) }
            val word = image.word(pc) ?: return emptyList()
            if (isRet(word) || isBr(word)) return emptyList()
            val target = branch(pc, word) ?: return listOf(pc + 4)
            return if (A64.bTarget(pc, word) != null) listOf(target) else listOf(target, pc + 4)
        }
    }

    private fun writesGp(word: Int, register: Int): Boolean = when (word) {
        0xd503241f.toInt(), 0xd503245f.toInt(), 0xd503249f.toInt(), 0xd50324df.toInt() -> false // BTI
        0xd503233f.toInt(), 0xd503237f.toInt(), 0xd50323bf.toInt(), 0xd50323ff.toInt() -> register == 30
        else -> StraightLineLiveness.mayWriteRegister(word, register)
    }
    private val PAC = setOf(0xd503233f.toInt(), 0xd503237f.toInt())
    private fun isBlr(word: Int) = word and 0xfffffc1f.toInt() == 0xd63f0000.toInt()
    private fun isBr(word: Int) = word and 0xfffffc1f.toInt() == 0xd61f0000.toInt()
    private fun isRet(word: Int) = word and 0xfffffc1f.toInt() == 0xd65f0000.toInt() || word in setOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt())
    private fun copy(word: Int): Int? = if (word and 0xffe0ffe0.toInt() in setOf(0xaa0003e0.toInt(), 0x2a0003e0)) (word ushr 16) and 31 else null
    private fun constant(word: Int): Long? {
        if (copy(word) == 31) return 0
        if (word and 0x7f800000 == 0x52800000) return ((word ushr 5) and 0xffff).toLong() shl (((word ushr 21) and 3) * 16)
        return null
    }
    private fun isZero(word: Int, register: Int) = word and 31 == register && constant(word) == 0L
    private fun branch(pc: Long, word: Int): Long? {
        A64.bTarget(pc, word)?.let { return it }
        if (word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 == 0x34000000)
            return pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
        if (word and 0x7e000000 == 0x36000000)
            return pc + (signExtend(((word ushr 5) and 0x3fff).toLong(), 14) shl 2)
        return null
    }
    /** first=nonnegative/normal, second=EFI error */
    private fun statusEdges(pc: Long, word: Int): Pair<Long, Long>? {
        if (word and 0x7e000000 != 0x36000000 || word ushr 31 != 1 || (word ushr 19) and 31 != 31) return null
        val target = branch(pc, word) ?: return null
        return if (word and 0x01000000 != 0) pc + 4 to target else target to pc + 4
    }
    private data class BooleanTest(val zero: Long, val nonzero: Long, val register: Int)
    private fun booleanEdges(image: PeImage, owner: Function, pc: Long, word: Int): BooleanTest? {
        val target = branch(pc, word) ?: return null
        if (word and 0x7e000000 == 0x34000000) return if (word and 0x01000000 != 0)
            BooleanTest(pc + 4, target, word and 31) else BooleanTest(target, pc + 4, word and 31)
        if (word and 0xff00001e.toInt() != 0x54000000) return null
        val tested = (pc - 4 downTo owner.start step 4).firstNotNullOfOrNull { at ->
            val raw = image.word(at) ?: return@firstNotNullOfOrNull null
            if (raw and 0xfffffc1f.toInt() == 0x72001c1f &&
                StraightLineLiveness.preserves(image, at + 4, pc, flags = true)) (raw ushr 5) and 31 else null
        } ?: return null
        return if (word and 1 != 0) BooleanTest(pc + 4, target, tested) else BooleanTest(target, pc + 4, tested)
    }
}
