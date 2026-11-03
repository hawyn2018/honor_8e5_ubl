package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test

class FastbootRecoverySectionsTest {
    private fun image(): PeImage {
        val b=ByteArray(0x5000)
        b[0]=0x4d; b[1]=0x5a; b.putWord(0x3c,0x800); b.putWord(0x800,0x4550)
        b[0x804]=0x64; b[0x805]=0xaa.toByte(); b[0x806]=2; b[0x814]=0xf0.toByte()
        val o=0x818; val t=o+0xf0
        b[o]=0x0b; b[o+1]=2; b.putWord(o+4,0x2000); b.putWord(o+8,0x2000)
        b.putWord(o+16,0x1000); b.putWord(o+32,0x1000); b.putWord(o+36,0x200)
        b.putWord(o+56,0x5000); b.putWord(o+60,0x1000); b.putWord(o+108,16)
        listOf(".text",".data").forEachIndexed { i,name ->
            val at=t+i*40; val r=0x1000+i*0x2000
            name.toByteArray().copyInto(b,at); b.putWord(at+8,0x2000); b.putWord(at+12,r)
            b.putWord(at+16,0x2000); b.putWord(at+20,r)
            b.putWord(at+36,if(i==0)0x60000020 else 0xc0000040.toInt())
        }
        b.putWord(0x1000,0xd503233f.toInt()); b.putWord(0x1004,0xd10083ff.toInt())
        return PeImage.parse(b)
    }
    private fun patched(source:PeImage):ByteArray {
        val before=source.bytes.copyOfRange(0x1004,0x1008)
        val after=ByteArray(4).also { it.putWord(0,0xd503201f.toInt()) }
        val edits=listOf(FastbootRecoverySections.Edit(0x1004,before,after))
        val l=FastbootRecoverySections.append(source,FastbootRecoverySections.receiptBytes(source,edits).size+256,4096)
        after.copyInto(l.output,0x1004); l.output[l.rx.raw]=0x7f
        FastbootRecoverySections.writeReceipt(l,source,edits,64)
        return l.output
    }
    @Test fun exactRoundtripPreservesOriginalEntryAndMappings() {
        val source=image(); val output=patched(source); val pe=PeImage.parse(output)
        assertEquals(source.entryRva,pe.entryRva); assertEquals(source.sections,pe.sections.take(2))
        assertTrue(pe.sections[2].executable); assertFalse(pe.sections[2].writable)
        assertTrue(pe.sections[3].writable); assertFalse(pe.sections[3].executable)
        assertArrayEquals(source.bytes,FastbootRecoverySections.remove(output).bytes)
    }
    @Test fun unownedPostPatchTextEditSurvivesRemoval() {
        val source=image(); val output=patched(source); output[0x3180]=0x63
        val expected=source.bytes.copyOf().also { it[0x3180]=0x63 }
        assertArrayEquals(expected,FastbootRecoverySections.remove(output).bytes)
    }
    @Test fun disposableOwnedCodeDamageIsRebuiltFromIndependentReceipt() {
        val source=image(); val output=patched(source); val pe=PeImage.parse(output)
        output[pe.sections[2].raw+8]=0x42
        assertArrayEquals(source.bytes,FastbootRecoverySections.remove(output).bytes)
    }
    @Test fun receiptDamageStopsRatherThanGuessingNativeHooks() {
        val output=patched(image()); val rx=PeImage.parse(output).sections[2]
        val footer=rx.raw+rx.rawSize-FastbootRecoverySections.FOOTER_BYTES
        output[footer-1]=(output[footer-1].toInt() xor 1).toByte()
        assertThrows(PatchException::class.java) { FastbootRecoverySections.remove(output) }
    }
    @Test fun anotherPatcherChangingAHookIsNotSilentlyOverwritten() {
        val output=patched(image()); output.putWord(0x1004,0xd65f03c0.toInt())
        assertThrows(PatchException::class.java) { FastbootRecoverySections.remove(output) }
    }
    @Test fun relocatedHeaderRoundtripPreservesOriginalHeaderBytes() {
        val source=image(); val b=source.bytes.copyOf()
        // Occupy just the next section slot; the independent unused DOS gap is
        // available for relocating the NT table without moving mapped sections.
        val at=0x818+0xf0+2*40; b[at]=0x51
        val input=PeImage.parse(b); val output=patched(input)
        assertNotEquals(input.bytes.u32(0x3c),output.u32(0x3c))
        assertArrayEquals(input.bytes,FastbootRecoverySections.remove(output).bytes)
    }
}
