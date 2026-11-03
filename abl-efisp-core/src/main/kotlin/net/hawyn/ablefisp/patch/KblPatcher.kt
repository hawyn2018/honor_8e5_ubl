package net.hawyn.ablefisp.patch

import java.security.MessageDigest

/** Standalone parent adapter. Deliberately not wired into PatchEngine.
 * No KBL registration or initialization runs on original Fastboot startup.
 * A proved unknown-command edge recognizes complete explicit runtime/display requests.
 * Caller supplies a separately validated EFI_BOOT_SERVICE_DRIVER image. */
object KblPatcher {
    data class Result(val bytes: ByteArray, val metadata: Map<String, Any>, val unchanged: Boolean)
    data class Removed(val bytes:ByteArray,val hadAdapter:Boolean,val fullyOriginal:Boolean,
                       val rebuiltFromDamage:Boolean=false)
    private const val DRIVER_OFFSET = 4096
    private const val META = 128
    private const val LAZY = 512
    private const val ENTRIES = 568
    private const val NAMES = 1152
    private const val RX_NAME = ".textkbl"
    private const val LEGACY_RX_NAME = ".kblrx"
    private val marker = "KBL-PARENT-1".toByteArray(Charsets.US_ASCII)
    private const val ADAPTER_REVISION = 3
    private val lazyCommands = listOf(
        "oem kblstart" to 1, "oem kbladb" to 17,
        "oem screenoff" to 18, "oem screenon" to 19, "oem screenstatus" to 20,
    )
    private val lazyOperations = lazyCommands.map { it.second }.toSet()
    private val baseCommands = listOf(
        "oem kblstart" to 1, "oem kbladb" to 17, "oem screenoff" to 18, "oem screenon" to 19, "oem screenstatus" to 20, "oem kblin" to 2, "oem kbloff" to 3, "oem kblstop" to 3, "oem kblstatus" to 4,
        "oem kblcmd" to 5, "oem kblput" to 6, "oem kblpoll" to 7, "oem kblstore" to 8,
        "oem kblreadopen" to 9, "oem kblreadclose" to 10,
        "getvar:kbl-reply-header" to 15, "getvar:kbl-reply:" to 16,
    )
    private val binaryCommands=listOf("fetch:" to 11,"getvar:max-fetch-size" to 12,
        "getvar:partition-size:" to 13,"getvar:has-slot:" to 14)
    /** Remove only this adapter before old feature/text re-analysis. Changes to
     * original code/data (including legitimate post-patch text edits) survive.
     * Damaged disposable adapter bytes may be discarded after independently
     * proving the original header and hook; no damaged instruction is reused. */
    fun removeAdapter(peBytes:ByteArray):ByteArray=removeAdapterDetails(peBytes).bytes
    fun removeAdapterDetails(peBytes:ByteArray):Removed {
        if(peBytes.size<2 || peBytes[0]!=0x4d.toByte() || peBytes[1]!=0x5a.toByte())
            return Removed(peBytes.copyOf(),false,true)
        val image=PeImage.parse(peBytes);val own=KblAdapterRecovery.ownedSections(image)
        if(own.isEmpty())return Removed(peBytes.copyOf(),false,true)
        return try { removeAdapterStrict(peBytes,image,own) }
        catch (failure:PatchException) {
            KblAdapterRecovery.remove(peBytes,image,own,failure.message.orEmpty())
        }
    }
    private fun removeAdapterStrict(peBytes:ByteArray,image:PeImage,own:List<PeSection>):Removed {
        if(own.size!=3 || own.last().rawSize<DRIVER_OFFSET)error("KBL剥离布局不完整")
        val img=own.last();val raw=img.raw;val m=raw+META
        val driverBytes=peBytes.u32(m+20).asBoundedInt("KBL driver长度")
        if(driverBytes<=0 || DRIVER_OFFSET.toLong()+driverBytes>img.rawSize)error("KBL剥离driver边界无效")
        patchChecked(peBytes,peBytes.copyOfRange(raw+DRIVER_OFFSET,raw+DRIVER_OFFSET+driverBytes),false) // verify all owned bytes
        val oldPe=peBytes.u32(raw+288).asBoundedInt("原PE头位置")
        val oldSize=peBytes.u32(raw+292).asBoundedInt("原文件长度")
        val count=peBytes.u32(raw+312).asBoundedInt("原节数量")
        val active=peBytes.u32(0x3c).asBoundedInt("当前PE头位置")
        val optSize=peBytes.u16(active+20);val span=24+optSize+(count+3)*40
        if(count !in 1..65532 || image.sections.size!=count+3 || oldSize>own.first().raw || oldSize<image.headersSize ||
            peBytes.u32(raw+316)!=span.toLong() || oldPe<0x40 || oldPe.toLong()+24+optSize+count*40>image.headersSize)
            error("KBL原始元数据边界不一致")
        val restored=peBytes.copyOf(oldSize)
        if(active!=oldPe){
            if(active<0x80 || active.toLong()+span>oldPe)error("KBL迁移头边界不一致")
            restored.fill(0,active,active+span);restored.putWord(0x3c,oldPe)
        }else{
            val o=oldPe+24;val slot=o+optSize+count*40
            restored.fill(0,slot,slot+120)
            restored[oldPe+6]=count.toByte();restored[oldPe+7]=(count ushr 8).toByte()
            listOf(4,8,56,64).forEachIndexed { i,off -> restored.putWord(o+off,peBytes.word(raw+296+i*4)) }
        }
        val hook=peBytes.u32(m+16);val hookAt=image.rvaToOffset(hook,4)?:error("KBL原hook缺失")
        if(hookAt+4>restored.size)error("KBL原hook超出原文件")
        restored.putWord(hookAt,peBytes.word(m+28))
        val oldHeader=restored.copyOfRange(oldPe,oldPe+24+optSize+count*40)
        if(!hash(oldHeader).contentEquals(peBytes.copyOfRange(raw+352,raw+384)))error("KBL恢复NT元数据不一致")
        PeImage.parse(restored)
        return Removed(restored,true,hash(restored).contentEquals(peBytes.copyOfRange(raw+320,raw+352)))
    }
    fun patch(peBytes: ByteArray, driverImage: ByteArray): Result {
        validateDriver(driverImage)
        val image=PeImage.parse(peBytes);val own=KblAdapterRecovery.ownedSections(image)
        if(own.isNotEmpty()){
            return try { patchExisting(peBytes,driverImage,image,own) }
            catch (failure:PatchException) {
                val removed=KblAdapterRecovery.remove(peBytes,image,own,failure.message.orEmpty())
                val rebuilt=patchChecked(removed.bytes,driverImage,true)
                rebuilt.copy(metadata=rebuilt.metadata+mapOf("rebuiltDamagedAdapter" to true),unchanged=false)
            }
        }
        return patchChecked(peBytes,driverImage,true)
    }
    private fun patchExisting(peBytes:ByteArray,driverImage:ByteArray,image:PeImage,own:List<PeSection>):Result {
            val old=own.last();val at=old.raw
            if(old.rawSize<DRIVER_OFFSET)error("已有KBL资源边界无效")
            val size=peBytes.u32(at+META+20).asBoundedInt("已有KBL driver长度")
            if(size<=0 || DRIVER_OFFSET.toLong()+size>old.rawSize)error("已有KBL driver范围无效")
            val embedded=peBytes.copyOfRange(at+DRIVER_OFFSET,at+DRIVER_OFFSET+size)
            // Validate the old image with its own complete module/driver first.
            // A new supplied driver must not prevent strict legacy removal.
            patchChecked(peBytes,embedded,false)
            val hook=peBytes.u32(at+META+16);val stub=peBytes.u32(at+META+24)
            val bytes=peBytes.u32(at+472).asBoundedInt("已有模块长度")
            if(bytes<=0 || bytes>own.first().rawSize)error("已有模块长度超出自有 RX 映射")
            val current=peBytes.u32(at+476)==ADAPTER_REVISION.toLong() && own.first().name==RX_NAME && image.word(hook)==A64.branch(hook,stub) &&
                peBytes.copyOfRange(own.first().raw,own.first().raw+bytes).contentEquals(KblParentModule.bytes) &&
                embedded.contentEquals(driverImage)
            if(current)return patchChecked(peBytes,driverImage,true)
            val removed=removeAdapterDetails(peBytes)
            val upgraded=patchChecked(removed.bytes,driverImage,true)
            return upgraded.copy(metadata=upgraded.metadata+mapOf("upgradedLegacyAdapter" to true),unchanged=false)
    }
    private fun patchChecked(peBytes:ByteArray,driverImage:ByteArray,requireCurrentModule:Boolean):Result {
        validateDriver(driverImage)
        val image = PeImage.parse(peBytes)
        if (image.bytes.size != peBytes.size) error("输入 PE 含未归属的尾部数据，请先明确容器边界")
        val existing = ownedSections(image)
        if (existing.isNotEmpty()) {
            val old = existing.last(); val m = old.raw + META
            if(old.rawSize<DRIVER_OFFSET)error("已有KBL资源边界无效")
            val moduleBytes=peBytes.u32(old.raw+472).asBoundedInt("已有模块长度")
            val version=peBytes.u32(old.raw+476)
            if(moduleBytes<=0 || moduleBytes>existing[0].rawSize || version !in 1L..ADAPTER_REVISION.toLong())
                error("已有KBL模块元数据无效")
            val actualModule=peBytes.copyOfRange(existing[0].raw,existing[0].raw+moduleBytes)
            if (existing.map { it.characteristics } != listOf(0x60000020L,0xc0000040L,0x40000040L) ||
                (requireCurrentModule && !actualModule.contentEquals(KblParentModule.bytes)) ||
                !peBytes.copyOfRange(existing[1].raw,existing[1].raw+existing[1].rawSize).all { it==0.toByte() })
                error("已有 KBL 映射权限、模块或上下文不一致")
            if (!peBytes.copyOfRange(m, m + marker.size).contentEquals(marker)) error("已有 KBL 元数据未匹配")
            if(peBytes.u32(old.raw+292)<=0 || peBytes.u32(old.raw+312) !in 1L..65532L)error("已有KBL缺少原始恢复元数据")
            val size = peBytes.u32(m + 20).asBoundedInt("KBL driver 长度")
            if (size != driverImage.size || old.rawSize.toLong() < DRIVER_OFFSET.toLong() + size ||
                !peBytes.copyOfRange(old.raw + DRIVER_OFFSET, old.raw + DRIVER_OFFSET + size).contentEquals(driverImage) ||
                !peBytes.copyOfRange(m + 32, m + 64).contentEquals(hash(actualModule)))
                error("已有 KBL 内容不同，请使用保留的原始输入重新生成")
            val actualRx=peBytes.copyOfRange(existing[0].raw,existing[0].raw+existing[0].rawSize)
            val actualImg=peBytes.copyOfRange(old.raw,old.raw+old.rawSize)
            val expectedImg=actualImg.copyOfRange(256,288);actualImg.fill(0,256,288)
            if(!hash(actualRx).contentEquals(peBytes.copyOfRange(m+96,m+128)) ||
                !hash(actualImg).contentEquals(expectedImg)) error("已有 KBL 指令或配置完整性不一致")
            val hook = peBytes.u32(m + 16)
            val stub = peBytes.u32(m + 24)
            val actual=image.word(hook)
            if ((version>=2L && actual!=A64.branch(hook,stub)) ||
                (version==1L && actual != A64.branch(hook, stub) && actual != linkedBranch(hook,stub)))
                error("已有 KBL 注册分支与元数据不一致")
            return Result(peBytes.copyOf(), mapOf("controlOnly" to (peBytes.u64(old.raw+96)==0L),
                "storeSupported" to (peBytes.u64(old.raw+88) and 1L!=0L),
                "binaryFetchFactsProven" to (peBytes.u64(old.raw+96)!=0L),"existing" to true,
                "adapterRevision" to version,
                "lazyExplicitCommands" to if(version==ADAPTER_REVISION.toLong())lazyCommands.map{it.first}else emptyList<String>(),
                "legacyBranchHook" to (actual==A64.branch(hook,stub)),"rxSectionName" to existing.first().name), true)
        }
        val facts = KblEnvironmentFacts.analyze(image)
        val plan = SuperUnlockPatcher.analyze(image, FlashingUnlockPatcher.analyze(image))
            ?: error("原注册流程和响应接口未匹配")
        val dispatch=KblDispatcherFacts.analyze(image,plan.nativeRegisterRva)
        val lazy=KblLazyEntryFacts.analyze(image,dispatch,plan.fastbootFailRva)
        val usb=try { KblUsbFacts.analyze(image,facts,plan) } catch(_:PatchException){null}
        val descriptor=usb?.let{KblUsbDescriptorFacts.analyze(image,it)}
        return install(image, driverImage, facts, plan, dispatch,usb,lazy,descriptor)
    }
    internal fun install(image: PeImage, driver: ByteArray, facts: KblEnvironmentFacts.Facts,
                         plan: SuperUnlockPatcher.Plan, dispatch:KblDispatcherFacts.Facts?=null,
                         usbFacts:KblUsbFacts.Facts?=null,lazyFacts:KblLazyEntryFacts.Facts?=null,
                         descriptorFacts:KblUsbDescriptorFacts.Facts?=null): Result {
        validateDriver(driver)
        val producer=dispatch?:error("lazy START需要已证明的native链表布局")
        val lazy=lazyFacts?:KblLazyEntryFacts.analyze(image,producer,plan.fastbootFailRva)
        val original = image.word(lazy.hookRva) ?: error("未知命令边缺失")
        if(original!=lazy.originalWord || A64.bTarget(lazy.hookRva,original)!=lazy.failCallRva ||
            image.word(lazy.failCallRva)?.let{A64.blTarget(lazy.failCallRva,it)}!=plan.fastbootFailRva)
            error("lazy原FAIL调用链不一致")
        val genericGetvar=tableHandler(image,plan,"getvar:")?:0L
        val genericFetch=tableHandler(image,plan,"fetch:")?:0L
        val usb=usbFacts?.takeIf { genericGetvar!=0L }
        val commands=baseCommands+if(usb!=null)binaryCommands else emptyList()
        // Prefix matching is native behavior. A broad existing prefix would
        // consume START before the unknown edge, so reject that ambiguity.
        if(tableNames(image,plan).any{prefix->lazyCommands.any{it.first.startsWith(prefix)}})
            error("原命令前缀已截获lazy KBL入口")
        val module = KblParentModule.bytes
        val wrappersAt = align(module.size.toLong(), 16).toInt()
        val wrapperBytes = 48
        val stubAt = align((wrappersAt + commands.size * wrapperBytes).toLong(), 16).toInt()
        // Each exact byte comparison emits four A64 instructions. Include the
        // NUL checks, context save/restore and call setup; capacity derives from
        // the registered names instead of a fixed two-command assumption.
        val gateCapacity = align((512 + lazyCommands.sumOf { (it.first.length + 1) * 16 }).toLong(), 16).toInt()
        val rxBytes = stubAt + gateCapacity
        val imageDataBytes=(DRIVER_OFFSET.toLong()+driver.size).asBoundedInt("KBL driver 与配置区总长度")
        val output = append(image, rxBytes, imageDataBytes)
        val sections = PeImage.parse(output).sections.takeLast(3)
        val rx = sections[0]; val ctx = sections[1]; val img = sections[2]
        val configRva = img.rva
        val data = ByteArray(imageDataBytes)
        fun q(at: Int, value: Long) { data.putWord(at, value.toInt()); data.putWord(at + 4, (value ushr 32).toInt()) }
        q(0, 0x31544e524150424bL); data.putWord(8, 1); data.putWord(12, 120)
        listOf(facts.imageHandleGlobal, facts.systemTableGlobal, facts.bootServicesGlobal,
            facts.runtimeServicesGlobal, plan.fastbootOkayRva, plan.fastbootFailRva,
            img.rva + DRIVER_OFFSET, driver.size.toLong(), ctx.rva, if(dispatch!=null)1L else 0L,
            if(usb!=null)img.rva+384 else 0L,genericGetvar,genericFetch).forEachIndexed { n,v -> q(16 + n*8,v) }
        marker.copyInto(data, META)
        data.putWord(META + 16, lazy.hookRva.toInt()); data.putWord(META + 20, driver.size)
        data.putWord(META + 24, (rx.rva + stubAt).toInt()); data.putWord(META + 28, original)
        hash(module).copyInto(data, META + 32); hash(driver).copyInto(data, META + 64)
        val oldPe=image.bytes.u32(0x3c).asBoundedInt("原PE头");val oldOptional=oldPe+24
        data.putWord(288,oldPe);data.putWord(292,image.bytes.size)
        listOf(4,8,56,64).forEachIndexed { i,off -> data.putWord(296+i*4,image.bytes.word(oldOptional+off)) }
        data.putWord(312,image.sections.size)
        data.putWord(316,24+image.bytes.u16(oldPe+20)+(image.sections.size+3)*40)
        hash(image.bytes).copyInto(data,320)
        hash(image.bytes.copyOfRange(oldPe,oldOptional+image.bytes.u16(oldPe+20)+image.sections.size*40)).copyInto(data,352)
        if(usb!=null){
            data.putWord(384,1);data.putWord(388,88);q(392,0)
            listOf(usb.protocolSlotRva,usb.ackBufferSlotRva,usb.commandRxBufferSlotRva,usb.downloadStateByteRva)
                .forEachIndexed { i,v->q(400+i*8,v) }
            data.putWord(432,usb.commandStateValue)
            val descriptor=descriptorFacts?.descriptorSetRva?:0L
            if(descriptor!=0L && (descriptor !in 1L..0xffff_ffffL || !image.isWritable(descriptor,64)))
                error("原USB descriptor set边界未证明")
            data.putWord(436,descriptor.toInt())
            listOf(plan.fastbootOkayRva,plan.fastbootFailRva,usb.counterFrequencyRva,usb.counterTicksRva)
                .forEachIndexed { i,v->q(440+i*8,v) }
        }
        data.putWord(472,module.size);data.putWord(476,ADAPTER_REVISION)
        q(LAZY,0x31595a414c4c424bL);data.putWord(LAZY+8,56);data.putWord(LAZY+12,commands.size)
        q(LAZY+16,plan.nativeRegisterRva);q(LAZY+24,producer.headGlobal);q(LAZY+32,img.rva+ENTRIES)
        listOf(producer.nextOffset,producer.prefixOffset,producer.lengthOffset,producer.handlerOffset)
            .forEachIndexed{i,v->data.putWord(LAZY+40+i*4,v)}
        driver.copyInto(data, DRIVER_OFFSET)
        var nameAt = NAMES
        val names = commands.map { (name, _) ->
            val address = img.rva + nameAt
            val bytes = name.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
            bytes.copyInto(data, nameAt); nameAt += bytes.size; address
        }
        if(nameAt>=DRIVER_OFFSET || ENTRIES+commands.size*16>NAMES)error("lazy注册表超出私有边界")
        commands.indices.forEach{i->q(ENTRIES+i*16,names[i]);q(ENTRIES+i*16+8,rx.rva+wrappersAt+i*wrapperBytes)}
        module.copyInto(output, rx.raw)
        commands.forEachIndexed { i, (_,op) ->
            val isLazy=op in lazyOperations
            val at = rx.rva + wrappersAt + i * wrapperBytes
            val e = Code(at)
            e.emit(0xd503245f.toInt()) // BTI c
            e.mov(5,2); e.mov(4,1); e.mov(3,0)
            if(isLazy)e.adr(2,img.rva+LAZY)else e.emit(0x52800002 or (op shl 5))
            if(isLazy)e.emit(0x52800004 or(op shl 5))else e.emit(0xd503201f.toInt())
            e.adr(0,configRva)
            val self = e.pc; e.adr(1,self)
            e.emit(0xd2800010.toInt() or ((self.toInt() and 0xffff) shl 5))
            e.emit(0xf2a00010.toInt() or (((self ushr 16).toInt() and 0xffff) shl 5))
            e.emit(0xcb100021.toInt()) // sub x1,x1,x16
            e.branch(rx.rva+if(isLazy)KblParentModule.LAZY_START_OFFSET else 0L)
            check(e.bytes().size == wrapperBytes)
            e.bytes().copyInto(output, rx.raw + wrappersAt + i * wrapperBytes)
        }
        val e = Code(rx.rva + stubAt)
        /* Only the original unknown-list edge reaches this gate. Complete
         * bounded comparison precedes every C/native/EFI call or state write. */
        e.emit(0xd10403ff.toInt()) // sub sp,sp,#256
        for (r in 0..28 step 2) e.pair(false,false,r,r+1,r*8)
        e.scalar(false,30,240)
        e.emit(0xd53b4209.toInt());e.scalar(false,9,248) // NZCV, ordinary A64 ABI accessible
        e.scalar(true,9,lazy.commandRegister*8) // original register, before NZCV scratch use
        val matched=ArrayList<Long>()
        lazyCommands.forEach{(name,op)->
            val comparisons=ArrayList<Long>()
            name.forEachIndexed{i,c->
                e.emit(0x3940012a or(i shl 10)) // ldrb w10,[x9,#i]
                e.emit(0x5280000b or(c.code shl 5));e.emit(0x6b0b015f)
                comparisons+=e.pc;e.emit(0)
            }
            e.emit(0x3940012a or(name.length shl 10));val terminal=e.pc;e.emit(0)
            e.emit(0x91000123.toInt()or(name.length shl 10)) // x3=command+exact name bytes
            e.emit(0x52800004 or(op shl 5)) // private lazy operation in w4
            matched+=e.pc;e.emit(0)
            val next=e.pc
            comparisons.forEach{pc->e.replace(pc,0x54000001 or((((next-pc)shr 2).toInt()and 0x7ffff)shl 5))}
            e.replace(terminal,0x3500000a or((((next-terminal)shr 2).toInt()and 0x7ffff)shl 5))
        }
        fun restore(){
            e.scalar(true,9,248);e.emit(0xd51b4209.toInt())
            for(r in 0..28 step 2)e.pair(true,false,r,r+1,r*8)
            e.scalar(true,30,240);e.emit(0x910403ff.toInt())
        }
        restore();e.branch(lazy.failCallRva)
        val invoke=e.pc;matched.forEach{pc->e.replace(pc,A64.branch(pc,invoke))}
        e.adr(0,configRva);val self=e.pc;e.adr(1,self)
        e.emit(0xd2800010.toInt()or((self.toInt()and 0xffff)shl 5))
        e.emit(0xf2a00010.toInt()or(((self ushr 16).toInt()and 0xffff)shl 5))
        e.emit(0xcb100021.toInt());e.adr(2,img.rva+LAZY)
        e.branch(rx.rva+KblParentModule.LAZY_START_OFFSET,true)
        restore();e.branch(lazy.continueRva)
        check(e.bytes().size <= gateCapacity)
        e.bytes().copyInto(output,rx.raw+stubAt)
        data.copyInto(output,img.raw)
        hash(output.copyOfRange(rx.raw,rx.raw+rx.rawSize)).copyInto(output,img.raw+META+96)
        hash(output.copyOfRange(img.raw,img.raw+img.rawSize)).copyInto(output,img.raw+256)
        // Original edge was B: fallback returns to the untouched real BL Fail.
        // New native/EFI calls use genuine paired BL/RET, never fabricated LR.
        output.putWord(requireNotNull(image.rvaToOffset(lazy.hookRva,4)),A64.branch(lazy.hookRva,rx.rva+stubAt))
        val checkImage=PeImage.parse(output)
        check(checkImage.entryRva==image.entryRva && checkImage.imageBase==image.imageBase &&
            checkImage.sections.take(image.sections.size)==image.sections && !checkImage.sections.last().executable)
        return Result(output,linkedMapOf("controlOnly" to (usb==null),"storeSupported" to (dispatch!=null),
            "binaryFetchFactsProven" to (usb!=null),"binaryFetchRuntimeRequiresObservedIdleFence" to true,
            "usbEvidence" to (usb?.evidence?:emptyMap<String,Long>()),"genericGetvarRva" to genericGetvar,
            "usbDescriptorSetRva" to (descriptorFacts?.descriptorSetRva?:0L),
            "usbDescriptorEvidence" to (descriptorFacts?.evidence?:emptyMap<String,Long>()),
            "genericFetchRva" to genericFetch,
            "dispatcherEvidence" to (dispatch?.evidence?:emptyMap<String,Long>()),
            "headerRelocated" to (output.u32(0x3c)!=image.bytes.u32(0x3c)),
            "rxSectionName" to RX_NAME,"originalCallRecordPreserved" to true,
            "adapterRevision" to ADAPTER_REVISION,"lazyGateBytes" to e.bytes().size,"lazyGateCapacity" to gateCapacity,
            "lazyStartOnly" to false,"lazyExplicitCommands" to lazyCommands.map{it.first},
            "preStartRegistrations" to 0,"lazyEvidence" to lazy.evidence,
            "registrationPreservation" to "original initialization untouched; native register only after exact runtime/display requests; original FAIL BL preserved",
            "registrationExitRva" to plan.registrationExitRva,"lazyHookRva" to lazy.hookRva,"originalCallRva" to lazy.failCallRva,
            "shimRva" to rx.rva,"stateRva" to ctx.rva,"driverRva" to img.rva+DRIVER_OFFSET,
            "driverBytes" to driver.size,"moduleSha256" to KblParentModule.SHA256,
            "driverSha256" to hash(driver).joinToString("") { "%02x".format(it.toInt() and 255) }),false)
    }
    /** The private trailing triplet and owned metadata, not an arbitrary .text
     * prefix, identify an adapter. An unrelated original .textkbl is retained. */
    private fun ownedSections(image:PeImage):List<PeSection> {
        val tail=image.sections.takeLast(3)
        if(tail.size==3 && tail[0].name in setOf(RX_NAME,LEGACY_RX_NAME) &&
            tail[1].name==".kblctx" && tail[2].name==".kblimg"){
            if(image.sections.dropLast(3).any { it.name in setOf(LEGACY_RX_NAME,".kblctx",".kblimg") })
                error("已有KBL映射标记重复")
            return tail
        }
        if(image.sections.any { it.name in setOf(LEGACY_RX_NAME,".kblctx",".kblimg") })error("已有KBL映射布局不完整")
        return emptyList()
    }
    private fun linkedBranch(pc:Long,target:Long)=A64.branch(pc,target) or 0x80000000.toInt()
    private fun tableHandler(image:PeImage,plan:SuperUnlockPatcher.Plan,name:String):Long? {
        fun rva(value:Long):Long?=when {
            image.rvaToOffset(value)!=null->value
            value>=image.imageBase && image.rvaToOffset(value-image.imageBase)!=null->value-image.imageBase
            else->null
        }
        return (plan.tableStartRva until plan.tableEndRva step 16).mapNotNull { at ->
            val off=image.rvaToOffset(at,16)?:return@mapNotNull null
            val text=rva(image.bytes.u64(off))?:return@mapNotNull null
            val handler=rva(image.bytes.u64(off+8))?:return@mapNotNull null
            handler.takeIf { image.ascii(text)==name && image.isCode(handler) }
        }.distinct().singleOrNull()
    }
    private fun tableNames(image:PeImage,plan:SuperUnlockPatcher.Plan):List<String> =
        (plan.tableStartRva until plan.tableEndRva step 16).mapNotNull{at->
            val off=image.rvaToOffset(at,16)?:return@mapNotNull null
            val ptr=image.bytes.u64(off)
            val rva=if(image.rvaToOffset(ptr)!=null)ptr else ptr-image.imageBase
            image.ascii(rva)
        }
    internal fun validateDriver(bytes: ByteArray) {
        if(bytes.size<512) error("EFI driver 大小无效")
        val p=PeImage.parse(bytes); val o=bytes.u32(0x3c).asBoundedInt("driver PE头")+24
        if(bytes.u16(o+68)!=11) error("KBL payload 必须为 EFI_BOOT_SERVICE_DRIVER subsystem 11")
        if(p.sections.any { it.executable && it.writable }) error("KBL driver 存在同时可写可执行节")
    }
    private fun append(image:PeImage,rxBytes:Int,imgBytes:Int):ByteArray {
        var src=image.bytes;var pe=src.u32(0x3c).asBoundedInt("PE头");var o=pe+24
        val count=src.u16(pe+6);val optionalSize=src.u16(pe+20)
        var table=o+optionalSize;var slot=table+count*40;var end=slot+120
        val firstRaw=image.sections.filter { it.rawSize>0 }.minOf { it.raw }
        if(count>65532) error("PE 节数量已占满真实 COFF uint16 字段，缺少三个 KBL 节名额")
        val headerEnd=minOf(image.headersSize,firstRaw)
        if(end>headerEnd || (slot until end).any { src[it]!=0.toByte() }) {
            /* Some firmware puts NT headers at the very end of the first page.
             * Preserve every original section/file mapping: relocate only the
             * active header into a proved zero/unowned gap before old NT data.
             * The original NT bytes stay in place as inert retained bytes. */
            val span=24+optionalSize+(count+3)*40
            val dirs=minOf(16L,src.u32(o+108),maxOf(0,(optionalSize-112)/8).toLong()).toInt()
            fun owned(start:Int,finish:Int):Boolean {
                for(i in 0 until dirs){val at=o+112+i*8;val a=src.u32(at);val n=src.u32(at+4)
                    if(n>0 && a<finish && start<a+n)return true}
                val a=src.u32(pe+12);val n=src.u32(pe+16)*18
                return n>0 && a<finish && start<a+n
            }
            val target=(0x80..pe-span step 8).firstOrNull { at ->
                at+span<=headerEnd && !owned(at,at+span) && (at until at+span).all { src[it]==0.toByte() }
            } ?: error("PE节表缺少三个空位，头部也没有可验证的空白区域")
            val copy=src.copyOf();src.copyInto(copy,target,pe,table+count*40)
            copy.putWord(0x3c,target);src=copy;pe=target;o=pe+24
            table=o+optionalSize;slot=table+count*40;end=slot+120
        }
        for(i in 0 until minOf(16L,src.u32(o+108),maxOf(0,(src.u16(pe+20)-112)/8).toLong()).toInt()) {
            val at=o+112+i*8;val a=src.u32(at);val n=src.u32(at+4)
            if(n>0 && a<end && slot<a+n) error("新增节表与现有目录重叠")
        }
        val sy=src.u32(pe+12);val sn=src.u32(pe+16)*18
        if(sn>0 && sy<end && slot<sy+sn) error("新增节表与COFF符号重叠")
        fun alignment(at:Int):Long {val v=src.u32(at);if(v==0L||v and(v-1)!=0L)error("PE对齐无效");return v}
        val fa=alignment(o+36);val sa=alignment(o+32);if(sa<fa)error("PE对齐顺序无效")
        val rawAlign=maxOf(4096,fa);val rvaAlign=maxOf(4096,sa)
        var raw=align(src.size.toLong(),rawAlign);var rva=align(maxOf(src.u32(o+56),image.sections.maxOf { it.rva+maxOf(it.virtualSize,it.rawSize.toLong()) }),rvaAlign)
        val layouts=listOf(Triple(RX_NAME,rxBytes,0x60000020),Triple(".kblctx",64,0xc0000040.toInt()),Triple(".kblimg",imgBytes,0x40000040)).map { (name,n,flags) ->
            val size=align(n.toLong(),rawAlign);val result=longArrayOf(raw,rva,size,flags.toLong() and 0xffffffffL)
            raw+=size;rva=align(rva+size,rvaAlign);name to result
        }
        val sizeOfImage=align(rva,sa)
        if(raw>Int.MAX_VALUE.toLong() || sizeOfImage>0xffffffffL)error("KBL 追加映射超出 ByteArray 或 PE32 RVA 范围")
        val out=src.copyOf(raw.toInt());layouts.forEachIndexed { i,(name,l) -> val at=slot+i*40
            name.toByteArray().copyInto(out,at);out.putWord(at+8,l[2].toInt());out.putWord(at+12,l[1].toInt())
            out.putWord(at+16,l[2].toInt());out.putWord(at+20,l[0].toInt());out.putWord(at+36,l[3].toInt())
        }
        out[pe+6]=(count+3).toByte();out[pe+7]=((count+3) ushr 8).toByte()
        val code=src.u32(o+4)+layouts[0].second[2];val initialized=src.u32(o+8)+layouts.drop(1).sumOf { it.second[2] }
        if(code>0xffffffffL||initialized>0xffffffffL)error("PE长度字段溢出")
        out.putWord(o+4,code.toInt());out.putWord(o+8,initialized.toInt());out.putWord(o+56,sizeOfImage.toInt());out.putWord(o+64,0)
        return out
    }
    internal class Code(private val start:Long) {
        private val words=ArrayList<Int>();val pc get()=start+words.size*4
        fun emit(w:Int){words+=w}
        fun replace(at:Long,w:Int){check(at>=start&&(at-start)%4==0L);words[((at-start)/4).toInt()]=w}
        fun mov(d:Int,s:Int)=emit(0xaa0003e0.toInt() or(s shl 16)or d)
        fun branch(target:Long,link:Boolean=false)=emit(A64.branch(pc,target) or(if(link)0x80000000.toInt() else 0))
        fun adr(r:Int,target:Long) {val d=target-pc;if(d !in -0x100000L..0xfffffL)error("KBL ADR超出范围")
            emit(0x10000000 or((d.toInt()and 3)shl 29)or(((d shr 2).toInt()and 0x7ffff)shl 5)or r)}
        fun adrpAdd(r:Int,target:Long){val d=((target and -4096L)-(pc and -4096L))shr 12
            if(d !in -0x100000L..0xfffffL)error("KBL ADRP超出范围")
            emit(0x90000000.toInt()or((d.toInt()and 3)shl 29)or(((d shr 2).toInt()and 0x7ffff)shl 5)or r)
            emit(0x91000000.toInt()or((target.toInt()and 4095)shl 10)or(r shl 5)or r)}
        fun pair(load:Boolean,q:Boolean,a:Int,b:Int,off:Int){val scale=if(q)16 else 8
            val op=if(q){if(load)0xad400000.toInt() else 0xad000000.toInt()}else{if(load)0xa9400000.toInt() else 0xa9000000.toInt()}
            check(off%scale==0 && off/scale in 0..63);emit(op or((off/scale)shl 15)or(b shl 10)or(31 shl 5)or a)}
        fun scalar(load:Boolean,r:Int,off:Int)=emit((if(load)0xf9400000.toInt() else 0xf9000000.toInt())or((off/8)shl 10)or(31 shl 5)or r)
        fun bytes()=ByteArray(words.size*4).also { b->words.forEachIndexed { i,w->b.putWord(i*4,w) } }
    }
    private fun align(v:Long,a:Long):Long=(v+a-1)/a*a
    private fun align(v:Long,a:Int)=align(v,a.toLong())
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun error(s:String):Nothing=throw PatchException("$s；原始输入保持不变。")
}
