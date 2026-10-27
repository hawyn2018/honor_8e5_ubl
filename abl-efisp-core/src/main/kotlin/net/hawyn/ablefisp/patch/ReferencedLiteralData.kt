package net.hawyn.ablefisp.patch

/**
 * Narrow code/data evidence for merged RX sections. Printable bytes alone are
 * never sufficient: a bounded literal must reach a direct-call argument through
 * a reachable, independently addressed PAC function. Any known control entry or
 * stored pointer into the object keeps the original conservative instruction scan.
 *
 * This only classifies the source of a decoded instruction. It never declares a
 * zero object free, removes a data/relocation reference, or changes the PE mapping.
 */
internal class ReferencedLiteralData(
    private val image: PeImage,
    private val code: List<Pair<Long, Int>>,
) {
    private data class Span(val start: Long, val end: Long)
    private val objects = HashMap<Span, Boolean>()
    private val sourceWords = HashMap<Long, Boolean>()
    private val frames by lazy {
        code.filter { it.second == 0xd503233f.toInt() || it.second == 0xd503237f.toInt() }.map { it.first }
    }
    private val references by lazy { A64.references(image).groupBy { it.address } }
    private val controlEntries by lazy {
        code.mapNotNull { (pc, word) -> directTarget(pc, word) }.toSet() + image.entryRva
    }
    private val storedPointers by lazy {
        buildSet {
            val base = image.imageBase.toULong()
            val maxRva = image.sections.maxOfOrNull { it.rva + maxOf(it.rawSize.toLong(), it.virtualSize) } ?: 0L
            for (offset in 0..image.bytes.size - 8 step 4) {
                val value = image.bytes.u64(offset).toULong()
                if (value < base) continue
                val rva = value - base
                if (rva <= maxRva.toULong() && image.rvaToOffset(rva.toLong()) != null) add(rva.toLong())
            }
        }
    }
    private val flows = HashMap<Pair<Long, Long>, A64ValueOrigins>()
    private data class Consumer(val start: Long, val argument: Int)
    private data class ConsumerSummary(val children: Set<Consumer>, val nulRead: Boolean, val ambiguous: Boolean)
    private val consumers = HashMap<Consumer, ConsumerSummary>()
    private val reachable by lazy { rootedInstructions() }

    fun containsInstructionWord(pc: Long): Boolean = sourceWords.getOrPut(pc) {
        val span = literalContaining(pc) ?: return@getOrPut false
        objects.getOrPut(span) { proveLiteral(span) }
    }

    private fun literalContaining(pc: Long): Span? {
        val section = image.sections.singleOrNull {
            it.executable && !it.writable && it.characteristics and 0x40000000L != 0L &&
                pc >= it.rva && pc + 4 <= it.rva + minOf(it.rawSize.toLong(), it.virtualSize.takeIf { size -> size > 0 } ?: it.rawSize.toLong())
        } ?: return null
        val at = image.rvaToOffset(pc, 4) ?: return null
        val low = section.raw
        val high = section.raw + minOf(section.rawSize.toLong(), section.virtualSize.takeIf { it > 0 } ?: section.rawSize.toLong()).toInt()
        var start = at
        while (start > low && image.bytes[start - 1] != 0.toByte()) {
            if (at - start >= MAX_LITERAL_BYTES || !printable(image.bytes[start - 1])) return null
            start--
        }
        var end = start
        while (end < high && image.bytes[end] != 0.toByte()) {
            if (end - start >= MAX_LITERAL_BYTES || !printable(image.bytes[end])) return null
            end++
        }
        if (end >= high || end - start < MIN_LITERAL_BYTES || at + 4 > end + 1) return null
        return Span(section.rva + start - section.raw, section.rva + end + 1 - section.raw)
    }

    private fun proveLiteral(span: Span): Boolean {
        // A data-looking object with an independent execution/pointer entry is ambiguous.
        if (controlEntries.any { it in span.start..<span.end } || storedPointers.any { it in span.start..<span.end }) return false
        val executed = reachable ?: return false
        if (executed.any { it in span.start..<span.end }) return false
        for (reference in references[span.start].orEmpty()) {
            if (reference.register !in 0..30 || reference.pc in span.start..<span.end) continue
            val index = frames.binarySearch(reference.pc).let { if (it >= 0) it else -it - 2 }
            if (index < 0 || index + 1 >= frames.size) continue
            val start = frames[index]; val end = frames[index + 1]
            if (end - start > MAX_FUNCTION_BYTES || start !in controlEntries && start !in storedPointers) continue
            val definitionEnd = StraightLineLiveness.referenceEnd(image, reference) ?: continue
            val flow = flows.getOrPut(start to end) { A64ValueOrigins(image, start, end) }
            if (flow.globalAddress(definitionEnd, reference.register) != span.start) continue
            // Proven argument values survive MOV aliases, local spills and CFG merges.
            for ((pc, word) in code) {
                val target = A64.blTarget(pc, word) ?: continue
                if (pc < reference.pc || pc !in start..<end || pc in span.start..<span.end ||
                    !image.isCode(target) || image.word(target) == 0) continue
                if ((0..7).any {
                        (flow.globalAddress(pc, it) == span.start ||
                            flow.derivesFrom(pc, it, definitionEnd - 4, reference.register)) &&
                            consumedAsString(Consumer(target, it))
                    }) return true
            }
        }
        return false
    }

    /** Follow actual rooted control flow, including fallthrough into a data-looking word. */
    private fun rootedInstructions(): Set<Long>? {
        val pending = ArrayDeque<Long>()
        pending.add(image.entryRva)
        frames.filter { it in controlEntries || it in storedPointers }.forEach(pending::add)
        val result = HashSet<Long>()
        while (pending.isNotEmpty()) {
            val pc = pending.removeFirst()
            if (pc in result || pc and 3 != 0L || !image.isCode(pc)) continue
            val word = image.word(pc) ?: continue
            if (word == 0) continue // UDF #0 terminates execution, rather than falling through zero padding.
            if (result.size >= MAX_REACHABLE_INSTRUCTIONS) return null
            result += pc
            val call = A64.blTarget(pc, word)
            if (call != null) { pending.add(call); pending.add(pc + 4); continue }
            val branch = A64.bTarget(pc, word)
            if (branch != null) { pending.add(branch); continue }
            if (isReturn(word) || isIndirectBranch(word)) continue
            directTarget(pc, word)?.let(pending::add)
            pending.add(pc + 4)
        }
        return result
    }

    /**
     * Passing an address to an arbitrary function is not a data-use proof. Require
     * a bounded direct-call chain with a byte load used for a NUL test, and reject
     * known execution, writable aliases and forwarding to an opaque indirect sink.
     */
    private fun consumedAsString(first: Consumer): Boolean {
        val pending = ArrayDeque<Consumer>()
        val visited = HashSet<Consumer>()
        pending.add(first)
        var nulRead = false
        while (pending.isNotEmpty()) {
            val consumer = pending.removeFirst()
            if (!visited.add(consumer)) continue
            if (visited.size > MAX_CONSUMERS) return false
            val summary = consumers.getOrPut(consumer) { summarizeConsumer(consumer) }
            if (summary.ambiguous) return false
            nulRead = nulRead || summary.nulRead
            summary.children.forEach(pending::add)
        }
        return nulRead
    }

    private fun summarizeConsumer(consumer: Consumer): ConsumerSummary {
        fun uncertain() = ConsumerSummary(emptySet(), false, true)
        val start = consumer.start
        val first = image.word(start) ?: return uncertain()
        if (first !in listOf(0xd503233f.toInt(), 0xd503237f.toInt(), 0xd503245f.toInt())) return uncertain()
        val section = image.sections.singleOrNull { start >= it.rva && start < it.rva + it.rawSize && it.executable } ?: return uncertain()
        val end = minOf(frames.firstOrNull { it > start } ?: Long.MAX_VALUE,
            start + MAX_FUNCTION_BYTES, section.rva + minOf(section.rawSize.toLong(), section.virtualSize))
        val flow = flows.getOrPut(start to end) { A64ValueOrigins(image, start, end) }
        if (flow.isReachable(start) != true) return uncertain()
        val children = HashSet<Consumer>()
        var nulRead = false
        for ((pc, word) in code) {
            if (pc !in start..<end || flow.isReachable(pc) != true) continue
            fun alias(register: Int) = flow.derivesFromEntry(pc, register, consumer.argument) ||
                flow.addressDerivesFromEntry(pc, register, consumer.argument)
            val indirect = isIndirectBranch(word) || word and 0xfffffc1f.toInt() == 0xd63f0000.toInt()
            if (indirect && (alias((word ushr 5) and 31) || (0..7).any(::alias))) return uncertain()
            val target = A64.blTarget(pc, word) ?: A64.bTarget(pc, word)?.takeIf { it !in start..<end }
            if (target != null) {
                for (argument in 0..7) if (alias(argument)) children += Consumer(target, argument)
            } else if (!isReturn(word) && !indirect && pc + 4 >= end && directTarget(pc, word) == null) return uncertain()
            // Integer scalar/pair stores through the candidate, or escaping its
            // address outside a private local frame, keep it ambiguous.
            if (word and 0x3b000000 in listOf(0x39000000, 0x38000000)) {
                val rn = (word ushr 5) and 31
                val operation = (word ushr 22) and 3
                val vector = word and 0x04000000 != 0
                val store = if (vector) operation and 1 == 0 else operation == 0
                if (store && (alias(rn) || !vector && alias(word and 31) && flow.stackAddress(pc, rn) == null)) return uncertain()
                // LDRB and both LDRSB destinations preserve the equivalence
                // between a zero loaded byte and a zero W/X value.
                if (word ushr 30 == 0 && !vector && operation in 1..3 && alias(rn)) {
                    val rd = word and 31
                    nulRead = nulRead || byteControlsZeroBranch(flow, pc, rd, end)
                }
            } else if (word and 0x3a000000 == 0x28000000 && word and 0x00400000 == 0) {
                val rn = (word ushr 5) and 31
                val vector = word and 0x04000000 != 0
                if (alias(rn) || !vector && (alias(word and 31) || alias((word ushr 10) and 31)) && flow.stackAddress(pc, rn) == null) return uncertain()
            }
        }
        return if (flow.isReachable(start) == true) ConsumerSummary(children, nulRead, false) else uncertain()
    }

    private fun byteControlsZeroBranch(flow: A64ValueOrigins, load: Long, register: Int, end: Long): Boolean {
        for ((pc, word) in code) {
            if (pc !in load + 4 until end || flow.isReachable(pc) != true) continue
            if (word and 0x7e000000 == 0x34000000 && flow.derivesFrom(pc, word and 31, load, register)) return true
            if (word and 0xff000010.toInt() != 0x54000000 || word and 15 !in 0..1) continue
            var test = pc - 4
            while (test > load && pc - test <= 128) {
                val raw = image.word(test) ?: break
                val sources = zeroTestSources(raw)
                if (sources != null) {
                    if (sources.all { flow.derivesFrom(test, it, load, register) } &&
                        flow.hasOnlyLinearEntry(test, pc) &&
                        StraightLineLiveness.preserves(image, test + 4, pc, flags = true)) return true
                    break // An unrelated flags definition hides every earlier zero test.
                }
                if (!StraightLineLiveness.preserves(image, test, test + 4, flags = true)) break
                test -= 4
            }
        }
        return false
    }

    private fun zeroTestSources(word: Int): List<Int>? {
        if (word and 0x7f80001f == 0x7100001f) {
            A64.cmpImmediate(word)?.let { (register, immediate) -> if (immediate == 0) return listOf(register) }
        }
        // TST Rn,Rm,LSL#0 is a zero test when both operands carry the same byte.
        if (word and 0x7fe0fc1f == 0x6a00001f) return listOf((word ushr 5) and 31, (word ushr 16) and 31)
        if (word and 0x7f80001f == 0x7200001f && logicalImmediate(word)?.let { it and 0xffL == 0xffL } == true) {
            return listOf((word ushr 5) and 31)
        }
        return null
    }

    /** ARM64 DecodeBitMasks for an ANDS logical immediate, including reserved encodings. */
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

    private fun isReturn(word: Int): Boolean = word and 0xfffffc1f.toInt() == 0xd65f0000.toInt() ||
        word in listOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt())

    private fun isIndirectBranch(word: Int): Boolean = word and 0xfffffc1f.toInt() == 0xd61f0000.toInt()

    private fun printable(value: Byte): Boolean = (value.toInt() and 0xff) in 0x20..0x7e || value.toInt() in listOf(9, 10, 13)

    private fun directTarget(pc: Long, word: Int): Long? {
        A64.blTarget(pc, word)?.let { return it }
        A64.bTarget(pc, word)?.let { return it }
        if (word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 == 0x34000000) {
            return pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
        }
        if (word and 0x7e000000 == 0x36000000) {
            return pc + (signExtend(((word ushr 5) and 0x3fff).toLong(), 14) shl 2)
        }
        return null
    }

    private companion object {
        const val MIN_LITERAL_BYTES = 8
        const val MAX_LITERAL_BYTES = 4096
        const val MAX_FUNCTION_BYTES = 16384L
        const val MAX_REACHABLE_INSTRUCTIONS = 1_000_000
        const val MAX_CONSUMERS = 128
    }
}
