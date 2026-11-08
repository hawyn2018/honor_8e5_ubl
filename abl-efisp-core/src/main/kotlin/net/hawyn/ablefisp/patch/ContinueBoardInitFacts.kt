package net.hawyn.ablefisp.patch

/** Discovers only the native platform context omitted by an early Fastboot entry. */
internal object ContinueBoardInitFacts {
    data class Protocol(val guidRva: Long, val outputRva: Long, val getterOffset: Int, val probeOffset: Int? = null)
    data class Copy(val sourceRva: Long, val destRva: Long, val size: Int)
    data class Plan(
        val bootServicesRva: Long,
        val locateMethodOffset: Int,
        val board: Protocol,
        val platform: Protocol,
        val boardValidationShift: Int,
        val boardValidationLimit: Int,
        val platformCopies: List<Copy>,
    )

    private data class Mem(val reg: Int, val base: Int, val offset: Int, val size: Int, val load: Boolean)
    private data class Locate(val guid: Long, val stackSlot: Int, val offset: Int)
    private data class Getter(val output: Long, val offset: Int, val gate: Long)
    private data class Validation(val shift: Int, val limit: Int, val copyStart: Long)

    fun analyze(image: PeImage, facts: FastbootRecoveryFacts.Facts): Plan {
        val route = facts.prerequisites.preparationRoute ?: fail("原生协议准备路径")
        val main = facts.main
        val bootServices = KblEnvironmentFacts.analyze(image).bootServicesGlobal
        val flow = A64ValueOrigins(image, main.start, main.end)
        fun global(pc: Long, reg: Int): Long? = flow.globalAddress(pc, reg)
        val references = A64.references(image)
        fun message(text: String): StringReference = references.singleOrNull {
            it.pc in main.start..<main.end && image.ascii(it.address)?.contains(text, ignoreCase = true) == true
        } ?: fail("原生 $text 日志")
        fun logger(ref: StringReference): Long {
            val call = (ref.pc + 4..minOf(ref.pc + 48, main.end - 4) step 4).firstOrNull { pc ->
                A64.blTarget(pc, word(image, pc)) != null
            } ?: fail("原生日志调用")
            if (A64.argumentState(image, ref.pc, call).registers[ref.register] != A64.Value.Address(ref.address))
                fail("原生日志参数")
            return call
        }

        val boardProbeStart = zeroSuccess(image, route.successGateRva)
        if (route.statusRegister != 0 || boardProbeStart != route.successDestinationRva)
            fail("主板协议成功分支")
        val boardLocateCall = (route.successGateRva - 4 downTo maxOf(route.entryRva, route.successGateRva - 32) step 4)
            .firstOrNull { indirectRegister(word(image, it)) != null } ?: fail("主板 LocateProtocol 调用")
        if (!StraightLineLiveness.preserves(image, boardLocateCall + 4, route.successGateRva, setOf(0)))
            fail("主板协议返回状态")
        val boardLocate = locate(image, boardLocateCall, route.entryRva, bootServices) { pc, reg ->
            global(pc, reg) ?: facts.prerequisites.materializableGlobalRegisters[reg]
        }

        val boardProbe = firstIndirect(image, boardProbeStart)
        val probeMethod = objectMethod(image, boardProbeStart, boardProbe, boardLocate.stackSlot, false)
        if (!StraightLineLiveness.preserves(image, boardProbeStart, boardProbe, setOf(0)))
            fail("主板协议探测参数")
        val boardGetterStart = zeroSuccess(image, statusGate(image, boardProbe))
        val board = getter(image, boardGetterStart, boardLocate.stackSlot)
        val boardLogStart = zeroSuccess(image, board.gate)
        val boardLog = logger(message("Honor BoardId Info"))
        if (boardLog !in boardLogStart..(boardLogStart + 64))
            fail("主板协议与 BoardId 日志的汇合点")

        val platformLocateCall = firstIndirect(image, boardLog + 4)
        val platformLocate = locate(image, platformLocateCall, boardLog + 4, bootServices, ::global)
        if (platformLocate.offset != boardLocate.offset || platformLocate.guid == boardLocate.guid)
            fail("主板与平台 LocateProtocol ABI")
        val platformGetterStart = zeroSuccess(image, statusGate(image, platformLocateCall))
        val platform = getter(image, platformGetterStart, platformLocate.stackSlot)
        val validatedStart = zeroSuccess(image, platform.gate)
        val platformLog = logger(message("Platform Info :"))
        val validation = validation(image, validatedStart, platform.output, platformLog)

        // The board getter dominates this normal-success continuation. A full
        // reaching-address proof from that block disallows guessing x24 or
        // substituting the platform output address for the board input.
        val preparedFlow = A64ValueOrigins(image, boardGetterStart, main.end)
        val copies = copies(image, preparedFlow, validation.copyStart, platformLog, board.output, platform.output)
        return Plan(bootServices, boardLocate.offset,
            Protocol(boardLocate.guid, board.output, board.offset, probeMethod),
            Protocol(platformLocate.guid, platform.output, platform.offset),
            validation.shift, validation.limit, copies)
    }

    private fun locate(image: PeImage, call: Long, lower: Long, bootServices: Long,
                       global: (Long, Int) -> Long?): Locate {
        val start = maxOf(lower, call - 56)
        val fnReg = indirectRegister(word(image, call)) ?: fail("LocateProtocol 间接调用")
        val methodPc = definition(image, call, start, fnReg)
        val method = memory(word(image, methodPc))?.takeIf { it.load && it.size == 8 && it.reg == fnReg }
            ?: fail("LocateProtocol 方法读取")
        val rootPc = definition(image, methodPc, start, method.base)
        val root = memory(word(image, rootPc))?.takeIf { it.load && it.size == 8 && it.reg == method.base }
            ?: fail("BootServices 指针读取")
        if (global(rootPc, root.base)?.plus(root.offset) != bootServices || method.offset != 0x140)
            fail("LocateProtocol 的 BootServices 来源")
        val args = A64.argumentState(image, rootPc, call).registers
        val guid = (args[0] as? A64.Value.Address)?.rva ?: fail("LocateProtocol GUID 参数")
        val slot = (args[2] as? A64.Value.Stack)?.offset?.takeIf { it in 0..0x10000 }?.toInt()
            ?: fail("LocateProtocol 输出指针")
        if (args[1] != A64.Value.Constant(0) || image.rvaToOffset(guid, 16) == null ||
            image.isCode(guid, 16) || slot and 7 != 0)
            fail("LocateProtocol 参数类型")
        return Locate(guid, slot, method.offset)
    }

    private fun getter(image: PeImage, start: Long, slot: Int): Getter {
        val call = firstIndirect(image, start)
        val method = objectMethod(image, start, call, slot, true)
        val args = A64.argumentState(image, start, call).registers
        val output = (args[1] as? A64.Value.Address)?.rva?.takeIf { image.isWritable(it, 16) }
            ?: fail("主板或平台的原生输出结构")
        return Getter(output, method, statusGate(image, call))
    }

    private fun objectMethod(image: PeImage, start: Long, call: Long, slot: Int, takesSelf: Boolean): Int {
        val target = indirectRegister(word(image, call)) ?: fail("协议方法调用")
        val methodPc = definition(image, call, start, target)
        val method = memory(word(image, methodPc))?.takeIf { it.load && it.size == 8 && it.reg == target }
            ?: fail("协议方法地址来源")
        val objectPc = definition(image, methodPc, start, method.base)
        val obj = memory(word(image, objectPc))?.takeIf {
            it.load && it.size == 8 && it.reg == method.base && it.base == 31 && it.offset == slot
        } ?: fail("协议对象必须来自 LocateProtocol 输出")
        if (takesSelf && (obj.reg != 0 || !StraightLineLiveness.preserves(image, objectPc + 4, call, setOf(0))))
            fail("协议 self 参数")
        if (method.offset !in 0..0x400 || method.offset and 7 != 0) fail("协议方法偏移")
        return method.offset
    }

    private fun validation(image: PeImage, start: Long, output: Long, log: Long): Validation {
        val flow = A64ValueOrigins(image, start, start + 56)
        val choices = (start..start + 36 step 4).mapNotNull { pc ->
            val cmp = A64.cmpImmediate(word(image, pc)) ?: return@mapNotNull null
            val shiftPc = definition(image, pc, start, cmp.first)
            val shiftWord = word(image, shiftPc)
            // LSR Wd,Wn,#amount is the UBFM alias with the high field at bit 31.
            if (shiftWord and 0xffc0fc00.toInt() != 0x53007c00) return@mapNotNull null
            val valueReg = (shiftWord ushr 5) and 31
            val readPc = definition(image, shiftPc, start, valueReg)
            val read = memory(word(image, readPc))?.takeIf { it.load && it.size == 4 } ?: return@mapNotNull null
            if (flow.globalAddress(readPc, read.base)?.plus(read.offset) != output) return@mapNotNull null
            val branch = word(image, pc + 4)
            if (branch and 0xff00001f.toInt() != 0x54000003) return@mapNotNull null // B.LO
            val target = pc + 4 + (signExtend(((branch ushr 5) and 0x7ffff).toLong(), 19) shl 2)
            if (target !in log - 64..log || cmp.second <= 0) return@mapNotNull null
            Validation((shiftWord ushr 16) and 31, cmp.second, target)
        }
        return choices.distinct().singleOrNull() ?: fail("原生平台范围判断")
    }

    private fun copies(image: PeImage, flow: A64ValueOrigins, start: Long, log: Long,
                       board: Long, platform: Long): List<Copy> {
        val copies = (start..<log step 4).mapNotNull { pc ->
            val store = memory(word(image, pc))?.takeIf { !it.load && it.size in setOf(4, 8) }
                ?: return@mapNotNull null
            val write = flow.globalAddress(pc, store.base)?.plus(store.offset) ?: fail("平台覆盖字段目标")
            val producer = definition(image, pc, start, store.reg)
            val load = memory(word(image, producer))?.takeIf { it.load && it.size == store.size }
                ?: fail("平台覆盖字段来源")
            val read = flow.globalAddress(producer, load.base)?.plus(load.offset) ?: fail("主板结构字段来源")
            if (read !in board..board + 16 - store.size || write !in platform..platform + 32 - store.size ||
                !image.isWritable(write, store.size)) fail("平台覆盖字段边界")
            Copy(read, write, store.size)
        }
        if (copies.size != 2 || copies.map { it.size }.toSet() != setOf(4, 8) ||
            copies.none { it.size == 4 && it.destRva == platform && it.sourceRva == board })
            fail("原生板号与设备树描述复制")
        return copies
    }

    private fun firstIndirect(image: PeImage, from: Long): Long {
        for (pc in from..from + 56 step 4) {
            val w = word(image, pc)
            if (indirectRegister(w) != null) return pc
            if (transfer(pc, w)) break
        }
        fail("协议调用必须在原生成功分支内")
    }

    private fun statusGate(image: PeImage, call: Long): Long {
        for (pc in call + 4..call + 24 step 4) {
            val w = word(image, pc)
            if (A64.cbzX0(pc, w) != null) return pc
            if (transfer(pc, w) || StraightLineLiveness.mayWriteRegister(w, 0)) break
        }
        fail("原生协议返回值检查")
    }

    private fun zeroSuccess(image: PeImage, gate: Long): Long =
        A64.cbzX0(gate, word(image, gate))?.takeIf { image.isCode(it) } ?: fail("原生协议成功出口")

    private fun definition(image: PeImage, use: Long, start: Long, reg: Int): Long {
        for (pc in use - 4 downTo start step 4) {
            val w = word(image, pc)
            if (transfer(pc, w)) break
            if (StraightLineLiveness.mayWriteRegister(w, reg)) return pc
        }
        fail("原生寄存器的唯一定义")
    }

    private fun transfer(pc: Long, w: Int): Boolean =
        A64.bTarget(pc, w) != null || A64.blTarget(pc, w) != null ||
            w and 0xff000010.toInt() == 0x54000000 ||
            w and 0x7e000000 in setOf(0x34000000, 0x36000000) ||
            w and 0xfe000000.toInt() == 0xd6000000.toInt()

    private fun indirectRegister(w: Int): Int? =
        if (w and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) (w ushr 5) and 31 else null

    private fun memory(w: Int): Mem? {
        val op = w and 0xffc00000.toInt()
        val size = when (op) {
            0xf9400000.toInt(), 0xf9000000.toInt() -> 8
            0xb9400000.toInt(), 0xb9000000.toInt() -> 4
            else -> return null
        }
        return Mem(w and 31, (w ushr 5) and 31, ((w ushr 10) and 0xfff) * size, size,
            w and 0x00400000 != 0)
    }

    private fun word(image: PeImage, pc: Long): Int = image.word(pc) ?: fail("原生指令越界")
    private fun fail(reason: String): Nothing = throw PatchException("Continue 原生板型初始化：$reason 未唯一证明。")
}


