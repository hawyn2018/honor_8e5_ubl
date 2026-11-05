package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test

class KblPatcherTest {
    private fun pe(subsystem:Int=10, code:Long=0x1000, data:Long=0x5000):ByteArray {
        val b=ByteArray(0x1400)
        b[0]=0x4d;b[1]=0x5a;b.putWord(0x3c,0x80);b.putWord(0x80,0x4550)
        b.putWord(0x84,0x0002aa64);b.putWord(0x94,0x202200f0);b.putWord(0x98,0x20b)
        b.putWord(0x9c,0x800);b.putWord(0xa0,0x800);b.putWord(0xa8,code.toInt())
        b.putWord(0xb8,0x1000);b.putWord(0xbc,0x200);b.putWord(0xd0,(data+0x1000).toInt())
        b.putWord(0xd4,0x400);b.putWord(0xdc,subsystem);b.putWord(0x104,16)
        listOf(Triple(".text",code,0x60000020),Triple(".data",data,0xc0000040.toInt())).forEachIndexed { i,(n,r,f)->
            val at=0x188+i*40;n.toByteArray().copyInto(b,at);b.putWord(at+8,0x800);b.putWord(at+12,r.toInt())
            b.putWord(at+16,0x800);b.putWord(at+20,0x400+i*0x800);b.putWord(at+36,f)
        }
        b.putWord(0x400,0xd65f03c0.toInt())
        b.putWord(0x440,A64.branch(code+0x40,code+0x300) or 0x80000000.toInt())
        b.putWord(0x500,A64.branch(code+0x100,code+0x120))
        b.putWord(0x520,A64.branch(code+0x120,code+0xb0) or 0x80000000.toInt())
        return b
    }
    private fun install(bytes:ByteArray=pe(),driver:ByteArray=pe(11),usb:Boolean=false,descriptor:Long?=null,commandRegister:Int=19):KblPatcher.Result {
        val im=PeImage.parse(bytes);val c=im.sections[0].rva;val d=im.sections[1].rva
        val facts=KblEnvironmentFacts.Facts(d,d+8,d+16,d+24,emptyMap())
        val p=SuperUnlockPatcher.Plan(c+0x20,0,c+0x24,c+0x40,0,c+0x80,c+0x90,64,16,17,
            c+0xa0,c+0xb0,"ANDROID-BOOT!".toByteArray(),d+0x100,d+0x200,c+0x1c,20)
        val dispatcher=KblDispatcherFacts.Facts(d+0x50,d+0x60,d+0x70,8,16,24,c+0x200,emptyMap())
        val lazy=KblLazyEntryFacts.Facts(c+0x100,A64.branch(c+0x100,c+0x120),c+0x120,c+0x124,commandRegister,c,emptyMap())
        val transport=if(usb)KblUsbFacts.Facts(d+0x30,d+0x38,c+0x300,c+0x304,c+0x308,c+0x30c,c+0x310,
            d+0x40,d+0x48,c+0x314,c+0x318,c+0x31c,emptyMap())else null
        return KblPatcher.install(im,driver,facts,p,dispatcher,transport,lazy,
            descriptor?.let{KblUsbDescriptorFacts.Facts(it,emptyMap())})
    }
    private fun largeDriver(bytes:Int):ByteArray {
        require(bytes>=0x1400 && bytes%512==0)
        return pe(11).copyOf(bytes).also {
            val length=bytes-0xc00;val data=0x188+40
            it.putWord(data+8,length);it.putWord(data+16,length)
            it.putWord(0xd0,((0x5000L+length+4095)/4096*4096).toInt())
        }
    }
    private fun manySections(count:Int):ByteArray {
        val header=((0x188L+count*40L+120+4095)/4096*4096).toInt()
        val code=header.toLong()+0x10000;val data=code+0x10000
        val original=pe(code=code,data=data)
        return ByteArray(header+0x1000).also { out->
            original.copyInto(out,0,0,0x400)
            original.copyInto(out,header,0x400,0x1400)
            out.putWord(0x84,(count shl 16) or 0xaa64)
            out.putWord(0xd4,header)
            out.putWord(0x188+20,header);out.putWord(0x188+40+20,header+0x800)
            for(i in 2 until count)".empty".toByteArray().copyInto(out,0x188+i*40)
        }
    }
    @Test fun bundledDriverAboveTwoMiBKeepsHashAndStructureChecks() {
        val driver=largeDriver(2*1024*1024+512)
        assertSame(driver,BundledKblDriver.validateResource(driver,driver.sha256()))
        assertThrows(PatchException::class.java){BundledKblDriver.validateResource(driver,"0".repeat(64))}
        val broken=driver.copyOf();broken[0]=0
        assertThrows(PatchException::class.java){BundledKblDriver.validateResource(broken,broken.sha256())}
    }
    @Test fun driverAndAppendedMappingAboveSixtyMiBAreAcceptedWithoutMutatingInput() {
        val original=pe();val driver=largeDriver(60*1024*1024+512)
        val result=install(original,driver);val parsed=PeImage.parse(result.bytes)
        assertTrue(result.bytes.size>60*1024*1024)
        val embedded=parsed.sections.last();assertEquals(driver.size.toLong(),result.bytes.u32(embedded.raw+128+20))
        assertEquals(driver.sha256(),java.security.MessageDigest.getInstance("SHA-256")
            .also{it.update(result.bytes,embedded.raw+4096,driver.size)}.digest().hex(false))
        assertArrayEquals(pe(),original)
    }
    @Test fun actualSectionCountAllowsLargeTablesAndAdapterRoundTrip() {
        for(count in listOf(65,97)) {
            val input=manySections(count);assertEquals(count,PeImage.parse(input).sections.size)
            val result=install(input);assertEquals(count+3,PeImage.parse(result.bytes).sections.size)
            assertArrayEquals(input,KblPatcher.removeAdapter(result.bytes))
            assertTrue(KblPatcher.patch(result.bytes,pe(11)).unchanged)
            val damaged=result.bytes.copyOf();val raw=PeImage.parse(damaged).sections.last().raw
            damaged[raw+4096+80]=(damaged[raw+4096+80].toInt() xor 1).toByte()
            assertArrayEquals(input,KblPatcher.removeAdapterDetails(damaged).bytes)
        }
    }
    @Test fun realCoffCountAndHeaderBoundsRemainChecked() {
        val full=manySections(65535);assertEquals(65535,PeImage.parse(full).sections.size)
        val noSlots=manySections(65533)
        assertThrows(PatchException::class.java){install(noSlots)}
        val truncated=manySections(97);truncated.putWord(0x84,(65535 shl 16) or 0xaa64)
        assertThrows(PatchException::class.java){PeImage.parse(truncated)}
        val zero=manySections(97);zero.putWord(0x84,0xaa64)
        assertThrows(PatchException::class.java){PeImage.parse(zero)}
    }
    @Test fun appendsIsolatedPermissionsAndKeepsOriginalMappingsAndEveryNonheaderByte() {
        val b=pe();val saved=b.copyOf();val r=install(b);val old=PeImage.parse(b);val n=PeImage.parse(r.bytes)
        assertArrayEquals(saved,b);assertEquals(old.entryRva,n.entryRva);assertEquals(old.sections,n.sections.take(2))
        assertEquals(listOf(".textkbl",".kblctx",".kblimg"),n.sections.takeLast(3).map{it.name})
        assertEquals(listOf(0x60000020L,0xc0000040L,0x40000040L),n.sections.takeLast(3).map{it.characteristics})
        assertTrue(r.bytes.copyOfRange(n.sections[3].raw,n.sections[3].raw+n.sections[3].rawSize).all{it==0.toByte()})
        for(i in 0x400 until b.size) if(i !in 0x500..0x503)assertEquals("original byte $i",b[i],r.bytes[i])
        val blob=n.sections.last();assertArrayEquals(pe(11),r.bytes.copyOfRange(blob.raw+4096,blob.raw+4096+pe(11).size))
        assertFalse(n.isCode(blob.rva+4096));assertTrue(r.metadata["storeSupported"] as Boolean)
        val branch=n.word(0x1100)!!;assertEquals(r.metadata["shimRva"] as Long+((KblParentModule.bytes.size+15)/16*16+17*48+15)/16*16,A64.bTarget(0x1100,branch))
        assertNull(A64.blTarget(0x1100,branch));assertEquals(old.word(0x1040),n.word(0x1040))
    }
    @Test fun displayCommandsAreExactLazyEntriesWithDerivedCapacityAndRevision() {
        val r=install();val im=PeImage.parse(r.bytes);val raw=im.sections.last().raw
        assertEquals(3L,r.bytes.u32(raw+476))
        assertEquals(listOf("oem kblstart","oem kbladb","oem screenoff","oem screenon","oem screenstatus"),r.metadata["lazyExplicitCommands"])
        assertEquals(17L,r.bytes.u32(raw+512+12))
        assertTrue((r.metadata["lazyGateBytes"] as Int)>1024)
        assertTrue((r.metadata["lazyGateBytes"] as Int)<=(r.metadata["lazyGateCapacity"] as Int))
        val names=(0 until 17).map { n -> im.ascii(r.bytes.u64(raw+568+n*16)) }
        assertTrue(names.containsAll(listOf("oem screenoff","oem screenon","oem screenstatus")))
    }
    @Test fun originalDisplayPrefixCollisionIsRejectedBeforeMutation() {
        for (prefix in listOf("oem screen", "oem screenoff", "oem screenstatus")) {
            val b=pe();b.putWord(0xd00,0x5300);b.putWord(0xd08,0x10a0)
            (prefix+"\u0000").toByteArray().copyInto(b,0xf00);val before=b.copyOf()
            assertThrows(PatchException::class.java){install(b)};assertArrayEquals(before,b)
        }
    }
    @Test fun generatesGateFixturesForActualArm64ExactMatchValidation() {
        val root=java.io.File(System.getProperty("user.dir"),"build/kbl-gate-fixtures").apply { mkdirs() }
        for ((name,c,d) in listOf(Triple("base",0x1000L,0x5000L),Triple("relocated",0x110000L,0x190000L))) {
            val commandRegister=if(name=="base")19 else 22
            val input=pe(code=c,data=d);val result=install(input,commandRegister=commandRegister);val parsed=PeImage.parse(result.bytes)
            val rx=parsed.sections[parsed.sections.size-3]
            java.io.File(root,"$name.img").writeBytes(result.bytes)
            java.io.File(root,"$name.json").writeText("""{"baseCode":$c,"hook":${c+0x100},"fail":${c+0x120},"continue":${c+0x124},"commandRegister":$commandRegister,"module":${rx.rva},"moduleLazy":${rx.rva+KblParentModule.LAZY_START_OFFSET}}""")
            assertArrayEquals(input,KblPatcher.removeAdapter(result.bytes))
        }
    }
    @Test fun configContainsDerivedRvasAndNoAbsoluteRuntimePointers() {
        val r=install();val im=PeImage.parse(r.bytes);val o=im.sections.last().raw
        assertEquals(120L,r.bytes.u32(o+12));assertEquals(0x5000L,r.bytes.u64(o+16))
        assertEquals(0x10a0L,r.bytes.u64(o+48));assertEquals(0x10b0L,r.bytes.u64(o+56))
        assertEquals(im.sections[3].rva,r.bytes.u64(o+80));assertEquals(1L,r.bytes.u64(o+88))
    }
    @Test fun originalDescriptorRvaUsesExistingUsbReservedWordAndMissingMeansNoAdb(){
        val b=pe();b.putWord(0xd00,0x5300);b.putWord(0xd08,0x10a0)
        "getvar:\u0000".toByteArray().copyInto(b,0xf00)
        for(value in listOf<Long?>(null,0x5400L)){
            val r=install(b,usb=true,descriptor=value);val image=PeImage.parse(r.bytes);val raw=image.sections.last().raw
            assertEquals(88L,r.bytes.u32(raw+388));assertEquals(value?:0L,r.bytes.u32(raw+436))
            assertEquals(0L,r.bytes.u64(raw+392)) // runtime parent base remains zero template
            assertArrayEquals(b,KblPatcher.removeAdapter(r.bytes))
        }
        val before=b.copyOf();assertThrows(PatchException::class.java){install(b,usb=true,descriptor=0x1000)}
        assertArrayEquals(before,b)
    }
    @Test fun originalBroadOemPrefixIsRejectedInsteadOfInstallingUnreachableGate(){
        val b=pe();b.putWord(0xd00,0x5300);b.putWord(0xd08,0x10a0)
        "oem\u0000".toByteArray().copyInto(b,0xf00);val before=b.copyOf()
        assertThrows(PatchException::class.java){install(b)};assertArrayEquals(before,b)
    }
    @Test fun relocatedCodeAndDataNeedNoFixedFirmwareRvaTable() {
        val r=install(pe(code=0x110000,data=0x190000));val im=PeImage.parse(r.bytes)
        assertNotNull(A64.bTarget(0x110100,im.word(0x110100)!!))
        val cfg=im.sections.last().raw;assertEquals(0x190000L,r.bytes.u64(cfg+16))
    }
    @Test fun wrongDriverSubsystemAndOccupiedHeaderFailWithoutInputMutation() {
        val b=pe();val snap=b.copyOf()
        assertThrows(PatchException::class.java){install(b,pe(10))};assertArrayEquals(snap,b)
        b[0x188+80]=1;val s=b.copyOf()
        assertThrows(PatchException::class.java){install(b)};assertArrayEquals(s,b)
    }
    @Test fun repeatImportKeepsIdenticalBytesAndRejectsInvalidDriver() {
        val r=install();val repeated=KblPatcher.patch(r.bytes,pe(11));assertTrue(repeated.unchanged)
        assertArrayEquals(r.bytes,repeated.bytes)
        assertThrows(PatchException::class.java){KblPatcher.patch(r.bytes,pe(10))}
    }
    @Test fun repeatImportRejectsCodeConfigAndPermissionDamage() {
        val r=install();val im=PeImage.parse(r.bytes)
        val offsets=listOf(im.sections[2].raw+KblParentModule.bytes.size+8,
            im.sections[4].raw+16,im.sections[3].raw,0x188+2*40+36)
        for(at in offsets){val b=r.bytes.copyOf();b[at]=(b[at].toInt() xor 1).toByte()
            assertThrows(PatchException::class.java){KblPatcher.patch(b,pe(11))}}
    }
    @Test fun movesOnlyActiveNtHeaderIntoVerifiedZeroGapWhenSectionTableIsAtPageEnd() {
        val original=pe();val b=ByteArray(original.size+0xc00)
        original.copyInto(b,0,0,64);original.copyInto(b,0xe58,0x80,0x188+80)
        original.copyInto(b,0x1000,0x400,original.size);b.putWord(0x3c,0xe58)
        val o=0xe58+24;val table=o+0xf0;b.putWord(o+60,0x1000)
        b.putWord(table+20,0x1000);b.putWord(table+60,0x1800)
        val snap=b.copyOf();val r=install(b);val im=PeImage.parse(r.bytes)
        assertArrayEquals(snap,b);assertEquals(0x80L,r.bytes.u32(0x3c))
        assertEquals(PeImage.parse(b).sections,im.sections.take(2))
        assertArrayEquals(b.copyOfRange(0xe58,0xe58+24+240+80),r.bytes.copyOfRange(0xe58,0xe58+24+240+80))
        for(i in 0x1000 until b.size)if(i !in 0x1100..0x1103)assertEquals(b[i],r.bytes[i])
        val removed=KblPatcher.removeAdapterDetails(r.bytes);assertTrue(removed.hadAdapter);assertTrue(removed.fullyOriginal)
        assertArrayEquals(b,removed.bytes)
        val damaged=b.copyOf();damaged.fill(1,0x80,0xe58)
        assertThrows(PatchException::class.java){install(damaged)}
    }
    @Test fun removeAdapterRestoresExactOriginalAndPreservesLaterTextOrDataEdits() {
        val input=pe();val r=install(input)
        val clean=KblPatcher.removeAdapterDetails(r.bytes)
        assertTrue(clean.fullyOriginal);assertArrayEquals(input,clean.bytes)
        val edited=r.bytes.copyOf();edited[0xc80]=77
        val result=KblPatcher.removeAdapterDetails(edited)
        val expected=input.copyOf();expected[0xc80]=77
        assertTrue(result.hadAdapter);assertFalse(result.fullyOriginal);assertArrayEquals(expected,result.bytes)
        val broken=edited.copyOf();broken[0x500]=(broken[0x500].toInt() xor 1).toByte()
        // This small fixture has no native dispatcher proof; a retained header
        // plus two arbitrary gate targets is not enough to restore changed code.
        assertThrows(PatchException::class.java){KblPatcher.removeAdapter(broken)}
        // An instruction no longer proving any incoming adapter branch cannot
        // be replaced using a guessed original address.
        val missing=edited.copyOf();missing.putWord(0x500,0xd503201f.toInt())
        assertThrows(PatchException::class.java){KblPatcher.removeAdapter(missing)}
    }
    @Test fun removeAdapterKeepsContainersAndPlainPeWithoutAdapterUnchanged() {
        for(b in listOf(byteArrayOf(1,2,3),pe())){val r=KblPatcher.removeAdapterDetails(b)
            assertFalse(r.hadAdapter);assertArrayEquals(b,r.bytes)}
    }
    private fun legacy(bytes:ByteArray):ByteArray {
        val b=bytes.copyOf();val p=PeImage.parse(b);val rx=p.sections[p.sections.size-3]
        val pe=b.u32(0x3c).toInt();val table=pe+24+b.u16(pe+20);val at=table+(p.sections.size-3)*40
        b.fill(0,at,at+8);".kblrx".toByteArray().copyInto(b,at)
        val img=p.sections.last().raw;val hook=b.u32(img+144);val stub=b.u32(img+152)
        b.putWord(requireNotNull(p.rvaToOffset(hook,4)),linked(hook,stub))
        b.putWord(img+476,1)
        b.fill(0,img+256,img+288)
        java.security.MessageDigest.getInstance("SHA-256").digest(b.copyOfRange(img,img+p.sections.last().rawSize)).copyInto(b,img+256)
        assertEquals(rx.rva,PeImage.parse(b).sections[p.sections.size-3].rva)
        return b
    }
    private fun linked(pc:Long,target:Long)=A64.branch(pc,target)or 0x80000000.toInt()
    @Test fun newNameMatchesVendorDepTextPredicateAndHookRetainsRealCallRecord() {
        val r=install();val p=PeImage.parse(r.bytes);val rx=p.sections[p.sections.size-3]
        assertEquals(".textkbl",rx.name);assertEquals('t',rx.name[1]);assertEquals('e',rx.name[2])
        val img=p.sections.last().raw;val hook=r.bytes.u32(img+144);val stub=r.bytes.u32(img+152)
        assertEquals(stub,A64.bTarget(hook,p.word(hook)!!));assertNull(A64.blTarget(hook,p.word(hook)!!))
        assertEquals(PeImage.parse(pe()).word(0x1040),p.word(0x1040))
        assertArrayEquals(pe(),KblPatcher.removeAdapter(r.bytes))
    }
    @Test fun removesLegacyAndRebuildsDamagedDisposableCode() {
        val old=legacy(install().bytes);assertArrayEquals(pe(),KblPatcher.removeAdapter(old))
        val p=PeImage.parse(old);val rx=p.sections[p.sections.size-3]
        val corrupt=old.copyOf();corrupt[rx.raw+20]=(corrupt[rx.raw+20].toInt()xor 1).toByte()
        val recovered=KblPatcher.removeAdapterDetails(corrupt)
        assertTrue(recovered.rebuiltFromDamage);assertArrayEquals(pe(),recovered.bytes)
    }
    @Test fun removesDamagedDriverConfigContextPermissionsAndSectionNameWithoutReusingThem() {
        val original=pe();val patched=install(original).bytes;val image=PeImage.parse(patched)
        val raw=image.sections.last().raw;val rx=image.sections[2].raw
        val table=patched.u32(0x3c).toInt()+24+patched.u16(patched.u32(0x3c).toInt()+20)
        val offsets=listOf(rx+20,raw+4096+100,raw+16,raw+256,raw+128+64,
            image.sections[3].raw,table+2*40+36,table+3*40+36,table+4*40)
        for(at in offsets) {
            val broken=patched.copyOf();broken[at]=(broken[at].toInt() xor 1).toByte();val before=broken.copyOf()
            val result=KblPatcher.removeAdapterDetails(broken)
            assertTrue("damage $at",result.rebuiltFromDamage);assertArrayEquals(original,result.bytes)
            assertArrayEquals(before,broken)
        }
    }
    @Test fun reconstructsInconsistentRecoveryFieldsFromPeAndGateRelationships() {
        val original=pe();val patched=install(original).bytes;val image=PeImage.parse(patched);val raw=image.sections.last().raw
        for(offset in listOf(144,156,288,292,296,300,304,308,312,316,352)) {
            val broken=patched.copyOf();broken[raw+offset]=(broken[raw+offset].toInt() xor 0x40).toByte()
            val result=KblPatcher.removeAdapterDetails(broken)
            assertTrue("record $offset",result.rebuiltFromDamage);assertArrayEquals(original,result.bytes)
        }
        val giant=patched.copyOf();giant.putWord(raw+472,Int.MAX_VALUE)
        val safe=KblPatcher.removeAdapterDetails(giant)
        assertTrue(safe.rebuiltFromDamage);assertArrayEquals(original,safe.bytes)
    }
    @Test fun retainsLaterOriginalTextChangesWhenDisposableKblIsDamaged() {
        val original=pe();val patched=install(original).bytes;val raw=PeImage.parse(patched).sections.last().raw
        val broken=patched.copyOf();broken[0xc80]=77;broken[raw+4096+80]=99
        val expected=original.copyOf();expected[0xc80]=77
        // An external producer preserving its source provenance can prove the
        // text-edited original even after disposable code/config is damaged.
        java.security.MessageDigest.getInstance("SHA-256").digest(expected).copyInto(broken,raw+320)
        val result=KblPatcher.removeAdapterDetails(broken)
        assertTrue(result.fullyOriginal);assertTrue(result.rebuiltFromDamage);assertArrayEquals(expected,result.bytes)
    }
    @Test fun missingIndependentOriginalEvidenceStopsWithoutGuessing() {
        val patched=install().bytes;val raw=PeImage.parse(patched).sections.last().raw
        val broken=patched.copyOf();broken.fill(0,raw+320,raw+384);broken[raw+16]=0
        val before=broken.copyOf()
        assertThrows(PatchException::class.java){KblPatcher.removeAdapter(broken)}
        assertArrayEquals(before,broken)
    }
    @Test fun gateShapeWithoutNativeFailProofDoesNotReplaceAnOriginalDigest() {
        val patched=install().bytes;val raw=PeImage.parse(patched).sections.last().raw
        val broken=patched.copyOf();broken.fill(0,raw+320,raw+352)
        // The synthetic gate has B -> BL and B -> continuation, and the saved
        // NT digest is intact. It still has no real native dispatcher evidence.
        assertThrows(PatchException::class.java){KblPatcher.removeAdapter(broken)}
    }
    @Test fun relocatedHeaderSurvivesDamagedSavedPositionAndHeaderDigest() {
        val original=pe();val b=ByteArray(original.size+0xc00)
        original.copyInto(b,0,0,64);original.copyInto(b,0xe58,0x80,0x188+80)
        original.copyInto(b,0x1000,0x400,original.size);b.putWord(0x3c,0xe58)
        val o=0xe58+24;val table=o+0xf0;b.putWord(o+60,0x1000)
        b.putWord(table+20,0x1000);b.putWord(table+60,0x1800)
        val patched=install(b).bytes;val raw=PeImage.parse(patched).sections.last().raw
        val broken=patched.copyOf();broken.fill(0,raw+352,raw+384);broken.putWord(raw+288,0x1000-400);broken[raw+4096+80]=3
        val result=KblPatcher.removeAdapterDetails(broken)
        assertTrue(result.rebuiltFromDamage);assertArrayEquals(b,result.bytes)
    }
    @Test fun unrelatedOriginalTextNamedSectionIsNotAnAdapter() {
        val b=pe();val table=b.u32(0x3c).toInt()+24+b.u16(b.u32(0x3c).toInt()+20)
        b.fill(0,table,table+8);".textkbl".toByteArray().copyInto(b,table)
        val r=KblPatcher.removeAdapterDetails(b);assertFalse(r.hadAdapter);assertArrayEquals(b,r.bytes)
        val withOwn=install(b);assertArrayEquals(b,KblPatcher.removeAdapter(withOwn.bytes))
    }
    @Test fun actualLegacyStandaloneUpgradeIsCanonicalAndRepeatedImportIsIdentical() {
        val path=generateSequence(java.io.File(System.getProperty("user.dir")).absoluteFile){it.parentFile}
            .map { java.io.File(it,"analysis/kbl-fastboot-failure-20261029/efisp-device-2m.img") }.firstOrNull{it.isFile}
        org.junit.Assume.assumeNotNull(path)
        val old=requireNotNull(path).readBytes();val saved=old.copyOf();val driver=BundledKblDriver.load()
        val base=KblPatcher.removeAdapter(old);val upgraded=KblPatcher.patch(old,driver)
        assertFalse(upgraded.unchanged);assertTrue(upgraded.metadata["upgradedLegacyAdapter"] as Boolean)
        val image=PeImage.parse(upgraded.bytes);assertEquals(".textkbl",image.sections[image.sections.size-3].name)
        val raw=image.sections.last().raw;val hook=upgraded.bytes.u32(raw+144)
        assertNotNull(A64.bTarget(hook,image.word(hook)!!));assertArrayEquals(base,KblPatcher.removeAdapter(upgraded.bytes))
        val again=KblPatcher.patch(upgraded.bytes,driver);assertTrue(again.unchanged);assertArrayEquals(upgraded.bytes,again.bytes)
        // A damaged source digest cannot use exact original-file SHA proof.
        // Reprove the native FAIL/dispatcher edge, discard damaged old driver
        // bytes, and generate the same canonical single adapter.
        val broken=again.bytes.copyOf();val brokenPe=PeImage.parse(broken);val brokenRaw=brokenPe.sections.last().raw
        broken.fill(0,brokenRaw+320,brokenRaw+352)
        broken[brokenRaw+4096+100]=(broken[brokenRaw+4096+100].toInt() xor 1).toByte()
        val restored=KblPatcher.removeAdapterDetails(broken)
        assertTrue(restored.rebuiltFromDamage);assertArrayEquals(base,restored.bytes)
        val rebuilt=KblPatcher.patch(broken,driver)
        assertTrue(rebuilt.metadata["rebuiltDamagedAdapter"] as Boolean)
        assertArrayEquals(again.bytes,rebuilt.bytes)
        val rebuiltAgain=KblPatcher.patch(rebuilt.bytes,driver)
        assertTrue(rebuiltAgain.unchanged);assertArrayEquals(rebuilt.bytes,rebuiltAgain.bytes)
        // Revision 2 with today's exact module/driver must still gain the new
        // command gate. Test the revision contract independently of driver hash.
        val revision2=again.bytes.copyOf();val revisionPe=PeImage.parse(revision2)
        val revisionRaw=revisionPe.sections.last().raw
        revision2.putWord(revisionRaw+476,2);revision2.fill(0,revisionRaw+256,revisionRaw+288)
        java.security.MessageDigest.getInstance("SHA-256").digest(revision2.copyOfRange(revisionRaw,revisionRaw+revisionPe.sections.last().rawSize))
            .copyInto(revision2,revisionRaw+256)
        val revisionSaved=revision2.copyOf();val refreshed=KblPatcher.patch(revision2,driver)
        assertTrue(refreshed.metadata["upgradedLegacyAdapter"] as Boolean);assertFalse(refreshed.unchanged)
        assertArrayEquals(again.bytes,refreshed.bytes);assertArrayEquals(revisionSaved,revision2)

        val facade=net.hawyn.ablefisp.api.AblEfisp.patchWithKbl(old,PatchMode.FREEDOM_FAKE_LOCK)
        // The default API adds the independently tested hash and ten policy
        // edits; adapter-only upgrade deliberately preserves the base. Apply
        // those exact instruction edits before rebuilding the owned mappings.
        fun applyCompatibilityPolicies(bytes:ByteArray) {
            val pe=PeImage.parse(bytes)
            val boot=BootCompatibilityPatcher.analyze(pe).edits
            val avb=AvbVersionCompatibilityPatcher.analyze(pe).edits
            val pss=PssVerificationPatcher.analyze(pe,SemanticPatcher.analyze(pe).stateVerifierRva).edits
            val key=FastbootKeyPolicyPatcher.analyze(pe).edits
            assertEquals(8,boot.size);assertEquals(2,avb.size)
            assertEquals(listOf("共用 RSA-PSS 验证入口固定成功"),pss.map{it.name})
            assertTrue((boot+avb).all{it.replacement.size==4})
            for(edit in boot+avb+pss+key)edit.replacement.copyInto(bytes,
                requireNotNull(pe.rvaToOffset(edit.rva,edit.replacement.size)))
        }
        // Independent native oracle for this exact historical bc5 fixture.
        // Production must discover the feature; expected bytes deliberately do
        // not call SpecialSdamCompatibilityPatcher to prove their own result.
        fun applySpecialSdamNativeOracle(bytes:ByteArray) {
            val pe=PeImage.parse(bytes)
            val gate=0x8670cL;val noUpdate=0x86774L
            assertEquals(0xd503233f.toInt(),requireNotNull(pe.word(0x866dcL))) // PACIASP
            assertEquals(0x3900001f,requireNotNull(pe.word(0x86700L))) // output byte = 0
            assertEquals(0x54000341,requireNotNull(pe.word(gate))) // B.NE native no-update
            assertEquals(0xaa1f03e0.toInt(),requireNotNull(pe.word(noUpdate))) // EFI_SUCCESS
            assertEquals(0x14000028,requireNotNull(pe.word(noUpdate+4))) // original cleanup
            bytes.putWord(requireNotNull(pe.rvaToOffset(gate,4)),0x1400001a) // B no-update
            assertEquals(noUpdate,A64.bTarget(gate,requireNotNull(PeImage.parse(bytes).word(gate))))
        }
        // Independent native-call oracle for this exact historical bc5 PE.
        // Restore the original BL only; the imported orphan belongs to the old
        // product and must remain byte-identical when the KBL receipt rebuilds.
        fun applyNativeReporterOracle(bytes:ByteArray) {
            val pe=PeImage.parse(bytes);val call=0x4ea30L;val reporter=0xad554L
            val nativeBl=0x94017ac9.toInt();val current=requireNotNull(pe.word(call))
            assertEquals(reporter,A64.blTarget(call,nativeBl))
            if(current==nativeBl)return
            val oldStub=requireNotNull(A64.blTarget(call,current))
            val stubOffset=requireNotNull(pe.rvaToOffset(oldStub,20))
            val saved=bytes.copyOfRange(stubOffset,stubOffset+20)
            assertEquals(0x34000096,requireNotNull(pe.word(oldStub)))
            assertEquals(0x71000f5f,requireNotNull(pe.word(oldStub+4)))
            assertEquals(0x54000041,requireNotNull(pe.word(oldStub+8)))
            assertEquals(0xd65f03c0.toInt(),requireNotNull(pe.word(oldStub+12)))
            assertEquals(reporter,A64.bTarget(oldStub+16,requireNotNull(pe.word(oldStub+16))))
            bytes.putWord(requireNotNull(pe.rvaToOffset(call,4)),nativeBl)
            assertArrayEquals(saved,bytes.copyOfRange(stubOffset,stubOffset+20))
            assertEquals(nativeBl,requireNotNull(PeImage.parse(bytes).word(call)))
        }
        val expectedBase=base.copyOf();val expectedPe=PeImage.parse(expectedBase)
        val hashPlans=AvbHashDescriptorPatcher.analyze(expectedPe);assertEquals(1,hashPlans.size)
        for(plan in hashPlans)for(edit in AvbHashDescriptorPatcher.edits(plan))
            edit.replacement.copyInto(expectedBase,requireNotNull(expectedPe.rvaToOffset(edit.rva,edit.replacement.size)))
        applyCompatibilityPolicies(expectedBase)
        applySpecialSdamNativeOracle(expectedBase)
        applyNativeReporterOracle(expectedBase)
        val expectedFacade=FastbootRecoveryPatcher.patch(KblPatcher.patch(VmDtboCompatPatcher.patch(expectedBase).bytes,driver).bytes).bytes
        assertFalse(facade.alreadyPatched);assertArrayEquals(expectedFacade,facade.output)
        val entry=net.hawyn.ablefisp.api.AblEfisp.scanText(old).entries.single{it.id=="fastboot-unlocked"}
        val noOp=net.hawyn.ablefisp.api.AblEfisp.editText(old,mapOf(entry.id to entry.text),false)
        assertArrayEquals(FastbootRecoveryPatcher.patch(upgraded.bytes).bytes,noOp.output)
        val label="device freedom (github hawyn2018)"
        val edited=net.hawyn.ablefisp.api.AblEfisp.editText(old,mapOf(entry.id to label),false)
        val after=net.hawyn.ablefisp.api.AblEfisp.scanText(edited.output).entries.single{it.id==entry.id}
        assertEquals(label,after.text)
        val editedImage=PeImage.parse(FastbootRecoveryPatcher.remove(edited.output))
        assertEquals(".textkbl",editedImage.sections[editedImage.sections.size-3].name)
        val same=net.hawyn.ablefisp.api.AblEfisp.editText(edited.output,mapOf(after.id to label),false)
        assertArrayEquals(edited.output,same.output)
        val reimport=net.hawyn.ablefisp.api.AblEfisp.patchWithKbl(edited.output,PatchMode.FREEDOM_FAKE_LOCK)
        // Text editing preserves the core instruction stream; its first default
        // patch import adds hash plus ten policy edits before becoming stable.
        val expectedEditedBase=VmDtboCompatPatcher.remove(KblPatcher.removeAdapter(FastbootRecoveryPatcher.remove(edited.output)));val expectedEditedPe=PeImage.parse(expectedEditedBase)
        val editedHashPlans=AvbHashDescriptorPatcher.analyze(expectedEditedPe);assertEquals(1,editedHashPlans.size)
        assertFalse(editedHashPlans.single().alreadyApplied)
        for(plan in editedHashPlans)for(edit in AvbHashDescriptorPatcher.edits(plan))
            edit.replacement.copyInto(expectedEditedBase,requireNotNull(expectedEditedPe.rvaToOffset(edit.rva,edit.replacement.size)))
        applyCompatibilityPolicies(expectedEditedBase)
        applySpecialSdamNativeOracle(expectedEditedBase)
        applyNativeReporterOracle(expectedEditedBase)
        assertFalse(reimport.alreadyPatched)
        assertArrayEquals(FastbootRecoveryPatcher.patch(KblPatcher.patch(VmDtboCompatPatcher.patch(expectedEditedBase).bytes,driver).bytes).bytes,reimport.output)
        val stable=net.hawyn.ablefisp.api.AblEfisp.patchWithKbl(reimport.output,PatchMode.FREEDOM_FAKE_LOCK)
        assertTrue(stable.alreadyPatched);assertArrayEquals(reimport.output,stable.output)
        assertArrayEquals(saved,old)
    }
}
