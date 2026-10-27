package net.hawyn.ablefisp.text

/** Offline display editor. Every runtime layout decision comes from the input PE itself. */
object TextEngine {
    fun scan(pe: ByteArray): TextScanResult {
        val image = TextPe(pe)
        val fastboot = FastbootTextDiscovery.discover(image)
        val entries = resourceEntries(image).map { it.entry }.toMutableList()
        entries += fastboot.plans.map { it.entry }
        val known = listOf("FASTBOOT&RESCUE MODE" to "Fastboot 标题", "Please Connect USB Cable" to "Fastboot USB 提示", "PHONE Locked" to "Fastboot 锁定标签", "PHONE Relocked" to "Fastboot 重新锁定标签", "PHONE Unlocked" to "Fastboot 解锁标签", "     Str123   " to "Fastboot 解锁标签")
        for ((needle, title) in known) {
            for ((offset, text) in image.asciiStrings().filter { it.second.contains(needle) && it.second.length < 256 && !it.second.contains("strncpy_s") }) {
                if (entries.none { it.fileOffset == offset } && fastboot.refs.any { it.target == image.offsetToRva(offset) }) entries += TextEntry("display-${image.offsetToRva(offset).toString(16)}", title, text, image.offsetToRva(offset), offset, text.length, false, "此常量没有形成唯一的复制／绘制引用，保留原文供查看")
            }
        }
        val notes = fastboot.notes + listOf("菜单资源每项最多 255 字节，保留字体、颜色与按键布局元数据。", "支持可打印 ASCII 与换行。Fastboot 长文案按固件字体自动分行，换行符计入缓冲区长度。")
        return TextScanResult(entries.sortedBy { it.fileOffset }, notes)
    }

    fun apply(pe: ByteArray, edits: Map<String, String>): TextPatchResult =
        apply(pe, edits, autoWrapFastboot = true)

    fun apply(pe: ByteArray, edits: Map<String, String>, autoWrapFastboot: Boolean): TextPatchResult {
        val image = TextPe(pe)
        val resources = resourceEntries(image).associateBy { it.entry.id }
        val fastboot = FastbootTextDiscovery.discover(image)
        val fastPlans = fastboot.plans.associateBy { it.entry.id }
        val allEntries = scan(pe).entries.associateBy { it.id }
        val wanted = linkedMapOf<String, String>()
        for ((id, value) in edits) {
            val entry = allEntries[id] ?: throw TextEditException("文本项目已变化，请重新扫描")
            val normalized = normalize(value)
            if (normalized == entry.text) continue
            requireText(entry.editable, entry.reason ?: "此项目当前只读")
            requireText(normalized.length <= entry.maxBytes, "${entry.title} 超过 ${entry.maxBytes} 字节")
            wanted[id] = normalized
        }
        if (wanted.isEmpty()) return TextPatchResult(pe.copyOf(), emptyList(), listOf("文字保持原样"))
        val allocator = TextAllocator(pe, fastboot.refs)
        val changes = mutableListOf<TextChange>()
        val notes = mutableListOf<String>()
        for ((id, value) in wanted.filterKeys { it in resources }) {
            val entry = resources.getValue(id).entry
            val bytes = value.toByteArray(Charsets.US_ASCII)
            allocator.data.fill(0, entry.fileOffset, entry.fileOffset + 256)
            bytes.copyInto(allocator.data, entry.fileOffset)
            changes += TextChange(id, entry.title, entry.text, value, entry.rva, entry.rva, entry.fileOffset)
        }
        val fastWanted = wanted.filterKeys { it in fastPlans }.toMutableMap()
        if (fastWanted.isNotEmpty()) {
            requireText(fastboot.fontCharZero != null && fastboot.renderer != null && fastboot.cursor != null && fastboot.lineBudget > 0 && fastboot.frameGlobal != null && fastboot.maxFontHeight > 0, "固件字体与布局尚未完整确认")
            // The original screen measures one status string for all lock states. Keep the
            // common width representative at least as wide as every displayed status.
            val copied = fastboot.plans.filter { it.copies.isNotEmpty() }
            if (fastWanted.keys.any { fastPlans.getValue(it).copies.isNotEmpty() }) {
                for (plan in copied) {
                    val original = fastWanted[plan.entry.id] ?: plan.entry.text
                    val wrapped = if (autoWrapFastboot) fastboot.wrap(image, original) else original
                    requireText(wrapped.length <= plan.entry.maxBytes, "${plan.entry.title} 布局后为 ${wrapped.length} 字节，上限 ${plan.entry.maxBytes} 字节，请减少文字")
                    if (wrapped != plan.entry.text || plan.entry.id in fastWanted) fastWanted[plan.entry.id] = wrapped
                }
            }
            for ((id, value) in fastWanted.toMap()) {
                val wrapped = if (autoWrapFastboot) fastboot.wrap(image, value) else value
                val plan = fastPlans.getValue(id)
                requireText(wrapped.length <= plan.entry.maxBytes, "${plan.entry.title} 布局后为 ${wrapped.length} 字节，上限 ${plan.entry.maxBytes} 字节，请减少文字")
                fastWanted[id] = wrapped
            }
            val widthArea = allocator.reserve(TextLayoutStubs.WIDTH_BYTES)
            val widthPayload = TextLayoutStubs.widthPayload(widthArea.rva, checkNotNull(fastboot.fontCharZero), fastboot.lineBudget)
            widthPayload.copyInto(allocator.data, widthArea.offset)
            val widthFunction = widthArea.rva + 16
            val hasMultiline = fastWanted.values.any { '\n' in it } || copied.any { '\n' in (fastWanted[it.entry.id] ?: it.entry.text) }
            var drawFunction: Long? = null
            if (hasMultiline) {
                val preliminary = TextLayoutStubs.drawPayload(0x1000, checkNotNull(fastboot.renderer), checkNotNull(fastboot.cursor), checkNotNull(fastboot.frameGlobal), checkNotNull(fastboot.fontCharZero), fastboot.maxFontHeight)
                val drawArea = allocator.reserve(preliminary.size)
                TextLayoutStubs.drawPayload(drawArea.rva, checkNotNull(fastboot.renderer), checkNotNull(fastboot.cursor), checkNotNull(fastboot.frameGlobal), checkNotNull(fastboot.fontCharZero), fastboot.maxFontHeight).copyInto(allocator.data, drawArea.offset)
                drawFunction = drawArea.rva + 32
            }
            val stored = mutableMapOf<String, TextStorage>()
            val copyCapacities = mutableMapOf<Long, Int>()
            for ((id, value) in fastWanted) {
                val plan = fastPlans.getValue(id)
                requireText(plan.entry.editable, plan.entry.reason ?: "文字引用校验失败")
                val bytes = value.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
                val storage = allocator.allocate(bytes)
                stored[id] = storage
                for (ref in plan.references) TextA64.redirect(allocator.data, image, ref, storage.rva)
                for (copy in plan.copies) {
                    requireText(bytes.size <= copy.maxCapacity, "Fastboot 缓冲区容量验证失败")
                    val capacity = maxOf(copy.capacity, bytes.size, copyCapacities[copy.capacityPc] ?: 0)
                    copyCapacities[copy.capacityPc] = capacity
                    put32(allocator.data, image.rvaToOffset(copy.countPc, 4), TextA64.movW(3, bytes.size).toLong())
                }
                for (call in plan.widthCalls) put32(allocator.data, image.rvaToOffset(call, 4), TextA64.branch(call, widthFunction, true).toLong())
                if (drawFunction != null) for (call in plan.drawCalls) put32(allocator.data, image.rvaToOffset(call, 4), TextA64.branch(call, drawFunction, true).toLong())
                if (value != plan.entry.text) changes += TextChange(id, plan.entry.title, plan.entry.text, value, plan.entry.rva, storage.rva, storage.offset)
            }
            for ((pc, capacity) in copyCapacities) put32(allocator.data, image.rvaToOffset(pc, 4), TextA64.movW(1, capacity).toLong())
            val copiedLayout = TextPe(allocator.data)
            for ((id, value) in fastWanted) {
                val required = value.toByteArray(Charsets.US_ASCII).size + 1
                for (copy in fastPlans.getValue(id).copies) {
                    val capacity = TextA64.movImmediate(copiedLayout.word(copy.capacityPc), 1)
                    val count = TextA64.movImmediate(copiedLayout.word(copy.countPc), 3)
                    requireText(count == required && capacity != null && capacity >= required && capacity <= copy.maxCapacity,
                        "Fastboot 复制长度与缓冲区容量回读不一致")
                }
            }
            if (copied.any { it.entry.id in fastWanted }) {
                val widest = copied.maxByOrNull { fastboot.width(image, fastWanted[it.entry.id] ?: it.entry.text) }
                requireText(widest != null && fastboot.measureRef != null && fastboot.measureCall != null, "共用状态测宽引用未确认")
                val value = fastWanted[widest!!.entry.id] ?: widest.entry.text
                val address = stored[widest.entry.id]?.rva ?: allocator.allocate(value.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)).rva
                TextA64.redirect(allocator.data, image, checkNotNull(fastboot.measureRef), address)
                val call = checkNotNull(fastboot.measureCall)
                put32(allocator.data, image.rvaToOffset(call, 4), TextA64.branch(call, widthFunction, true).toLong())
                if (drawFunction != null) {
                    val draw = checkNotNull(fastboot.commonDrawPc)
                    put32(allocator.data, image.rvaToOffset(draw, 4), TextA64.branch(draw, drawFunction, true).toLong())
                }
            }
            notes += if (autoWrapFastboot)
                "Fastboot 文案按输入固件原标题宽度 ${fastboot.lineBudget} 像素分行；测宽改为最长行，保留原面板尺寸门槛。"
            else "Fastboot 文案保留输入分行，不插入自动换行；按实际完整行宽测量，保留原面板尺寸门槛。"
            if (hasMultiline) notes += "换行后保持同一文本块起点，每个字形在绘制前按输入固件实际帧缓冲宽高夹住坐标，并继续使用原绘制与纵向滚动处理。"
            notes += "已同步复制长度、所需缓冲区容量及所有已确认的文字引用。"
        }
        val verified = TextPe(allocator.data)
        for (resource in resources.values) requireText(pe.copyOfRange(resource.entry.fileOffset + 256, resource.entry.fileOffset + 280).contentEquals(allocator.data.copyOfRange(resource.entry.fileOffset + 256, resource.entry.fileOffset + 280)), "菜单布局元数据校验失败")
        for (change in changes) requireText(verified.cString(change.newRva, maxOf(1024, change.newText.length + 1)) == change.newText, "输出文字读回校验失败")
        return TextPatchResult(allocator.data, changes, notes + "文字及长度参数已应用。")
    }

    internal fun normalize(value: String): String {
        val text = value.replace("\r\n", "\n").replace('\r', '\n')
        requireText(text.all { it == '\n' || it.code in 32..126 }, "当前固件字体编辑支持 ASCII 字符和换行，请移除中文、制表符或其他控制字符")
        return text
    }

    private data class ResourcePlan(val entry: TextEntry)
    private fun resourceEntries(image: TextPe): List<ResourcePlan> {
        val candidates = linkedMapOf<Int, String>()
        fun metadata(offset: Int): Boolean {
            if (offset < 0 || offset + 280 > image.data.size) return false
            return image.u32(offset + 256) in 0..2 && image.u32(offset + 260) <= 16 && image.u32(offset + 264) == 1L && image.u32(offset + 268) <= 16 && image.u32(offset + 272) <= 0x10000 && image.u32(offset + 276) == 9L
        }
        for (section in image.sections.filter { it.name == ".data" || it.flags and 0x80000000L != 0L }) {
            var at = section.raw
            while (at + 280 <= section.raw + section.size) {
                if (metadata(at) && (metadata(at - 280) || metadata(at + 280))) {
                    val end = (at until at + 256).firstOrNull { image.data[it] == 0.toByte() }
                    if (end != null && (at until end).all { TextPe.visible(image.data[it].toInt() and 255) } && (end until at + 256).all { image.data[it] == 0.toByte() }) {
                        val text = image.data.copyOfRange(at, end).toString(Charsets.US_ASCII)
                        candidates[at] = text
                    }
                }
                at += 4
            }
        }
        var unlocked = 0
        return candidates.map { (at, text) ->
            val before = candidates[at - 280].orEmpty()
            val after = candidates[at + 280].orEmpty()
            val title = when {
                text.contains("has been unlocked", true) || (before.contains("<!>") && after.contains("Press Power", true)) -> "开机解锁提示 ${++unlocked}"
                text.contains("Press Power", true) || text.contains("continue to boot", true) -> "开机操作提示"
                text.contains("<!>") -> "开机警告标志"
                text.contains("unlocking the bootloader", true) -> "解锁确认说明"
                text.contains("Your device", true) -> "开机状态提示"
                text.isBlank() -> "空白菜单资源"
                else -> "开机菜单文字"
            }
            val rva = image.offsetToRva(at)
            ResourcePlan(TextEntry("resource-${rva.toString(16)}", title, text, rva, at, 255))
        }
    }
}
