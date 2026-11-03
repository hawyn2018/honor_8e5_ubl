package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test

/** Synthetic firmware roles move independently; these do not model real GPIO/USB. */
class FastbootRecoveryFactsTest {
    private data class Fixture(val image: PeImage, val c: Long, val d: Long,
        val base: Int, val field: Int, val status: Int)

    private fun fixture(c: Long = 0x1000, d: Long = 0x9000, base: Int = 26,
        field: Int = 0x40, status: Int = 19, noScanLog: Boolean = false,
        extraFatalGate: Boolean = false, separateBootTarget: Boolean = false,
        wrongContinueFlag: Boolean = false,canaryWidth:Int=0,abiRegister:Int?=null,
        initializeConstant:Boolean=true,stackInput:Int?=null,initializeStack:Boolean=false,
        unknownCheckpointSp:Boolean=false,inlineCanary:Boolean=false): Fixture {
        val b=ByteArray(0x3400)
        b[0]=77; b[1]=90; b.putWord(0x3c,0x80); b.putWord(0x80,0x4550)
        b.putWord(0x84,0x0002aa64); b.putWord(0x94,0x202200f0); b.putWord(0x98,0x20b)
        b.putWord(0xa8,c.toInt()); b.putWord(0xb8,0x1000); b.putWord(0xbc,0x200)
        b.putWord(0xd0,(d+0x1000).toInt()); b.putWord(0xd4,0x400); b.putWord(0x104,16)
        for (i in 0..1) {
            val at=0x188+i*40; (if(i==0)".text" else ".data").toByteArray().copyInto(b,at)
            b.putWord(at+8,if(i==0)0x2000 else 0x1000); b.putWord(at+12,(if(i==0)c else d).toInt())
            b.putWord(at+16,if(i==0)0x2000 else 0x1000); b.putWord(at+20,if(i==0)0x400 else 0x2400)
            b.putWord(at+36,if(i==0)0x60000020 else 0xc0000040.toInt())
        }
        fun at(rva: Long)=if(rva in c..<c+0x2000)0x400+(rva-c).toInt() else 0x2400+(rva-d).toInt()
        fun w(pc: Long, word: Int) { b.putWord(at(pc),word) }
        fun adr(pc:Long, target:Long, register:Int) {
            val n=target-pc
            w(pc,0x10000000 or((n.toInt()and 3)shl 29)or(((n.toInt()shr 2)and 0x7ffff)shl 5)or register)
        }
        fun q(rva:Long,value:Long) { b.putWord(at(rva),value.toInt()); b.putWord(at(rva)+4,(value ushr 32).toInt()) }
        fun text(rva:Long, value:String) { (value+"\u0000").toByteArray().copyInto(b,at(rva)) }
        fun bl(pc:Long, target:Long)=w(pc,A64.branch(pc,target)or 0x80000000.toInt())
        fun tb(pc:Long,target:Long,register:Int,bit:Int)=w(pc,0x37000000 or
            ((bit and 31)shl 19)or((bit ushr 5)shl 31)or((((target-pc)/4).toInt()and 0x3fff)shl 5)or register)
        for(i in 0 until 0x2000 step 4)w(c+i,0xd503201f.toInt())
        text(d+0x200,"No fastboot support for flashless chipsets, Infinte loop")
        text(d+0x280,"couldnt init fastboot , exiting")
        text(d+0x300,if(noScanLog)"Other text" else "ScanCode = (0x%x)")
        text(d+0x340,"Key Stroke Read")
        text(d+0x380,"continue")
        text(d+0x3c0,"Error reading key status")
        text(d+0x400,"LinuxLoader detector init")
        w(c,0xd503233f.toInt()); w(c+4,0xd10083ff.toInt())
        w(c+8,0xa9014ffe.toInt()); w(c+0x20,0xa9414ffe.toInt())
        w(c+0x24,0x910083ff.toInt());w(c+0x28,0xd50323bf.toInt())
        w(c+0x2c,A64.branch(c+0x2c,c+0x400))
        w(c+0x400,0xd503233f.toInt()); w(c+0x404,0xd10403ff.toInt()) // sub sp,sp,#0x100
        adr(c+0x408,d,base)
        if(abiRegister!=null&&initializeConstant) {
            w(c+0x418,0x52800000 or(5 shl 5)or abiRegister)
            w(c+0x41c,0x72a00000 or(1 shl 5)or abiRegister)
        }
        if(stackInput!=null&&initializeStack) {
            w(c+0x420,0x5280156a)
            w(c+0x424,0xf9000000.toInt()or((stackInput/8)shl 10)or(31 shl 5)or 10)
        }
        if(unknownCheckpointSp)w(c+0x448,0x9100029f.toInt()) // SP := unknown incoming X20
        adr(c+0x44c,d+0x400,1); bl(c+0x450,c+0x1800)
        w(c+0x480,0x9100a3e0.toInt()); bl(c+0x484,c+0x1600) // out SP+0x28
        adr(c+0x490,d+0x3c0,1); bl(c+0x494,c+0x1800)
        w(c+0x700,0x39400000 or(field shl 10)or(base shl 5)or 8)
        tb(c+0x704,c+0x800,8,0)
        w(c+0x70c,0x910043e0.toInt()); bl(c+0x710,c+0x1900)
        w(c+0x714,0xb4000040.toInt()); bl(c+0x720,c+0x1940)
        w(c+0x780,0x910403ff.toInt()); w(c+0x784,0xd65f03c0.toInt())
        if(canaryWidth!=0) {
            val load=if(canaryWidth==8)0xf9400000.toInt() else 0xb9400000.toInt()
            val store=if(canaryWidth==8)0xf9000000.toInt() else 0xb9000000.toInt()
            adr(c+0x40c,d+0x80,8);w(c+0x410,load or(8 shl 5)or 8)
            w(c+0x414,store or((0xf8/canaryWidth)shl 10)or(31 shl 5)or 8)
            adr(c+0x780,d+0x80,9);w(c+0x784,0x5280062a);w(c+0x788,store or(9 shl 5)or 10)
            w(c+0x78c,load or((0xf8/canaryWidth)shl 10)or(31 shl 5)or 8);bl(c+0x790,c+0x1a00)
            w(c+0x794,0x54000001 or((((0x940-0x794)/4))shl 5))
            w(c+0x798,0x910403ff.toInt());w(c+0x79c,0xd65f03c0.toInt())
            w(c+0x1a00,0xd503245f.toInt());w(c+0x1a04,load or(9 shl 5)or 9)
            w(c+0x1a08,if(canaryWidth==8)0xeb08013f.toInt() else 0x6b08013f)
            w(c+0x1a0c,0xd65f03c0.toInt());q(d+0x80,0x12345678)
            if(inlineCanary) {
                w(c+0x790,load or(9 shl 5)or 9)
                w(c+0x794,if(canaryWidth==8)0xeb09011f.toInt() else 0x6b09011f)
                w(c+0x798,0x54000001 or(((0x940-0x798)/4)shl 5))
                w(c+0x79c,0x910403ff.toInt());w(c+0x7a0,0xd65f03c0.toInt())
            }
        }
        tb(c+0x800,c+0x900,status,63)
        if(abiRegister!=null) {
            w(c+0x804,0xf9400000.toInt()or(base shl 5)or 8)
            w(c+0x808,0x913fec01.toInt()or(abiRegister shl 5)) // ADD X1,revision,#0xffb
            w(c+0x80c,0xeb00001f.toInt()or(abiRegister shl 16)or(8 shl 5))
        }
        if(stackInput!=null)w(c+0x810,0xf9400000.toInt()or((stackInput/8)shl 10)or(31 shl 5)or 9)
        adr(c+0x820,d+0x280,1); bl(c+0x824,c+0x1800); w(c+0x828,A64.branch(c+0x828,c+0x780))
        w(c+0x850,0x39400000 or((field+4)shl 10)or(base shl 5)or 8)
        adr(c+0x900,d+0x200,1); bl(c+0x908,c+0x1800); w(c+0x90c,A64.branch(c+0x90c,c+0x90c))
        if(extraFatalGate)tb(c+0x880,c+0x900,status,63)
        w(c+0x1000,0xd503233f.toInt()); w(c+0x1004,0xd10043ff.toInt())
        adr(c+0x1008,d,19); w(c+0x100c,0x910023e0.toInt()); bl(c+0x1010,c+0x1900)
        w(c+0x1014,0xb4000060.toInt()); w(c+0x1018,A64.branch(c+0x1018,c+0x1030))
        w(c+0x1020,0x52800029); w(c+0x1024,0x39000000 or
            ((field+if(wrongContinueFlag)8 else 4)shl 10)or(19 shl 5)or 9)
        w(c+0x1028,0x910023e0.toInt()); bl(c+0x102c,c+if(separateBootTarget)0x1980 else 0x1940)
        w(c+0x1030,0x910043ff.toInt()); w(c+0x1034,0xd65f03c0.toInt())
        w(c+0x1600,0xd503233f.toInt()); adr(c+0x1604,d+0x300,1); bl(c+0x1608,c+0x1800)
        adr(c+0x160c,d+0x340,1); bl(c+0x1610,c+0x1800); w(c+0x1614,0xd65f03c0.toInt())
        for(a in listOf(0x1700,0x1800,0x1900,0x1940,0x1980)) {
            w(c+a,0xd503233f.toInt()); w(c+a+4,0xd65f03c0.toInt())
        }
        q(d+0x100,d+0x380); q(d+0x108,c+0x1000)
        return Fixture(PeImage.parse(b),c,d,base,field,status)
    }
    private fun verify(f:Fixture) {
        val facts=FastbootRecoveryFacts.analyze(f.image)
        assertEquals(f.c+0x400,facts.main.start)
        assertEquals(f.c+0x2c,facts.entryTailBranchRva)
        assertEquals(f.c+4,facts.entryPrefix.hookRva)
        assertEquals(0x20L,facts.entryPrefix.entryFrameBytes)
        assertTrue(facts.entryPrefix.tailRestoresEntryFrame)
        assertFalse(facts.entryPrefix.overwrittenWordPcRelative)
        assertEquals(listOf(0xd503233f.toInt(),0xd10083ff.toInt()),facts.entryPrefix.replayWords)
        assertEquals(f.c+0x484,facts.keyReadCallRva)
        assertEquals(0x28L,facts.keyResultStackOffset)
        assertEquals(f.c+0x800,facts.fastbootGateRva)
        assertEquals(f.c+0x804,facts.fastbootSetupRva)
        assertEquals(f.field,facts.requestFlagByteOffset)
        assertEquals(f.base,facts.requestFlagBaseRegister)
        assertEquals(f.status,facts.cardInfoGateRegister)
        assertEquals(f.c+0x784,facts.commonReturnRva)
        assertEquals(0x100L,facts.frame.bytes)
        assertTrue(facts.frame.returnRestoresFrame)
        assertEquals(f.d+f.field+4,facts.continueFlow.flagRva)
        assertEquals(f.c+0x1940,facts.continueFlow.bootLinuxTargetRva)
        assertTrue(facts.continueFlow.invokesChildBootLinux)
        assertTrue(facts.continueFlow.returnedBootLinuxNeedsFlagClear)
        assertTrue(facts.prerequisites.note,facts.prerequisites.earlyJumpProven)
        assertEquals(f.c+0x454,facts.prerequisites.checkpointRva)
        assertEquals(f.c+0x450,facts.diagnosticSites["detectorInitLogCall"])
    }
    @Test fun namedRolesAndControlEdgesAreLinked(){verify(fixture())}
    @Test fun independentlyMovedTextDataRegistersAndFields(){verify(fixture(0x9000,0x23000,23,0xa0,24))}
    @Test fun keyProviderNeedsBothItsOwnDiagnosticRoles(){assertThrows(PatchException::class.java){verify(fixture(noScanLog=true))}}
    @Test fun ambiguousFatalGateIsNotGuessed(){assertThrows(PatchException::class.java){verify(fixture(extraFatalGate=true))}}
    @Test fun continueMustCallTheSameChildBootLinux(){assertThrows(PatchException::class.java){verify(fixture(separateBootTarget=true))}}
    @Test fun continueExitFlagMustBeReadByMain(){assertThrows(PatchException::class.java){verify(fixture(wrongContinueFlag=true))}}
    @Test fun guardRvaWidthSavedSlotAndCompareAreLinkedWithoutMatchingItsValue() {
        for(width in listOf(4,8)) {
            val f=fixture(canaryWidth=width)
            val facts=FastbootRecoveryFacts.analyze(f.image)
            val guard=assertNotNullCanary(facts)
            assertEquals(f.d+0x80,guard.globalRva);assertEquals(width,guard.width)
            assertEquals(0xf8,guard.stackOffset);assertEquals(f.c+0x414,guard.saveRva)
            assertEquals(f.c+0x78c,guard.savedReadRva);assertEquals(f.c+0x790,guard.compareCallRva)
            assertEquals(f.c+0x1a08,guard.compareRva);assertEquals(f.c+0x788,guard.resetStoreRva)
        }
    }
    @Test fun earlyEntryRematerializesAddressAndMoveWideConstantFromTheirDataflow() {
        for((base,revision) in listOf(26 to 27,23 to 25)) {
            val f=fixture(base=base,abiRegister=revision)
            val bytes=f.image.bytes.copyOf();val p=FastbootRecoveryFacts.analyze(f.image).prerequisites
            assertTrue(p.note,p.earlyJumpProven)
            assertEquals(setOf(base,revision),p.liveInRegisters)
            assertEquals(f.d,p.materializableGlobalRegisters[base])
            assertEquals(0x10005L,p.materializableConstants[revision])
            assertEquals(-0x100,p.checkpointStackOffset)
            assertArrayEquals("analysis must preserve all normal firmware paths",bytes,f.image.bytes)
        }
    }
    @Test fun unknownIncomingValuePreventsTheEarlyJumpRatherThanInventingARevision() {
        val p=FastbootRecoveryFacts.analyze(fixture(abiRegister=24,initializeConstant=false).image).prerequisites
        assertFalse(p.earlyJumpProven)
        assertTrue(p.unsupportedReasons.any { it.contains("X24") })
    }
    @Test fun earlyCheckpointMustAlreadyContainEveryNativeStackInput() {
        val uninitialized=FastbootRecoveryFacts.analyze(fixture(stackInput=0x30).image).prerequisites
        assertFalse(uninitialized.earlyJumpProven)
        assertEquals(8,uninitialized.nativeStackInputs[0x30])
        assertTrue(uninitialized.unsupportedReasons.any { it.contains("SP+30/8") })
        val initialized=FastbootRecoveryFacts.analyze(fixture(stackInput=0x30,initializeStack=true).image).prerequisites
        assertTrue(initialized.note,initialized.earlyJumpProven)
    }
    @Test fun ambiguousCheckpointStackPointerPreventsTheEarlyJump() {
        val p=FastbootRecoveryFacts.analyze(fixture(unknownCheckpointSp=true).image).prerequisites
        assertFalse(p.earlyJumpProven)
        assertTrue(p.unsupportedReasons.any { it.contains("SP") })
    }
    @Test fun anInlinedGuardKeepsTheSameSaveResetCompareAndFailureRelationship() {
        for(width in listOf(4,8)) {
            val f=fixture(canaryWidth=width,inlineCanary=true)
            val facts=FastbootRecoveryFacts.analyze(f.image);val g=assertNotNullCanary(facts)
            assertNull(g.compareCallRva);assertEquals(f.c+0x794,g.compareRva)
            assertEquals(f.c+0x798,g.failureBranchRva);assertEquals(width,g.width)
            assertTrue(facts.prerequisites.note,facts.prerequisites.earlyJumpProven)
        }
    }
    @Test fun actualOldAndNewFamiliesHaveCompleteEarlyNativeFastbootInputs() {
        val paths=listOf("analysis/honor-sm8850-abl-compare/abl-inner-LinuxLoader.pe",
            "analysis/ablnew-compat/ablnew-inner.pe")
        val root=generateSequence(java.io.File(".").absoluteFile){it.parentFile}
            .firstOrNull { it.resolve(paths[0]).isFile&&it.resolve(paths[1]).isFile }
        org.junit.Assume.assumeNotNull(root)
        for(path in paths) {
            val image=PeImage.parse(requireNotNull(root).resolve(path).readBytes())
            val bytes=image.bytes.copyOf();val f=FastbootRecoveryFacts.analyze(image)
            assertTrue("$path: ${f.prerequisites.note}",f.prerequisites.earlyJumpProven)
            val route=requireNotNull(f.prerequisites.preparationRoute)
            assertEquals(route.entryRva,f.prerequisites.recoveryEntryRva)
            assertEquals(image.word(route.successGateRva),route.originalSuccessWord)
            assertTrue(route.afterPreparationGlobalRegisters.isEmpty())
            assertTrue(route.afterPreparationConstants.isEmpty())
            assertTrue(f.prerequisites.preservedLiveInRegisters.contains(requireNotNull(f.cardInfo).statusRegister))
            assertEquals(-f.frame.bytes,f.prerequisites.checkpointStackOffset?.toLong())
            assertArrayEquals(bytes,image.bytes)
            val prefix=requireNotNull(f.scopePrefix)
            assertEquals(f.fastbootSetupRva,prefix.startRva)
            assertEquals(prefix.startRva+12,prefix.loggerCallRva)
            assertEquals(prefix.loggerCallRva+4,prefix.resumeRva)
            assertEquals(4,prefix.originalWords.size)
            assertEquals(1,prefix.formatRegister)
            assertTrue(prefix.noExternalEntryProven)
        }
    }
    private fun scopeFixture(c:Long=0x1000,d:Long=0x9000,formatRegister:Int=1):Fixture {
        val f=fixture(c,d);val b=f.image.bytes.copyOf();val setup=c+0x804
        fun w(pc:Long,x:Int){b.putWord(requireNotNull(f.image.rvaToOffset(pc,4)),x)}
        val delta=d+0x480-setup
        w(setup,0x10000000 or((delta.toInt()and 3)shl 29)or
            (((delta.toInt()shr 2)and 0x7ffff)shl 5)or formatRegister)
        w(setup+4,0x52800000 or(0x40 shl 5)or(1-formatRegister))
        w(setup+8,0xd503201f.toInt())
        w(setup+12,A64.branch(setup+12,c+0x1800)or 0x80000000.toInt())
        "Launching fastboot\n\u0000".toByteArray().copyInto(b,requireNotNull(f.image.rvaToOffset(d+0x480)))
        return f.copy(image=PeImage.parse(b))
    }
    @Test fun theWholeLaunchingPrefixMovesWithItsCommonLoggerCall() {
        for((c,d,r) in listOf(Triple(0x1000L,0x9000L,1),Triple(0x11000L,0x29000L,0))) {
            val f=scopeFixture(c,d,r);val bytes=f.image.bytes.copyOf()
            val prefix=requireNotNull(FastbootRecoveryFacts.discoverScopePrefix(f.image,
                FastbootRecoveryFacts.FunctionRange(c+0x400,c+0x1000),c+0x804,c+0x1800))
            assertEquals(c+0x804,prefix.startRva);assertEquals(c+0x810,prefix.loggerCallRva)
            assertEquals(c+0x814,prefix.resumeRva);assertEquals(c+0x1800,prefix.loggerTargetRva)
            assertEquals(d+0x480,prefix.formatRva);assertEquals(r,prefix.formatRegister)
            assertTrue(prefix.noExternalEntryProven);assertEquals(4,prefix.originalWords.size)
            assertArrayEquals(bytes,f.image.bytes)
        }
    }
    @Test fun unalignedInteriorNumbersAndAddressLiteralsAreNotArm64FunctionEntries() {
        for(kind in listOf("stored","address")) {
            val f=scopeFixture();val setup=f.c+0x804;val changed=f.image.bytes.copyOf()
            val target=setup+5 // Inside the range, but not an ARM64 instruction.
            if(kind=="stored") {
                val raw=requireNotNull(f.image.rvaToOffset(f.d+0x180,8))
                changed.putWord(raw,target.toInt());changed.putWord(raw+4,0)
            } else {
                val pc=f.c+0x600;val delta=target-pc
                changed.putWord(requireNotNull(f.image.rvaToOffset(pc,4)),0x10000000 or
                    ((delta.toInt()and 3)shl 29)or(((delta.toInt()shr 2)and 0x7ffff)shl 5)or 9)
            }
            val prefix=FastbootRecoveryFacts.discoverScopePrefix(PeImage.parse(changed),
                FastbootRecoveryFacts.FunctionRange(f.c+0x400,f.c+0x1000),setup,f.c+0x1800)
            assertNotNull(kind,prefix)
            assertEquals(setup,requireNotNull(prefix).startRva)
            assertTrue(prefix.noExternalEntryProven)
        }
    }
    @Test fun alignedInteriorStoredPointersAndCodeAddressesStillPreventMovingThePrefix() {
        for(kind in listOf("stored","address")) {
            val f=scopeFixture();val setup=f.c+0x804;val changed=f.image.bytes.copyOf()
            val target=setup+4
            if(kind=="stored") {
                val raw=requireNotNull(f.image.rvaToOffset(f.d+0x180,8))
                changed.putWord(raw,target.toInt());changed.putWord(raw+4,0)
            } else {
                val pc=f.c+0x600;val delta=target-pc
                changed.putWord(requireNotNull(f.image.rvaToOffset(pc,4)),0x10000000 or
                    ((delta.toInt()and 3)shl 29)or(((delta.toInt()shr 2)and 0x7ffff)shl 5)or 9)
            }
            assertNull(kind,FastbootRecoveryFacts.discoverScopePrefix(PeImage.parse(changed),
                FastbootRecoveryFacts.FunctionRange(f.c+0x400,f.c+0x1000),setup,f.c+0x1800))
        }
    }
    @Test fun theLaunchingPrefixRejectsSideEffectsAndInteriorEntries() {
        val f=scopeFixture();val c=f.c;val d=f.d;val setup=c+0x804
        fun reject(change:(ByteArray)->Unit) {
            val b=f.image.bytes.copyOf();change(b)
            assertNull(FastbootRecoveryFacts.discoverScopePrefix(PeImage.parse(b),
                FastbootRecoveryFacts.FunctionRange(c+0x400,c+0x1000),setup,c+0x1800))
        }
        fun w(b:ByteArray,pc:Long,x:Int){b.putWord(requireNotNull(f.image.rvaToOffset(pc,4)),x)}
        reject { w(it,setup+4,0xf90003e0.toInt()) } // STR to caller stack
        reject { w(it,setup+4,0x910043ff.toInt()) } // ADD SP,SP,#16
        reject { w(it,setup+4,0xd53b4200.toInt()) } // system-register access
        reject { w(it,setup+4,0x4e201c00) } // SIMD
        reject { w(it,setup+4,A64.branch(setup+4,c+0x1900)or 0x80000000.toInt()) }
        reject { w(it,c+0x600,A64.branch(c+0x600,setup+4)) }
        reject { w(it,c+0x600,A64.branch(c+0x600,setup+4)or 0x80000000.toInt()) }
        reject { w(it,c+0x600,0xb4000000.toInt()or((((setup+4-(c+0x600))/4).toInt()and 0x7ffff)shl 5)) }
        reject { w(it,c+0x600,0x37000000 or((((setup+4-(c+0x600))/4).toInt()and 0x3fff)shl 5)) }
        reject { w(it,c+0x600,0x54000000 or((((setup+4-(c+0x600))/4).toInt()and 0x7ffff)shl 5)) }
        reject { val delta=setup+8-(c+0x600);w(it,c+0x600,0x10000000 or
            ((delta.toInt()and 3)shl 29)or(((delta.toInt()shr 2)and 0x7ffff)shl 5)or 9) }
        reject { val raw=requireNotNull(f.image.rvaToOffset(d+0x180,8));it.putWord(raw,(setup+4).toInt());it.putWord(raw+4,0) }
        reject { // An exception entry is independently addressed without a BL.
            it.putWord(0x98+112+3*8,(d+0x500).toInt());it.putWord(0x98+116+3*8,8)
            w(it,d+0x500,(setup+8).toInt());w(it,d+0x504,1)
        }
    }
    private fun preparationFixture(c:Long=0x1000,d:Long=0x9000,base:Int=26,status:Int=19,
        revision:Int=27,finalStatus:Int=0,missingFinalGate:Boolean=false,missingOutPointer:Boolean=false,
        invertFinalGate:Boolean=false):Fixture {
        val f=fixture(c,d,base=base,status=status);val bytes=f.image.bytes.copyOf()
        fun w(pc:Long,value:Int){bytes.putWord(requireNotNull(f.image.rvaToOffset(pc,4)),value)}
        fun adr(pc:Long,address:Long,r:Int){val delta=address-pc;w(pc,0x10000000 or
            ((delta.toInt()and 3)shl 29)or(((delta.toInt()shr 2)and 0x7ffff)shl 5)or r)}
        fun tb(pc:Long,address:Long,r:Int)=w(pc,0xb7f80000.toInt()or
            ((((address-pc)/4).toInt()and 0x3fff)shl 5)or r)
        fun locate(root:Long,outOffset:Int,guid:Long) {
            w(root,0xf9400000.toInt()or((0x80/8)shl 10)or(base shl 5)or 8)
            adr(root+4,guid,0);w(root+8,0x910003e2.toInt()or(outOffset shl 10))
            w(root+12,0xaa1f03e1.toInt());w(root+16,0xf940a108.toInt());w(root+20,0xd63f0100.toInt())
        }
        w(c+0x430,0xd63f0100.toInt());w(c+0x434,0xaa0003e0.toInt()or status)
        tb(c+0x438,c+0x500,status);w(c+0x43c,A64.branch(c+0x43c,c+0x67c))
        adr(c+0x67c,d+0x400,1);w(c+0x680,A64.branch(c+0x680,c+0x1800)or 0x80000000.toInt())
        w(c+0x500,0x52800034);w(c+0x504,0x52800176);w(c+0x508,0x2a1f03f5)
        locate(c+0x50c,0x70,d+0x20);tb(c+0x524,c+0x780,0)
        if(missingOutPointer)w(c+0x514,0xaa1f03e2.toInt())
        w(c+0x528,0xf9403be0.toInt());w(c+0x52c,0x52800000 or(4 shl 5)or revision)
        w(c+0x530,0x72a00000 or(2 shl 5)or revision);w(c+0x534,0xf9400008.toInt())
        w(c+0x538,0xeb00001f.toInt()or(revision shl 16)or(8 shl 5))
        w(c+0x53c,0x54000009 or(((0x548-0x53c)/4)shl 5))
        w(c+0x540,0xf9405c08.toInt());w(c+0x544,0xd63f0100.toInt())
        locate(c+0x548,0x80,d+0x30)
        if(finalStatus==0)adr(c+0x560,d+0x80,21) else w(c+0x560,0xaa0003e0.toInt()or finalStatus)
        val branchDestination=if(invertFinalGate)0x780 else 0x580
        w(c+0x564,if(missingFinalGate)0xd503201f.toInt() else
            (if(invertFinalGate)0xb5000000.toInt() else 0xb4000000.toInt())or
            (((branchDestination-0x564)/4)shl 5)or finalStatus)
        w(c+0x568,A64.branch(c+0x568,c+if(invertFinalGate)0x580 else 0x780))
        return f.copy(image=PeImage.parse(bytes))
    }
    private fun preparation(f:Fixture)=FastbootRecoveryFacts.discoverPreparationRoute(f.image,
        FastbootRecoveryFacts.FunctionRange(f.c+0x400,f.c+0x1000),f.c+0x684,f.d+0x80,
        FastbootRecoveryFacts.CardInfo(f.c+0x430,f.c+0x434,f.status,f.c+0x438))
    @Test fun nativePreparationDiscoveryFollowsProtocolRolesAcrossMovementAndReturnRegisterChanges() {
        for(f in listOf(preparationFixture(),preparationFixture(0x9000,0x23000,23,24,25,22))) {
            val original=f.image.bytes.copyOf();val route=requireNotNull(preparation(f))
            assertEquals(f.c+0x500,route.entryRva);assertEquals(f.c+0x564,route.successGateRva)
            assertEquals(f.c+0x580,route.successDestinationRva)
            assertEquals(f.image.word(route.successGateRva),route.originalSuccessWord)
            assertArrayEquals(original,f.image.bytes)
        }
    }
    @Test fun nativePreparationNeedsItsActualSuccessGateAndStackOutParameter() {
        assertNull(preparation(preparationFixture(missingFinalGate=true)))
        assertNull(preparation(preparationFixture(missingOutPointer=true)))
    }
    @Test fun missingProtocolEnvironmentNeverPermitsAnUnprovedDirectNativeSetupJump() {
        val f=fixture();val bytes=f.image.bytes.copyOf()
        bytes.putWord(requireNotNull(f.image.rvaToOffset(f.c+0x804,4)),0xd63f0100.toInt())
        val p=FastbootRecoveryFacts.analyze(PeImage.parse(bytes)).prerequisites
        assertFalse(p.earlyJumpProven)
        assertTrue(p.unsupportedReasons.any { it.contains("preparation") })
    }
    @Test fun invertedNativeStatusGuardKeepsItsOriginalNonzeroErrorEdge() {
        val f=preparationFixture(0x19000,0x43000,23,24,25,22,invertFinalGate=true)
        val original=f.image.bytes.copyOf();val route=requireNotNull(preparation(f))
        assertEquals(f.c+0x564,route.successGateRva);assertEquals(22,route.statusRegister)
        assertEquals(f.c+0x568,route.successDestinationRva)
        assertEquals(0xb5000000.toInt(),route.originalSuccessWord and 0xff000000.toInt())
        assertArrayEquals(original,f.image.bytes)
    }
    @Test fun externalNativeControlEdgesAreRejectedInsteadOfSilentlyDropped() {
        for(conditional in listOf(false,true)) {
            val f=fixture();val bytes=f.image.bytes.copyOf();val pc=f.c+0x804;val to=f.c+0x1600
            val word=if(conditional)0xb5000000.toInt()or((((to-pc)/4).toInt()and 0x7ffff)shl 5)
                else A64.branch(pc,to)
            bytes.putWord(requireNotNull(f.image.rvaToOffset(pc,4)),word)
            val p=FastbootRecoveryFacts.analyze(PeImage.parse(bytes)).prerequisites
            assertFalse(p.earlyJumpProven);assertTrue(p.unsupportedReasons.any { it.contains("边界") })
        }
    }
    @Test fun onlyTheFullyVerifiedCoreRegistrationAdapterMayBeSummarized() {
        val relative="repairs/abl-efisp-1.3.7-fastboot-recovery-20261031/source/abl-efisp-core/build/fastboot-recovery-fixtures"
        val root=generateSequence(java.io.File(".").absoluteFile){it.parentFile}
            .firstOrNull { it.resolve("$relative/old-freedom-native.img").isFile }
        org.junit.Assume.assumeNotNull(root)
        val names=listOf("old-freedom-native.img","old-freedom_fake_lock-native.img",
            "new-freedom-native.img","new-freedom_fake_lock-native.img")
        for(name in names) {
            val file=requireNotNull(root).resolve("$relative/$name")
            org.junit.Assume.assumeTrue(file.isFile)
            val image=PeImage.parse(file.readBytes());val original=image.bytes.copyOf()
            val facts=FastbootRecoveryFacts.analyze(image);val p=facts.prerequisites
            assertTrue("$name: ${p.note}",p.earlyJumpProven)
            assertNotNull("$name first Launching prefix",facts.scopePrefix)
            val summary=p.summarizedNativeAdapters.entries.single()
            val stub=requireNotNull(image.word(summary.key)?.let { A64.bTarget(summary.key,it) })
            assertNotEquals(summary.value,image.word(summary.key))
            assertArrayEquals("the real registration wrapper must remain installed",original,image.bytes)
            // Corrupt its save-frame setup while retaining all its names and
            // other apparent roles. Full byte verification must reject it.
            val damaged=image.bytes.copyOf()
            damaged.putWord(requireNotNull(image.rvaToOffset(stub+8,4)),0xd503201f.toInt())
            val invalid=FastbootRecoveryFacts.analyze(PeImage.parse(damaged)).prerequisites
            assertFalse(invalid.earlyJumpProven)
            assertTrue(invalid.summarizedNativeAdapters.isEmpty())
            assertTrue(invalid.unsupportedReasons.any { it.contains("边界") })
        }
    }
    private fun branchingReadOnlyFixture(pointer:Int=9,byte:Int=10,postIndex:Boolean=false,
        invalid:String?=null,stackArgument:Boolean=false):Fixture {
        val f=fixture();val b=f.image.bytes.copyOf()
        fun w(pc:Long,value:Int){b.putWord(requireNotNull(f.image.rvaToOffset(pc,4)),value)}
        val text=f.d+0x500;val pc=f.c+0x804;val delta=text-pc
        w(pc,if(stackArgument)0x9100c3e0.toInt() else 0x10000000 or((delta.toInt()and 3)shl 29)or
            (((delta.toInt()shr 2)and 0x7ffff)shl 5))
        w(f.c+0x808,A64.branch(f.c+0x808,f.c+0x1b00)or 0x80000000.toInt())
        // Keep the unrelated continue-poll role independently addressable even
        // when a negative helper intentionally violates the helper ABI.
        val basePc=f.c+0x80c;val baseDelta=f.d-basePc
        w(basePc,0x10000000 or((baseDelta.toInt()and 3)shl 29)or
            (((baseDelta.toInt()shr 2)and 0x7ffff)shl 5)or f.base)
        "ab\u0000".toByteArray().copyInto(b,requireNotNull(f.image.rvaToOffset(text,3)))
        w(f.c+0x1b00,0xaa0003e0.toInt()or pointer);w(f.c+0x1b04,0x52800000)
        w(f.c+0x1b08,(if(postIndex)0x38401400 else 0x39400000)or(pointer shl 5)or byte)
        w(f.c+0x1b0c,if(postIndex)0xd503201f.toInt() else 0x91000400.toInt()or(pointer shl 5)or pointer)
        w(f.c+0x1b10,0x34000000 or(3 shl 5)or byte);w(f.c+0x1b14,0x11000400)
        w(f.c+0x1b18,A64.branch(f.c+0x1b18,f.c+0x1b08));w(f.c+0x1b1c,0xd65f03c0.toInt())
        when(invalid) {
            "store"->w(f.c+0x1b0c,0x39000000 or(pointer shl 5)or byte)
            "stack"->w(f.c+0x1b0c,0xf94003eb.toInt())
            "call"->w(f.c+0x1b0c,A64.branch(f.c+0x1b0c,f.c+0x1800)or 0x80000000.toInt())
        }
        return f.copy(image=PeImage.parse(b))
    }
    @Test fun closedReadOnlyIntegerLeafLoopsPreserveAbiAcrossRegisterAndLoadFormChanges() {
        for(f in listOf(branchingReadOnlyFixture(),branchingReadOnlyFixture(14,13),
            branchingReadOnlyFixture(postIndex=true))) {
            val original=f.image.bytes.copyOf();val p=FastbootRecoveryFacts.analyze(f.image).prerequisites
            assertTrue(p.note,p.earlyJumpProven);assertArrayEquals(original,f.image.bytes)
        }
    }
    @Test fun aBranchingHelperWithWritesStackCallsOrNonvolatileChangesRemainsUnproved() {
        val invalid=listOf(branchingReadOnlyFixture(invalid="store"),branchingReadOnlyFixture(invalid="stack"),
            branchingReadOnlyFixture(invalid="call"),branchingReadOnlyFixture(pointer=19),
            branchingReadOnlyFixture(stackArgument=true))
        for(f in invalid) {
            val p=FastbootRecoveryFacts.analyze(f.image).prerequisites
            assertFalse(p.earlyJumpProven);assertTrue(p.unsupportedReasons.any { it.contains("helper") })
        }
    }
    @Test fun actualHnublCustomizedLongLabelAndKblImageRetainsAProvenRecoveryRoute() {
        val relative="analysis/kbl-136-efi-unlimited-20261031/efisp136-current-production.img"
        val file=generateSequence(java.io.File(".").absoluteFile){it.parentFile}
            .map { it.resolve(relative) }.firstOrNull { it.isFile }
        org.junit.Assume.assumeNotNull(file)
        val image=PeImage.parse(requireNotNull(file).readBytes());val original=image.bytes.copyOf()
        val facts=FastbootRecoveryFacts.analyze(image);val p=facts.prerequisites
        assertTrue(p.note,p.earlyJumpProven);assertNotNull(p.preparationRoute)
        assertNotNull(facts.scopePrefix)
        assertArrayEquals(original,image.bytes)
    }
    private fun assertNotNullCanary(f:FastbootRecoveryFacts.Facts):FastbootRecoveryFacts.Canary {
        assertNotNull(f.canary);return requireNotNull(f.canary)
    }
}
