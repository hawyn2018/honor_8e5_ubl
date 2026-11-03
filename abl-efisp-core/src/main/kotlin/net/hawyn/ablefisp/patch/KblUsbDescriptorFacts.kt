package net.hawyn.ablefisp.patch

/** Late, original descriptor-set input to UsbDevice.StartEx. Finding this input
 * needs no startup hook. The driver validates every live pointed-to descriptor
 * after explicit START; the file contains no model/address compatibility table. */
internal object KblUsbDescriptorFacts {
    data class Facts(val descriptorSetRva:Long,val evidence:Map<String,Long>)
    private data class Load(val pc:Long,val rt:Int,val rn:Int,val offset:Int)
    private fun load(pc:Long,w:Int):Load? {
        if(w and 0xffc00000.toInt()==0xf9400000.toInt())
            return Load(pc,w and 31,(w ushr 5)and 31,((w ushr 10)and 4095)*8)
        if(w and 0xffe00c00.toInt()==0xf8400000.toInt()) {
            val v=(w ushr 12)and 511
            return Load(pc,w and 31,(w ushr 5)and 31,if(v and 256!=0)v-512 else v)
        }
        return null
    }
    private fun blr(w:Int)=if(w and 0xfffffc1f.toInt()==0xd63f0000.toInt())(w ushr 5)and 31 else null
    fun analyze(image:PeImage,usbFacts:KblUsbFacts.Facts):Facts?=analyze(image,usbFacts.protocolSlotRva)
    internal fun analyze(image:PeImage,protocolSlotRva:Long):Facts? {
        if(!image.isWritable(protocolSlotRva,8))return null
        val code=image.instructions().toList()
        val starts=code.filter { it.second in setOf(0xd503233f.toInt(),0xd503237f.toInt()) }.map { it.first }
        val candidates=arrayListOf<Facts>()
        // First narrow by the public StartEx ABI method offset, then prove the
        // object, call target and argument in the whole owning function's CFG.
        val owners=code.filter { load(it.first,it.second)?.offset==0x48 }.mapNotNull { (pc,_)->
            val start=starts.lastOrNull { it<=pc }?:return@mapNotNull null
            val end=starts.firstOrNull { it>start }?:return@mapNotNull null
            start to end
        }.distinct()
        for((start,end)in owners) {
            val body=code.filter { it.first in start..<end }
            val flow=A64ValueOrigins(image,start,end)
            val loads=body.mapNotNull { load(it.first,it.second) }
            fun carries(pc:Long,reg:Int,l:Load)=flow.derivesFrom(pc,reg,l.pc,l.rt)||
                (pc>l.pc&&flow.hasOnlyLinearEntry(l.pc,pc)&&A64ValueOrigins(image,l.pc+4,end).derivesFromEntry(pc,reg,l.rt))
            for((pc,w)in body) {
                val target=blr(w)?:continue
                for(method in loads.filter { it.pc<pc&&it.offset==0x48&&carries(pc,target,it) }) {
                    for(obj in loads.filter { it.pc<method.pc&&carries(method.pc,method.rn,it)&&
                        flow.globalAddress(it.pc,it.rn)?.plus(it.offset)==protocolSlotRva }) {
                        val descriptor=flow.globalAddress(pc,0)?:continue
                        if(descriptor and 7!=0L||descriptor !in 1..0xffff_ffffL||!image.isWritable(descriptor,64))continue
                        candidates+=Facts(descriptor,linkedMapOf("startExCall" to pc,"startExMethodLoad" to method.pc,
                            "originalProtocolLoad" to obj.pc,"originalProtocolSlot" to protocolSlotRva,
                            "originalDescriptorSet" to descriptor))
                    }
                }
            }
        }
        // Two distinct StartEx sites are ambiguous even if a descriptor happens
        // to share an address. No guess is serialized into the driver.
        return candidates.distinct().singleOrNull()
    }
}
