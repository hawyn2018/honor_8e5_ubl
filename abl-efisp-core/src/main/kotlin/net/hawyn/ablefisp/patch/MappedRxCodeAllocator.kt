package net.hawyn.ablefisp.patch

import java.math.BigInteger

/**
 * Reserve zero tail padding in the caller's existing RX mapping. This allocator
 * never grows a file/section, changes PE headers or treats an interior zero hole
 * as free space. All returned ranges are planned together before any caller writes.
 *
 * The reference audit is deliberately conservative: directory/relocation storage,
 * statically addressed code/data, and unresolved ADRP pages exclude a zero tail.
 * It is a static audit, not a claim about opaque firmware memory protections.
 */
internal object MappedRxCodeAllocator {
    data class Request(val anchorRva: Long, val size: Int)
    data class Allocation(val rva: Long, val size: Int)

    private data class Tail(val section: PeSection, val paddingStart: Long, val start: Long, val end: Long)
    private data class Conflict(val address: Long, val reason: String)
    private data class Protected(val start: Long, val end: Long, val reason: String)
    private const val GUARD = 16L
    private const val ALIGNMENT = 16L

    fun allocate(image: PeImage, requests: List<Request>): List<Allocation> {
        if (requests.isEmpty()) return emptyList()
        val pe = image.bytes.u32(0x3c).asBoundedInt("PE 头位置")
        val mappedImageSize = image.bytes.u32(pe + 24 + 56)
        val owners = requests.map { request ->
            if (request.size !in 1..1024 * 1024 || request.anchorRva and 3L != 0L) {
                throw PatchException("原映射代码分配：调用点或代码长度无效。")
            }
            val owner = image.sections.singleOrNull { section ->
                section.executable && !section.writable && section.characteristics and 0x40000000L != 0L &&
                    section.characteristics and 0x02000000L == 0L && section.rva + mappedSize(section) <= mappedImageSize &&
                    request.anchorRva >= section.rva && request.anchorRva + 4 <= section.rva + mappedSize(section)
            } ?: throw PatchException("原映射代码分配：调用点不在保留的只读可执行节内。")
            if (image.word(request.anchorRva) == 0) throw PatchException("原映射代码分配：调用点位于零填充而非已有指令。")
            owner
        }
        val tails = owners.distinct().map { section ->
            val mapped = mappedSize(section)
            var endOfContent = section.raw + mapped.toInt()
            while (endOfContent > section.raw && image.bytes[endOfContent - 1] == 0.toByte()) endOfContent--
            val start = align16(section.rva + endOfContent - section.raw + GUARD)
            val end = section.rva + mapped
            if (start >= end) throw PatchException("原映射代码分配：调用点所在节没有带保护间隔的零填充尾部。")
            Tail(section, section.rva + endOfContent - section.raw, start, end)
        }
        val next = tails.associate { it.section to it.start }.toMutableMap()
        val reservedEnds = HashMap<PeSection, Long>()
        val result = requests.mapIndexed { index, request ->
            val section = owners[index]
            val tail = tails.single { it.section == section }
            val start = requireNotNull(next[section])
            val end = start + request.size
            if (end > tail.end || image.rvaToOffset(start, request.size) == null) {
                throw PatchException("原映射代码分配：调用点所在节的可用零尾不足，未追加新代码节。")
            }
            A64.branch(request.anchorRva, start) // Both sites must be in direct A64 branch range.
            // The guard separates original initialized content from the new
            // code. Individual self-contained stubs need alignment and disjoint
            // ranges, not a second artificial guard after every reservation.
            next[section] = align16(end)
            reservedEnds[section] = end
            Allocation(start, request.size)
        }
        val audit = ReferenceAudit(image, tails)
        audit.run()
        for (tail in tails) {
            // Audit the entire planned prefix, including alignment gaps.
            // A later referenced area remains untouched; never step over it and
            // guess that the remainder of a zero object is disposable padding.
            val conflict = audit.firstConflict(tail.start, requireNotNull(reservedEnds[tail.section])) ?: continue
            throw PatchException(
                "原映射代码分配：计划零尾仍有${conflict.reason}引用（RVA 0x${conflict.address.toString(16)}），已停止生成。",
            )
        }
        return result
    }

    private fun mappedSize(section: PeSection): Long =
        minOf(section.rawSize.toLong(), section.virtualSize.takeIf { it > 0 } ?: section.rawSize.toLong())

    private fun align16(value: Long): Long = (value + ALIGNMENT - 1) and -ALIGNMENT

    private class ReferenceAudit(val image: PeImage, val tails: List<Tail>) {
        private val protected = ArrayList<Protected>()
        private val code by lazy { image.instructions().toList() }
        private val literalData by lazy { ReferencedLiteralData(image, code) }
        private var instructionOrigin: Long? = null
        private val branches by lazy { code.mapNotNull { (pc, word) -> directTarget(pc, word)?.let { pc to it } } }
        private val flows = HashMap<Pair<Long, Long>, A64ValueOrigins>()
        private val initializedObjectReferences by lazy {
            A64.references(image).filter { reference ->
                StraightLineLiveness.referenceEnd(image, reference) != null &&
                    image.rvaToOffset(reference.address)?.let { image.bytes[it] != 0.toByte() } == true
            }
        }

        fun firstConflict(start: Long, end: Long): Conflict? =
            protected.firstOrNull { it.start < end && start < it.end }?.let { Conflict(maxOf(start, it.start), it.reason) }

        private fun protect(address: Long, size: Long, reason: String) {
            if (address < 0 || size <= 0 || address > 0xffff_ffffL) return
            val end = minOf(0x1_0000_0000L, address + size)
            if (tails.any { address < it.end && it.start < end }) {
                if (instructionOrigin?.let(literalData::containsInstructionWord) == true) return
                protected += Protected(address, end, reason)
            }
        }

        private fun protectAddress(address: Long, reason: String) {
            protect(address, 1, reason)
            // A pointer into the zero run may designate a zero-initialized object
            // of unknown size, including bytes in the alignment/guard prefix.
            // Its remainder is never treated as another free zero hole.
            for (tail in tails) if (address >= tail.paddingStart && address < tail.end) {
                protect(address, tail.end - address, "$reason 零对象")
            }
        }

        private fun hasFollowingInitializedObject(address: Long, tail: Tail, origin: Long, pc: Long): Boolean =
            address < tail.paddingStart && initializedObjectReferences.any { reference ->
                reference.address > address && reference.address < tail.paddingStart && reference.pc !in origin - 8..pc
            }

        private fun protectEscape(address: Long, origin: Long, pc: Long, reason: String) {
            for (tail in tails) if ((address and -4096L) < tail.end &&
                tail.paddingStart < (address and -4096L) + 4096 &&
                !hasFollowingInitializedObject(address, tail, origin, pc)) {
                protect(tail.start, tail.end - tail.start, "$reason@0x${pc.toString(16)}")
            }
        }

        fun run() {
            protect(image.entryRva, 4, "入口")
            directoriesAndRelocations()
            instructions()
            instructionOrigin = null
            // Packed firmware tables can place 64-bit VAs on a 4-byte boundary.
            // Restricting this to relocation entries would miss such stored pointers.
            for (offset in 0..image.bytes.size - 8 step 4) {
                pointerRva(image.bytes.u64(offset))?.let { protectAddress(it, "绝对地址") }
            }
        }

        private fun pointerRva(value: Long): Long? {
            val pointer = value.toULong(); val base = image.imageBase.toULong()
            if (pointer < base) return null
            val rva = pointer - base
            return rva.takeIf { it <= 0xffff_ffffUL }?.toLong()
        }

        private fun directoriesAndRelocations() {
            val source = image.bytes
            val pe = source.u32(0x3c).asBoundedInt("PE 头位置")
            val optional = pe + 24
            val capacity = (source.u16(pe + 20) - 112) / 8
            val count = source.u32(optional + 108)
            if (count > 16 || count > capacity) throw PatchException("原映射代码分配：PE 目录表超出可审计范围。")
            var relocAddress = 0L; var relocSize = 0L
            repeat(count.toInt()) { index ->
                val at = optional + 112 + index * 8
                val address = source.u32(at); val size = source.u32(at + 4)
                if (address == 0L || size == 0L) return@repeat
                if (address + size > 0x1_0000_0000L) throw PatchException("原映射代码分配：PE 目录地址溢出。")
                if (index == 4) {
                    // The certificate directory uses file offsets, not RVAs.
                    if (address + size > source.size) throw PatchException("原映射代码分配：PE 证书范围无效。")
                    for (tail in tails) {
                        val rawStart = tail.section.raw + tail.start - tail.section.rva
                        val rawEnd = tail.section.raw + tail.end - tail.section.rva
                        if (address < rawEnd && rawStart < address + size) {
                            val start = tail.section.rva + maxOf(address, rawStart) - tail.section.raw
                            val end = tail.section.rva + minOf(address + size, rawEnd) - tail.section.raw
                            protect(start, end - start, "证书目录")
                        }
                    }
                } else protect(address, size, "PE 目录")
                if (index == 5) { relocAddress = address; relocSize = size }
            }
            if (relocSize == 0L) return
            val length = relocSize.asBoundedInt("重定位目录长度")
            val begin = image.rvaToOffset(relocAddress, length)
                ?: throw PatchException("原映射代码分配：重定位目录未完整映射。")
            var at = begin
            val end = begin + length
            while (at < end) {
                if (at + 8 > end) throw PatchException("原映射代码分配：重定位块头被截断。")
                val page = source.u32(at); val block = source.u32(at + 4)
                if (page == 0L && block == 0L) {
                    if ((at until end).any { source[it] != 0.toByte() }) throw PatchException("原映射代码分配：重定位尾部无效。")
                    break
                }
                if (block < 8 || block and 1L != 0L || block > end - at) throw PatchException("原映射代码分配：重定位块范围无效。")
                for (entry in at + 8 until at + block.toInt() step 2) {
                    val value = source.u16(entry)
                    val type = value ushr 12
                    if (type == 0) continue
                    if (type != 10) throw PatchException("原映射代码分配：存在尚未审计的 ARM64 重定位类型 $type。")
                    val storage = page + (value and 0xfff)
                    protect(storage, 8, "重定位存储")
                    val offset = image.rvaToOffset(storage, 8)
                        ?: throw PatchException("原映射代码分配：重定位存储点未映射。")
                    pointerRva(source.u64(offset))?.let { protectAddress(it, "重定位目标") }
                }
                at += block.toInt()
            }
        }

        private fun instructions() {
            for ((pc, word) in image.instructions()) {
                instructionOrigin = pc
                directTarget(pc, word)?.let { protect(it, 4, "代码分支") }
                if (word and 0x3b000000 == 0x18000000) {
                    val target = pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
                    val vector = word and 0x04000000 != 0
                    val op = word ushr 30
                    val size = if (vector) (4L shl op.coerceAtMost(2)) else if (op == 1) 8L else 4L
                    protect(target, size, "字面量读取")
                }
                A64.adr(pc, word)?.takeIf { it.first != 31 }?.let {
                    protectAddress(it.second, "ADR 地址")
                    exactAccesses(pc + 4, it.first, it.second)
                }
                val page = A64.adrp(pc, word)?.takeIf { it.first != 31 } ?: continue
                // An unconsumed page base can address any byte on that page.
                if (!auditPageUse(pc, page.first, page.second)) protect(page.second, 4096, "未解析 ADRP 页")
            }
        }

        /** Stop only when the page register is consumed into an exact address or independently overwritten. */
        private fun auditPageUse(pc: Long, register: Int, page: Long): Boolean {
            for (n in 1..16) {
                val at = pc + n * 4
                if (!image.isCode(at)) return false
                val word = image.word(at) ?: return false
                val added = A64.add(word)
                if (added?.rn == register) {
                    val address = page + added.immediate
                    protectAddress(address, "ADRP/ADD 地址")
                    exactAccesses(at + 4, added.rd, address)
                    if (added.rd == register) return true
                    continue
                }
                if (word and 0x3b000000 == 0x39000000 && (word ushr 5) and 31 == register) {
                    val vector = word and 0x04000000 != 0
                    val op = (word ushr 22) and 3
                    val width = if (vector && op >= 2) 16L else 1L shl (word ushr 30)
                    protect(page + ((word ushr 10) and 0xfff) * width, width, "ADRP 内存访问")
                    if (!vector && op != 0 && word and 31 == register) return true
                    continue
                }
                // Known writes which do not consume the previous page value.
                if (A64.adr(at, word)?.first == register || A64.adrp(at, word)?.first == register ||
                    A64.movWImmediate(word)?.first == register || A64.movXZero(word) == register ||
                    added?.let { it.rd == register && it.rn != register } == true) return true
                if (word and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt()) {
                    val destination = word and 31; val source = (word ushr 16) and 31
                    if (source == register) return false // Page alias has not been bounded.
                    if (destination == register) return true
                }
                if (directTarget(at, word) != null || word and 0xfffffc1f.toInt() in listOf(
                        0xd61f0000.toInt(), 0xd63f0000.toInt(), 0xd65f0000.toInt(),
                    )) return false
                if (!StraightLineLiveness.preserves(image, at, at + 4, setOf(register))) return false
                // An arithmetic/register-index use may escape the page. Refuse
                // that still-live base instead of guessing the dynamic offset.
                if (word and 0x1f000000 in listOf(0x0a000000, 0x0b000000) &&
                    ((word ushr 5) and 31 == register || (word ushr 16) and 31 == register)) return false
                if (word and 0x3b000000 == 0x38000000 && (word ushr 5) and 31 == register) return false
            }
            return false
        }

        /** Include fixed-offset accesses through a materialized address and its local aliases. */
        private fun exactAccesses(start: Long, register: Int, address: Long) {
            val values = hashMapOf(register to address)
            val constants = HashMap<Int, Long>()
            for (n in 0 until 24) {
                val pc = start + n * 4
                if (!image.isCode(pc) || values.isEmpty()) return
                val word = image.word(pc) ?: return
                if (A64.blTarget(pc, word) != null || word and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) {
                    for ((argument, value) in values) if (argument in 0..7) protectEscape(value, start, pc, "末对象指针传参逃逸")
                    return
                }
                if (directTarget(pc, word) != null || word and 0xfffffc1f.toInt() in listOf(
                        0xd61f0000.toInt(), 0xd63f0000.toInt(), 0xd65f0000.toInt(),
                    )) {
                    // Outgoing argument aliases may escape through a tail call;
                    // a loop's non-argument table-base register is not an ABI
                    // pointer transfer merely because a direct branch repeats.
                    for ((argument, value) in values) if (argument in 0..7) {
                        protectEscape(value, start, pc, "末对象地址越过局部控制流")
                    }
                    return
                }
                val add = A64.add(word)
                if (add != null) {
                    val source = values[add.rn]
                    if (source == null) values.remove(add.rd) else {
                        values[add.rd] = source + add.immediate
                        protectAddress(source + add.immediate, "地址加法")
                    }
                    val constant = constants[add.rn]
                    if (constant == null) constants.remove(add.rd) else constants[add.rd] = constant + add.immediate
                    continue
                }
                val wideMove = moveWideConstant(word, constants)
                if (wideMove != null) {
                    val (rd, value) = wideMove
                    values.remove(rd)
                    if (value == null) constants.remove(rd) else constants[rd] = value
                    continue
                }
                val zero = A64.movXZero(word)
                if (zero != null) {
                    values.remove(zero); constants[zero] = 0
                    continue
                }
                if (word and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt()) {
                    val destination = word and 31; val source = values[(word ushr 16) and 31]
                    if (source == null) values.remove(destination) else values[destination] = source
                    val constant = constants[(word ushr 16) and 31]
                    if (constant == null) constants.remove(destination) else constants[destination] = constant
                    continue
                }
                if (word and 0x3b000000 == 0x39000000) {
                    val vector = word and 0x04000000 != 0
                    val operation = (word ushr 22) and 3
                    val width = if (vector && operation >= 2) 16L else 1L shl (word ushr 30)
                    values[(word ushr 5) and 31]?.let { base ->
                        protect(base + ((word ushr 10) and 0xfff) * width, width, "固定地址内存访问")
                    }
                    if (!vector && operation == 0) values[word and 31]?.let { protectEscape(it, start, pc, "末对象指针存储逃逸") }
                    if (!vector && operation != 0) values.remove(word and 31)
                    if (!vector && operation != 0) constants.remove(word and 31)
                    continue
                }
                if (word and 0x3b000000 == 0x38000000) {
                    val baseRegister = (word ushr 5) and 31
                    val base = values[baseRegister]
                    val vector = word and 0x04000000 != 0
                    val operation = (word ushr 22) and 3
                    val width = if (vector && operation >= 2) 16L else 1L shl (word ushr 30)
                    if (word and 0x00200000 != 0) {
                        val index = (word ushr 16) and 31
                        val option = (word ushr 13) and 7
                        val scale = if (word and 0x1000 != 0) width else 1L
                        if (base != null) {
                            val exact = constants[index]
                            val bounds = if (exact != null) {
                                val extended = when (option) { 2 -> exact and 0xffff_ffffL; 6 -> exact.toInt().toLong(); else -> exact }
                                extended to extended
                            } else indexBounds(pc, index, option)
                            if (bounds == null) {
                                // The audit proves accesses to the padding page,
                                // not arbitrary out-of-bounds reads from unrelated
                                // objects elsewhere in the image. Page-local
                                // unresolved indexing is never guessed safe.
                                for (tail in tails) if ((base and -4096L) < tail.end &&
                                    tail.paddingStart < (base and -4096L) + 4096) {
                                    // Under the valid independent-object access
                                    // assumption, an initialized object followed
                                    // by a separately addressed initialized object
                                    // does not own the final zero tail. This is
                                    // not an arbitrary cross-object OOB proof.
                                    if (hasFollowingInitializedObject(base, tail, start, pc)) continue
                                    protect(tail.start, tail.end - tail.start, "未定界寄存器索引@0x${pc.toString(16)}")
                                }
                            } else {
                                protectIndexedRange(base, bounds, scale, width, start, pc,
                                    "定界寄存器内存访问@0x${pc.toString(16)} base=0x${base.toString(16)} bounds=${bounds.first}..${bounds.second} scale=$scale option=$option")
                            }
                        }
                    } else {
                        val displacement = signExtend(((word ushr 12) and 0x1ff).toLong(), 9)
                        val mode = (word ushr 10) and 3
                        if (base != null) {
                            protect(base + if (mode == 1) 0 else displacement, width, "未缩放内存访问")
                            if (mode in listOf(1, 3)) values[baseRegister] = base + displacement
                        }
                    }
                    if (!vector && operation == 0) values[word and 31]?.let { protectEscape(it, start, pc, "末对象指针存储逃逸") }
                    if (!vector && operation != 0) { values.remove(word and 31); constants.remove(word and 31) }
                    continue
                }
                if (word and 0x3a000000 == 0x28000000) {
                    val baseRegister = (word ushr 5) and 31
                    val base = values[baseRegister]
                    val vector = word and 0x04000000 != 0
                    val width = if (vector) 4L shl (word ushr 30) else if (word ushr 31 != 0) 8L else 4L
                    val displacement = signExtend(((word ushr 15) and 127).toLong(), 7) * width
                    val mode = (word ushr 23) and 3
                    if (base != null) {
                        protect(base + if (mode == 1) 0 else displacement, width * 2, "成对内存访问")
                        if (mode in listOf(1, 3)) values[baseRegister] = base + displacement
                    }
                    if (!vector && word and 0x00400000 != 0) {
                        for (rd in listOf(word and 31, (word ushr 10) and 31)) { values.remove(rd); constants.remove(rd) }
                    } else if (!vector) {
                        for (rd in listOf(word and 31, (word ushr 10) and 31)) values[rd]?.let { protectEscape(it, start, pc, "末对象指针成对存储逃逸") }
                    }
                    continue
                }
                // Unknown writes terminate an alias; opaque indexed accesses do
                // not manufacture a guessed target or a claimed dynamic bound.
                values.keys.filter { StraightLineLiveness.mayWriteRegister(word, it) }.forEach(values::remove)
                constants.keys.filter { StraightLineLiveness.mayWriteRegister(word, it) }.forEach(constants::remove)
            }
            values.values.forEach { protectEscape(it, start, start + 96, "末对象地址局部分析预算耗尽") }
        }

        /** AArch64 address arithmetic is modulo 2^64, including extended/scaled indexes. */
        private fun protectIndexedRange(
            base: Long, bounds: Pair<Long, Long>, scale: Long, width: Long, origin: Long, pc: Long, reason: String,
        ) {
            val modulus = BigInteger.ONE.shiftLeft(64)
            val first = BigInteger.valueOf(bounds.first)
            val last = BigInteger.valueOf(bounds.second)
            val stride = BigInteger.valueOf(scale)
            val length = last.subtract(first).multiply(stride).add(BigInteger.valueOf(width))
            if (length.signum() <= 0) return
            val begin = BigInteger.valueOf(base).add(first.multiply(stride)).mod(modulus)
            fun segment(low: BigInteger, high: BigInteger) {
                // All PE RVAs are below 2^32. Clip in wide arithmetic before
                // narrowing; a large unrelated interval must not protect every tail.
                for (tail in tails) {
                    val overlapStart = low.max(BigInteger.valueOf(tail.start))
                    val overlapEnd = high.min(BigInteger.valueOf(tail.end))
                    if (overlapStart >= overlapEnd) continue
                    // A mask/bitfield bound is an upper envelope, not an exact
                    // index. Retain the same valid independent-object assumption
                    // as opaque indexing: an earlier initialized object followed
                    // by a separately addressed initialized object does not own
                    // the zero tail. Exact computed targets never take this path.
                    if (bounds.first != bounds.second && hasFollowingInitializedObject(base, tail, origin, pc)) continue
                    protect(overlapStart.toLong(), overlapEnd.subtract(overlapStart).toLong(), reason)
                }
            }
            if (length >= modulus) segment(BigInteger.ZERO, modulus)
            else {
                val end = begin.add(length)
                segment(begin, end.min(modulus))
                if (end > modulus) segment(BigInteger.ZERO, end.subtract(modulus))
            }
        }

        /** MOVZ/MOVN/MOVK retain all 64 bits instead of treating X constants as unknown W values. */
        private fun moveWideConstant(word: Int, constants: Map<Int, Long>): Pair<Int, Long?>? {
            if (word and 0x1f800000 != 0x12800000) return null
            val wide = word ushr 31 != 0
            val operation = (word ushr 29) and 3
            val shift = ((word ushr 21) and 3) * 16
            if (operation == 1 || !wide && shift >= 32) return null
            val rd = word and 31
            val immediate = ((word ushr 5) and 0xffff).toLong() shl shift
            val value = when (operation) {
                0 -> immediate.inv()
                2 -> immediate
                else -> constants[rd]?.let { (it and (0xffffL shl shift).inv()) or immediate }
            }
            return rd to value?.let { if (wide) it else it and 0xffff_ffffL }
        }

        private fun indexBounds(pc: Long, register: Int, extension: Int): Pair<Long, Long>? {
            if (extension !in listOf(2, 3, 6, 7)) return null
            for (distance in 1..24) {
                val comparePc = pc - distance * 4L
                val raw = image.word(comparePc) ?: return null
                val sourceMaximum = sourceWUpperBound(comparePc, (raw ushr 5) and 31)
                val mask = andMask(raw)?.let { if (sourceMaximum != null) minOf(it, sourceMaximum) else it }
                    ?: unsignedBitfieldBound(raw, sourceMaximum)
                if (mask != null && raw and 31 == register &&
                    StraightLineLiveness.preserves(image, comparePc + 4, pc, setOf(register))) {
                    if (extension == 6 && mask > Int.MAX_VALUE || extension == 2 && mask > 0xffff_ffffL) return null
                    return 0L to mask
                }
                val compare = A64.cmpImmediate(raw)
                if (compare?.first != register) {
                    if (StraightLineLiveness.mayWriteRegister(raw, register)) break
                    continue
                }
                // A W bound does not constrain unknown X high bits.
                if (extension in listOf(3, 7) && raw ushr 31 == 0) continue
                val branchPc = comparePc + 4
                val branch = image.word(branchPc) ?: continue
                val condition = branch and 0xff00001f.toInt()
                if (condition !in listOf(0x54000008, 0x54000002)) continue // B.HI / B.HS outside the read block
                val target = directTarget(branchPc, branch) ?: continue
                if (target <= pc || !StraightLineLiveness.preserves(image, branchPc + 4, pc, setOf(register))) continue
                if (branches.any { (from, to) -> to in branchPc + 4..pc && from !in branchPc..pc }) continue
                val maximum = compare.second.toLong() - if (condition == 0x54000002) 1 else 0
                if (maximum >= 0 && maximum <= Int.MAX_VALUE) return 0L to maximum
            }
            return inductionBounds(pc, register)
        }

        /** A dominating W definition also constrains a following X bitfield operation. */
        private fun sourceWUpperBound(pc: Long, register: Int): Long? {
            if (register == 31) return 0
            for (distance in 1..24) {
                val at = pc - distance * 4L
                val word = image.word(at) ?: return null
                if (!image.isCode(at) || directTarget(at, word) != null ||
                    word and 0xfffffc1f.toInt() in listOf(0xd61f0000.toInt(), 0xd63f0000.toInt(), 0xd65f0000.toInt())) return null
                if (!StraightLineLiveness.mayWriteRegister(word, register)) continue
                if (branches.any { (from, to) -> to in at + 4..pc && from !in at..pc }) return null
                val rd = word and 31
                // Integer data processing has sf=0 for a W destination. The
                // narrow opcode classes exclude SIMD and store source fields.
                if (rd == register && word ushr 31 == 0 &&
                    (word and 0x1f000000 in listOf(0x11000000, 0x12000000, 0x13000000, 0x0a000000, 0x0b000000) ||
                        word and 0x1f800000 == 0x12800000)) return 0xffff_ffffL
                val scalar = word and 0x04000000 == 0
                if (scalar && rd == register && word and 0x3b000000 in listOf(0x39000000, 0x38000000)) {
                    val size = word ushr 30; val operation = (word ushr 22) and 3
                    if (operation == 1 && size <= 2) return (1L shl (8 shl size)) - 1
                    if (operation == 3 && size <= 1) return 0xffff_ffffL
                }
                if (scalar && word and 0x3a000000 == 0x28000000 && word ushr 30 == 0 && word and 0x00400000 != 0 &&
                    register in listOf(rd, (word ushr 10) and 31)) return 0xffff_ffffL
                if (scalar && rd == register && word and 0xff000000.toInt() == 0x18000000) return 0xffff_ffffL
                return null
            }
            return null
        }

        /** Prove the ordinary zero/+1/exit-on-N induction, including a private spill/reload. */
        private fun inductionBounds(readPc: Long, index: Int): Pair<Long, Long>? {
            val body = code.filter { it.first in maxOf(0, readPc - 0x1000)..readPc + 0x4000 }
            for ((header, raw) in body.filter { it.first < readPc }) {
                val compared = A64.cmpImmediate(raw) ?: continue
                val counter = compared.first; val count = compared.second
                if (count !in 1..4096 || counter == 31) continue
                val exitWord = image.word(header + 4) ?: continue
                if (exitWord and 0xff00001f.toInt() != 0x54000000) continue
                val exit = directTarget(header + 4, exitWord) ?: continue
                if (exit <= readPc || exit > readPc + 0x4000) continue
                val backEdges = body.filter { (at, word) -> at in readPc + 4..<exit && A64.bTarget(at, word) == header }
                if (backEdges.size != 1) continue
                val back = backEdges.single().first
                val initialization = body.lastOrNull { (at, instruction) -> at in maxOf(0, header - 128)..<header &&
                    (A64.movXZero(instruction) == counter || A64.movWImmediate(instruction) == (counter to 0)) &&
                    StraightLineLiveness.preserves(image, at + 4, header, setOf(counter))
                } ?: continue
                val start = initialization.first
                if (branches.any { (from, to) -> to in start + 4..back && from !in start..back }) continue
                if (body.any { it.first in start..<exit && it.second and 0xfffffc1f.toInt() == 0xd61f0000.toInt() }) continue
                val flow = flows.getOrPut(start to exit) { A64ValueOrigins(image, start, exit) }
                for ((increment, word) in body.filter { it.first in readPc + 4..<back }) {
                    val add = A64.add(word) ?: continue
                    if (add.rd != counter || add.rn != index || add.immediate != 1L) continue
                    val definition = A64ValueOrigin.Definition(increment, counter)
                    val expected = setOf(A64ValueOrigin.Zero, definition)
                    if (flow.origins(header, counter) != expected || flow.origins(back, counter) != setOf(definition)) continue
                    if (index == counter && flow.origins(readPc, index) == expected &&
                        body.filter { it.first in header + 8..<readPc }.none { (at, instruction) ->
                            (A64.blTarget(at, instruction) != null || instruction and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) && counter < 19 ||
                                StraightLineLiveness.mayWriteRegister(instruction, counter)
                        }) return 0L to count - 1L
                    // A volatile induction variable may be used as call arguments;
                    // require its pre-call private spill and the corresponding load.
                    for ((loadPc, loadWord) in body.filter { it.first in maxOf(header + 8, readPc - 64)..<readPc }) {
                        if (loadWord and 0xffc0001f.toInt() != (0xf9400000.toInt() or index)) continue
                        val base = flow.stackAddress(loadPc, (loadWord ushr 5) and 31) ?: continue
                        val slot = base + ((loadWord ushr 10) and 0xfff) * 8
                        if (!StraightLineLiveness.preserves(image, loadPc + 4, readPc, setOf(index))) continue
                        val stores = body.filter { (at, instruction) ->
                            if (at !in header + 8..<loadPc || instruction and 0xffc0001f.toInt() != (0xf9000000.toInt() or counter)) false
                            else flow.stackAddress(at, (instruction ushr 5) and 31)?.plus(((instruction ushr 10) and 0xfff) * 8) == slot
                        }
                        if (stores.size != 1) continue
                        val storePc = stores.single().first
                        if (!StraightLineLiveness.preserves(image, header + 8, storePc, setOf(counter))) continue
                        if (branches.any { (from, to) -> to in storePc + 4..loadPc && from !in storePc..loadPc }) continue
                        if (!privateSpillPreserved(body, flow, storePc, loadPc, slot)) continue
                        if (body.filter { it.first in loadPc + 4..<increment }.any { (at, instruction) ->
                                (A64.blTarget(at, instruction) != null || instruction and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) && index < 19 ||
                                    StraightLineLiveness.mayWriteRegister(instruction, index)
                            }) continue
                        return 0L to count - 1L
                    }
                }
            }
            return null
        }

        /** Caller-private fixed spill, under the same valid independent-object/ABI assumption as the local audit. */
        private fun privateSpillPreserved(
            body: List<Pair<Long, Int>>, flow: A64ValueOrigins, store: Long, load: Long, slot: Int,
        ): Boolean {
            for ((pc, word) in body.filter { it.first in store + 4..<load }) {
                if ((word and 0x1f000000 == 0x11000000 && word and 31 == 31 && word and 0x20000000 == 0) ||
                    word and 0xfffffc1f.toInt() == 0xd61f0000.toInt()) return false
                if (A64.blTarget(pc, word) != null || word and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) {
                    if ((0..7).any { flow.stackAddress(pc, it)?.let { address -> address in slot until slot + 8 } == true }) return false
                    continue
                }
                val baseRegister = (word ushr 5) and 31
                val base = flow.stackAddress(pc, baseRegister) ?: continue
                var offset: Long; var width: Long
                if (word and 0x3b000000 in listOf(0x39000000, 0x38000000)) {
                    val vector = word and 0x04000000 != 0
                    val operation = (word ushr 22) and 3
                    val isStore = if (vector) operation and 1 == 0 else operation == 0
                    if (!isStore) continue
                    width = if (vector && operation >= 2) 16 else 1L shl (word ushr 30)
                    if (word and 0x3b000000 == 0x39000000) offset = ((word ushr 10) and 0xfff) * width
                    else {
                        if (word and 0x00200000 != 0) return false
                        val mode = (word ushr 10) and 3
                        if (baseRegister == 31 && mode in listOf(1, 3)) return false
                        offset = if (mode == 1) 0 else signExtend(((word ushr 12) and 0x1ff).toLong(), 9)
                    }
                } else if (word and 0x3a000000 == 0x28000000) {
                    if (word and 0x00400000 != 0) continue
                    val vector = word and 0x04000000 != 0
                    val unit = if (vector) 4L shl (word ushr 30) else if (word ushr 31 != 0) 8L else 4L
                    val mode = (word ushr 23) and 3
                    if (baseRegister == 31 && mode in listOf(1, 3)) return false
                    offset = if (mode == 1) 0 else signExtend(((word ushr 15) and 127).toLong(), 7) * unit
                    width = unit * 2
                } else continue
                if (base + offset < slot + 8 && slot < base + offset + width) return false
            }
            return true
        }

        /** A64 logical-immediate AND gives an unsigned upper bound independent of its input. */
        private fun andMask(word: Int): Long? {
            if (word and 0x1f800000 != 0x12000000 || (word ushr 29) and 3 !in listOf(0, 3)) return null
            val sf = word ushr 31
            val n = (word ushr 22) and 1
            val imms = (word ushr 10) and 63
            val immr = (word ushr 16) and 63
            val combined = (n shl 6) or (imms.inv() and 63)
            val length = 31 - Integer.numberOfLeadingZeros(combined)
            if (length !in 1..6 || sf == 0 && length > 5) return null
            val elementSize = 1 shl length
            val levels = elementSize - 1
            val bits = imms and levels
            val rotate = immr and levels
            if (bits == levels) return null
            val all = if (elementSize == 64) ULong.MAX_VALUE else (1UL shl elementSize) - 1UL
            val ones = (1UL shl (bits + 1)) - 1UL
            val element = if (rotate == 0) ones else ((ones shr rotate) or (ones shl (elementSize - rotate))) and all
            var result = 0UL
            for (shift in 0 until (if (sf == 0) 32 else 64) step elementSize) result = result or (element shl shift)
            return result.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
        }

        private fun unsignedBitfieldBound(word: Int, sourceMaximum: Long? = null): Long? {
            if (word and 0x7f800000 != 0x53000000 || (word ushr 22) and 1 != word ushr 31) return null
            val width = if (word ushr 31 == 0) 32 else 64
            val rotate = (word ushr 16) and 63
            val top = (word ushr 10) and 63
            if (rotate >= width || top >= width) return null
            val bits = if (rotate <= top) top - rotate + 1 else top + 1
            if (bits == 64) return null
            val sourceMask = (1UL shl bits) - 1UL
            val limited = sourceMaximum?.toULong()?.let { if (rotate <= top) it shr rotate else it }
            val mask = minOf(sourceMask, limited ?: sourceMask) shl if (rotate <= top) 0 else width - rotate
            return mask.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
        }

        private fun directTarget(pc: Long, word: Int): Long? {
            A64.bTarget(pc, word)?.let { return it }
            A64.blTarget(pc, word)?.let { return it }
            if (word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 == 0x34000000) {
                return pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
            }
            if (word and 0x7e000000 == 0x36000000) return pc + (signExtend(((word ushr 5) and 0x3fff).toLong(), 14) shl 2)
            return null
        }
    }
}
