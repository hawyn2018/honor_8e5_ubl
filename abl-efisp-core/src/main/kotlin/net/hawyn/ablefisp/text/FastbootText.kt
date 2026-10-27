package net.hawyn.ablefisp.text

internal data class CopyTextSite(val source: TextRef, val capacityPc: Long, val countPc: Long, val capacity: Int, val bufferOffset: Int, val drawPc: Long, val phaseStart: Long, val functionStart: Long, val maxCapacity: Int)
internal data class FastbootTextPlan(val entry: TextEntry, val references: List<TextRef>, val copies: List<CopyTextSite>, val widthCalls: List<Long>, val drawCalls: List<Long>)
internal data class FastbootTextContext(val plans: List<FastbootTextPlan>, val refs: List<TextRef>, val fontCharZero: Long?, val lineBudget: Int, val measureRef: TextRef?, val measureCall: Long?, val renderer: Long?, val cursor: Long?, val commonDrawPc: Long?, val notes: List<String>, val frameGlobal: Long? = null, val maxFontHeight: Int = 0) {
    fun glyphWidth(image: TextPe, character: Char): Int = image.data[image.rvaToOffset(checkNotNull(fontCharZero) + character.code * 16L)].toInt() and 255
    fun width(image: TextPe, value: String): Int = value.split('\n').maxOfOrNull { line -> line.sumOf { glyphWidth(image, it) } } ?: 0
    fun wrap(image: TextPe, value: String): String {
        requireText(fontCharZero != null && lineBudget > 0, "尚未确认固件字体与测宽布局")
        val result = mutableListOf<String>()
        for (paragraph in value.split('\n')) {
            var line = StringBuilder()
            var pixels = 0
            for (character in paragraph) {
                val glyph = glyphWidth(image, character)
                requireText(glyph in 1..128 && glyph <= lineBudget, "字形宽度超出固件布局范围")
                if (pixels + glyph > lineBudget && line.isNotEmpty()) {
                    val space = line.lastIndexOf(" ")
                    if (space > 0) {
                        result += line.substring(0, space).trimEnd()
                        line = StringBuilder(line.substring(space + 1).trimStart())
                        pixels = line.sumOf { glyphWidth(image, it) }
                    } else {
                        result += line.toString(); line = StringBuilder(); pixels = 0
                    }
                }
                if (pixels + glyph > lineBudget && line.isNotEmpty()) {
                    result += line.toString(); line = StringBuilder(); pixels = 0
                }
                if (line.isEmpty() && character == ' ' && result.isNotEmpty()) continue
                line.append(character); pixels += glyph
            }
            result += line.toString()
        }
        return result.joinToString("\n")
    }
}

internal object FastbootTextDiscovery {
    fun discover(image: TextPe): FastbootTextContext {
        val refs = TextA64.refs(image)
        val flow by lazy { TextArgumentFlow(image) }
        val strings = image.asciiStrings()
        val roles = listOf("PHONE_UNLOCKED strncpy_s error" to ("fastboot-unlocked" to "Fastboot 解锁标签"), "PHONE_LOCKED strncpy_s error" to ("fastboot-locked" to "Fastboot 锁定标签"), "PHONE_RELOCKED strncpy_s error" to ("fastboot-relocked" to "Fastboot 重新锁定标签"))
        val sites = linkedMapOf<String, Pair<String, CopyTextSite>>()
        for ((log, role) in roles) {
            val anchors = strings.filter { it.second.startsWith(log) }.map { image.offsetToRva(it.first) }
            val errors = refs.filter { it.target in anchors }
            val copies = errors.mapNotNull { error -> refs.asSequence().filter { it.register == 2 && it.pc < error.pc && it.pc >= error.pc - 0x180 }.mapNotNull { copySite(image, it, error.pc, flow) }.maxByOrNull { it.source.pc } }.distinctBy { it.source.pc }
            if (copies.size == 1) sites[role.first] = role.second to copies.single()
        }
        val allCopies = sites.values.map { it.second }
        val representative = sites["fastboot-unlocked"]?.second ?: allCopies.firstOrNull()
        if (representative == null) return FastbootTextContext(emptyList(), refs, null, 0, null, null, null, null, null, listOf("Fastboot 标签复制与绘制路径未形成唯一匹配，相关项目仅展示原文。"))
        val measureCandidates = refs.filter { ref -> ref.register == 0 && ref.pc < representative.phaseStart && ref.pc >= representative.phaseStart - 0x600 }.mapNotNull { ref ->
            val callPc = (ref.secondPc ?: ref.pc) + 4
            val function = TextA64.call(image, callPc) ?: return@mapNotNull null
            val font = identifyFont(image, refs, function) ?: return@mapNotNull null
            Triple(ref, callPc, font)
        }
        val measure = measureCandidates.maxByOrNull { it.first.pc }
        val fontZero = measure?.third?.first
        val widthFunctions = measureCandidates.mapNotNull { TextA64.call(image, it.second) }.toSet()
        val nativeWidth = measure?.second?.let { TextA64.call(image, it) }
        val drawTarget = TextA64.call(image, representative.drawPc)
        val ownedDraw = drawTarget?.let { TextLayoutStubs.readDraw(image, it) }
        val renderer = ownedDraw?.first ?: drawTarget
        val cursor = ownedDraw?.second ?: renderer?.let { identifyCursor(image, refs, it) }
        val rendererEnvironment = renderer?.let { identifyRendererEnvironment(image, it) }
        val knownTitle = strings.firstOrNull { it.second == "FASTBOOT&RESCUE MODE" }
        val titleReference = measureCandidates.filter { it.first.pc < (measure?.first?.pc ?: 0) }.maxByOrNull { it.first.pc }?.first
        val titleFromPrior = titleReference?.target?.let { image.cString(it) }
        val title = knownTitle?.second ?: titleFromPrior
        val storedBudget = measure?.third?.second?.takeIf { it > 0 } ?: measureCandidates.map { it.third.second }.firstOrNull { it > 0 } ?: 0
        val budget = if (storedBudget > 0) storedBudget else if (fontZero != null && title != null) title.sumOf { image.data[image.rvaToOffset(fontZero + it.code * 16L)].toInt() and 255 } else 0
        val layoutReady = fontZero != null && renderer != null && cursor != null && rendererEnvironment != null && budget in 64..8192
        val nativeRenderer = renderer
        val allFunctions = widthFunctions + listOfNotNull(nativeWidth)
        fun widthCall(ref: TextRef): Long? {
            val (pc, target) = referencedCall(image, ref) ?: return null
            return if (target in allFunctions || TextLayoutStubs.readWidth(image, target) != null) pc else null
        }
        fun drawCall(ref: TextRef): Long? {
            val (pc, target) = referencedCall(image, ref) ?: return null
            return if (target == nativeRenderer || TextLayoutStubs.readDraw(image, target)?.first == nativeRenderer) pc else null
        }
        val plans = mutableListOf<FastbootTextPlan>()
        for ((id, roleAndSite) in sites) {
            val (titleValue, site) = roleAndSite
            val actual = image.cString(site.source.target, 1024) ?: continue
            val related = refs.filter { it.target == site.source.target }
            val copyUses = related.mapNotNull { ref -> if (ref.register == 2) copySite(image, ref, ref.pc + 0x100, flow) else null }.distinctBy { it.source.pc }
            val widths = related.mapNotNull(::widthCall)
            val draws = related.mapNotNull(::drawCall)
            val supported = related.all { ref -> copyUses.any { it.source.pc == ref.pc } || (ref.register == 0 && (widthCall(ref) != null || drawCall(ref) != null)) }
            val capacity = copyUses.minOfOrNull { it.maxCapacity } ?: site.maxCapacity
            val editable = layoutReady && supported && capacity >= actual.length + 1
            val reason = when { !layoutReady -> "固件字体／换行／测宽布局尚未完整确认"; !supported -> "存在尚未确认用途的文字引用"; !editable -> "复制缓冲区布局尚未确认"; else -> null }
            plans += FastbootTextPlan(TextEntry(id, titleValue, actual, site.source.target, image.rvaToOffset(site.source.target), (capacity - 1).coerceAtLeast(actual.length), editable, reason), related, copyUses.ifEmpty { listOf(site) }, widths, draws)
        }
        val firstPc = allCopies.minOf { it.source.pc } - 0x1000
        val lastPc = allCopies.maxOf { it.source.pc } + 0x600
        val direct = refs.filter { it.register == 0 && it.pc in firstPc..lastPc && drawCall(it) != null }.groupBy { it.target }
        for ((target, drawingRefs) in direct) {
            if (plans.any { it.entry.rva == target }) continue
            val value = image.cString(target, 1024) ?: continue
            if (value.length > 1023) continue
            val related = refs.filter { it.target == target }
            val supported = related.all { it.register == 0 && (widthCall(it) != null || drawCall(it) != null) }
            val titleValue = when { target == titleReference?.target || value.contains("FASTBOOT", true) -> "Fastboot 标题"; value.contains("USB", true) -> "Fastboot USB 提示"; else -> "Fastboot 显示文字" }
            val editable = layoutReady && supported
            plans += FastbootTextPlan(TextEntry("fastboot-direct-${drawingRefs.minOf { it.pc }.toString(16)}", titleValue, value, target, image.rvaToOffset(target), maxOf(255, value.length), editable, if (editable) null else "此常量存在未确认引用，暂保留原文"), related, emptyList(), related.mapNotNull(::widthCall), related.mapNotNull(::drawCall))
        }
        val notes = mutableListOf<String>()
        if (layoutReady) notes += "已从输入镜像识别实际字体，Fastboot 分行预算为原标题宽度 $budget 像素；保留原面板尺寸检查。"
        if (allCopies.any { it.maxCapacity > it.capacity }) notes += "已通过同函数临时区初始化及活跃栈引用检查，支持扩容 Fastboot 状态标签缓冲区。"
        if (!layoutReady) notes += "Fastboot 布局证据不足，相关文字列为只读；开机资源文字仍可独立编辑。"
        return FastbootTextContext(plans, refs, fontZero, budget, measure?.first, measure?.second, renderer, cursor, representative.drawPc, notes, rendererEnvironment?.first, rendererEnvironment?.second ?: 0)
    }

    private fun referencedCall(image: TextPe, reference: TextRef): Pair<Long, Long>? = runCatching {
        var pc = (reference.secondPc ?: reference.pc) + 4
        repeat(32) {
            TextA64.call(image, pc)?.let { return@runCatching pc to it }
            val word = image.word(pc).toLong() and 0xffff_ffffL
            // Follow only straight-line argument preparation while X0 remains the string.
            if (word and 0xfe000000L == 0xd6000000L || word and 0x7c000000L == 0x14000000L || word and 0xff000010L == 0x54000000L || word and 0x7e000000L == 0x34000000L || word and 0x7e000000L == 0x36000000L) return@runCatching null
            if (writesArgumentZero(word)) return@runCatching null
            pc += 4
        }
        null
    }.getOrNull()

    private fun writesArgumentZero(word: Long): Boolean {
        val destination = word and 31
        if (word and 0x04400000L == 0x00400000L && word and 0x3a000000L == 0x28000000L && (word ushr 10) and 31 == 0L) return true
        if (destination != 0L) return false
        if (word and 0x3b000000L == 0x18000000L && word and 0x04000000L == 0L) return true
        if ((word and 0x9f000000L) in listOf(0x10000000L, 0x90000000L)) return true
        if ((word and 0x1f000000L) in listOf(0x11000000L, 0x0b000000L, 0x0a000000L, 0x1a000000L)) return true
        if (word and 0x1f800000L == 0x12800000L) return true
        if (word and 0x04000000L == 0L && word and 0x00400000L != 0L && (word and 0x3a000000L == 0x28000000L || (word and 0x3b000000L) in listOf(0x39000000L, 0x38000000L))) return true
        if ((word and 0xff3ffc00L) in listOf(0x1e380000L, 0x1e390000L, 0x9e380000L, 0x9e390000L)) return true
        return false
    }

    private fun copySite(image: TextPe, ref: TextRef, errorPc: Long, flow: TextArgumentFlow): CopyTextSite? = runCatching {
        if (ref.register != 2 || ref.pc >= errorPc) return@runCatching null
        val end = ref.secondPc ?: ref.pc
        val capacityPc = if (TextA64.movImmediate(image.word(ref.pc - 4), 1) != null) ref.pc - 4 else flow.definition(ref.pc, 1) ?: return@runCatching null
        val destinationPc = if (TextA64.stackAddress(image.word(ref.pc - 8), 0) != null && capacityPc == ref.pc - 4) ref.pc - 8 else flow.definition(ref.pc, 0) ?: return@runCatching null
        val countPc = end + 4; val callPc = end + 8
        val capacity = TextA64.movImmediate(image.word(capacityPc), 1) ?: return@runCatching null
        val buffer = TextA64.stackAddress(image.word(destinationPc), 0) ?: return@runCatching null
        val count = TextA64.movImmediate(image.word(countPc), 3) ?: return@runCatching null
        if (TextA64.call(image, callPc) == null || capacity !in 2..1024 || count !in 1..capacity) return@runCatching null
        val text = image.cString(ref.target, capacity) ?: return@runCatching null
        if (text.length + 1 > count) return@runCatching null
        val cbz = image.word(callPc + 4).toLong() and 0xffff_ffffL
        if (cbz and 0xff00001fL != 0xb4000000L) return@runCatching null
        val success = callPc + 4 + (sxText((cbz ushr 5) and 0x7ffff, 19) shl 2)
        val drawPc = (0..32).asSequence().map { success + it * 4 }.firstOrNull { pc -> TextA64.stackAddress(image.word(pc), 0) == buffer && TextA64.call(image, pc + 4) != null }?.plus(4) ?: return@runCatching null
        val phase = (1..512).asSequence().map { ref.pc - it * 4 }.takeWhile { it >= 4 }.firstOrNull { pc ->
            val access = stackMemory(image.word(pc)); access != null && access.first == buffer && access.second == 32 && image.word(pc - 4) == 0x6f00e400
        } ?: destinationPc
        val function = (0..16384).asSequence().map { phase - it * 4 }.takeWhile { it >= 0 }.firstOrNull { image.word(it) == 0xd503233f.toInt() } ?: phase
        val max = proveCapacity(image, function, phase, drawPc, buffer, capacity)
        CopyTextSite(ref, capacityPc, countPc, capacity, buffer, drawPc, phase, function, max)
    }.getOrNull()

    private fun proveCapacity(image: TextPe, start: Long, phase: Long, end: Long, buffer: Int, existing: Int): Int {
        if (existing >= 256 || phase >= end || phase <= start) return existing
        var clearSize = existing
        var at = start
        while (at < phase) {
            if (TextA64.stackAddress(image.word(at), 0) == buffer) {
                var zero = false; var size = 0; var called = false
                for (i in 1..6) {
                    val w = image.word(at + i * 4)
                    if (w == 0x2a1f03e1 || TextA64.movImmediate(w, 1) == 0) zero = true
                    TextA64.movImmediate(w, 2)?.let { size = it }
                    if (TextA64.call(image, at + i * 4) != null) { called = true; break }
                }
                if (zero && called && size in 64..256) clearSize = maxOf(clearSize, size)
            }
            at += 4
        }
        if (clearSize <= existing) return existing
        at = phase
        while (at <= end + 4) {
            val w = image.word(at)
            val access = stackMemory(w)
            if (access != null && access.first < buffer + clearSize && access.first + access.second > buffer + existing) return existing
            for (r in 0..30) {
                val address = TextA64.stackAddress(w, r)
                if (address != null && address > buffer && address < buffer + clearSize) return existing
            }
            at += 4
        }
        return clearSize
    }
    private fun stackMemory(word: Int): Pair<Int, Int>? {
        val w = word.toLong() and 0xffff_ffffL
        if ((w ushr 5) and 31 != 31L) return null
        if (w and 0x3a000000L == 0x28000000L) {
            val opc = ((w ushr 30) and 3).toInt(); val vector = w and 0x04000000L != 0L
            val size = if (vector) 4 shl opc else if (opc == 2) 8 else 4
            return (sxText((w ushr 15) and 127, 7).toInt() * size) to size * 2
        }
        if (w and 0x3b000000L == 0x39000000L) {
            val size = if (w and 0x04800000L == 0x04800000L) 16 else 1 shl ((w ushr 30) and 3).toInt()
            return (((w ushr 10) and 0xfff).toInt() * size) to size
        }
        if (w and 0x3b200000L == 0x38000000L) {
            val size = 1 shl ((w ushr 30) and 3).toInt()
            return sxText((w ushr 12) and 511, 9).toInt() to size
        }
        return null
    }
    private fun identifyFont(image: TextPe, refs: List<TextRef>, function: Long): Pair<Long, Int>? {
        TextLayoutStubs.readWidth(image, function)?.let { (font, budget) -> if (validFont(image, font)) return font to budget }
        val end = functionEnd(image, function, 0x100)
        val candidates = refs.filter { it.pc in function until end }.map { it.target - 512 }.filter { validFont(image, it) }.distinct()
        return candidates.singleOrNull()?.let { it to 0 }
    }
    private fun validFont(image: TextPe, zero: Long): Boolean = runCatching {
        val widths = (32..126).map { image.data[image.rvaToOffset(zero + it * 16L)].toInt() and 255 }
        widths.all { it in 1..128 } && widths.distinct().size >= 6
    }.getOrDefault(false)

    private fun functionEnd(image: TextPe, start: Long, maximum: Int): Long {
        for (offset in 4 until maximum step 4) {
            val word = image.word(start + offset)
            if (word == 0xd65f03c0.toInt()) return start + offset + 4
            if (word == 0xd503233f.toInt()) return start + offset
        }
        return start + maximum
    }

    private fun identifyRendererEnvironment(image: TextPe, renderer: Long): Pair<Long, Int>? = runCatching {
        val framePointers = mutableListOf<Long>()
        val heights = mutableListOf<Int>()
        for (offset in 0 until 0x100 step 4) {
            val pc = renderer + offset
            val word = image.word(pc).toLong() and 0xffff_ffffL
            for (register in 0..30) TextA64.movImmediate(word.toInt(), register)?.let { if (it in 16..128) heights += it }
            if (word and 0x9f000000L != 0x90000000L) continue
            val register = (word and 31).toInt()
            val load = image.word(pc + 4).toLong() and 0xffff_ffffL
            val branch = image.word(pc + 8).toLong() and 0xffff_ffffL
            if (load and 0xffc003ffL != (0xf9400000L or (register.toLong() shl 5) or register.toLong())) continue
            if (branch and 0xff00001fL != (0xb4000000L or register.toLong())) continue
            val page = (pc and -4096L) + (sxText((((word ushr 5) and 0x7ffff) shl 2) or ((word ushr 29) and 3), 21) shl 12)
            framePointers += page + (((load ushr 10) and 0xfff) shl 3)
        }
        val frame = framePointers.distinct().singleOrNull() ?: return@runCatching null
        val height = heights.maxOrNull() ?: return@runCatching null
        image.rvaToOffset(frame, 8)
        frame to height
    }.getOrNull()

    private fun identifyCursor(image: TextPe, refs: List<TextRef>, renderer: Long): Long? {
        val end = functionEnd(image, renderer, 0x800)
        val candidates = refs.filter { it.pc in renderer until end }.filter { ref ->
            (1..5).any { delta ->
                val pc = (ref.secondPc ?: ref.pc) + delta * 4
                val w = image.word(pc).toLong() and 0xffff_ffffL
                w and 0xffc0001fL == 0x2900001fL && ((w ushr 5) and 31).toInt() == ref.register && ((w ushr 15) and 127) == 0L
            }
        }.map { it.target }.distinct()
        return candidates.singleOrNull()
    }
}
