package net.hawyn.ablefisp.patch

/**
 * Independent BLTools-style one-way DeviceInfo transaction. Ordinary flashing
 * unlock remains unchanged. Coordinates are derived from command tables, native
 * registration and the existing semantically proved read/write wrapper.
 * Behavior reference: SuperTurtleDev/gbl_root_canoe commit
 * 4ef438cfde42943694fde5753bbf5d8c0b4ec02e, AndroidToolsPkg/BLTools (BSD-3-Clause).
 */
internal object SuperUnlockPatcher {
    data class Plan(
        val registrationGateRva: Long,
        val registrationOriginalWord: Int,
        val registrationContinueRva: Long,
        val registrationExitRva: Long,
        val exitCondition: Int,
        val nativeRegisterRva: Long,
        val readWriteBackendRva: Long,
        val deviceInfoBytes: Int,
        val normalFieldOffset: Int,
        val criticalFieldOffset: Int,
        val fastbootOkayRva: Long,
        val fastbootFailRva: Long,
        val magic: ByteArray,
        val tableStartRva: Long,
        val tableEndRva: Long,
        val loopCompareRva: Long,
        val loopIndexRegister: Int,
        val existingAllocationRva: Long? = null,
    ) {
        val frameBytes get() = (deviceInfoBytes + 15) and -16
        val notes get() = listOf(
            "新增独立 fastboot oem superunlock，原 flashing unlock 修补及确认流程保持。",
            "按命令表、注册循环与 native DeviceInfo 读写关系定位，不使用机型、固件版本或固定地址表。",
            "按 BLTools 单向解锁流程 fresh READ 整体 DeviceInfo，仅将普通解锁标志置一并保留 critical/其他字段，再 WRITE 和独立 READ 核对必要状态。",
            "该命令本身为显式执行请求，不调用原确认菜单；已解锁时不重复写入，不执行 toggle、Keymaster reset 或 recovery wipe。",
            "回读只核对魔数及目标状态，允许后端更新非目标字段；READ 的完整填充依赖原协议契约。",
            "BLTools 和原流程仍使用同一 VerifiedBoot DeviceInfo 服务；成功响应不等于已证明厂商 OEMINFO 真实锁态、断电持久性或 data 解密。",
        )
    }

    private data class Function(val start: Long, val end: Long)
    private class Index(val image: PeImage) {
        val code = image.instructions().toList()
        val starts = code.filter { it.second in setOf(0xd503233f.toInt(), 0xd503237f.toInt()) }.map { it.first }
        val refs by lazy { A64.references(image) }
        private val flows = hashMapOf<Function, A64ValueOrigins>()
        fun function(pc: Long): Function? {
            val a = starts.lastOrNull { it <= pc } ?: return null
            return Function(a, starts.firstOrNull { it > pc } ?: return null)
        }
        fun body(f: Function) = code.filter { it.first in f.start..<f.end }
        fun flow(f: Function) = flows.getOrPut(f) { A64ValueOrigins(image, f.start, f.end) }
    }
    private data class Table(val start: Long, val end: Long)
    private data class Registration(val gate: Long, val raw: Int, val continuing: Long, val exit: Long,
                                    val native: Long, val table: Table, val compare: Long, val iterator: Int)

    fun analyze(image: PeImage, backend: FlashingUnlockPatcher.Plan?): Plan? {
        if (backend == null) return null
        val virtual = image.bytes.copyOf()
        val existing = hashMapOf<Long, Long>()
        // Decode only our own complete registration prefix. The full block is
        // verified below after deriving all functional relationships again.
        for ((site, word) in image.instructions()) {
            val stub = A64.bTarget(site, word) ?: continue
            if (!image.isCode(stub, SuperUnlockCode.TOTAL_BYTES)) continue
            val first = image.word(stub) ?: continue
            if (first and 0xff00001f.toInt() != 0x54000000 || conditionalTarget(stub, first) != stub + 8) continue
            val continuing = image.word(stub + 4)?.let { A64.bTarget(stub + 4, it) } ?: continue
            val last = stub + SuperUnlockCode.REGISTRATION_CODE_BYTES - 4
            val exit = image.word(last)?.let { A64.bTarget(last, it) } ?: continue
            val cmp = image.word(site - 4)?.let(::compareImmediate) ?: continue
            val candidates = listOf(condition(site, exit, 0), condition(site, continuing, 1))
            val original = candidates.singleOrNull { candidate ->
                val target = conditionalTarget(site, candidate)
                (candidate and 15 == 0 && continuing == site + 4 && target > site) ||
                    (candidate and 15 == 1 && exit == site + 4 && target > site)
            } ?: continue
            if (cmp.second <= 0) continue
            virtual.putWord(requireNotNull(image.rvaToOffset(site, 4)), original)
            existing[site] = stub
        }
        val index = Index(PeImage.parse(virtual))
        val tables = tables(index.image)
        val registrations = tables.flatMap { registration(index, it) }.distinct()
        if (registrations.size != 1) incomplete("原命令注册循环未唯一匹配", registrations.size > 1)
        val r = registrations.single()
        val okay = responses(index, "OKAY")
        val fail = responses(index, "FAIL")
        val pairs = okay.flatMap { a -> fail.filter { it.second == a.second }.map { a.first to it.first } }.distinct()
        if (pairs.size != 1) incomplete("Fastboot OKAY/FAIL 传输关系未唯一匹配", pairs.size > 1)
        val expectedMagic = "ANDROID-BOOT!".toByteArray(Charsets.US_ASCII)
        val magicAddresses = image.bytes.occurrences(expectedMagic + byteArrayOf(0)).mapNotNull(image::offsetToRva).toSet()
        if (magicAddresses.isEmpty() || index.refs.none { it.address in magicAddresses }) incomplete("DeviceInfo 魔数来源未匹配")
        if (backend.normalFieldOffset < expectedMagic.size || backend.criticalFieldOffset < expectedMagic.size ||
            backend.criticalFieldOffset >= backend.deviceInfoBytes || backend.normalFieldOffset == backend.criticalFieldOffset)
            incomplete("DeviceInfo 字段边界不完整")
        val plan = Plan(r.gate, r.raw, r.continuing, r.exit, 0, r.native, backend.writeBackendRva,
            backend.deviceInfoBytes, backend.normalFieldOffset, backend.criticalFieldOffset,
            pairs.single().first, pairs.single().second, expectedMagic,
            r.table.start, r.table.end, r.compare, r.iterator, existing[r.gate])
        plan.existingAllocationRva?.let { at ->
            val offset = image.rvaToOffset(at, SuperUnlockCode.TOTAL_BYTES) ?: incomplete("已有命令块边界无效")
            if (!image.bytes.copyOfRange(offset, offset + SuperUnlockCode.TOTAL_BYTES).contentEquals(SuperUnlockCode.bytes(plan, at)))
                incomplete("已有 superunlock 命令块与重新定位结果不一致")
        }
        return plan
    }

    fun edits(plan: Plan, allocationRva: Long): List<PlannedEdit> = listOf(
        PlannedEdit("oem superunlock：独立命令注册入口", plan.registrationGateRva,
            ByteArray(4).also { it.putWord(0, A64.branch(plan.registrationGateRva, allocationRva)) }),
        PlannedEdit("oem superunlock：BLTools 读改写及独立回读", allocationRva, SuperUnlockCode.bytes(plan, allocationRva)),
    )

    fun metadata(plan: Plan, allocation: Long): Map<String, Any> = linkedMapOf(
        "registrationGateRva" to plan.registrationGateRva, "registrationOriginalWord" to (plan.registrationOriginalWord.toLong() and 0xffffffffL),
        "registrationContinueRva" to plan.registrationContinueRva, "registrationExitRva" to plan.registrationExitRva,
        "nativeRegisterRva" to plan.nativeRegisterRva, "readWriteBackendRva" to plan.readWriteBackendRva,
        "deviceInfoBytes" to plan.deviceInfoBytes, "normalFieldOffset" to plan.normalFieldOffset,
        "frameBytes" to plan.frameBytes,
        "criticalFieldOffset" to plan.criticalFieldOffset, "fastbootOkayRva" to plan.fastbootOkayRva,
        "fastbootFailRva" to plan.fastbootFailRva, "tableStartRva" to plan.tableStartRva,
        "tableEndRva" to plan.tableEndRva, "loopCompareRva" to plan.loopCompareRva,
        "loopIndexRegister" to plan.loopIndexRegister, "allocationRva" to allocation,
        "allocationBytes" to SuperUnlockCode.TOTAL_BYTES,
        "handlerRva" to allocation + SuperUnlockCode.REGISTRATION_BYTES,
        "commandNameRva" to allocation + SuperUnlockCode.dataOffsets().getValue("command"),
    )

    private fun tables(image: PeImage): List<Table> {
        fun record(offset: Int): Pair<String, Long>? {
            if (offset < 0 || offset + 16 > image.bytes.size) return null
            val nameAt = pointer(image, image.bytes.u64(offset)) ?: return null
            val name = image.ascii(nameAt, 96) ?: return null
            if (name.isEmpty() || name.any { it.code !in 32..126 }) return null
            val handler = pointer(image, image.bytes.u64(offset + 8)) ?: return null
            if (!image.isCode(handler) || image.word(handler) !in setOf(0xd503233f.toInt(), 0xd503237f.toInt())) return null
            return name to handler
        }
        val found = arrayListOf<Table>()
        for (offset in 0..image.bytes.size - 16 step 8) {
            if (record(offset)?.first != "flashing unlock") continue
            var low = offset; var high = offset + 16
            while (record(low - 16) != null) low -= 16
            while (record(high) != null) high += 16
            val names = (low until high step 16).map { requireNotNull(record(it)).first }.toSet()
            if (!names.containsAll(setOf("flashing lock", "flash:", "erase:", "getvar:", "download:"))) continue
            found += Table(requireNotNull(image.offsetToRva(low)), requireNotNull(image.offsetToRva(high - 1)) + 1)
        }
        return found.distinct()
    }

    private fun registration(index: Index, table: Table): List<Registration> {
        val result = arrayListOf<Registration>(); val image = index.image
        for ((pairPc, pair) in index.code) {
            // 64-bit offset LDP X0,X1,[base,#offset]. Register allocation of base
            // and iterator is deliberately not part of identity.
            if (pair and 0xffc07fff.toInt() != (0xa9400400.toInt() or ((pair ushr 5 and 31) shl 5))) continue
            val pairBase = (pair ushr 5) and 31
            val pairOffset = signed((pair ushr 15 and 127).toLong(), 7) * 8
            val owner = index.function(pairPc) ?: continue
            val flow = index.flow(owner)
            val additions = (1..4).mapNotNull { n ->
                val pc = pairPc - n * 4; val word = image.word(pc) ?: return@mapNotNull null
                if (word and 0xffe0fc00.toInt() != 0x8b000000.toInt() || word and 31 != pairBase) return@mapNotNull null
                val left = (word ushr 5) and 31; val right = (word ushr 16) and 31
                listOf(left to right, right to left).firstOrNull { (base, _) -> flow.globalAddress(pc, base)?.plus(pairOffset) == table.start }
                    ?.let { (_, iterator) -> pc to iterator }
            }
            if (additions.size != 1) continue
            val (addition, iterator) = additions.single()
            val call = (1..4).mapNotNull { n ->
                val pc = pairPc + n * 4; image.word(pc)?.let { A64.blTarget(pc, it) }?.let { pc to it }
            }.firstOrNull() ?: continue
            if (!nativeRegister(index, call.second)) continue
            val branches = (1..12).mapNotNull { n ->
                val gate = addition - n * 4; val raw = image.word(gate) ?: return@mapNotNull null
                if (raw and 0xff000010.toInt() != 0x54000000 || raw and 15 !in 0..1) return@mapNotNull null
                val cmpPc = gate - 4; val cmp = image.word(cmpPc)?.let(::compareImmediate) ?: return@mapNotNull null
                if (cmp.first != iterator || cmp.second.toLong() != table.end - table.start) return@mapNotNull null
                val target = conditionalTarget(gate, raw)
                val continuing = if (raw and 15 == 0) gate + 4 else target
                val exit = if (raw and 15 == 0) target else gate + 4
                if (continuing > addition || continuing < gate || exit in continuing..call.first) return@mapNotNull null
                val back = (call.first + 4 until minOf(owner.end, call.first + 40) step 4).firstOrNull { pc ->
                    image.word(pc)?.let { A64.bTarget(pc, it) } == cmpPc
                } ?: return@mapNotNull null
                val increment = (call.first + 4 until back step 4).any { pc ->
                    image.word(pc)?.let(A64::add)?.let { it.rd == iterator && it.rn == iterator && it.immediate == 16L } == true
                }
                if (!increment || exit in continuing..back) return@mapNotNull null
                Registration(gate, raw, continuing, exit, call.second, table, cmpPc, iterator)
            }
            result += branches
        }
        return result.distinct()
    }

    private fun nativeRegister(index: Index, entry: Long): Boolean {
        val f = index.function(entry)?.takeIf { it.start == entry } ?: return false
        val flow = index.flow(f)
        val stores = index.body(f).mapNotNull { (pc, word) ->
            if (word and 0xffc00000.toInt() != 0xf9000000.toInt()) return@mapNotNull null
            Triple(pc, (word ushr 5) and 31, word and 31)
        }
        val afterCalls = index.body(f).mapNotNull { (pc, word) ->
            A64.blTarget(pc, word)?.let { pc + 4 }
        }.associateWith { start -> A64ValueOrigins(index.image, start, f.end) }
        return stores.any { a -> flow.derivesFromEntry(a.first, a.third, 0) && stores.any { b ->
            flow.derivesFromEntry(b.first, b.third, 1) &&
                (flow.sameValue(a.first, a.second, b.first, b.second) || afterCalls.any { (start, local) ->
                    start <= minOf(a.first, b.first) && local.derivesFromEntry(a.first, a.second, 0) &&
                        local.derivesFromEntry(b.first, b.second, 0)
                })
        } }
    }

    private fun responses(index: Index, prefix: String): List<Pair<Long, Long>> {
        val addresses = index.image.bytes.occurrences((prefix + '\u0000').toByteArray(Charsets.US_ASCII))
            .mapNotNull(index.image::offsetToRva).toSet()
        return index.refs.filter { it.address in addresses }.mapNotNull { ref ->
            val f = index.function(ref.pc) ?: return@mapNotNull null
            val tails = index.body(f).mapNotNull { (pc, word) ->
                A64.bTarget(pc, word)?.takeIf { it !in f.start..<f.end && index.image.isCode(it) &&
                    index.flow(f).globalAddress(pc, 0) == ref.address }
            }.distinct()
            if (tails.size != 1) null else f.start to tails.single()
        }.distinct()
    }

    private fun pointer(image: PeImage, value: Long): Long? = when {
        image.rvaToOffset(value) != null -> value
        value >= image.imageBase && image.rvaToOffset(value - image.imageBase) != null -> value - image.imageBase
        else -> null
    }
    private fun compareImmediate(word: Int): Pair<Int, Int>? = if (word and 0x7f00001f == 0x7100001f)
        ((word ushr 5) and 31) to (((word ushr 10) and 4095) shl (if (word and 0x400000 != 0) 12 else 0)) else null
    private fun conditionalTarget(pc: Long, word: Int) = pc + (signed(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
    private fun condition(pc: Long, target: Long, condition: Int): Int {
        val d = target - pc
        if (d and 3L != 0L || d !in -0x100000L..<0x100000L) incomplete("原条件跳转范围无效")
        return 0x54000000 or (((d shr 2).toInt() and 0x7ffff) shl 5) or condition
    }
    private fun signed(value: Long, bits: Int) = (value shl (64 - bits)) shr (64 - bits)
    private fun incomplete(message: String, ambiguous: Boolean = false): Nothing = throw PatchException(
        "oem superunlock：$message，本次未生成镜像。",
        if (ambiguous) PatchFailureKind.AMBIGUOUS_CORE_FEATURE else PatchFailureKind.INCOMPLETE_CORE_FEATURE,
    )
}
