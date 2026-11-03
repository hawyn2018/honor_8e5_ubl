package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test

class KblUsbDescriptorFactsTest {
    private fun fixture(code:Long=0x1000,data:Long=0x5000,objectReg:Int=21,methodReg:Int=9,
        wrongSlot:Boolean=false,methodOffset:Int=0x48,clobber:Boolean=false,
        nonGlobal:Boolean=false,alias:Boolean=false,ambiguous:Boolean=false):PeImage {
        val bytes=ByteArray(0x2400)
        bytes[0]=77;bytes[1]=90;bytes.putWord(0x3c,0x80);bytes.putWord(0x80,0x4550)
        bytes.putWord(0x84,0x0002aa64);bytes.putWord(0x94,0x202200f0);bytes.putWord(0x98,0x20b)
        bytes.putWord(0xa8,code.toInt());bytes.putWord(0xb8,0x1000);bytes.putWord(0xbc,0x200)
        bytes.putWord(0xd0,(data+0x1000).toInt());bytes.putWord(0xd4,0x400);bytes.putWord(0x104,16)
        for(i in 0..1){val at=0x188+i*40;(if(i==0)".text" else ".data").toByteArray().copyInto(bytes,at)
            bytes.putWord(at+8,0x1000);bytes.putWord(at+12,(if(i==0)code else data).toInt())
            bytes.putWord(at+16,0x1000);bytes.putWord(at+20,0x400+i*0x1000)
            bytes.putWord(at+36,if(i==0)0x60000020 else 0xc0000040.toInt())}
        var pc=code
        fun emit(w:Int){bytes.putWord(0x400+(pc-code).toInt(),w);pc+=4}
        fun adr(r:Int,t:Long){val v=t-pc;emit(0x10000000 or((v.toInt()and 3)shl 29)or(((v shr 2).toInt()and 0x7ffff)shl 5)or r)}
        fun mov(d:Int,s:Int)=emit(0xaa0003e0.toInt()or(s shl 16)or d)
        fun function(at:Long,descriptor:Long){pc=at;emit(0xd503233f.toInt())
            adr(19,data+if(wrongSlot)8 else 0)
            emit(0xf9400000.toInt()or(19 shl 5)or objectReg)
            emit(0xf9400000.toInt()or((methodOffset/8)shl 10)or(objectReg shl 5)or methodReg)
            if(nonGlobal)mov(0,1) else if(alias){adr(20,descriptor);mov(0,20)}else adr(0,descriptor)
            if(clobber)mov(methodReg,1)
            emit(0xd63f0000.toInt()or(methodReg shl 5));emit(0xd65f03c0.toInt())}
        function(code,data+0x100)
        if(ambiguous)function(code+0x100,data+0x200)
        pc=code+0x200;emit(0xd503233f.toInt());emit(0xd65f03c0.toInt())
        return PeImage.parse(bytes)
    }
    @Test fun derivesOriginalStartExDescriptorWithoutStartupHook(){
        val f=KblUsbDescriptorFacts.analyze(fixture(),0x5000)!!
        assertEquals(0x5100L,f.descriptorSetRva);assertEquals(0x1014L,f.evidence["startExCall"])
    }
    @Test fun toleratesAddressAndRegisterChangesAndArgumentAlias(){
        assertEquals(0x20100L,KblUsbDescriptorFacts.analyze(fixture(0x9000,0x20000,22,12,alias=true),0x20000)!!.descriptorSetRva)
    }
    @Test fun rejectsAnotherProtocolObject(){assertNull(KblUsbDescriptorFacts.analyze(fixture(wrongSlot=true),0x5000))}
    @Test fun rejectsAnotherMethod(){assertNull(KblUsbDescriptorFacts.analyze(fixture(methodOffset=0x10),0x5000))}
    @Test fun rejectsClobberedIndirectTarget(){assertNull(KblUsbDescriptorFacts.analyze(fixture(clobber=true),0x5000))}
    @Test fun rejectsCallerOwnedPointer(){assertNull(KblUsbDescriptorFacts.analyze(fixture(nonGlobal=true),0x5000))}
    @Test fun rejectsAmbiguousStartExSites(){assertNull(KblUsbDescriptorFacts.analyze(fixture(ambiguous=true),0x5000))}
    @Test fun rejectsNonWritableProtocolSlot(){assertNull(KblUsbDescriptorFacts.analyze(fixture(),0x1000))}
}
