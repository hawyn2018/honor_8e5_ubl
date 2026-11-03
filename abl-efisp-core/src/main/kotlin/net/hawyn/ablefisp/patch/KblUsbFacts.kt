package net.hawyn.ablefisp.patch

import java.lang.ref.WeakReference

/** Actual native USB object/consumer relationships. These facts do not assert
 * TX-idle: that is a live observer witness, never inferred from handler entry. */
internal object KblUsbFacts {
    data class Facts(
        val protocolSlotRva:Long, val ackBufferSlotRva:Long,
        val nativeAckCommonRva:Long, val nativeAckSendCallRva:Long,
        val mainPollFunctionRva:Long, val mainPollCallRva:Long,
        val locateProtocolCallRva:Long, val commandRxBufferSlotRva:Long,
        val downloadStateByteRva:Long, val dataReadyRva:Long,
        val counterFrequencyRva:Long, val counterTicksRva:Long, val evidence:Map<String,Long>,
    ) {
        val requiresObservedIdleFence get()=true
        val commandStateValue get()=0
    }
    private data class Function(val start:Long,val end:Long)
    private data class Mem(val pc:Long,val rt:Int,val rn:Int,val offset:Int,val size:Int,val load:Boolean)
    private class Index(val image:PeImage) {
        val code=image.instructions().toList()
        val starts=code.filter { it.second in setOf(0xd503233f.toInt(),0xd503237f.toInt()) }.map { it.first }
        // A scan can visit many unrelated functions. Cache their proofs without
        // retaining every graph until the whole USB binding scan has finished.
        // The VM, rather than a separate memory budget, decides when to reclaim.
        private val flows=HashMap<Function,WeakReference<A64ValueOrigins>>()
        fun owner(pc:Long):Function? {val a=starts.lastOrNull { it<=pc }?:return null
            return Function(a,starts.firstOrNull { it>a }?:return null)}
        fun body(f:Function)=code.filter { it.first in f.start..<f.end }
        fun flow(f:Function):A64ValueOrigins = flows[f]?.get()
            ?: A64ValueOrigins(image,f.start,f.end).also { flows[f]=WeakReference(it) }
        fun suffixFlow(start:Long,end:Long)=flow(Function(start,end))
    }
    private data class Method(val call:Long,val methodLoad:Mem,val objectLoad:Mem,val slot:Long)
    private fun memory(pc:Long,w:Int):Mem? {
        if(w and 0x3b200000==0x38000000&&w and 0x04800000==0) {
            val size=1 shl(w ushr 30);val mode=(w ushr 10)and 3
            if(size !in setOf(1,4,8)||mode==2)return null
            val offset=if(mode==1)0 else signed(((w ushr 12)and 511).toLong(),9).toInt()
            return Mem(pc,w and 31,(w ushr 5)and 31,offset,size,w and 0x400000!=0)
        }
        val op=w and 0xffc00000.toInt()
        val load=op in setOf(0xf9400000.toInt(),0xb9400000.toInt(),0x39400000)
        if(!load&&op !in setOf(0xf9000000.toInt(),0xb9000000.toInt(),0x39000000))return null
        val size=when(w ushr 30){3->8;2->4;0->1;else->return null}
        return Mem(pc,w and 31,(w ushr 5)and 31,((w ushr 10)and 4095)*size,size,load)
    }
    private fun blr(w:Int)=if(w and 0xfffffc1f.toInt()==0xd63f0000.toInt())(w ushr 5)and 31 else null
    private fun methods(index:Index,f:Function,offset:Int):List<Method> {
        val flow=index.flow(f);val body=index.body(f)
        val loads=body.mapNotNull { memory(it.first,it.second) }.filter { it.load&&it.size==8 }
        return body.flatMap { (pc,w)->val target=blr(w)?:return@flatMap emptyList()
            loads.filter { it.pc<pc&&it.offset==offset&&loadedValue(index,f,flow,pc,target,it) }.flatMap { method->
                loads.filter { it.pc<method.pc&&flow.derivesFrom(method.pc,method.rn,it.pc,it.rt) }.mapNotNull { obj->
                    flow.globalAddress(obj.pc,obj.rn)?.plus(obj.offset)?.takeIf { index.image.isWritable(it,8) }
                        ?.let { Method(pc,method,obj,it) }
                }
            }
        }.distinct()
    }
    fun analyze(image:PeImage,environment:KblEnvironmentFacts.Facts,plan:SuperUnlockPatcher.Plan):Facts {
        val index=Index(image)
        fun tail(entry:Long):Set<Long> {val f=index.owner(entry)?.takeIf { it.start==entry }?:fail("ACK wrapper boundary")
            return index.body(f).mapNotNull { (pc,w)->A64.bTarget(pc,w)?.takeIf { it !in f.start..<f.end&&image.isCode(it) } }.toSet()}
        val ack=tail(plan.fastbootOkayRva).intersect(tail(plan.fastbootFailRva)).singleOrNull()?:fail("shared ACK sender")
        val af=index.owner(ack)?.takeIf { it.start==ack }?:fail("ACK common boundary")
        val ackFlow=index.flow(af);val ackLoads=index.body(af).mapNotNull { memory(it.first,it.second) }.filter { it.load&&it.size==8 }
        val send=methods(index,af,0x10).singleOrNull { m->A64.immediateBefore(image,m.call,0,12)==0x81 }?:fail("ACK EP81 Send ABI")
        val response=ackLoads.singleOrNull { it.pc<send.call&&ackFlow.globalAddress(it.pc,it.rn)!=null&&
            ackFlow.derivesFrom(send.call,2,it.pc,it.rt) }?:fail("ACK stable response-buffer slot")
        val bufferSlot=ackFlow.globalAddress(response.pc,response.rn)!!+response.offset
        if(!image.isWritable(bufferSlot,8)||bufferSlot==send.slot)fail("distinct writable ACK buffer slot")
        // The same global response buffer is given to the bounded native formatter.
        val format=index.body(af).singleOrNull { (pc,w)->pc<send.call&&A64.blTarget(pc,w)!=null&&
            A64.immediateBefore(image,pc,1,12)==0x40&&ackLoads.any { l->
                ackFlow.globalAddress(l.pc,l.rn)?.plus(l.offset)==bufferSlot&&ackFlow.derivesFrom(pc,0,l.pc,l.rt) }
        }?:fail("ACK buffer formatter relationship")

        // LocateProtocol must consume the actual USB Device GUID and publish its
        // result directly into exactly the slot used by both ACK and event loop.
        val locate=index.code.mapNotNull { (pc,w)->memory(pc,w)?.takeIf { it.load&&it.size==8&&it.offset==0x140 }?.let { pc } }
            .mapNotNull(index::owner).distinct().flatMap { f->methods(index,f,0x140).filter { m->
                m.slot==environment.bootServicesGlobal&&index.flow(f).globalAddress(m.call,2)==send.slot&&
                    index.flow(f).origins(m.call,1)==setOf(A64ValueOrigin.Zero)&&guidAt(index,f,m.call)
            } }.distinct().singleOrNull()?:fail("USB GUID -> BootServices.LocateProtocol -> Fbd slot")

        // Main poll has a HandleEvent call with three disjoint, properly sized
        // caller-owned stack outputs, plus native RX Send and transfer parsing.
        // Intermediate private completion handling remains in the live binding.
        val polls=index.code.mapNotNull { (pc,w)->memory(pc,w)?.takeIf { it.load&&it.size==8&&it.offset==0x18 }?.let { pc } }
            .mapNotNull(index::owner).distinct().flatMap { f->
                methods(index,f,0x18).filter { it.slot==send.slot }.mapNotNull { poll->
                    val flow=index.flow(f)
                    val fields=(0..2).map { flow.stackAddress(poll.call,it)?:return@mapNotNull null }
                    val ranges=listOf(fields[0] until fields[0]+4,fields[1] until fields[1]+8,fields[2] until fields[2]+40)
                    if(ranges.indices.any { a->(a+1 until ranges.size).any { b->ranges[a].any { it in ranges[b] } } })return@mapNotNull null
                    val body=index.body(f)
                    val kind=body.any { (pc,w)->val l=memory(pc,w)
                        l!=null&&l.load&&l.size==4&&flow.stackAddress(pc,l.rn)?.plus(l.offset)==fields[0]&&
                            hasCompare(index,f,body,flow,l,2) }
                    val outcome=body.any { (pc,w)->val l=memory(pc,w)
                        l!=null&&l.load&&l.size==4&&flow.stackAddress(pc,l.rn)?.plus(l.offset)==fields[2]&&
                            hasCompare(index,f,body,flow,l,1) }
                    val ep=body.any { (pc,w)->val l=memory(pc,w)
                        l!=null&&l.load&&l.size==1&&flow.stackAddress(pc,l.rn)?.plus(l.offset)==fields[2]+4&&body.any { (test,raw)->
                            test>pc&&raw and 0x7f000000==0x37000000&&((raw ushr 19)and 31)==7&&
                                loadedValue(index,f,flow,test,raw and 31,l) } }
                    val receive=methods(index,f,0x10).any { it.slot==send.slot&&A64.immediateBefore(image,it.call,0,12)==1 }
                    if(kind&&outcome&&ep&&receive)f to poll else null
                }
            }.distinct().singleOrNull()?:fail("main native HandleEvent / transfer outcome / RX rearm relationship")
        val poll=polls.second
        val rx=commandRx(index,polls.first)
        fun reader(word:Int):Long=index.code.filter { (pc,w)->w==word&&image.word(pc+4)==0xd65f03c0.toInt()&&
            index.code.any { (call,insn)->A64.blTarget(call,insn)==pc } }.map { it.first }.distinct().singleOrNull()
            ?:fail("existing passive counter reader")
        val frequency=reader(0xd53be000.toInt());val ticks=reader(0xd53be020.toInt())
        return Facts(send.slot,bufferSlot,ack,send.call,polls.first.start,poll.call,locate.call,
            rx.buffer,rx.state,rx.ready,frequency,ticks,
            linkedMapOf("ackProtocolLoad" to send.objectLoad.pc,"ackSendMethodLoad" to send.methodLoad.pc,
                "ackBufferLoad" to response.pc,"ackBoundedFormatterCall" to format.first,
                "locateBootServicesLoad" to locate.objectLoad.pc,"locateMethodLoad" to locate.methodLoad.pc,
                "pollProtocolLoad" to poll.objectLoad.pc,"pollMethodLoad" to poll.methodLoad.pc,
                "commandRxBufferLoad" to rx.bufferLoad,"downloadStateLoad" to rx.stateLoad,
                "dataReadyCall" to rx.call))
    }
    private data class Rx(val buffer:Long,val state:Long,val ready:Long,val bufferLoad:Long,val stateLoad:Long,val call:Long)
    private fun commandRx(index:Index,f:Function):Rx {
        val flow=index.flow(f);val body=index.body(f);val image=index.image
        val states=body.mapNotNull { memory(it.first,it.second) }.filter { it.load&&it.size==1&&flow.globalAddress(it.pc,it.rn)!=null }
        val found=states.flatMap { state->
            val global=flow.globalAddress(state.pc,state.rn)!!+state.offset
            body.filter { (pc,w)->val c=A64.cmpImmediate(w)
                pc>state.pc&&c?.second==1&&loadedValue(index,f,flow,pc,c.first,state) }.mapNotNull { (compare,_)->
                val gate=image.word(compare+4)?:return@mapNotNull null
                if(gate and 0xff00001f.toInt()!=0x54000001)return@mapNotNull null
                val target=compare+4+(signed(((gate ushr 5)and 0x7ffff).toLong(),19) shl 2)
                if(target !in f.start..<f.end)return@mapNotNull null
                val call=body.firstOrNull { (pc,w)->pc in target..<minOf(target+40,f.end)&&A64.blTarget(pc,w)!=null }?:return@mapNotNull null
                val ready=A64.blTarget(call.first,call.second)!!
                val local=index.suffixFlow(target,call.first+4)
                val buffer=body.mapNotNull { memory(it.first,it.second) }.singleOrNull { m->
                    m.pc in target..<call.first&&m.load&&m.size==8&&local.globalAddress(m.pc,m.rn)!=null&&
                        local.derivesFrom(call.first,1,m.pc,m.rt) }?:return@mapNotNull null
                val slot=local.globalAddress(buffer.pc,buffer.rn)!!+buffer.offset
                if(!image.isWritable(slot,8))return@mapNotNull null
                val rf=index.owner(ready)?.takeIf { it.start==ready }?:return@mapNotNull null
                val rflow=index.flow(rf)
                // DataReady independently tests bit0 of the same byte: command
                // state zero follows its no-download branch. Runtime also
                // matches the exact command DMA buffer, not a downloaded body.
                val shared=index.body(rf).mapNotNull { memory(it.first,it.second) }.any { l->
                    l.load&&l.size==1&&rflow.globalAddress(l.pc,l.rn)?.plus(l.offset)==global&&index.body(rf).any { (pc,w)->
                        pc>l.pc&&w and 0x7f000000==0x36000000&&((w ushr 19)and 31)==0&&
                            loadedValue(index,rf,rflow,pc,w and 31,l) } }
                if(shared)Rx(slot,global,ready,buffer.pc,state.pc,call.first)else null
            }
        }.distinct()
        return found.singleOrNull()?:fail("native command RX buffer / DataReady shared download state")
    }
    private fun loadedValue(index:Index,f:Function,flow:A64ValueOrigins,pc:Long,reg:Int,load:Mem)=
        flow.derivesFrom(pc,reg,load.pc,load.rt)||(pc>load.pc&&flow.hasOnlyLinearEntry(load.pc,pc)&&
            index.suffixFlow(load.pc+4,f.end).derivesFromEntry(pc,reg,load.rt))
    private fun hasCompare(index:Index,f:Function,body:List<Pair<Long,Int>>,flow:A64ValueOrigins,load:Mem,value:Int)=body.any { (pc,w)->
        val c=A64.cmpImmediate(w);pc>load.pc&&c?.second==value&&loadedValue(index,f,flow,pc,c.first,load) }
    private val usbGuid=byteArrayOf(0x48,0xce.toByte(),0xd9.toByte(),0xd9.toByte(),0xb8.toByte(),0x44,0x49,0x4f,
        0x8e.toByte(),0x3e,0x2a,0x3b,0x92.toByte(),0x7d,0xc6.toByte(),0xc1.toByte())
    private fun guidAt(index:Index,f:Function,call:Long):Boolean {
        val flow=index.flow(f)
        flow.globalAddress(call,0)?.let { rva->val at=index.image.rvaToOffset(rva,16)?:return@let
            if(index.image.bytes.copyOfRange(at,at+16).contentEquals(usbGuid))return true }
        val start=flow.stackAddress(call,0)?:return false
        val bytes=arrayOfNulls<Byte>(16)
        for((pc,w)in index.body(f).asReversed()) {
            if(pc>=call||pc<call-512)continue
            val m=memory(pc,w)?.takeIf { !it.load }
            if(m==null){
                // Do not silently ignore a later pair/vector store which can
                // overwrite bytes reconstructed from earlier scalar stores.
                val range=otherStackWrite(flow,pc,w)
                if(range!=null&&(range.first until range.first+range.second).any { a->a-start in 0..15&&bytes[a-start]==null })return false
                continue
            }
            val base=flow.stackAddress(pc,m.rn)?:continue
            val a=base+m.offset-start
            if(a>=16||a+m.size<=0)continue
            if(!flow.hasOnlyLinearEntry(pc,call))return false
            val constant=constant(index.image,f.start,pc,m.rt,m.size*8,flow)
            for(i in 0 until m.size)if(a+i in 0..15&&bytes[a+i]==null){
                if(constant==null)return false
                bytes[a+i]=(constant ushr(i*8)).toByte()
            }
        }
        return bytes.indices.all { bytes[it]==usbGuid[it] }
    }
    private fun otherStackWrite(flow:A64ValueOrigins,pc:Long,w:Int):Pair<Int,Int>? {
        if(w and 0x00400000!=0)return null
        val rn=(w ushr 5)and 31;val base=flow.stackAddress(pc,rn)?:return null
        if(w and 0x3a000000==0x28000000){
            val vector=w and 0x04000000!=0;val opc=w ushr 30
            val size=if(vector)4 shl opc else if(opc==2)8 else 4
            val mode=(w ushr 23)and 3
            val offset=if(mode==1)0 else signed(((w ushr 15)and 127).toLong(),7).toInt()*size
            return base+offset to 2*size
        }
        if(w and 0x3b000000==0x39000000&&w and 0x04000000!=0){
            val size=if(w and 0x00800000!=0)16 else 1 shl(w ushr 30)
            return base+((w ushr 10)and 4095)*size to size
        }
        return null
    }
    /** Bounded preceding MOVZ/MOVN/MOVK chain. Unknown writers stay unknown. */
    private fun constant(image:PeImage,start:Long,at:Long,reg:Int,bits:Int,flow:A64ValueOrigins):Long? {
        if(reg==31)return 0
        var mask=0L;var value=0L;var pc=at-4
        val full=if(bits>=64)-1L else (1L shl bits)-1
        while(pc>=maxOf(start,at-256)) {
            val w=image.word(pc)?:return null;val rd=w and 31
            val wide=w and 0x7f800000
            if(rd==reg&&wide in setOf(0x52800000,0x72800000,0x12800000)) {
                val shift=((w ushr 21)and 3)*16;val imm=((w ushr 5)and 65535).toLong() shl shift
                val lane=65535L shl shift
                if(wide==0x72800000){value=value or(imm and mask.inv());mask=mask or lane}
                else {val original=if(wide==0x12800000)imm.inv() else imm
                    return if(flow.hasOnlyLinearEntry(pc,at))(value or(original and mask.inv()))and full else null}
            } else if(A64.blTarget(pc,w)!=null||blr(w)!=null) {if(reg<=18)return null}
            else if(w and 0xffe0ffe0.toInt()==0xaa0003e0.toInt()&&rd==reg) {
                if(!flow.hasOnlyLinearEntry(pc,at))return null
                val src=(w ushr 16)and 31;val c=constant(image,start,pc,src,bits,flow)?:return null
                return (value or(c and mask.inv()))and full
            } else {
                val m=memory(pc,w)
                val noWrite=(m!=null&&!m.load)||A64.bTarget(pc,w)!=null||w and 0xff000010.toInt()==0x54000000||
                    w and 0x7e000000==0x34000000||w and 0x7e000000==0x36000000||w and 0x3b000000==0x29000000
                if(rd==reg&&!noWrite)return null
            }
            if(mask and full==full)return if(flow.hasOnlyLinearEntry(pc,at))value and full else null
            pc-=4
        }
        return null
    }
    private fun fail(s:String):Nothing=throw PatchException("KBL USB binding：$s 未唯一证明。")
    private fun signed(v:Long,b:Int)=(v shl(64-b))shr(64-b)
}
