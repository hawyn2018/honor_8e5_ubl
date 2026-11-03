package net.hawyn.ablefisp.patch

/** EFISP-local policy at the semantically proven shared PSS API, never an RSA primitive. */
internal object PssVerificationPatcher {
    private val pacA = 0xd503233f.toInt()
    private val pacB = 0xd503237f.toInt()
    private val autA = 0xd50323bf.toInt()
    private val autB = 0xd50323ff.toInt()
    private val movZero = 0x2a1f03e0
    private val ret = 0xd65f03c0.toInt()
    private val landings = setOf(0xd503245f.toInt(), 0xd503249f.toInt(), 0xd50324df.toInt())

    fun analyze(image: PeImage, commonEntry: Long?): SemanticPlan {
        val entry = commonEntry ?: throw PatchException("状态认证的共用验证入口尚未证明。")
        if (entry and 3L != 0L) throw PatchException("共用验证入口未按 ARM64 指令对齐。")
        val code = image.instructions().toList()
        val sectionEnd = image.sections.firstOrNull {
            entry in it.rva until it.rva + minOf(it.rawSize.toLong(),
                it.virtualSize.takeIf { n -> n > 0 } ?: it.rawSize.toLong())
        }?.let { it.rva + minOf(it.rawSize.toLong(),
            it.virtualSize.takeIf { n -> n > 0 } ?: it.rawSize.toLong()) }
            ?: throw PatchException("共用验证函数边界未完整映射。")
        var patch = entry
        while (patch + 4 <= sectionEnd && image.isCode(patch, 4) && image.word(patch) in landings) patch += 4
        val signed = image.word(patch)
        if (signed == pacA || signed == pacB) patch += 4
        val end = code.firstOrNull { (pc, word) -> pc in patch..<sectionEnd && word in setOf(pacA, pacB) }?.first
            ?: sectionEnd
        if (entry >= end || !image.isCode(entry, 16)) throw PatchException("共用验证入口结构不完整。")
        val words = when (signed) {
            pacA -> listOf(autA, movZero, ret)
            pacB -> listOf(autB, movZero, ret)
            else -> listOf(movZero, ret)
        }
        val alreadyApplied = words.withIndex().all { (index, word) -> image.word(patch + index * 4) == word }
        // Do not borrow evidence from a neighbouring PAC-less leaf function or
        // dead literals after RET. For a previous result, only our exact early
        // return can expose the preserved continuation for recognition.
        val reachable = evidenceNodes(image, entry, end,
            if (alreadyApplied) patch + words.size * 4 else entry, code)
        val references = A64.references(image).filter { it.pc in reachable }
        val texts = references.mapNotNull { ref -> image.ascii(ref.address, 256)?.lowercase() }
        val pssEvidence = texts.any { "pss" in it }
        if (!pssEvidence) return SemanticPlan(emptyList(), listOf(
            "状态认证入口未呈现共用 RSA-PSS 功能证据，未对其他验证算法添加固定成功策略。"))
        val name = texts.any { "sec_rsa_pss_verify" in it }
        val failed = texts.any { "pss" in it && "signature" in it && "fail" in it }
        val success = texts.any { "pss" in it && "signature" in it && "success" in it }
        val invalid = texts.any { "sec_data_pss" in it && "parameter" in it }
        if (!name || !failed || !success || !invalid)
            throw PatchException("共用 PSS 的接口、成功/失败及参数数据流证据不完整，未改写入口。")
        // The caller-side proof already binds two descriptors to one OEMINFO
        // signature buffer and proves zero -> the existing successful state path.
        // Every direct caller retains its BL; body/string references stay available
        // for re-import recognition after the entry is made non-executing.
        val callers = code.filter { (pc, word) -> A64.blTarget(pc, word) == entry }
        if (callers.size < 2) throw PatchException("共用 PSS 验证入口的调用关系不完整。")
        if (patch + words.size * 4 > end || !image.isCode(patch, words.size * 4))
            throw PatchException("共用 PSS 入口没有完整的无栈帧返回空间。")
        // A landing/PAC prefix is the only original code retained before RET.
        // No frame store, allocation, descriptor dereference or logger executes.
        val replacement = ByteArray(words.size * 4)
        words.forEachIndexed { index, word -> replacement.putWord(index * 4, word) }
        return SemanticPlan(listOf(PlannedEdit("共用 RSA-PSS 验证入口固定成功", patch, replacement)), listOf(
            "按 OEMINFO 签名来源、双描述符、共用调用目标和已有零值成功路径定位 PSS API。",
            "已对该 EFISP 共用 PSS 入口固定返回成功，覆盖 ${callers.size} 处直接调用；不是实际验签通过。",
            "保留 BTI/PAC 入口与返回约定，早退前没有栈帧/分配或内存访问；该 API 原验证及其成功/失败日志不再执行。",
            "通用日志、其他 RSA/AVB 验证实现、父 ABL/XBL/TEE 与持久认证记录没有因此修改。",
        ))
    }

    private fun evidenceNodes(image: PeImage, entry: Long, end: Long, start: Long,
                              code: List<Pair<Long, Int>>): Set<Long> {
        val otherEntries = code.mapNotNull { (pc, word) -> A64.blTarget(pc, word) }
            .filter { it != entry }.toSet()
        val pending = ArrayDeque<Long>()
        val visited = HashSet<Long>()
        pending.add(start)
        while (pending.isNotEmpty()) {
            val pc = pending.removeLast()
            if (pc !in entry..<end || pc in otherEntries || !image.isCode(pc, 4) || !visited.add(pc)) continue
            val word = image.word(pc) ?: continue
            val jump = A64.bTarget(pc, word)
            if (jump != null) {
                pending.add(jump)
                continue
            }
            // RET/BR end this path. Calls retain their fall-through; their
            // separate callee bodies never contribute function-local evidence.
            if (word and 0xfffffc1f.toInt() == 0xd65f0000.toInt() ||
                word and 0xfffffc1f.toInt() == 0xd61f0000.toInt() ||
                word == 0xd65f0bff.toInt() || word == 0xd65f0fff.toInt()) continue
            val conditional = when {
                word and 0xff000010.toInt() == 0x54000000 ||
                    word and 0x7e000000 == 0x34000000 ->
                    pc + (signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) shl 2)
                word and 0x7e000000 == 0x36000000 ->
                    pc + (signExtend(((word ushr 5) and 0x3fff).toLong(), 14) shl 2)
                else -> null
            }
            conditional?.let(pending::add)
            pending.add(pc + 4)
        }
        return visited
    }
}
