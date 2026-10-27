package net.hawyn.ablefisp.patch

/** Disable only the ABL root-status cache, without writing its persistent OEMINFO source. */
internal object CheckRootPolicyPatcher {
    private data class Function(val start: Long, val end: Long)
    private data class Read(val pc: Long, val function: Function, val buffer: Int)
    private data class Match(val read: Long, val store: Long, val global: Long)
    private data class Access(val register: Int, val base: Int, val offset: Long, val size: Int, val load: Boolean)
    private data class IndexedStore(val base: Int, val index: Int, val extension: Int, val shift: Int, val size: Int)

    private class Index(val image: PeImage) {
        val code = image.instructions().toList()
        val starts = code.filter { it.second in setOf(0xd503233f.toInt(), 0xd503237f.toInt()) }.map { it.first }
        private val flows = HashMap<Function, A64ValueOrigins>()
        fun function(pc: Long): Function? {
            val start = starts.lastOrNull { it <= pc } ?: return null
            val end = starts.firstOrNull { it > pc } ?: return null
            return Function(start, end)
        }
        fun words(function: Function) = code.asSequence().filter { it.first in function.start..<function.end }
        fun flow(function: Function) = flows.getOrPut(function) { A64ValueOrigins(image, function.start, function.end) }
        fun releaseFlow(function: Function) { flows.remove(function) }
    }

    fun analyze(image: PeImage): SemanticPlan {
        val index = Index(image)
        val reads = ArrayList<Read>()
        for ((pc, word) in index.code) {
            val target = A64.blTarget(pc, word) ?: continue
            if (!image.isCode(target) || A64.immediateBefore(image, pc, 0) != 0x43 ||
                A64.immediateBefore(image, pc, 2) != 12) continue
            val function = index.function(pc) ?: continue
            val state = A64.argumentState(image, maxOf(function.start, pc - 112), pc)
            if (state.registers[0] != A64.Value.Constant(0x43) || state.registers[2] != A64.Value.Constant(12)) continue
            val buffer = index.flow(function).stackAddress(pc, 1) ?: continue
            if (!isReadProtocol(index, target)) continue
            reads += Read(pc, function, buffer)
        }
        if (reads.isEmpty()) {
            val evidence = listOf("check_root_stat", "checkroot").any {
                image.bytes.occurrences(it.toByteArray(Charsets.US_ASCII)).isNotEmpty()
            }
            if (evidence) incomplete()
            return SemanticPlan(emptyList(), listOf("未发现 OEMINFO 根状态缓存功能，没有添加该项修改。"))
        }
        val matches = ArrayList<Match>()
        for (read in reads) {
            val flow = index.flow(read.function)
            val body = index.words(read.function).filter { it.first > read.pc }.toList()
            for ((normalizer, raw) in body) {
                if (raw and -32 != 0x1a9f17e0) continue // CSET Wd,EQ
                val ccmp = body.lastOrNull { (pc, word) -> pc < normalizer &&
                    word and 0xfffffc1f.toInt() == 0x7a411804 &&
                    StraightLineLiveness.preserves(image, pc + 4, normalizer, flags = true)
                } ?: continue // CCMP Wn,#1,#4,NE: a short-circuit OR of equality-to-one.
                val cmp = body.lastOrNull { (pc, word) -> pc < ccmp.first && A64.cmpImmediate(word)?.second == 1 &&
                    StraightLineLiveness.preserves(image, pc + 4, ccmp.first, flags = true)
                } ?: continue
                val first = A64.cmpImmediate(cmp.second)!!.first
                val second = (ccmp.second ushr 5) and 31
                val fields = setOf(
                    recordField(index, read, cmp.first, first),
                    recordField(index, read, ccmp.first, second),
                )
                if (fields != setOf(read.buffer, read.buffer + 4)) continue
                val result = raw and 31
                for ((store, word) in body.filter { it.first > normalizer }) {
                    val pair = A64.stpW(word) ?: continue
                    if (pair.rt2 != 31) continue
                    if (pair.rt == 31) {
                        // Reprove the original normalized value even after the sink
                        // was changed to WZR. Its producing computation stays intact.
                        if ((0..30).none { flow.derivesFromOrZero(store, it, normalizer, result) }) continue
                    } else if (!flow.derivesFromOrZero(store, pair.rt, normalizer, result)) continue
                    val global = flow.globalAddress(store, pair.rn)?.plus(pair.offset) ?: continue
                    if (!image.isWritable(global, 8)) continue
                    if (!hasConsumersAndUniqueWrite(index, global, store)) continue
                    matches += Match(read.pc, store, global)
                }
            }
        }
        val unique = matches.distinctBy { it.store to it.global }
        if (unique.size != 1) throw PatchException(
            "假回锁：OEMINFO 根状态缓存的读取、归一化、唯一写入与消费关系未唯一匹配，本次未生成镜像。",
            if (unique.size > 1) PatchFailureKind.AMBIGUOUS_CORE_FEATURE else PatchFailureKind.INCOMPLETE_CORE_FEATURE,
        )
        val target = unique.single()
        val word = requireNotNull(image.word(target.store))
        val replacement = ByteArray(4).also { it.putWord(0, word or 31) }
        return SemanticPlan(listOf(PlannedEdit("禁用 ABL check_root_stat 缓存判定", target.store, replacement)), listOf(
            "已按 OEMINFO 0x43/12 字节、双字段判定、缓存写入及独立消费者定位根状态功能。",
            "仅将 ABL 内存中的 check_root_stat 固定为零，保留读取协议及相邻字段原有清零；没有改写 OEMINFO 持久记录。",
            "此修改不改变设备的真实持久 BL 锁态，也不证明其他厂商通道或 TEE 接受。",
        ))
    }

    private fun incomplete(): Nothing = throw PatchException(
        "假回锁：发现根状态功能证据，但 OEMINFO 缓存来源未完整匹配，本次未生成镜像。",
        PatchFailureKind.INCOMPLETE_CORE_FEATURE,
    )

    /** Match the actual protocol read ABI and preserve its original backend. */
    private fun isReadProtocol(index: Index, target: Long): Boolean {
        val function = index.function(target)?.takeIf { it.start == target } ?: return false
        val flow = index.flow(function)
        val body = index.words(function).toList()
        return body.any { (call, word) ->
            if (word and 0xfffffc1f.toInt() !in setOf(0xd61f0000.toInt(), 0xd63f0000.toInt())) return@any false
            val pointer = (word ushr 5) and 31
            val method = body.lastOrNull { (pc, raw) -> pc < call && raw and 0xffc00000.toInt() == 0xf9400000.toInt() &&
                raw and 31 == pointer && ((raw ushr 10) and 0xfff) * 8 == 0x10 &&
                methodPointerSurvives(index, function, pc, call, pointer)
            } ?: return@any false
            method.first < call && (0..2).all { flow.derivesFromEntry(call, it, it) }
        }
    }

    private fun methodPointerSurvives(index: Index, function: Function, definition: Long, call: Long, register: Int): Boolean {
        val interval = definition + 4..<call
        for ((pc, word) in index.words(function)) {
            val target = directTarget(pc, word)
            if (target != null && target in interval && pc !in definition..<call) return false
            if (pc !in interval) continue
            if (word in setOf(0xd503233f.toInt(), 0xd503237f.toInt(), 0xd50323bf.toInt(), 0xd50323ff.toInt())) {
                if (register == 30) return false
                continue
            }
            if (A64.blTarget(pc, word) != null || word and 0xfffffc1f.toInt() in
                setOf(0xd61f0000.toInt(), 0xd63f0000.toInt(), 0xd65f0000.toInt())) return false
            if (StraightLineLiveness.mayWriteRegister(word, register)) return false
        }
        return true
    }

    private fun directTarget(pc: Long, word: Int): Long? {
        A64.bTarget(pc, word)?.let { return it }
        if (word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 == 0x34000000)
            return pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
        if (word and 0x7e000000 == 0x36000000)
            return pc + (signExtend(((word ushr 5) and 0x3fff).toLong(), 14) shl 2)
        return null
    }

    /** Trace only exact copy operations back to a 32-bit field of the read buffer. */
    private fun recordField(index: Index, read: Read, before: Long, register: Int): Int? {
        var wanted = register
        val image = index.image
        var pc = before - 4
        while (pc > read.pc) {
            val word = image.word(pc) ?: return null
            if (isTransfer(word)) return null
            val memory = memoryAccess(word).firstOrNull { it.load && it.register == wanted }
            if (memory != null) {
                if (memory.size != 4) return null
                val base = index.flow(read.function).stackAddress(pc, memory.base) ?: return null
                // The source and load share the same SP: no hidden frame change.
                if (!spPreserved(image, read.pc + 4, pc)) return null
                return (base + memory.offset).toInt()
            }
            val source = copySource(word, wanted)
            if (source != null) wanted = source
            else if (StraightLineLiveness.mayWriteRegister(word, wanted)) return null
            pc -= 4
        }
        return null
    }

    private fun hasConsumersAndUniqueWrite(index: Index, global: Long, expectedStore: Long): Boolean {
        val image = index.image
        val page = global and -4096L
        val rootFunctions = index.code.filter { (pc, word) ->
            A64.adrp(pc, word)?.second == page || A64.adr(pc, word)?.second?.let { it and -4096L == page } == true
        }.mapNotNull { index.function(it.first) }.distinct()
        // A constant index can resolve into the cache from any materialized base,
        // including a different page. Unknown cross-object OOB access is outside
        // this local cache-source proof, rather than silently assumed impossible.
        val constantIndexedFunctions = index.code.mapNotNull { (pc, word) ->
            val access = indexedStore(word) ?: return@mapNotNull null
            val function = index.function(pc) ?: return@mapNotNull null
            function.takeIf { constantBefore(index, function, pc, access.index) != null }
        }.distinct()
        val functions = (rootFunctions + constantIndexedFunctions).distinct()
        val rootBases = hashSetOf(global, page)
        for (function in rootFunctions) for ((pc, word) in index.words(function)) for (access in memoryAccess(word)) {
            val base = addressBefore(index, function, pc, access.base) ?: continue
            if (base + access.offset == global) rootBases += base
        }
        val writers = HashSet<Long>()
        val getters = HashSet<Long>()
        val byteConsumers = HashSet<Long>()
        for (function in functions) {
            val flow = index.flow(function)
            val body = index.words(function).toList()
            for ((pc, word) in body) {
                indexedStore(word)?.let { access ->
                    addressBefore(index, function, pc, access.base)?.let { base ->
                        val value = constantBefore(index, function, pc, access.index)
                        if (value != null) {
                            val extended = when (access.extension) {
                                2 -> value and 0xffff_ffffL // UXTW
                                6 -> value.toInt().toLong() // SXTW
                                else -> value // LSL/SXTX use all 64 bits.
                            }
                            val address = base + (extended shl access.shift)
                            if (overlaps(address, access.size, global)) writers += pc
                        } else if ((base in rootBases || base and -4096L == page) &&
                            unknownIndexMayOverlap(base, access, global, rangeBefore(index, function, pc, access.index))) {
                            // A known global base is not a proof that its dynamic
                            // indexed write misses the root cache. Refuse until a
                            // value/range actually proves this write is disjoint.
                            return false
                        }
                    }
                }
                for (access in memoryAccess(word)) {
                val base = addressBefore(index, function, pc, access.base) ?: continue
                val address = base + access.offset
                if (!access.load) {
                    if (address < global + 4 && global < address + access.size) writers += pc
                    continue
                }
                if (address != global) continue
                if (access.size == 4 && rootFeedsGetter(index, function, pc, access.register)) getters += function.start
                // Only the low byte reaches this independent channel. LDRB,
                // LDRH, LDR W and LDR X therefore have the same observed byte.
                if (rootFeedsByte(index, function, pc, access.register)) byteConsumers += function.start
                }
            }
            if (function !in rootFunctions) index.releaseFlow(function)
        }
        return writers == setOf(expectedStore) && getters.any { getter -> byteConsumers.any { it != getter } }
    }

    /** Resolve a local full-width pointer spill without treating a W truncation as a pointer copy. */
    private fun addressBefore(index: Index, function: Function, before: Long, register: Int, depth: Int = 0): Long? {
        if (register !in 0..30 || depth > 16) return null
        val flow = index.flow(function)
        flow.globalAddress(before, register)?.let { return it }
        var pc = before - 4
        while (pc >= function.start) {
            val word = index.image.word(pc) ?: return null
            if (isTransfer(word)) return null
            if (!StraightLineLiveness.mayWriteRegister(word, register)) { pc -= 4; continue }
            A64.adrp(pc, word)?.takeIf { it.first == register }?.let { return it.second }
            A64.adr(pc, word)?.takeIf { it.first == register }?.let { return it.second }
            val add = A64.add(word)?.takeIf { it.rd == register && it.rn != 31 }
            if (add != null) return addressBefore(index, function, pc, add.rn, depth + 1)?.plus(add.immediate)
            if (word and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt() && word and 31 == register) {
                return addressBefore(index, function, pc, (word ushr 16) and 31, depth + 1)
            }
            val load = memoryAccess(word).firstOrNull { it.load && it.register == register && it.size == 8 } ?: return null
            val stack = flow.stackAddress(pc, load.base)?.toLong()?.plus(load.offset) ?: return null
            var at = pc - 4
            while (at >= function.start) {
                val previous = index.image.word(at) ?: return null
                if (isTransfer(previous) || !spPreserved(index.image, at, pc)) return null
                for (store in memoryAccess(previous).filter { !it.load }) {
                    val storeStack = flow.stackAddress(at, store.base)?.toLong()?.plus(store.offset) ?: continue
                    if (storeStack < stack + 8 && stack < storeStack + store.size) {
                        if (storeStack != stack || store.size != 8) return null
                        return addressBefore(index, function, at, store.register, depth + 1)
                    }
                }
                at -= 4
            }
            return null
        }
        return null
    }

    /** Scalar and SIMD register-offset stores have no base writeback. */
    private fun indexedStore(word: Int): IndexedStore? {
        if (word and 0x3b200c00 != 0x38200800) return null
        val vector = word and 0x04000000 != 0
        val operation = (word ushr 22) and 3
        if ((!vector && operation != 0) || (vector && operation and 1 != 0)) return null
        val size = if (vector && operation and 2 != 0) 16 else 1 shl (word ushr 30)
        val extension = (word ushr 13) and 7
        if (extension !in setOf(2, 3, 6, 7)) return null // Other options are unallocated for this form.
        val shift = if (word and 0x1000 != 0) size.countTrailingZeroBits() else 0
        return IndexedStore((word ushr 5) and 31, (word ushr 16) and 31, extension, shift, size)
    }

    private fun unknownIndexMayOverlap(base: Long, access: IndexedStore, global: Long, known: Pair<Long, Long>?): Boolean {
        val range = when (access.extension) {
            2 -> known?.takeIf { it.first >= 0 && it.second <= 0xffff_ffffL } ?: (0L to 0xffff_ffffL)
            6 -> when {
                known == null -> Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong()
                known.first >= 0 && known.second <= Int.MAX_VALUE -> known
                known.first >= 0x8000_0000L && known.second <= 0xffff_ffffL ->
                    (known.first - 0x1_0000_0000L) to (known.second - 0x1_0000_0000L)
                else -> Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong()
            }
            else -> known ?: return true // An unrestricted X index may reach the cache modulo 64 bits.
        }
        if (range.first < Long.MIN_VALUE shr access.shift || range.second > Long.MAX_VALUE shr access.shift) return true
        val minimum = range.first shl access.shift
        val maximum = range.second shl access.shift
        if (maximum > Long.MAX_VALUE - base) return true
        val low = base + minimum
        val high = base + maximum
        return low < global + 4 && global < high + access.size
    }

    private fun overlaps(address: Long, size: Int, global: Long): Boolean =
        address < global + 4 && global < address + size

    /** Exact small scalar constant expressions; unknown definitions stay unknown. */
    private fun constantBefore(index: Index, function: Function, before: Long, register: Int, depth: Int = 0): Long? {
        if (register == 31) return 0
        if (register !in 0..30 || depth > 24) return null
        var pc = before - 4
        while (pc >= function.start) {
            val word = index.image.word(pc) ?: return null
            if (isTransfer(word)) return null
            if (!StraightLineLiveness.mayWriteRegister(word, register)) { pc -= 4; continue }
            if (word and 31 != register) return null
            val wide = word and 0x1f800000 == 0x12800000
            val bits64 = word ushr 31 == 1
            val mask = if (bits64) -1L else 0xffff_ffffL
            if (wide) {
                val operation = (word ushr 29) and 3
                val shift = ((word ushr 21) and 3) * 16
                if (!bits64 && shift >= 32) return null
                val immediate = ((word ushr 5) and 0xffff).toLong() shl shift
                return when (operation) {
                    0 -> immediate.inv() and mask // MOVN
                    2 -> immediate and mask // MOVZ
                    3 -> constantBefore(index, function, pc, register, depth + 1)?.let {
                        ((it and (0xffffL shl shift).inv()) or immediate) and mask
                    }
                    else -> null
                }
            }
            if (A64.movXZero(word) == register || A64.movWImmediate(word)?.let { it.first == register && it.second == 0 } == true) return 0
            val copy = copySource(word, register)
            if (copy != null) return constantBefore(index, function, pc, copy, depth + 1)?.let { it and mask }
            if (word and 0x1f000000 == 0x11000000 && word and 0x20000000 == 0) {
                val source = (word ushr 5) and 31
                if (source == 31) return null // SP is not an integer zero register here.
                val magnitude = ((word ushr 10) and 0xfff).toLong() shl if (word and 0x00400000 != 0) 12 else 0
                val delta = if (word and 0x40000000 != 0) -magnitude else magnitude
                return constantBefore(index, function, pc, source, depth + 1)?.let { (it + delta) and mask }
            }
            return null
        }
        return null
    }

    /** Unsigned bitfields and W writes prove useful bounds without guessing an index value. */
    private fun rangeBefore(index: Index, function: Function, before: Long, register: Int, depth: Int = 0): Pair<Long, Long>? {
        if (register == 31) return 0L to 0L
        if (depth > 24) return null
        constantBefore(index, function, before, register)?.let { return it to it }
        var pc = before - 4
        while (pc >= function.start) {
            val word = index.image.word(pc) ?: return null
            if (isTransfer(word)) return null
            if (!StraightLineLiveness.mayWriteRegister(word, register)) { pc -= 4; continue }
            if (word and 31 != register) return null
            val bits = if (word ushr 31 == 1) 64 else 32
            if (word and 0x7f800000 == 0x53000000 && ((word ushr 22) and 1) == if (bits == 64) 1 else 0) {
                val rotate = (word ushr 16) and 63
                val top = (word ushr 10) and 63
                if (rotate >= bits || top >= bits) return null
                val width = if (rotate <= top) top - rotate + 1 else top + 1
                val shift = if (rotate <= top) 0 else bits - rotate
                if (width + shift >= 64) return null
                return 0L to (((1L shl width) - 1) shl shift)
            }
            val copied = copySource(word, register)
            if (copied != null) {
                if (bits == 32) return 0L to 0xffff_ffffL
                return rangeBefore(index, function, pc, copied, depth + 1)
            }
            memoryAccess(word).firstOrNull { it.load && it.register == register && it.size <= 4 }?.let {
                return 0L to ((1L shl (it.size * 8)) - 1)
            }
            if (bits == 32 && (word and 0x1f000000 in setOf(0x11000000, 0x0b000000, 0x0a000000) ||
                        word and 0x1f800000 in setOf(0x12000000, 0x13000000, 0x13800000) ||
                        word and 0x1fe00000 == 0x1a800000)) return 0L to 0xffff_ffffL
            return null
        }
        return null
    }

    private fun rootFeedsGetter(index: Index, function: Function, load: Long, source: Int): Boolean {
        val image = index.image
        val body = index.words(function).toList()
        val flow = index.flow(function)
        for ((compare, word) in body.filter { it.first > load }) {
            val tested = when {
                A64.cmpImmediate(word)?.second == 1 -> A64.cmpImmediate(word)!!.first
                word and 0xfffffc1f.toInt() == 0x7a411804 -> (word ushr 5) and 31
                else -> continue
            }
            if (!copyFrom(index, load, compare, tested, source)) continue
            for ((select, raw) in body.filter { it.first > compare }) {
                if (raw and 0x1fe00000 != 0x1a800000 ||
                    !StraightLineLiveness.preserves(image, compare + 4, select, flags = true)) continue
                val result = raw and 31
                if (body.any { (ret, retWord) -> ret > select && retWord and 0xfffffc1f.toInt() == 0xd65f0000.toInt() &&
                        flow.derivesFromOrZero(ret, 0, select, result) }) return true
            }
        }
        return false
    }

    private fun rootFeedsByte(index: Index, function: Function, load: Long, source: Int): Boolean {
        val flow = index.flow(function)
        for ((pc, word) in index.words(function).filter { it.first > load }) {
            for (access in memoryAccess(word)) {
                if (access.load || access.size != 1 || flow.stackAddress(pc, access.base) == null) continue
                if (copyFrom(index, load, pc, access.register, source)) return true
            }
        }
        return false
    }

    private fun copyFrom(index: Index, definition: Long, before: Long, register: Int, original: Int): Boolean {
        var wanted = register
        var pc = before - 4
        while (pc > definition) {
            val word = index.image.word(pc) ?: return false
            if (isTransfer(word)) return false
            val copy = copySource(word, wanted)
            if (copy != null) wanted = copy
            else if (StraightLineLiveness.mayWriteRegister(word, wanted)) return false
            pc -= 4
        }
        return wanted == original
    }

    private fun copySource(word: Int, destination: Int): Int? {
        if (word and 31 != destination || destination == 31) return null
        if (word and 0x7fe0ffe0 == 0x2a0003e0) return ((word ushr 16) and 31).takeIf { it != 31 }
        if (word and 0x7ffffc00 == 0x11000000) return ((word ushr 5) and 31).takeIf { it != 31 }
        return null
    }

    private fun spPreserved(image: PeImage, from: Long, until: Long): Boolean {
        for (pc in from until until step 4) {
            val w = image.word(pc) ?: return false
            if (w and 0x1f000000 == 0x11000000 && w and 31 == 31 && w and 0x20000000 == 0) return false
            if (w and 0x3a000000 == 0x28000000 && (w ushr 5) and 31 == 31 && (w ushr 23) and 3 in setOf(1, 3)) return false
            if (w and 0x3b200000 == 0x38000000 && (w ushr 5) and 31 == 31 && (w ushr 10) and 3 in setOf(1, 3)) return false
        }
        return true
    }

    private fun isTransfer(word: Int): Boolean = A64.blTarget(0, word) != null || A64.bTarget(0, word) != null ||
        word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 in setOf(0x34000000, 0x36000000) ||
        word and 0xfffffc1f.toInt() in setOf(0xd61f0000.toInt(), 0xd63f0000.toInt(), 0xd65f0000.toInt())

    private fun memoryAccess(word: Int): List<Access> {
        if (word and 0x3b000000 == 0x39000000 && word and 0x04000000 == 0) {
            val size = 1 shl (word ushr 30)
            val op = (word ushr 22) and 3
            if (op !in 0..1) return emptyList()
            return listOf(Access(word and 31, (word ushr 5) and 31, ((word ushr 10) and 0xfff).toLong() * size, size, op == 1))
        }
        if (word and 0x3b200c00 == 0x38000000 && word and 0x04000000 == 0) {
            val op = (word ushr 22) and 3
            if (op !in 0..1) return emptyList()
            return listOf(Access(word and 31, (word ushr 5) and 31, signExtend(((word ushr 12) and 0x1ff).toLong(), 9), 1 shl (word ushr 30), op == 1))
        }
        if (word and 0x3b000000 == 0x29000000 && word and 0x04000000 == 0) {
            val size = if (word ushr 31 == 1) 8 else 4
            val offset = signExtend(((word ushr 15) and 127).toLong(), 7) * size
            val base = (word ushr 5) and 31
            return listOf(Access(word and 31, base, offset, size, word and 0x00400000 != 0),
                Access((word ushr 10) and 31, base, offset + size, size, word and 0x00400000 != 0))
        }
        return emptyList()
    }
}
