package net.hawyn.ablefisp.patch

/** Prove the registered-handler ABI from the node producer and its consumer.
 * No model name, firmware version, instruction address or node offset table. */
internal object KblDispatcherFacts {
    data class Facts(val headGlobal: Long, val downloadGlobal: Long, val receivedGlobal: Long,
        val prefixOffset: Int, val lengthOffset: Int, val handlerOffset: Int,
        val dispatchCall: Long, val evidence: Map<String, Long>, val nextOffset:Int=0)
    private data class Mem(val pc: Long, val rt: Int, val rn: Int, val offset: Int, val size: Int, val load: Boolean)
    private fun mem(pc: Long, w: Int): Mem? {
        val op=w and 0xffc00000.toInt()
        val load=op in setOf(0xf9400000.toInt(),0xb9400000.toInt())
        if(!load && op !in setOf(0xf9000000.toInt(),0xb9000000.toInt())) return null
        val size=if(w ushr 30==3)8 else 4
        return Mem(pc,w and 31,(w ushr 5)and 31,((w ushr 10)and 4095)*size,size,load)
    }
    fun analyze(image:PeImage, nativeRegister:Long):Facts {
        val code=image.instructions().toList()
        val starts=code.filter { it.second in setOf(0xd503233f.toInt(),0xd503237f.toInt()) }.map { it.first }
        val end=starts.firstOrNull { it>nativeRegister } ?: fail("注册函数边界")
        if(nativeRegister !in starts) fail("注册函数入口")
        val body=code.filter { it.first in nativeRegister..<end }
        val f=A64ValueOrigins(image,nativeRegister,end)
        val stores=body.mapNotNull { mem(it.first,it.second) }.filter { !it.load }
        val tails=body.mapNotNull { (pc,w)->A64.blTarget(pc,w)?.let { pc+4 } }
            .associateWith { A64ValueOrigins(image,it,end) }
        fun sameNode(a:Mem,b:Mem)=f.sameValue(a.pc,a.rn,b.pc,b.rn)||tails.any { (start,flow)->
            start<=minOf(a.pc,b.pc)&&flow.derivesFromEntry(a.pc,a.rn,0)&&flow.derivesFromEntry(b.pc,b.rn,0) }
        val results=ArrayList<Facts>()
        for(prefix in stores.filter { it.size==8&&f.derivesFromEntry(it.pc,it.rt,0) })
        for(handler in stores.filter { it.size==8&&f.derivesFromEntry(it.pc,it.rt,1)&&sameNode(prefix,it)&&it.offset!=prefix.offset })
        for(length in stores.filter { it.size==4&&sameNode(prefix,it) }) {
            // Length is the return of a function called with the exact prefix.
            val lengthCall=body.lastOrNull { (pc,w)->pc<length.pc&&A64.blTarget(pc,w)!=null }
                ?.takeIf { (pc,_)-> f.derivesFromEntry(pc,0,0)&&
                    f.hasOnlyLinearEntry(pc,length.pc)&&
                    A64ValueOrigins(image,pc+4,end).derivesFromEntry(length.pc,length.rt,0) } ?: continue
            val headStores=stores.filter { it.size==8&&f.globalAddress(it.pc,it.rn)!=null }
            for(headStore in headStores) {
                if(!tails.any { (start,flow)->start<=minOf(prefix.pc,headStore.pc)&&
                    flow.derivesFromEntry(prefix.pc,prefix.rn,0)&&flow.derivesFromEntry(headStore.pc,headStore.rt,0) }) continue
                val head=f.globalAddress(headStore.pc,headStore.rn)!!+headStore.offset
                if(!image.isWritable(head,8))continue
                // Registration prepends: node.next=oldHead, head=node.
                val next=stores.singleOrNull { s->s.size==8&&sameNode(prefix,s)&&body.any { (pc,w)->
                    val l=mem(pc,w);l!=null&&l.load&&l.size==8&&
                        f.globalAddress(pc,l.rn)?.plus(l.offset)==head&&f.derivesFrom(s.pc,s.rt,pc,l.rt) } } ?: continue
                if(next.offset in setOf(prefix.offset,handler.offset,length.offset))continue
                for((headLoadPc,headLoadWord) in code) {
                    val headLoad=mem(headLoadPc,headLoadWord)?.takeIf { it.load&&it.size==8 }?:continue
                    if(headLoad.offset!=next.offset)continue
                    val owner=starts.lastOrNull { it<=headLoadPc }?:continue
                    val ownerEnd=starts.firstOrNull { it>owner }?:continue
                    val windowStart=owner
                    val local=A64ValueOrigins(image,windowStart,minOf(ownerEnd,headLoadPc+4))
                    if(local.globalAddress(headLoadPc,headLoad.rn)?.plus(headLoad.offset)!=head)continue
                    val stop=ownerEnd
                    val slice=code.filter { it.first in headLoadPc+4..<stop }
                    val flow=A64ValueOrigins(image,headLoadPc+4,stop)
                    val node=headLoad.rt
                    fun nodeLoad(m:Mem,off:Int,size:Int)=m.load&&m.offset==off&&m.size==size&&
                        flow.derivesFromEntry(m.pc,m.rn,node)
                    val loads=slice.mapNotNull { mem(it.first,it.second) }.filter { it.load }
                    for(h in loads.filter { nodeLoad(it,handler.offset,8) }) {
                        val call=slice.singleOrNull { (pc,w)->pc>h.pc&&w and 0xfffffc1f.toInt()==0xd63f0000.toInt()&&
                            flow.derivesFrom(pc,(w ushr 5)and 31,h.pc,h.rt) } ?:continue
                        val compare=slice.filter { (pc,w)->pc<h.pc&&A64.blTarget(pc,w)!=null }.singleOrNull { (pc,_)->
                            loads.any { nodeLoad(it,prefix.offset,8)&&flow.derivesFrom(pc,1,it.pc,it.rt) }&&
                            loads.any { nodeLoad(it,length.offset,4)&&flow.derivesFrom(pc,2,it.pc,it.rt) }
                        } ?:continue
                        // The only matching path after compare returns zero.
                        val returned=A64ValueOrigins(image,compare.first+4,stop)
                        val gates=slice.filter { (pc,w)->pc in compare.first+4..<h.pc &&
                            w and 0x7f000000==0x35000000 &&
                            pc+(signed(((w ushr 5)and 0x7ffff).toLong(),19) shl 2)==headLoadPc &&
                            returned.derivesFromEntry(pc,w and 31,0) &&
                            flow.hasOnlyLinearEntry(compare.first+4,pc) }
                        val (gatePc,gate)=gates.singleOrNull()?:continue
                        val branchTarget=gatePc+(signed(((gate ushr 5)and 0x7ffff).toLong(),19) shl 2)
                        if(branchTarget!=headLoadPc||
                            !flow.derivesFromEntry(gatePc,headLoad.rn,node)||
                            !flow.hasOnlyLinearEntry(gatePc+4,call.first))continue
                        val ptr=loads.singleOrNull { it.size==8&&it.pc>gatePc&&
                            flow.globalAddress(it.pc,it.rn)!=null&&flow.derivesFrom(call.first,1,it.pc,it.rt) }?:continue
                        val count=loads.singleOrNull { it.size==4&&it.pc>gatePc&&
                            flow.globalAddress(it.pc,it.rn)!=null&&flow.derivesFrom(call.first,2,it.pc,it.rt) }?:continue
                        val pointerGlobal=flow.globalAddress(ptr.pc,ptr.rn)!!+ptr.offset
                        val countGlobal=flow.globalAddress(count.pc,count.rn)!!+count.offset
                        if(!image.isWritable(pointerGlobal,8)||!image.isWritable(countGlobal,4)||
                            (pointerGlobal<countGlobal+4&&countGlobal<pointerGlobal+8))continue
                        val add=slice.singleOrNull { (pc,w)->pc>gatePc&&pc<call.first&&
                            w and 0xffe0fc00.toInt()==0x8b000000.toInt()&&
                            flow.derivesFrom(call.first,0,pc,w and 31)&&
                            flow.sameValue(compare.first,0,pc,(w ushr 5)and 31)&&
                            loads.any { nodeLoad(it,length.offset,4)&&flow.derivesFrom(pc,(w ushr 16)and 31,it.pc,it.rt) }
                        }?:continue
                        results+=Facts(head,pointerGlobal,countGlobal,prefix.offset,length.offset,handler.offset,call.first,
                            linkedMapOf("prefixStore" to prefix.pc,"handlerStore" to handler.pc,"lengthCall" to lengthCall.first,
                                "headStore" to headStore.pc,"headLoad" to headLoadPc,"prefixCompare" to compare.first,
                                "suffixAdd" to add.first,"downloadLoad" to ptr.pc,"receivedLoad" to count.pc),next.offset)
                    }
                }
            }
        }
        return results.distinct().singleOrNull()?:fail("节点生产/消费、suffix、download、uint32 count未唯一证明")
    }
    private fun signed(v:Long,b:Int)=(v shl(64-b))shr(64-b)
    private fun fail(s:String):Nothing=throw PatchException("KBL dispatcher ABI：$s。")
}
