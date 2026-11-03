package net.hawyn.ablefisp.patch
import org.junit.Assert.*
import org.junit.Test

class KblDispatcherFactsTest {
    private data class Fixture(val image:PeImage,val register:Long,val head:Long,val pointer:Long,val count:Long)
    private fun fixture(code:Long=0x1000,data:Long=0x4000,node:Int=20,prefix:Int=8,len:Int=16,handler:Int=24,
        count64:Boolean=false,wrongHead:Boolean=false,clobber:Boolean=false,nextOffset:Int=0,reloadGlobal:Boolean=false,
        consumerGap:Int=0,resultRegister:Int=0):Fixture {
        val b=ByteArray(0x2400);b[0]=77;b[1]=90;b.putWord(0x3c,0x80);b.putWord(0x80,0x4550)
        b.putWord(0x84,0x0002aa64);b.putWord(0x94,0x202200f0);b.putWord(0x98,0x20b)
        b.putWord(0xa8,code.toInt());b.putWord(0xb8,0x1000);b.putWord(0xbc,0x200)
        b.putWord(0xd0,(data+0x1000).toInt());b.putWord(0xd4,0x400);b.putWord(0x104,16)
        for(i in 0..1){val at=0x188+i*40;(if(i==0)".text" else ".data").toByteArray().copyInto(b,at)
            b.putWord(at+8,0x1000);b.putWord(at+12,(if(i==0)code else data).toInt())
            b.putWord(at+16,0x1000);b.putWord(at+20,0x400+i*0x1000);b.putWord(at+36,if(i==0)0x60000020 else 0xc0000040.toInt())}
        var pc=code
        fun emit(w:Int){b.putWord(requireNotNull(PeImage.parse(b).rvaToOffset(pc,4)),w);pc+=4}
        fun mov(d:Int,s:Int)=emit(0xaa0003e0.toInt()or(s shl 16)or d)
        fun adr(d:Int,t:Long){val v=t-pc;emit(0x10000000 or((v.toInt()and 3)shl 29)or(((v shr 2).toInt()and 0x7ffff)shl 5)or d)}
        fun mem(load:Boolean,r:Int,n:Int,o:Int=0,size:Int=8)=emit((if(size==8){if(load)0xf9400000.toInt() else 0xf9000000.toInt()}else{if(load)0xb9400000.toInt() else 0xb9000000.toInt()})or((o/size)shl 10)or(n shl 5)or r)
        fun bl(t:Long)=emit(A64.branch(pc,t)or 0x80000000.toInt())
        fun cb(r:Int,t:Long,nz:Boolean=false)=emit(0xb4000000.toInt()or(if(nz)0x1000000 else 0)or((((t-pc)shr 2).toInt()and 0x7ffff)shl 5)or r)
        emit(0xd503233f.toInt());mov(21,0);mov(19,1);bl(code+0x300);cb(0,code+0xf0)
        mov(node,0);mem(false,21,node,prefix);mov(0,21);bl(code+0x310)
        mem(false,19,node,handler);if(clobber)mov(0,21);mem(false,0,node,len,4)
        adr(8,data);mem(true,9,8);mem(false,9,node,nextOffset);mem(false,node,8);emit(0xd65f03c0.toInt())
        pc=code+0xf0;emit(0xd65f03c0.toInt());pc=code+0x100;emit(0xd503233f.toInt());mov(19,0)
        val headBase=if(reloadGlobal)15 else node
        adr(headBase,data+if(wrongHead)8 else 0);val loop=pc;mem(true,node,headBase);cb(node,code+0x1f0+consumerGap)
        repeat(consumerGap/4){emit(0xd503201f.toInt())}
        mem(true,1,node,prefix);mov(0,19);mem(true,2,node,len,4);bl(code+0x320)
        if(resultRegister!=0){mov(resultRegister,0);emit(0xd503201f.toInt())}
        cb(resultRegister,loop,true)
        mem(true,9,node,len,4);adr(10,data+0x40);mem(true,8,node,handler);mem(true,1,10)
        emit(0x8b090260.toInt());adr(11,data+0x80);mem(true,2,11,size=if(count64)8 else 4)
        emit(0xd63f0100.toInt());emit(0xd65f03c0.toInt());pc=code+0x1f0+consumerGap;emit(0xd65f03c0.toInt())
        pc=code+0x200+consumerGap;emit(0xd503233f.toInt());emit(0xd65f03c0.toInt())
        return Fixture(PeImage.parse(b),code,data,data+0x40,data+0x80)
    }
    private fun check(f:Fixture){val r=KblDispatcherFacts.analyze(f.image,f.register)
        assertEquals(f.head,r.headGlobal);assertEquals(f.pointer,r.downloadGlobal);assertEquals(f.count,r.receivedGlobal)}
    @Test fun producerAndConsumerAgree(){check(fixture())}
    @Test fun movedCodeGlobalsNodeRegisterAndFields(){check(fixture(0x9000,0x20000,22,16,8,40))}
    @Test fun consumerBeyondTheOldWindowStillUsesTheProvenFunctionBoundary(){check(fixture(consumerGap=256))}
    @Test fun copiedComparisonResultAndSchedulingGapStillLocateTheMismatchLoop(){check(fixture(resultRegister=11))}
    @Test fun rejects64BitReceivedCount(){val f=fixture(count64=true);assertThrows(PatchException::class.java){KblDispatcherFacts.analyze(f.image,f.register)}}
    @Test fun rejectsOtherCommandList(){val f=fixture(wrongHead=true);assertThrows(PatchException::class.java){KblDispatcherFacts.analyze(f.image,f.register)}}
    @Test fun rejectsClobberedLength(){val f=fixture(clobber=true);assertThrows(PatchException::class.java){KblDispatcherFacts.analyze(f.image,f.register)}}
    @Test fun rejectsMismatchedNextField(){val f=fixture(nextOffset=32);assertThrows(PatchException::class.java){KblDispatcherFacts.analyze(f.image,f.register)}}
    @Test fun rejectsLoopWhichReloadsTheGlobalHead(){val f=fixture(reloadGlobal=true);assertThrows(PatchException::class.java){KblDispatcherFacts.analyze(f.image,f.register)}}
}
