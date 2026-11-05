package net.hawyn.ablefisp.patch

/** Make one section-header slot inside the existing SizeOfHeaders. Original
 * sections and directories stay at their RVAs/raw offsets. VM's receipt owns
 * the complete old header block and restores this preparation byte for byte. */
internal object VmDtboHeaderSpace {
    data class Result(val image:PeImage,val relocated:Boolean)
    fun prepare(image:PeImage):Result {
        val source=image.bytes
        val oldPe=source.u32(0x3c).asBoundedInt("VM DTBO PE头")
        val optional=oldPe+24;val optionalBytes=source.u16(oldPe+20)
        val count=source.u16(oldPe+6)
        if(count==0xffff)throw PatchException("VM DTBO：PE节数量已用尽格式字段。")
        val table=optional+optionalBytes;val slot=table+count*40
        val headerEnd=minOf(image.headersSize,image.sections.filter { it.rawSize>0 }.minOf { it.raw })
        fun owned(first:Int,end:Int):Boolean {
            val capacity=maxOf(0,(optionalBytes-112)/8)
            val directories=minOf(16L,source.u32(optional+108),capacity.toLong()).toInt()
            for(i in 0 until directories) {
                val at=optional+112+i*8;val address=source.u32(at);val bytes=source.u32(at+4)
                // Header file offsets and RVAs coincide; this also covers the
                // security directory, whose address is explicitly a file offset.
                if(bytes>0&&address<end&&first.toLong()<address+bytes)return true
            }
            val symbols=source.u32(oldPe+12);val bytes=source.u32(oldPe+16)*18L
            return bytes>0&&symbols<end&&first.toLong()<symbols+bytes
        }
        if(slot.toLong()+40<=headerEnd&&!owned(slot,slot+40)&&
            (slot until slot+40).all { source[it]==0.toByte() })return Result(image,false)
        val span=24L+optionalBytes+(count+1L)*40
        if(span>headerEnd)throw PatchException("VM DTBO：现有PE头范围容纳不了新增节表。")
        // The standard DOS header owns bytes [0,64). Search the remaining
        // existing header space dynamically, never by firmware/model identity.
        val target=(64..oldPe-span.toInt() step 8).firstOrNull { at->
            at.toLong()+span<=headerEnd&&!owned(at,at+span.toInt())&&
                (at until at+span.toInt()).all { source[it]==0.toByte() }
        }?:throw PatchException("VM DTBO：现有PE头内未找到完整全零且无目录/符号占用的新节表位置。")
        val output=source.copyOf()
        source.copyInto(output,target,oldPe,table+count*40)
        output.putWord(0x3c,target)
        val prepared=PeImage.parse(output)
        if(prepared.headersSize!=image.headersSize||prepared.sections!=image.sections||
            prepared.entryRva!=image.entryRva||prepared.imageBase!=image.imageBase||
            !output.copyOfRange(image.headersSize,output.size).contentEquals(source.copyOfRange(image.headersSize,source.size)))
            throw PatchException("VM DTBO：NT头准备改变了原生节或映射。")
        return Result(prepared,true)
    }
}
