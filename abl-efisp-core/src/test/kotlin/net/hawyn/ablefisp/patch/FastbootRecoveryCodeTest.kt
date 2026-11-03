package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test

class FastbootRecoveryCodeTest {
    @Test fun saveAndRestoreUseAlignedIdenticalRealFrame() {
        val e=FastbootRecoveryPatcher.Code(0x100000);e.save();e.restore()
        val b=e.bytes()
        assertEquals(0xd10443ff.toInt(),b.word(0));assertEquals(0x910443ff.toInt(),b.word(b.size-4))
        val stores=(4 until 64 step 4).map { b.word(it) }
        val loads=(84 until 144 step 4).map { b.word(it) }
        // Fifteen pairs plus independent x30/NZCV must all fit the 272-byte frame.
        assertEquals(15,stores.size);assertEquals(15,loads.size)
        stores.zip(loads).forEach { (store,load) -> assertEquals(store or 0x00400000,load) }
        assertEquals(0xf9007bfe.toInt(),b.word(64)) // x30 at SP+240.
        assertEquals(0xf9007fe9.toInt(),b.word(72)) // NZCV snapshot at SP+248.
    }
    @Test fun conditionalReplayKeepsBothOriginalEdgesWithoutShortRangeAssumption() {
        val e=FastbootRecoveryPatcher.Code(0x800000)
        // TBNZ x9,#63 from native pc0x1000 to0x1100.
        val original=0xb7f80009.toInt() or (64 shl 5)
        e.replay(0x1000,original)
        val b=e.bytes()
        assertEquals((original and (0x3fff shl 5).inv()) or (2 shl 5),b.word(0))
        assertEquals(0x1004L,A64.bTarget(0x800004,b.word(4)))
        assertEquals(0x1100L,A64.bTarget(0x800008,b.word(8)))
    }
    @Test fun adrReplayRecomputesTheOriginalTargetWithPicAddressing() {
        val e=FastbootRecoveryPatcher.Code(0x800000)
        e.replay(0x1000,0x10000809) // adr x9,0x1100
        val b=e.bytes(); val page=A64.adrp(0x800000,b.word(0))!!
        assertEquals(9,page.first)
        assertEquals(0x1100L,page.second+A64.add(b.word(4))!!.immediate)
    }
    @Test fun literalLoadRecomputesAddressRatherThanLoadingNewCodeBytes() {
        val e=FastbootRecoveryPatcher.Code(0x800000)
        e.replay(0x1000,0x580000a9) // LDR X9, native PC+20.
        val b=e.bytes();val page=A64.adrp(0x800000,b.word(0))!!
        assertEquals(0x1014L,page.second+A64.add(b.word(4))!!.immediate)
        assertEquals(0xf9400129.toInt(),b.word(8))
    }
    @Test fun simdLiteralPreservesItsScratchAndStack() {
        val e=FastbootRecoveryPatcher.Code(0x800000);e.replay(0x1000,0x9c0000a9.toInt())
        val b=e.bytes()
        assertEquals(0xd10043ff.toInt(),b.word(0));assertEquals(0x910043ff.toInt(),b.word(b.size-4))
        assertEquals(0xf90003f1.toInt(),b.word(4));assertEquals(0xf94003f1.toInt(),b.word(b.size-8))
        assertEquals(0x3dc00229,b.word(16))
    }
}
