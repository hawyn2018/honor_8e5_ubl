package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test

class A64ValueOriginsTest {
    @Test fun copyCyclesConvergeFromTheirExternalDefinition() {
        val image = synthetic(mapOf(
            0x1000 to csel(21), 0x1004 to storeW(21, 31, 0x3c),
            0x1008 to conditional(0x34000000, 0x1008, 0x1020),
            0x100c to loadW(8, 31, 0x3c), 0x1010 to storeW(8, 31, 0x3c),
            0x1014 to A64.branch(0x1014, 0x1008),
            0x1020 to loadW(8, 31, 0x3c), 0x1028 to RET,
        ))
        val origins = A64ValueOrigins(image, 0x1000, 0x1030)
        assertTrue(origins.derivesFrom(0x1024, 8, 0x1000, 21))
        assertTrue(origins.stackDerivesFrom(0x1024, 0x3c, 4, 0x1000, 21))
    }

    @Test fun mergesAdmitOnlyThePolicyAndZero() {
        val bytes = linkedMapOf(
            0x1000 to csel(21), 0x1004 to storeW(21, 31, 0x3c),
            0x1008 to conditional(0x34000000, 0x1008, 0x1020),
            0x100c to storeW(31, 31, 0x3c), 0x1010 to A64.branch(0x1010, 0x1024),
            0x1024 to loadW(8, 31, 0x3c), 0x102c to RET,
        )
        val origins = A64ValueOrigins(synthetic(bytes), 0x1000, 0x1030)
        assertFalse(origins.derivesFrom(0x1028, 8, 0x1000, 21))
        assertTrue(origins.derivesFromOrZero(0x1028, 8, 0x1000, 21))
        bytes[0x100c] = 0x52800048 // MOV W8,#2
        bytes[0x1010] = storeW(8, 31, 0x3c)
        bytes[0x1014] = A64.branch(0x1014, 0x1024)
        assertFalse(A64ValueOrigins(synthetic(bytes), 0x1000, 0x1030).derivesFromOrZero(0x1028, 8, 0x1000, 21))
    }

    @Test fun stackAliasesAndUnscaledLoadsKeepTheSource() {
        val origins = A64ValueOrigins(synthetic(mapOf(
            0x1000 to csel(21), 0x1004 to add(9, 31, 0x3c),
            0x1008 to 0xaa0903ea.toInt(), // MOV X10,X9
            0x100c to storeW(21, 10, 0),
            0x1010 to 0xb843c3e8.toInt(), // LDUR W8,[SP,#0x3C]
            0x1018 to RET,
        )), 0x1000, 0x1020)
        assertEquals(0x3c, origins.stackAddress(0x100c, 10))
        assertTrue(origins.derivesFrom(0x1014, 8, 0x1000, 21))
    }

    @Test fun changingTheFrameDoesNotReuseThePreviousSlot() {
        val origins = A64ValueOrigins(synthetic(mapOf(
            0x1000 to csel(21), 0x1004 to storeW(21, 31, 0x3c),
            0x1008 to add(31, 31, 16), 0x100c to loadW(8, 31, 0x3c), 0x1014 to RET,
        )), 0x1000, 0x1020)
        assertFalse(origins.derivesFromOrZero(0x1010, 8, 0x1000, 21))
    }

    @Test fun checkedExternalTableWritesDoNotAliasTheSpill() {
        val origins = A64ValueOrigins(tableImage(), 0x1000, 0x1090)
        assertTrue(origins.derivesFrom(0x1038, 8, 0x1008, 21))
    }

    @Test fun aTableWriteOverlappingItsGuardedCounterIsRejected() {
        val origins = A64ValueOrigins(tableImage(arrayOffset = 0x138), 0x1000, 0x1090)
        assertFalse(origins.derivesFromOrZero(0x1038, 8, 0x1008, 21))
    }

    @Test fun anUnsignedUpperBoundCannotBeReplacedWithTheOppositeCondition() {
        val origins = A64ValueOrigins(tableImage(guardCondition = 3), 0x1000, 0x1090)
        assertFalse(origins.derivesFromOrZero(0x1038, 8, 0x1008, 21))
    }

    @Test fun outlinedLeafClobbersAreProvedWithoutAHelperAddressList() {
        val origins = A64ValueOrigins(synthetic(mapOf(
            0x1000 to csel(8), 0x1004 to (A64.branch(0x1004, 0x1040) or 0x80000000.toInt()),
            0x100c to RET, 0x1040 to 0x52800001, 0x1044 to RET, // Helper writes only W1.
        )), 0x1000, 0x1020)
        assertTrue(origins.derivesFrom(0x1008, 8, 0x1000, 8))
    }

    @Test fun onlyAProvenNonzeroClosedLoopRemovesCallFallthrough() {
        fun program(flag: Int) = synthetic(mapOf(
            0x1000 to csel(21), 0x1004 to conditional(0x34000000, 0x1004, 0x1014),
            0x1008 to (A64.branch(0x1008, 0x1080) or 0x80000000.toInt()),
            0x100c to 0x52800055, // MOV W21,#2 on a returned error path.
            0x1014 to storeW(21, 31, 0x3c), 0x1018 to loadW(8, 31, 0x3c), 0x1020 to RET,
            0x1080 to (0x52800008 or (flag shl 5)), 0x1084 to storeW(8, 31, 12),
            0x1088 to loadW(8, 31, 12), 0x108c to conditional(0x35000008, 0x108c, 0x1088), 0x1090 to RET,
        ))
        assertTrue(A64ValueOrigins(program(1), 0x1000, 0x1040).derivesFrom(0x101c, 8, 0x1000, 21))
        assertFalse(A64ValueOrigins(program(0), 0x1000, 0x1040).derivesFromOrZero(0x101c, 8, 0x1000, 21))
    }

    @Test fun fieldBaseMustStillComeFromTheEntryArgument() {
        val code = linkedMapOf(0x1000 to 0xaa0003f4.toInt(), 0x1004 to loadW(8, 20, 4), 0x100c to RET)
        assertTrue(A64ValueOrigins(synthetic(code), 0x1000, 0x1010).derivesFromEntry(0x1004, 20, 0))
        code[0x1000] = 0xaa0103f4.toInt() // MOV X20,X1
        code[0x1008] = 0xaa0003f4.toInt() // Unrelated late copy of X0.
        assertFalse(A64ValueOrigins(synthetic(code), 0x1000, 0x1010).derivesFromEntry(0x1004, 20, 0))
    }

    private fun tableImage(arrayOffset: Int = 0x130, guardCondition: Int = 8): PeImage = synthetic(mapOf(
        0x1000 to 0xaa0003f3.toInt(), // MOV X19,X0: caller-owned object
        0x1004 to add(23, 19, arrayOffset), 0x1008 to csel(21), 0x100c to storeW(21, 31, 0x5c),
        0x1010 to mem(0xf9400000.toInt(), 2, 19, 0x430, 8),
        0x1014 to 0xf1007c5f.toInt(), // CMP X2,#31
        0x1018 to conditional(0x54000000 or guardCondition, 0x1018, 0x1080),
        0x101c to 0x5280030c, // MOV W12,#24
        0x1020 to 0x9b0c7c4b.toInt(), // MUL X11,X2,X12
        0x1024 to 0xf82b6ae9.toInt(), // STR X9,[X23,X11]
        0x1028 to mem(0xf9400000.toInt(), 11, 19, 0x430, 8),
        0x102c to 0x9b0c5d6c.toInt(), // MADD X12,X11,X12,X23
        0x1030 to mem(0xf9000000.toInt(), 8, 12, 16, 8),
        0x1034 to loadW(8, 31, 0x5c), 0x103c to RET, 0x1080 to RET,
    ))

    private fun csel(rd: Int) = 0x1a8813e0 or rd // CSEL Wd,WZR,W8,NE
    private fun add(rd: Int, rn: Int, amount: Int) = 0x91000000.toInt() or (amount shl 10) or (rn shl 5) or rd
    private fun mem(base: Int, rt: Int, rn: Int, offset: Int, width: Int) = base or ((offset / width) shl 10) or (rn shl 5) or rt
    private fun loadW(rt: Int, rn: Int, offset: Int) = mem(0xb9400000.toInt(), rt, rn, offset, 4)
    private fun storeW(rt: Int, rn: Int, offset: Int) = mem(0xb9000000.toInt(), rt, rn, offset, 4)
    private fun conditional(base: Int, pc: Int, target: Int) = base or ((((target - pc) / 4) and 0x7ffff) shl 5)

    private fun synthetic(code: Map<Int, Int>): PeImage {
        val bytes = ByteArray(0x3000)
        fun half(at: Int, value: Int) { bytes[at] = value.toByte(); bytes[at + 1] = (value ushr 8).toByte() }
        bytes[0] = 0x4d; bytes[1] = 0x5a
        bytes.putWord(0x3c, 0x80); bytes.putWord(0x80, 0x4550)
        half(0x84, 0xaa64); half(0x86, 1); half(0x94, 0xf0)
        val optional = 0x98
        half(optional, 0x20b); bytes.putWord(optional + 16, 0x1000)
        bytes.putWord(optional + 32, 0x1000); bytes.putWord(optional + 36, 0x1000)
        bytes.putWord(optional + 56, 0x3000); bytes.putWord(optional + 60, 0x1000)
        val section = optional + 0xf0
        ".text".toByteArray().copyInto(bytes, section)
        bytes.putWord(section + 8, 0x2000); bytes.putWord(section + 12, 0x1000)
        bytes.putWord(section + 16, 0x2000); bytes.putWord(section + 20, 0x1000)
        bytes.putWord(section + 36, 0x60000020)
        for (at in 0x1000 until bytes.size step 4) bytes.putWord(at, 0xd503201f.toInt())
        code.forEach { (pc, word) -> bytes.putWord(pc, word) }
        return PeImage.parse(bytes)
    }

    private companion object { val RET = 0xd65f03c0.toInt() }
}
