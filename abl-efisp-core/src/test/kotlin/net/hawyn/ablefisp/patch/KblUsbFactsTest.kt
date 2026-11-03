package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test
import java.lang.ref.WeakReference

class KblUsbFactsTest {
    private data class Fixture(val image:PeImage,val env:KblEnvironmentFacts.Facts,val plan:SuperUnlockPatcher.Plan,val data:Long,val code:Long)
    private fun fixture(code:Long=0x1000,data:Long=0x5000,method:Int=20,stackGuid:Boolean=false,
        wrongGuid:Boolean=false,wrongPollSlot:Boolean=false,wrongEndpoint:Boolean=false,wrongState:Boolean=false,bypassGuid:Boolean=false,clobberGuid:Boolean=false):Fixture {
        val bytes=ByteArray(0x3400)
        bytes[0]=77;bytes[1]=90;bytes.putWord(0x3c,0x80);bytes.putWord(0x80,0x4550)
        bytes.putWord(0x84,0x0002aa64);bytes.putWord(0x94,0x202200f0);bytes.putWord(0x98,0x20b)
        bytes.putWord(0xa8,code.toInt());bytes.putWord(0xb8,0x1000);bytes.putWord(0xbc,0x200)
        bytes.putWord(0xd0,(data+0x1000).toInt());bytes.putWord(0xd4,0x400);bytes.putWord(0x104,16)
        for(i in 0..1){val at=0x188+i*40;(if(i==0)".text" else ".data").toByteArray().copyInto(bytes,at)
            bytes.putWord(at+8,if(i==0)0x2000 else 0x1000);bytes.putWord(at+12,(if(i==0)code else data).toInt())
            bytes.putWord(at+16,if(i==0)0x2000 else 0x1000);bytes.putWord(at+20,0x400+i*0x2000)
            bytes.putWord(at+36,if(i==0)0x60000020 else 0xc0000040.toInt())}
        var pc=code
        fun put(at:Long,w:Int){val raw=if(at in code..<code+0x2000)0x400+(at-code).toInt()else 0x2400+(at-data).toInt();bytes.putWord(raw,w)}
        fun emit(w:Int){put(pc,w);pc+=4}
        fun mov(d:Int,s:Int)=emit(0xaa0003e0.toInt()or(s shl 16)or d)
        fun imm(r:Int,v:Int)=emit(0x52800000 or((v and 65535)shl 5)or r)
        fun adr(r:Int,t:Long){val v=t-pc;emit(0x10000000 or((v.toInt()and 3)shl 29)or(((v shr 2).toInt()and 0x7ffff)shl 5)or r)}
        fun mem(load:Boolean,r:Int,n:Int,o:Int=0,size:Int=8){val op=when(size){8->if(load)0xf9400000.toInt() else 0xf9000000.toInt();4->if(load)0xb9400000.toInt() else 0xb9000000.toInt();else->if(load)0x39400000 else 0x39000000}
            emit(op or((o/size)shl 10)or(n shl 5)or r)}
        fun bl(t:Long)=emit(A64.branch(pc,t)or 0x80000000.toInt())
        fun br(t:Long)=emit(A64.branch(pc,t))
        fun cond(at:Long,t:Long,cc:Int)=put(at,0x54000000 or((((t-at)shr 2).toInt()and 0x7ffff)shl 5)or cc)
        fun cmp(r:Int,v:Int)=emit(0x7100001f or(v shl 10)or(r shl 5))
        fun ret()=emit(0xd65f03c0.toInt())
        fun pac()=emit(0xd503233f.toInt())
        pac();br(code+0x100);pc=code+0x40;pac();br(code+0x100)
        pc=code+0x100;pac();adr(19,data);mem(true,8,19);mem(true,method,8,16)
        mem(true,0,19,16);imm(1,64);bl(code+0x600)
        mem(true,2,19,16);mov(1,0);imm(0,if(wrongEndpoint)0x82 else 0x81);emit(0xd63f0000.toInt()or(method shl 5));ret()
        pc=code+0x200;pac();emit(0xd10103ff.toInt());adr(19,data+if(wrongPollSlot)0x200 else 0)
        mem(true,8,19);mem(true,8,8,24);emit(0x910033e0.toInt());emit(0x910003e1.toInt());emit(0x910043e2.toInt());emit(0xd63f0100.toInt())
        mem(true,8,31,12,4);cmp(8,2);val notKind=pc;emit(0)
        mem(true,8,31,16,4);cmp(8,1);val notComplete=pc;emit(0)
        mem(true,9,31,20,1);val txBranch=pc;emit(0)
        adr(10,data+0x30);mem(true,8,10,size=1);cmp(8,1);val normalBranch=pc;emit(0)
        adr(10,data+0x40);mem(true,1,10);val toReady=pc;emit(0)
        val normal=pc;adr(10,data+8);mem(true,1,10);val ready=pc;bl(code+0x500);ret()
        val tx=pc;mem(true,8,19);mem(true,8,8,16);mem(true,2,19,8);imm(1,64);imm(0,1);emit(0xd63f0100.toInt())
        val done=pc;ret();cond(notKind,done,1);cond(notComplete,done,1);cond(normalBranch,normal,1)
        put(toReady,A64.branch(toReady,ready));put(txBranch,0x37000000 or(7 shl 19)or((((tx-txBranch)shr 2).toInt()and 0x3fff)shl 5)or 9)
        pc=code+0x400;pac();adr(19,data+0x80);mem(true,8,19);mem(true,8,8,0x140)
        val guid=intArrayOf(0xd9d9ce48.toInt(),0x4f4944b8,0x3b2a3e8e,0xc1c67d92.toInt())
        if(wrongGuid)guid[0]=guid[0]xor 1
        if(stackGuid){for(i in guid.indices){imm(9,guid[i]);emit(0x72a00009 or((guid[i]ushr 16)shl 5));
            if(bypassGuid&&i==0)emit(0xb4000040.toInt());mem(false,9,31,32+i*4,4)}
            if(clobberGuid)emit(0xa9027fff.toInt());emit(0x910083e0.toInt())}
        else{guid.forEachIndexed { i,v->put(data+0x100+i*4,v) };adr(0,data+0x100)}
        mov(1,31);adr(2,data);emit(0xd63f0100.toInt());ret()
        pc=code+0x500;pac();adr(10,data+if(wrongState)0x31 else 0x30);mem(true,8,10,size=1)
        emit(0x36000048);ret();ret()
        pc=code+0x600;pac();bl(code+0x700);bl(code+0x708);ret()
        pc=code+0x700;emit(0xd53be000.toInt());ret();emit(0xd53be020.toInt());ret()
        pc=code+0x800;pac();ret()
        val image=PeImage.parse(bytes);val env=KblEnvironmentFacts.Facts(data+0x60,data+0x68,data+0x80,data+0x88,emptyMap())
        val plan=SuperUnlockPatcher.Plan(code+0x20,0,code+0x24,code+0x40,0,code+0x80,code+0x90,64,16,17,
            code,code+0x40,"ANDROID-BOOT!".toByteArray(),data+0x100,data+0x200,code+0x1c,20)
        return Fixture(image,env,plan,data,code)
    }
    private fun check(f:Fixture){val r=KblUsbFacts.analyze(f.image,f.env,f.plan)
        assertEquals(f.data,r.protocolSlotRva);assertEquals(f.data+16,r.ackBufferSlotRva)
        assertEquals(f.data+8,r.commandRxBufferSlotRva);assertEquals(f.data+0x30,r.downloadStateByteRva)
        assertEquals(f.code+0x200,r.mainPollFunctionRva);assertEquals(f.code+0x700,r.counterFrequencyRva)
        assertEquals(f.code+0x708,r.counterTicksRva);assertTrue(r.requiresObservedIdleFence);assertEquals(0,r.commandStateValue)}
    @Test fun derivesSharedProtocolAndLiveObserverInputs(){check(fixture())}
    @Test fun followsMovedRvasAndMethodRegister(){check(fixture(0x9000,0x20000,22))}
    @Test fun reconstructsGuidFromMovkAndStackStores(){check(fixture(stackGuid=true))}
    @Test fun rejectsAnotherGuid(){val f=fixture(wrongGuid=true);assertThrows(PatchException::class.java){KblUsbFacts.analyze(f.image,f.env,f.plan)}}
    @Test fun rejectsPollOnOtherObject(){val f=fixture(wrongPollSlot=true);assertThrows(PatchException::class.java){KblUsbFacts.analyze(f.image,f.env,f.plan)}}
    @Test fun rejectsOtherTransmitEndpoint(){val f=fixture(wrongEndpoint=true);assertThrows(PatchException::class.java){KblUsbFacts.analyze(f.image,f.env,f.plan)}}
    @Test fun rejectsUnrelatedDownloadState(){val f=fixture(wrongState=true);assertThrows(PatchException::class.java){KblUsbFacts.analyze(f.image,f.env,f.plan)}}
    @Test fun rejectsBranchWhichCanBypassGuidConstruction(){val f=fixture(stackGuid=true,bypassGuid=true);assertThrows(PatchException::class.java){KblUsbFacts.analyze(f.image,f.env,f.plan)}}
    @Test fun rejectsLaterPairOverwriteOfGuid(){val f=fixture(stackGuid=true,clobberGuid=true);assertThrows(PatchException::class.java){KblUsbFacts.analyze(f.image,f.env,f.plan)}}
    @Test fun reclaimedSuffixGraphsRebuildTheSameProofWithoutChangingUsbBinding() {
        val f=fixture(0x9000,0x20000,22)
        // Clear the same weak reference the VM may clear, deterministically;
        // this test must not depend on GC timing or a configured heap size.
        val indexClass=Class.forName("net.hawyn.ablefisp.patch.KblUsbFacts\$Index")
        val index=indexClass.getDeclaredConstructor(PeImage::class.java).apply { isAccessible=true }.newInstance(f.image)
        val suffix=indexClass.getDeclaredMethod("suffixFlow",Long::class.javaPrimitiveType,Long::class.javaPrimitiveType)
            .apply { isAccessible=true }
        val start=f.code+0x200;val end=f.code+0x400
        fun proof()=suffix.invoke(index,start,end) as A64ValueOrigins
        fun signature(flow:A64ValueOrigins)=(start until end step 4).map { pc->
            listOf(flow.isReachable(pc),flow.stackAddress(pc,31),flow.globalAddress(pc,19),
                (0..30).map { flow.origins(pc,it) })
        }
        val first=proof();val expected=signature(first)
        assertSame(first,proof())
        val cache=indexClass.getDeclaredField("flows").apply { isAccessible=true }.get(index) as Map<*,*>
        (cache.values.single() as WeakReference<*>).clear()
        val rebuilt=proof()
        assertNotSame(first,rebuilt)
        assertEquals(expected,signature(rebuilt))
        check(f)
    }
}
