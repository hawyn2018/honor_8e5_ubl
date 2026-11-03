package net.hawyn.ablefisp.patch

/** EFI ABI facts derived from entry arguments and their stored data flow.
 * This is analysis only: no boot hook, firmware execution or patch is added.
 */
internal object KblEnvironmentFacts {
    data class Facts(
        val imageHandleGlobal: Long,
        val systemTableGlobal: Long,
        val bootServicesGlobal: Long,
        val runtimeServicesGlobal: Long,
        val evidence: Map<String, Long>,
    )
    private data class Store(val pc: Long, val value: Int, val address: Long)
    private data class Load(val pc: Long, val value: Int, val field: Int)

    fun analyze(image: PeImage): Facts {
        val start = image.entryRva
        val words = image.instructions().filter { it.first >= start }.take(2048).toList()
        val end = words.firstOrNull { (pc, w) -> pc > start &&
            w in setOf(0xd503233f.toInt(), 0xd503237f.toInt()) }?.first
            ?: throw PatchException("KBL：未完整识别 EFI 入口函数边界。")
        val flow = A64ValueOrigins(image, start, end)
        val stores = words.filter { it.first < end }.mapNotNull { (pc, word) ->
            // STR Xt,[Xn,#imm12*8]; global may be zero-initialized virtual data.
            if (word and 0xffc00000.toInt() != 0xf9000000.toInt()) return@mapNotNull null
            val value = word and 31
            val base = (word ushr 5) and 31
            val address = flow.globalAddress(pc, base)?.plus(((word ushr 10) and 4095) * 8L)
                ?: return@mapNotNull null
            if (!image.isWritable(address, 8) || image.isCode(address, 8)) return@mapNotNull null
            Store(pc, value, address)
        }
        fun argument(number: Int): Store = stores.filter { flow.derivesFromEntry(it.pc, it.value, number) }
            .distinctBy { it.address }.singleOrNull()
            ?: throw PatchException("KBL：EFI 参数 $number 的全局保存关系未唯一匹配。")
        val handle = argument(0)
        val table = argument(1)
        val loads = words.filter { it.first < end }.mapNotNull { (pc, word) ->
            if (word and 0xffc00000.toInt() != 0xf9400000.toInt()) return@mapNotNull null
            val base = (word ushr 5) and 31
            val offset = ((word ushr 10) and 4095) * 8
            if (offset !in setOf(0x58, 0x60)) return@mapNotNull null
            // UEFI's standardized SYSTEM_TABLE offsets identify roles, not a
            // model/firmware address. Prove an unshifted entry-X1 object base.
            val anchors = (0..30).filter { flow.derivesFromEntry(pc, it, 1) }
            val unshifted = flow.derivesFromEntry(pc, base, 1) || anchors.any {
                flow.addressDerivesFromEntry(pc, base, 1) && flow.objectOffset(pc, base, it) == 0
            }
            if (!unshifted) return@mapNotNull null
            Load(pc, word and 31, offset)
        }
        fun field(offset: Int): Pair<Load, Store> = loads.filter { it.field == offset }.flatMap { load ->
            // A reloaded external table pointer may be conservatively treated
            // as stack-aliasing by the general model. Its exact entry-X1
            // identity was separately proved above. For a linear load-to-store
            // region, track the loaded register as a fresh input rather than
            // weakening the shared provenance model or accepting Unknown.
            val tail = A64ValueOrigins(image, load.pc + 4, end)
            stores.filter { store ->
                flow.derivesFrom(store.pc, store.value, load.pc, load.value) ||
                    (store.pc > load.pc && flow.hasOnlyLinearEntry(load.pc, store.pc) &&
                        tail.derivesFromEntry(store.pc, store.value, load.value))
            }.map { load to it }
        }.distinctBy { it.second.address }.singleOrNull()
            ?: throw PatchException("KBL：EFI SYSTEM_TABLE+$offset 的全局保存关系未唯一匹配。")
        val boot = field(0x60)
        val runtime = field(0x58)
        val addresses = listOf(handle.address, table.address, boot.second.address, runtime.second.address)
        if (addresses.distinct().size != 4) throw PatchException("KBL：EFI 环境全局字段相互重叠。")
        return Facts(handle.address, table.address, boot.second.address, runtime.second.address,
            linkedMapOf("imageHandleStore" to handle.pc, "systemTableStore" to table.pc,
                "bootServicesLoad" to boot.first.pc, "bootServicesStore" to boot.second.pc,
                "runtimeServicesLoad" to runtime.first.pc, "runtimeServicesStore" to runtime.second.pc))
    }
}
