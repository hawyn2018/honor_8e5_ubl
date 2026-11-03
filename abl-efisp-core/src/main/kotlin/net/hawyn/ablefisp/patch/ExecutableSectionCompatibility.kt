package net.hawyn.ablefisp.patch

/** Metadata-only migration of this tool's previously emitted RX sections.
 * The firmware SEC DEP helper checks .text-style names in addition to PE flags.
 * Do not change permissions, mappings, instruction bytes or unrelated names. */
internal object ExecutableSectionCompatibility {
    data class Result(val bytes:ByteArray,val changes:List<PatchMatch>)
    private val legacy=mapOf(".su_cmd" to ".textsu", ".bfpol" to ".textpol", ".eftxt" to ".texttxt")
    fun normalize(bytes:ByteArray):Result {
        val image=PeImage.parse(bytes)
        val pe=bytes.u32(0x3c).asBoundedInt("PE头")
        val table=pe+24+bytes.u16(pe+20)
        val output=bytes.copyOf();val changes=ArrayList<PatchMatch>()
        image.sections.forEachIndexed { i,s->
            val name=legacy[s.name]?:return@forEachIndexed
            if(!s.executable||s.writable||s.characteristics and 0x40000000L==0L)return@forEachIndexed
            val at=table+i*40;val before=output.copyOfRange(at,at+8)
            output.fill(0,at,at+8);name.toByteArray(Charsets.US_ASCII).copyInto(output,at)
            changes+=PatchMatch("UEFI 可执行节名称兼容：${s.name} → $name",at.toLong(),at,
                before.hex(),output.copyOfRange(at,at+8).hex(),false)
        }
        return Result(output,changes)
    }
}
