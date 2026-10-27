package net.hawyn.ablefisp.patch

/** Delivery-time evidence only. This file is in src/test and is excluded from the APK. */
data class VerificationCheck(val name: String, val passed: Boolean, val detail: String)

data class VerificationResult(val checks: List<VerificationCheck>) {
    val allPassed: Boolean get() = checks.isNotEmpty() && checks.all { it.passed }
    val summary: String get() = if (allPassed) "静态结构与功能参考检查 ${checks.size}/${checks.size} 通过"
        else "${checks.count { !it.passed }} 项检查未通过：${checks.filterNot { it.passed }.joinToString { it.name }}"
}

/** Checks local PE structure and the recognized initialization path. No hardware boot,
 * firmware-chain authentication, or device execution is claimed by this verifier. */
object OutputVerifier {
    private fun freedomPlan(image: PeImage): List<PlannedEdit> {
        val recovery = BootFailurePolicyPatcher.analyze(image)
        val pending = recovery.filter { item ->
            val stub = item.existingStubRva
            stub == null || image.sections.none { section ->
                val length = minOf(section.rawSize.toLong(), section.virtualSize.takeIf { it > 0 } ?: section.rawSize.toLong())
                section.executable && !section.writable && section.characteristics and 0x02000000L == 0L &&
                    item.callRva >= section.rva && item.callRva + 4 <= section.rva + length &&
                    stub >= section.rva && stub + BootFailurePolicyPatcher.STUB_BYTES <= section.rva + length
            }
        }
        val slots = MappedRxCodeAllocator.allocate(image, pending.map {
            MappedRxCodeAllocator.Request(it.callRva, BootFailurePolicyPatcher.STUB_BYTES)
        })
        val destinations = pending.zip(slots).associate { (item, slot) -> item.callRva to slot.rva }
        return SemanticPatcher.plan(image) + recovery.flatMap {
            BootFailurePolicyPatcher.edits(it, destinations[it.callRva] ?: requireNotNull(it.existingStubRva))
        } + RollbackUpdatePatcher.analyze(image).edits
    }

    fun verifyCore(sourcePe: ByteArray, outputPe: ByteArray, patches: List<PatchMatch>): VerificationResult {
        val checks = ArrayList<VerificationCheck>()
        val pair = parsePair(sourcePe, outputPe, checks) ?: return VerificationResult(checks)
        val (source, output) = pair
        basicChecks(source, output, checks)
        checks += check("核心修补保持文件大小", source.bytes.size == output.bytes.size,
            "source=${source.bytes.size}, output=${output.bytes.size}")
        checks += check("核心修补保持 PE 头与节表", source.bytes.copyOfRange(0, source.headersSize)
            .contentEquals(output.bytes.copyOfRange(0, minOf(output.headersSize, output.bytes.size))), "核心修补只改已定位代码和分区名称。")
        try {
            val expected = freedomPlan(source)
            val finalPlan = freedomPlan(output)
            val finalTargets = SemanticPatcher.plan(source).size in 3..4 && finalPlan.size == expected.size && finalPlan.all { edit ->
                output.rvaToOffset(edit.rva, edit.replacement.size)?.let { output.bytes.matches(it, edit.replacement) } == true
            }
            checks += check("核心分支、加载目标与条件恢复均为最终状态", finalTargets,
                finalPlan.joinToString { "${it.name}@0x${it.rva.toString(16)}" })
            val sourceTargets = expected.map { it.name to it.rva }
            checks += check("功能目标身份保持", finalPlan.map { it.name to it.rva } == sourceTargets,
                "读取记录、共享验签调用、成功状态写入、日志循环和分区加载链重新匹配。")
            val allowed = HashSet<Int>()
            expected.forEach { edit ->
                val offset = source.rvaToOffset(edit.rva, edit.replacement.size) ?: error("Unmapped planned edit")
                repeat(edit.replacement.size) { allowed += offset + it }
            }
            var outside = 0
            var changed = 0
            for (offset in 0 until minOf(source.bytes.size, output.bytes.size)) {
                if (source.bytes[offset] != output.bytes[offset]) {
                    changed++
                    if (offset !in allowed) outside++
                }
            }
            checks += check("字节差异严格限制在${expected.size}个目标", source.bytes.size == output.bytes.size && outside == 0,
                "targets=${expected.size}, changed=$changed bytes, outside_allowlist=$outside bytes")
            val reportConsistent = patches.size == expected.size && patches.zip(expected).all { (reported, planned) ->
                val offset = source.rvaToOffset(planned.rva, planned.replacement.size) ?: return@all false
                reported.name == planned.name && reported.rva == planned.rva && reported.fileOffset == offset &&
                    reported.before == source.bytes.copyOfRange(offset, offset + planned.replacement.size).hex() &&
                    reported.after == planned.replacement.hex() &&
                    reported.alreadyApplied == source.bytes.matches(offset, planned.replacement)
            }
            checks += check("修补报告与实际字节对应", reportConsistent, "逐项核对原字节、目标字节、RVA、文件偏移和已应用标志。")
        } catch (error: Exception) {
            checks += check("核心功能参考路径", false, error.message ?: error.javaClass.simpleName)
        }
        return VerificationResult(checks)
    }

    /** Use the immediately preceding working PE as source for a text-edit step. */
    fun verifyStructure(sourcePe: ByteArray, outputPe: ByteArray): VerificationResult {
        val checks = ArrayList<VerificationCheck>()
        val pair = parsePair(sourcePe, outputPe, checks) ?: return VerificationResult(checks)
        val (source, output) = pair
        basicChecks(source, output, checks)
        try {
            val before = SemanticPatcher.plan(source)
            val after = SemanticPatcher.plan(output)
            val identities = before.map { it.name to it.rva } == after.map { it.name to it.rva }
            val states = identities && before.all { edit ->
                val oldOffset = source.rvaToOffset(edit.rva, edit.replacement.size) ?: return@all false
                val newOffset = output.rvaToOffset(edit.rva, edit.replacement.size) ?: return@all false
                source.bytes.copyOfRange(oldOffset, oldOffset + edit.replacement.size)
                    .contentEquals(output.bytes.copyOfRange(newOffset, newOffset + edit.replacement.size))
            }
            checks += check("既有核心分支与加载目标保持", states,
                "重新识别完整功能路径，并比较文字修改前后的三个分支与分区名称。")
        } catch (error: Exception) {
            checks += check("既有核心功能参考路径", false, error.message ?: error.javaClass.simpleName)
        }
        return VerificationResult(checks)
    }

    private data class Header(
        val pe: Int, val optional: Int, val sectionTable: Int,
        val sectionAlignment: Long, val fileAlignment: Long, val imageSize: Long,
        val subsystem: Int, val relocationRva: Long, val relocationSize: Int,
    )
    private fun header(image: PeImage): Header {
        val b = image.bytes
        val pe = b.u32(0x3c).asBoundedInt("PE header")
        val optional = pe + 24
        val dirs = b.u32(optional + 108)
        return Header(pe, optional, optional + b.u16(pe + 20), b.u32(optional + 32), b.u32(optional + 36),
            b.u32(optional + 56), b.u16(optional + 68),
            if (dirs > 5) b.u32(optional + 152) else 0,
            if (dirs > 5) b.u32(optional + 156).asBoundedInt("relocation size") else 0)
    }

    private fun parsePair(source: ByteArray, output: ByteArray, checks: MutableList<VerificationCheck>): Pair<PeImage, PeImage>? {
        return try {
            val a = PeImage.parse(source)
            val b = PeImage.parse(output)
            checks += check("ARM64 PE32+ 结构解析", true, "源与输出均通过文件头、节边界、重叠和入口映射解析。")
            a to b
        } catch (error: Exception) {
            checks += check("ARM64 PE32+ 结构解析", false, error.message ?: error.javaClass.simpleName)
            null
        }
    }

    private fun basicChecks(source: PeImage, output: PeImage, checks: MutableList<VerificationCheck>) {
        val a = header(source); val b = header(output)
        checks += check("入口 RVA 与入口指令保持", source.entryRva == output.entryRva && output.isCode(output.entryRva) &&
            source.word(source.entryRva) == output.word(output.entryRva), "entry=0x${output.entryRva.toString(16)}，映射至可执行节。")
        checks += check("加载基址与 UEFI 子系统保持", source.imageBase == output.imageBase && a.subsystem == b.subsystem,
            "ImageBase=0x${output.imageBase.toString(16)}, Subsystem=${b.subsystem}")
        val preserved = source.sections.all { old ->
            val matches = output.sections.filter { it.rva == old.rva }
            matches.size == 1 && matches.single().let { fresh ->
                fresh.name == old.name && fresh.characteristics == old.characteristics &&
                    fresh.virtualSize >= old.virtualSize && fresh.rawSize >= old.rawSize
            }
        }
        checks += check("原有节 RVA、权限与内容容量保持", preserved,
            "源 ${source.sections.size} 节，输出 ${output.sections.size} 节；允许在原有映射之外新增节。")
        val alignment = isPowerOfTwo(b.sectionAlignment) && isPowerOfTwo(b.fileAlignment) &&
            b.sectionAlignment >= b.fileAlignment && (b.sectionAlignment >= 4096 || b.sectionAlignment == b.fileAlignment) &&
            a.sectionAlignment == b.sectionAlignment && a.fileAlignment == b.fileAlignment &&
            output.headersSize.toLong() % b.fileAlignment == 0L && output.sections.all {
                it.rva % b.sectionAlignment == 0L &&
                    (it.rawSize == 0 || (it.raw.toLong() % b.fileAlignment == 0L && it.rawSize.toLong() % b.fileAlignment == 0L))
            }
        checks += check("PE 节和文件对齐", alignment,
            "SectionAlignment=0x${b.sectionAlignment.toString(16)}, FileAlignment=0x${b.fileAlignment.toString(16)}")
        val virtualEnd = maxOf(output.headersSize.toLong(), output.sections.maxOfOrNull { it.rva + maxOf(it.virtualSize, it.rawSize.toLong()) } ?: 0L)
        val expectedImageSize = if (b.sectionAlignment > 0) ((virtualEnd + b.sectionAlignment - 1) / b.sectionAlignment) * b.sectionAlignment else -1
        checks += check("SizeOfImage 覆盖完整加载映射", b.imageSize == expectedImageSize,
            "declared=0x${b.imageSize.toString(16)}, expected=0x${expectedImageSize.toString(16)}")
        val added = output.sections.filter { candidate -> source.sections.none { it.rva == candidate.rva } }
        checks += check("新增节映射及可执行权限", added.all {
            it.characteristics and 0x40000000L != 0L && (!it.executable || it.characteristics and 0x40000000L != 0L) &&
                it.rawSize > 0 && output.rvaToOffset(it.rva, it.rawSize) == it.raw
        }, if (added.isEmpty()) "无新增节。" else added.joinToString { "${it.name}: RVA=0x${it.rva.toString(16)}, flags=0x${it.characteristics.toString(16)}" })
        var relocationEqual = a.relocationRva == b.relocationRva && a.relocationSize == b.relocationSize
        if (relocationEqual && a.relocationSize > 0) {
            val before = source.rvaToOffset(a.relocationRva, a.relocationSize)
            val after = output.rvaToOffset(b.relocationRva, b.relocationSize)
            relocationEqual = before != null && after != null && source.bytes.copyOfRange(before, before + a.relocationSize)
                .contentEquals(output.bytes.copyOfRange(after, after + b.relocationSize))
        }
        for (old in source.sections.filter { it.name == ".reloc" }) {
            val fresh = output.sections.singleOrNull { it.rva == old.rva }
            if (fresh == null || fresh.rawSize != old.rawSize || !source.bytes.copyOfRange(old.raw, old.raw + old.rawSize)
                    .contentEquals(output.bytes.copyOfRange(fresh.raw, fresh.raw + fresh.rawSize))) relocationEqual = false
        }
        checks += check("原重定位目录及重定位数据保持", relocationEqual,
            "reloc RVA=0x${b.relocationRva.toString(16)}, size=0x${b.relocationSize.toString(16)}")
        var changedBranches = 0
        val invalidBranches = ArrayList<String>()
        fun inspectBranch(pc: Long, word: Int) {
            val destination = A64.blTarget(pc, word) ?: A64.bTarget(pc, word) ?: return
            changedBranches++
            if (!output.isCode(destination)) invalidBranches += "0x${pc.toString(16)} -> 0x${destination.toString(16)}"
        }
        for ((pc, oldWord) in source.instructions()) {
            val fresh = output.word(pc) ?: continue
            if (oldWord != fresh) inspectBranch(pc, fresh)
        }
        for (section in added.filter { it.executable }) {
            var pc = section.rva
            while (pc + 4 <= section.rva + section.rawSize) {
                output.word(pc)?.let { inspectBranch(pc, it) }
                pc += 4
            }
        }
        checks += check("新增及改写分支目标映射", invalidBranches.isEmpty(),
            "检查 $changedBranches 条 B/BL；${if (invalidBranches.isEmpty()) "目标均在可执行映射内。" else invalidBranches.joinToString()}")
    }

    private fun isPowerOfTwo(value: Long): Boolean = value > 0 && value and (value - 1) == 0L
    private fun check(name: String, passed: Boolean, detail: String) = VerificationCheck(name, passed, detail)
}
