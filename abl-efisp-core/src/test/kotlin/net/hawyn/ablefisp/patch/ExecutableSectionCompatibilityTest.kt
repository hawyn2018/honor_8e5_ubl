package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test

class ExecutableSectionCompatibilityTest {
    private val table=0x188
    private val rx=0x60000020L
    private fun fixture(tails:List<Pair<String,Long>>,overlay:Boolean=false):ByteArray {
        val sections=listOf(".text" to rx)+tails
        val rawEnd=0x400+sections.size*0x200
        val b=ByteArray(rawEnd+if(overlay)64 else 0)
        b[0]=0x4d;b[1]=0x5a;b.putWord(0x3c,0x80);b.putWord(0x80,0x4550)
        b.putWord(0x84,(sections.size shl 16) or 0xaa64);b.putWord(0x94,0x202200f0)
        b.putWord(0x98,0x20b);b.putWord(0x9c,sections.size*0x200);b.putWord(0xa8,0x1000)
        b.putWord(0xb0,0x40000000);b.putWord(0xb4,1)
        b.putWord(0xb8,0x1000);b.putWord(0xbc,0x200)
        b.putWord(0xd0,(sections.size+1)*0x1000);b.putWord(0xd4,0x400);b.putWord(0xd8,0x12345678)
        b.putWord(0x104,16)
        sections.forEachIndexed { i,(name,flags)->
            require(name.length<=8);val at=table+i*40
            name.toByteArray(Charsets.US_ASCII).copyInto(b,at)
            b.putWord(at+8,0x200);b.putWord(at+12,(i+1)*0x1000)
            b.putWord(at+16,0x200);b.putWord(at+20,0x400+i*0x200);b.putWord(at+36,flags.toInt())
            b.fill((0x31+i).toByte(),0x400+i*0x200,0x600+i*0x200)
        }
        b.putWord(0x400,0xd65f03c0.toInt())
        if(overlay){
            b.fill(0xa5.toByte(),rawEnd,b.size)
            b.putWord(0x98+112+4*8,rawEnd);b.putWord(0x98+116+4*8,64)
        }
        return b
    }
    @Test fun normalizesOnlyKnownRxHeaderNamesAndPreservesAllOtherBytes() {
        val source=fixture(listOf(".su_cmd" to rx,".bfpol" to rx,".eftxt" to rx),true)
        val saved=source.copyOf();val before=PeImage.parse(source)
        val result=ExecutableSectionCompatibility.normalize(source);val after=PeImage.parse(result.bytes)
        assertArrayEquals(saved,source);assertEquals(3,result.changes.size)
        assertEquals(listOf(".text",".textsu",".textpol",".texttxt"),after.sections.map{it.name})
        assertEquals(before.entryRva,after.entryRva);assertEquals(before.imageBase,after.imageBase)
        assertEquals(before.headersSize,after.headersSize);assertEquals(source.size,result.bytes.size)
        before.sections.zip(after.sections).forEach { (a,b)->assertEquals(a.copy(name=b.name),b) }
        val permitted=result.changes.flatMap{it.fileOffset until it.fileOffset+8}.toSet()
        for(i in source.indices)if(i !in permitted)assertEquals("byte 0x${i.toString(16)}",source[i],result.bytes[i])
        result.changes.forEach { change->
            assertEquals(change.fileOffset.toLong(),change.rva)
            assertEquals(source.copyOfRange(change.fileOffset,change.fileOffset+8).hex(),change.before)
            assertEquals(result.bytes.copyOfRange(change.fileOffset,change.fileOffset+8).hex(),change.after)
            assertFalse(change.alreadyApplied)
        }
        after.sections.drop(1).forEach { s->assertEquals('t',s.name[1]);assertEquals('e',s.name[2]) }
    }
    @Test fun leavesUnknownNamesAndNonRxOrWritableAliasesUnchanged() {
        val source=fixture(listOf(".su_cmd" to 0xc0000040L,".bfpol" to 0xe0000020L,
            ".eftxt" to 0x20000020L,".custom" to rx,".SU_CMD" to rx,".kblrx" to rx))
        val result=ExecutableSectionCompatibility.normalize(source)
        assertTrue(result.changes.isEmpty());assertArrayEquals(source,result.bytes)
    }
    @Test fun repeatedNormalizationAndCurrentNamesAreByteIdentical() {
        val source=fixture(listOf(".su_cmd" to rx,".textpol" to rx,".texttxt" to rx))
        val first=ExecutableSectionCompatibility.normalize(source);assertEquals(1,first.changes.size)
        val second=ExecutableSectionCompatibility.normalize(first.bytes)
        assertTrue(second.changes.isEmpty());assertArrayEquals(first.bytes,second.bytes)
        assertEquals(listOf(".text",".textsu",".textpol",".texttxt"),PeImage.parse(second.bytes).sections.map{it.name})
    }
}
