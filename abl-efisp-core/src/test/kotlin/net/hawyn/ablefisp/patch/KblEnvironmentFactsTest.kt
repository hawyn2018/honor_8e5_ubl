package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test

class KblEnvironmentFactsTest {
    private data class Fixture(val image: PeImage, val data: Long, val stores: List<Long>)
    private fun fixture(code: Long = 0x1000, data: Long = 0x4000, sys: Int = 20,
                        global: Int = 9, adr: Boolean = false, spill: Boolean = false,
                        bypassBootLoad: Boolean = false, clobberBootLoad: Boolean = false): Fixture {
        val b = ByteArray(0x1400)
        b[0] = 0x4d; b[1] = 0x5a
        b.putWord(0x3c, 0x80); b.putWord(0x80, 0x4550); b.putWord(0x84, 0x0002aa64)
        b.putWord(0x94, 0x202200f0); b.putWord(0x98, 0x20b)
        b.putWord(0xa8, code.toInt()); b.putWord(0xb8, 0x1000); b.putWord(0xbc, 0x200)
        b.putWord(0xd0, ((data and -4096L) + 0x1000).toInt()); b.putWord(0xd4, 0x400)
        b.putWord(0x104, 16)
        val sections = listOf(Triple(code, 0x400, 0x60000020), Triple(data and -4096L, 0xc00, 0xc0000040.toInt()))
        sections.forEachIndexed { i, (rva, raw, flags) ->
            val at = 0x188 + i * 40
            (if (i == 0) ".text" else ".data").toByteArray().copyInto(b, at)
            b.putWord(at + 8, 0x800); b.putWord(at + 12, rva.toInt())
            b.putWord(at + 16, 0x800); b.putWord(at + 20, raw); b.putWord(at + 36, flags)
        }
        val insns = arrayListOf<Int>(); val stores = arrayListOf<Long>()
        fun emit(w: Int) { insns += w }
        fun str(rt: Int, rn: Int, off: Int) {
            stores += code + insns.size * 4
            emit(0xf9000000.toInt() or ((off / 8) shl 10) or (rn shl 5) or rt)
        }
        fun ldr(rt: Int, rn: Int, off: Int) = emit(0xf9400000.toInt() or ((off / 8) shl 10) or (rn shl 5) or rt)
        emit(0xd503233f.toInt())
        if (spill) {
            emit(0xd10083ff.toInt()); str(1, 31, 0); ldr(sys, 31, 0)
        } else emit(0xaa0103e0.toInt() or sys)
        val pc = code + insns.size * 4
        if (adr) {
            val delta = data - pc
            emit(0x10000000 or ((delta.toInt() and 3) shl 29) or (((delta shr 2).toInt() and 0x7ffff) shl 5) or global)
        } else {
            val delta = ((data and -4096L) - (pc and -4096L)) shr 12
            emit(0x90000000.toInt() or ((delta.toInt() and 3) shl 29) or (((delta shr 2).toInt() and 0x7ffff) shl 5) or global)
            if (data and 4095L != 0L) emit(0x91000000.toInt() or ((data.toInt() and 4095) shl 10) or (global shl 5) or global)
        }
        str(0, global, 0); str(sys, global, 8)
        if (bypassBootLoad) emit(0xb4000040.toInt()) // CBZ X0 skips the table load.
        ldr(10, sys, 0x60)
        if (clobberBootLoad) emit(0xaa0003ea.toInt()) // MOV X10,X0 replaces its value.
        str(10, global, 16)
        ldr(11, sys, 0x58); str(11, global, 24)
        if (spill) emit(0x910083ff.toInt())
        emit(0xd50323bf.toInt()); emit(0xd65f03c0.toInt())
        emit(0xd503233f.toInt()); emit(0xd65f03c0.toInt())
        insns.forEachIndexed { i, w -> b.putWord(0x400 + i * 4, w) }
        return Fixture(PeImage.parse(b), data, stores)
    }
    private fun check(f: Fixture) {
        val result = KblEnvironmentFacts.analyze(f.image)
        assertEquals(f.data, result.imageHandleGlobal)
        assertEquals(f.data + 8, result.systemTableGlobal)
        assertEquals(f.data + 16, result.bootServicesGlobal)
        assertEquals(f.data + 24, result.runtimeServicesGlobal)
    }
    @Test fun derivesEfiRolesWithIndependentRawRvaMappings() { check(fixture()) }
    @Test fun followsMovedCodeDataAndRegisterAllocation() { check(fixture(0x9000, 0x20000, 22, 14)) }
    @Test fun understandsAdrAsAnAddressRatherThanAnAdrpPage() { check(fixture(0x1000, 0x8458, 23, 12, adr = true)) }
    @Test fun followsSystemTableSpillAndReload() { check(fixture(spill = true)) }
    @Test fun rejectsAPathWhichStoresWithoutLoadingTheTableField() {
        assertThrows(PatchException::class.java) { KblEnvironmentFacts.analyze(fixture(spill = true, bypassBootLoad = true).image) }
    }
    @Test fun rejectsAClobberedFieldLoadEvenWithAnExactGlobalAddress() {
        assertThrows(PatchException::class.java) { KblEnvironmentFacts.analyze(fixture(spill = true, clobberBootLoad = true).image) }
    }
    @Test fun doesNotAcceptReadOnlyStorageForMutableEfiGlobals() {
        val f = fixture(); val bytes = f.image.bytes.copyOf()
        bytes.putWord(0x188 + 40 + 36, 0x40000040)
        assertThrows(PatchException::class.java) { KblEnvironmentFacts.analyze(PeImage.parse(bytes)) }
    }
    @Test fun doesNotGuessBootServicesFromAnotherSystemTableField() {
        val f = fixture(); val bytes = f.image.bytes.copyOf()
        val load = f.stores[2] - 4
        bytes.putWord(requireNotNull(f.image.rvaToOffset(load)), 0xf9402a8a.toInt()) // X20+0x50, not +0x60
        assertThrows(PatchException::class.java) { KblEnvironmentFacts.analyze(PeImage.parse(bytes)) }
    }
}
