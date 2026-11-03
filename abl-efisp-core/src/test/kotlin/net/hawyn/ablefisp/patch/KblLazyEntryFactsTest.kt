package net.hawyn.ablefisp.patch
import org.junit.Assert.*
import org.junit.Test

class KblLazyEntryFactsTest {
    private data class F(val image:PeImage,val dispatch:KblDispatcherFacts.Facts,val fail:Long,val hook:Long)
    private fun fixture(c:Long=0x1000,d:Long=0x4000,cmd:Int=19,index:Int=21,
        noTerminator:Boolean=false,wrongOrigin:Boolean=false,bypass:Boolean=false,wrongJoin:Boolean=false,
        terminatorBypass:Boolean=false):F {
        val b=ByteArray(0x2400);b[0]=77;b[1]=90;b.putWord(0x3c,0x80);b.putWord(0x80,0x4550)
        b.putWord(0x84,0x0002aa64);b.putWord(0x94,0x202200f0);b.putWord(0x98,0x20b)
        b.putWord(0xa8,c.toInt());b.putWord(0xb8,0x1000);b.putWord(0xbc,0x200)
        b.putWord(0xd0,(d+0x1000).toInt());b.putWord(0xd4,0x400);b.putWord(0x104,16)
        for(i in 0..1){val at=0x188+i*40;(if(i==0)".text" else ".data").toByteArray().copyInto(b,at)
            b.putWord(at+8,0x1000);b.putWord(at+12,(if(i==0)c else d).toInt());b.putWord(at+16,0x1000)
            b.putWord(at+20,0x400+i*0x1000);b.putWord(at+36,if(i==0)0x60000020 else 0xc0000040.toInt())}
        fun w(off:Int,x:Int){b.putWord(0x400+off,x)}
        w(0,0xd503233f.toInt());w(4,0xf101001f.toInt());w(8,0x52800808)
        w(12,0x9a883000.toInt()or index);w(16,0xaa0003e0.toInt()or((if(wrongOrigin)2 else 1)shl 16)or cmd)
        w(20,if(noTerminator)0xd503201f.toInt()else 0x3820683f or(index shl 16))
        for(i in 24 until 0x40 step 4)w(i,0xd503201f.toInt())
        w(0x40,0xf9400294.toInt());w(0x44,0xb4000000.toInt()or((((0xc0-0x44)/4))shl 5)or 20)
        for(i in 0x48 until 0x78 step 4)w(i,0xd503201f.toInt())
        w(0x78,0x8b090000.toInt()or(cmd shl 5));w(0x7c,0xd503201f.toInt());w(0x80,0xd63f0100.toInt())
        w(0x84,A64.branch(c+0x84,c+if(wrongJoin)0xe8 else 0xe4))
        w(0xc0,0xd503201f.toInt());w(0xc4,0xd503201f.toInt());w(0xc8,A64.branch(c+0xc8,c+0xe0))
        w(0xe0,A64.branch(c+0xe0,c+0x300)or 0x80000000.toInt());w(0xe4,0xd65f03c0.toInt())
        w(0x100,if(bypass)A64.branch(c+0x100,c+0xc4)else 0xd65f03c0.toInt())
        w(0x400,0xd503233f.toInt())
        if(terminatorBypass){w(20,0xb4000002.toInt()or(((0x40-20)/4)shl 5));w(24,0x3820683f or(index shl 16))}
        return F(PeImage.parse(b),KblDispatcherFacts.Facts(d,d+8,d+16,8,16,24,c+0x80,
            mapOf("headLoad" to c+0x40,"suffixAdd" to c+0x78)),c+0x300,c+0xc8)
    }
    private fun check(f:F){val r=KblLazyEntryFacts.analyze(f.image,f.dispatch,f.fail)
        assertEquals(f.hook,r.hookRva);assertEquals(f.fail,A64.blTarget(r.failCallRva,f.image.word(r.failCallRva)!!))
        assertEquals(65L,r.evidence["commandCapacity"])}
    @Test fun unknownOnlyEdgePreservesOriginalFailCall(){check(fixture())}
    @Test fun movedFunctionsAndCommandRegisters(){check(fixture(0x9000,0x23000,22,23))}
    @Test fun nodeCopyAndGapBeforeEmptyBranchKeepTheUnknownOnlyEntry(){
        val f=fixture();val b=f.image.bytes.copyOf()
        fun put(pc:Long,w:Int){b.putWord(requireNotNull(f.image.rvaToOffset(pc,4)),w)}
        put(0x1044,0xaa1403eb.toInt()) // mov x11,x20
        put(0x1048,0xd503201f.toInt())
        put(0x104c,0xb4000000.toInt() or (((0x10c0-0x104c)/4) shl 5) or 11)
        check(f.copy(image=PeImage.parse(b)))
    }
    @Test fun nopBeforeKnownExitAndFlagsPreservingClampGapAreAccepted(){
        val f=fixture();val b=f.image.bytes.copyOf()
        fun put(pc:Long,w:Int){b.putWord(requireNotNull(f.image.rvaToOffset(pc,4)),w)}
        put(0x1004,0xf101001f.toInt())
        put(0x1008,0x52800808)
        put(0x100c,0xd503201f.toInt())
        put(0x1010,0x9a883015.toInt()) // csel x21,x0,x8,lo
        put(0x1014,0xaa0103f3.toInt()) // mov x19,x1
        put(0x1018,0x3835683f) // strb wzr,[x1,x21]
        put(0x1084,0xd503201f.toInt())
        put(0x1088,A64.branch(0x1088,0x10e4))
        check(f.copy(image=PeImage.parse(b)))
    }
    @Test fun rejectUnprovedBufferAndPointer(){for(f in listOf(fixture(noTerminator=true),fixture(wrongOrigin=true)))
        assertThrows(PatchException::class.java){KblLazyEntryFacts.analyze(f.image,f.dispatch,f.fail)}}
    @Test fun rejectExternalEntryIntoUnknownBlock(){val f=fixture(bypass=true)
        assertThrows(PatchException::class.java){KblLazyEntryFacts.analyze(f.image,f.dispatch,f.fail)}}
    @Test fun rejectMismatchedKnownContinuation(){val f=fixture(wrongJoin=true)
        assertThrows(PatchException::class.java){KblLazyEntryFacts.analyze(f.image,f.dispatch,f.fail)}}
    @Test fun rejectTerminatorWhichDoesNotDominateUnknownEdge(){val f=fixture(terminatorBypass=true)
        assertThrows(PatchException::class.java){KblLazyEntryFacts.analyze(f.image,f.dispatch,f.fail)}}
}
