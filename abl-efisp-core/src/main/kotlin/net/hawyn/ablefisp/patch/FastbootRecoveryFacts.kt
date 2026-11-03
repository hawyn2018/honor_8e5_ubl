package net.hawyn.ablefisp.patch

/** Role/CFG based recovery coordinates. No firmware identity or address table is used.
 *
 * A location is not an ABI proof. In particular [RecoveryEntryPrerequisites] must
 * not be treated as permission to branch out of a nested frame into main.
 */
internal object FastbootRecoveryFacts {
    data class FunctionRange(val start: Long, val end: Long)
    data class MainFrame(val bytes: Long, val returnRestoresFrame: Boolean,
        val hasDynamicStackBody: Boolean, val prologueStackWrites: List<Long>)
    data class EntryPrefix(val hookRva:Long,val originalWord:Int,val resumeRva:Long,
        val landingWords:List<Int>,val replayWords:List<Int>,val originalPrefixBytes:ByteArray,
        val overwrittenWordPcRelative:Boolean,val entryFrameBytes:Long,val tailRestoresEntryFrame:Boolean)
    data class Canary(val globalRva:Long,val width:Int,val stackOffset:Int,
        val saveRva:Long,val savedReadRva:Long,val globalReadRva:Long,
        val compareRva:Long,val compareCallRva:Long?,val failureBranchRva:Long,val resetStoreRva:Long,
        val checkingGlobalReadRva:Long)
    data class CardInfo(val locateCallRva: Long, val resultStoreRva: Long,
        val statusRegister: Int, val checkpointRva: Long)
    data class ContinueFlow(val handlerRva: Long, val authCallRva: Long,
        val authTargetRva: Long, val bootLinuxCallRva: Long, val bootLinuxTargetRva: Long,
        val flagRva: Long, val flagStoreRva: Long, val pollFlagLoadRva: Long) {
        val invokesChildBootLinux get() = true
        val returnedBootLinuxNeedsFlagClear get() = true
    }
    data class PreparationRoute(val entryRva:Long,val successGateRva:Long,
        val originalSuccessWord:Int,val statusRegister:Int,val successDestinationRva:Long,
        val afterPreparationGlobalRegisters:Map<Int,Long> = emptyMap(),
        val afterPreparationConstants:Map<Int,Long> = emptyMap(),val proof:String)
    /** Whole first logger-call prefix. Moving only its first address instruction
     * would split the format-address provenance used by existing EFI -bl readers.
     * The producer proves an uninterrupted, side-effect-free parameter prefix
     * and no independently addressed entry into its interior. */
    data class FastbootScopePrefix(val startRva:Long,val originalWords:List<Int>,
        val loggerCallRva:Long,val loggerTargetRva:Long,val resumeRva:Long,
        val formatRva:Long,val formatRegister:Int,val noExternalEntryProven:Boolean,val proof:String)
    data class RecoveryEntryPrerequisites(val checkpointRva: Long,
        val earlyJumpProven: Boolean, val materializableGlobalRegisters: Map<Int, Long>,
        val note: String, val materializableConstants: Map<Int,Long> = emptyMap(),
        val liveInRegisters: Set<Int> = emptySet(), val checkpointStackOffset: Int? = null,
        val nativeStackInputs: Map<Int,Int> = emptyMap(), val unsupportedReasons: List<String> = emptyList(),
        val recoveryEntryRva:Long = 0,val preparationRoute:PreparationRoute? = null,
        val preservedLiveInRegisters:Set<Int> = emptySet(),
        val summarizedNativeAdapters:Map<Long,Int> = emptyMap())
    data class Facts(val entryRva: Long, val entryTailBranchRva: Long,val entryPrefix:EntryPrefix,
        val main: FunctionRange, val frame: MainFrame, val commonReturnRva: Long,
        val commonExitRva: Long?, val keyReadCallRva: Long, val keyHookRva: Long,
        val keyResultStackOffset: Long, val requestFlagRva: Long?,
        val requestFlagBaseRegister: Int, val requestFlagByteOffset: Int,
        val requestFlagLoadRva: Long, val requestFlagBranchRva: Long,
        val normalBootRva: Long, val fastbootGateRva: Long, val fastbootSetupRva: Long,
        val cardInfoGateRegister: Int, val fatalSpinRvas: List<Long>,
        val cardInfo: CardInfo?, val continueFlow: ContinueFlow,
        val prerequisites: RecoveryEntryPrerequisites, val diagnosticSites: Map<String, Long>,val canary:Canary?,
        val scopePrefix:FastbootScopePrefix? = null) {
        val canaryGlobalRva get()=canary?.globalRva?:0L
        val canaryWidth get()=canary?.width?:0
    }

    private data class Mem(val rt: Int, val rn: Int, val offset: Int, val size: Int, val load: Boolean)
    private class Index(val image: PeImage) {
        val code = image.instructions().toList()
        val refs = A64.references(image)
        val starts = code.filter { it.second in setOf(0xd503233f.toInt(), 0xd503237f.toInt()) }.map { it.first }
        val directIncoming = code.mapNotNull { (pc, w) -> target(pc, w)?.let { it to pc } }.groupBy({ it.first }, { it.second })
        fun owner(pc: Long): FunctionRange? {
            val a = starts.lastOrNull { it <= pc } ?: return null
            val z = starts.firstOrNull { it > a } ?: image.sections.firstOrNull { image.isCode(a) && a in it.rva..<it.rva+it.rawSize }?.let { it.rva+it.rawSize } ?: return null
            return FunctionRange(a, z)
        }
        fun body(f: FunctionRange) = code.filter { it.first in f.start..<f.end }
        fun messages(fragment: String, f: FunctionRange? = null): List<StringReference> = refs.filter {
            (f == null || it.pc in f.start..<f.end) && image.ascii(it.address)?.contains(fragment, ignoreCase = true) == true
        }
        /** A local ADR/MOV/ADD definition must dominate this window and survive it.
         * Ordinary PAC functions preserve callee-saved registers; unproven outlined
         * helpers do not get that assumption. Ambiguity remains null. */
        fun globalAt(pc: Long, register: Int, lower: Long, depth: Int = 0): Long? {
            if (depth > 16 || register !in 0..30) return null
            var at = pc - 4
            while (at >= lower) {
                val w = image.word(at) ?: return null
                if (A64.blTarget(at, w) != null || w and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) {
                    val call = A64.blTarget(at, w)
                    if(call==null) {
                        // Native UEFI protocol methods follow AAPCS64 and preserve
                        // nonvolatile GP registers. Their returned pointers remain
                        // unknown; this only preserves an already-defined base.
                        if(register<19)return null
                        at-=4;continue
                    }
                    val leaf=linearLeaf(image,call)
                    if(leaf==null&&register in 19..29&&readOnlyLeaf(image,call)!=null) {
                        at-=4;continue
                    }
                    val literal=leaf?.lastOrNull()?.let { end->
                        A64.argumentState(image,call,end.first+4).registers[register] as? A64.Value.Address
                    }
                    if(literal!=null)return literal.rva
                    if(leaf!=null&&leaf.none { (p,x)->!landing(x)&&
                        x and 0xfffffc1f.toInt()!=0xd65f0000.toInt()&&StraightLineLiveness.mayWriteRegister(x,register) }) {
                        at-=4;continue
                    }
                    if (register < 19 || image.word(call) !in setOf(0xd503233f.toInt(), 0xd503237f.toInt())) return null
                    at -= 4; continue
                }
                if (!StraightLineLiveness.mayWriteRegister(w, register)) { at -= 4; continue }
                val value = A64.adr(at, w)?.takeIf { it.first == register }?.second
                    ?: A64.adrp(at, w)?.takeIf { it.first == register }?.second
                    ?: A64.add(w)?.takeIf { it.rd == register }?.let { a -> globalAt(at, a.rn, lower, depth+1)?.plus(a.immediate) }
                    ?: if (w and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt() && w and 31 == register)
                        globalAt(at, (w ushr 16) and 31, lower, depth+1) else null
                if (value == null) return null
                if (directIncoming.any { (to, from) -> to in at+4..<pc && from.any { source ->
                    source !in at..<pc && !(source >= pc && source < (owner(pc)?.end ?: pc) &&
                        (pc..source step 4).all { q -> image.word(q)?.let { !StraightLineLiveness.mayWriteRegister(it, register) } == true })
                } }) return null
                return value
            }
            return null
        }
    }

    fun analyze(image: PeImage): Facts {
        val index = Index(image)
        val fatalRefs = index.messages("No fastboot support for flashless")
        val main = fatalRefs.mapNotNull { index.owner(it.pc) }.distinct().singleOrNull()
            ?: fail("Fastboot 主函数未唯一定位")
        val words = index.body(main)
        val fatalRef = fatalRefs.singleOrNull { it.pc in main.start..<main.end } ?: fail("CardInfo fatal 日志")
        val spin = words.filter { (pc, w) -> pc >= fatalRef.pc && A64.bTarget(pc, w) == pc }
            .firstOrNull()?.first ?: fail("fatal 自旋末端")
        val fatalGate = words.singleOrNull { (pc, w) -> isTest(w) && testBit(w) == 63 &&
            w and 0x01000000 != 0 && target(pc, w)?.let { it <= fatalRef.pc && fatalRef.pc <= it+24 } == true }
            ?: fail("CardInfo 错误分支")
        val flagBranch = words.singleOrNull { (pc, w) -> isTest(w) && testBit(w) == 0 &&
            w and 0x01000000 != 0 && target(pc, w) == fatalGate.first }
            ?: fail("Fastboot 请求标志消费者")
        val flagLoad = words.lastOrNull { (pc, w) -> pc < flagBranch.first && flagBranch.first-pc <= 24 &&
            memory(w)?.let { it.load && it.size == 1 && it.rt == flagBranch.second and 31 } == true }
            ?: fail("Fastboot 请求标志读取")
        val flagMem = requireNotNull(memory(flagLoad.second))
        val requestFlag = index.globalAt(flagLoad.first, flagMem.rn, main.start)?.plus(flagMem.offset)
            ?.takeIf { image.isWritable(it, 1) }

        val provider = index.messages("ScanCode").mapNotNull { index.owner(it.pc) }.distinct()
            .filter { index.messages("Key Stroke Read", it).isNotEmpty() }.singleOrNull()
            ?: fail("按键提供者及扫描码输出关系")
        val keyCall = words.singleOrNull { (pc, w) -> A64.blTarget(pc, w) == provider.start }
            ?: fail("主函数按键调用")
        val keyArgs = A64.argumentState(image, maxOf(main.start, keyCall.first-32), keyCall.first)
        val keyStack = (keyArgs.registers[0] as? A64.Value.Stack)?.offset ?: fail("按键输出的主 frame 地址")
        val returns = words.filter { it.second and 0xfffffc1f.toInt() == 0xd65f0000.toInt() ||
            it.second in setOf(0xd65f0bff.toInt(), 0xd65f0fff.toInt()) }.map { it.first }
        val commonRet = returns.singleOrNull() ?: fail("主函数返回边界")
        val entryOwner = index.owner(image.entryRva) ?: fail("EFI 入口范围")
        val entryTail = index.body(entryOwner).singleOrNull { (pc, w) -> A64.bTarget(pc, w) == main.start }
            ?: fail("EFI 包装器到主函数的尾调用")

        val initRefs = index.messages("couldnt init fastboot", main)
        val initRef = initRefs.filter { image.ascii(it.address)?.contains("exiting",ignoreCase=true)==true }.singleOrNull()
            ?: initRefs.singleOrNull()
            ?: fail("Fastboot 初始化失败诊断")
        val exitTargets = words.filter { (pc, _) -> pc >= initRef.pc && pc < initRef.pc+160 }
            .mapNotNull { (pc, w) -> target(pc, w)?.takeIf { it in main.start..<commonRet } }.distinct()
        val commonExit = exitTargets.maxByOrNull { index.directIncoming[it].orEmpty().size }
        val diagnostics = linkedMapOf("fatalCardInfo" to fatalRef.pc, "fastbootInitFailure" to initRef.pc)
        val fatalLogCall = words.firstOrNull { (pc,w) -> pc in fatalRef.pc..fatalRef.pc+32 && A64.blTarget(pc,w)!=null }
        val logTarget = fatalLogCall?.let { A64.blTarget(it.first,it.second) }
        val detectorRef = index.messages("LinuxLoader detector init",main).singleOrNull()
        val detectorCall = detectorRef?.let { ref -> words.firstOrNull { (pc,w) ->
            pc in ref.pc..ref.pc+32 && A64.blTarget(pc,w)==logTarget }?.first }
        if(detectorRef!=null)diagnostics["detectorInitLog"]=detectorRef.pc
        if(detectorCall!=null){diagnostics["detectorInitLogCall"]=detectorCall;diagnostics["detectorInitCheckpoint"]=detectorCall+4}
        for ((role, text) in listOf("keyReadError" to "Error reading key status", "resetReasonError" to "Error locating the reset reason",
            "usbProtocolError" to "couldnt find USB device protocol", "continueDetected" to "Continue detected", "uninitError" to "couldnt uninit fastboot"))
            index.messages(text, main).singleOrNull()?.let { diagnostics[role] = it.pc }
        val cardInfo = cardInfo(index, main, fatalGate.second and 31)
        val continueFlow = continueFlow(index, main)
        val frame = frame(image, main, commonRet)
        // Resolve incoming gBS consumers by standardized method ABI, not by a
        // register number. Old loaders reuse their gBS-slot register before the
        // detector checkpoint, so merely preserving every register is insufficient.
        val env = runCatching { KblEnvironmentFacts.analyze(image) }.getOrNull()
        val globals = if(env==null)emptyMap() else incomingGlobals(index,main,fatalGate.first+4,initRef.pc,
            commonExit,env.bootServicesGlobal)
        val entryPrefix=entryPrefix(image,entryOwner,entryTail.first)
        val canary=canary(index,main,commonRet,commonExit)
        val prerequisites = recoveryPrerequisites(index,main,detectorCall?.plus(4),fatalGate.first+4,
            commonRet,frame,entryPrefix,canary,globals,env,cardInfo)
        return Facts(image.entryRva, entryTail.first,entryPrefix, main, frame, commonRet, commonExit,
            keyCall.first, keyCall.first, keyStack, requestFlag, flagMem.rn, flagMem.offset,
            flagLoad.first, flagBranch.first, flagBranch.first+4, fatalGate.first, fatalGate.first+4,
            fatalGate.second and 31, listOf(spin), cardInfo, continueFlow, prerequisites, diagnostics,canary,
            logTarget?.let { scopePrefix(index,main,fatalGate.first+4,it) })
    }

    internal fun discoverScopePrefix(image:PeImage,main:FunctionRange,setup:Long,logger:Long):FastbootScopePrefix? =
        scopePrefix(Index(image),main,setup,logger)

    private fun scopePrefix(index:Index,main:FunctionRange,setup:Long,logger:Long):FastbootScopePrefix? {
        val image=index.image
        if(setup !in main.start..<main.end||setup and 3L!=0L||!image.isCode(logger))return null
        val words=ArrayList<Int>();var at=setup
        while(at<main.end) {
            val w=image.word(at)?.takeIf { image.isCode(at) }?:return null
            val call=A64.blTarget(at,w)
            if(call!=null) {
                if(call!=logger)return null
                words+=w
                val args=A64.argumentState(image,setup,at)
                val formats=(0..1).mapNotNull { r ->
                    val address=(args.registers[r] as? A64.Value.Address)?.rva?:return@mapNotNull null
                    val text=image.ascii(address)?:return@mapNotNull null
                    val name="Launching fastboot"
                    if(!text.startsWith(name)||text.getOrNull(name.length)?.let { it.isLetterOrDigit()||it=='_' }==true)
                        return@mapNotNull null
                    r to address
                }
                val format=formats.singleOrNull()?:return null
                val end=at+4
                val interior=setup+4..<end
                fun interiorCodeEntry(address:Long)=address and 3L==0L&&image.isCode(address,4)&&address in interior
                // Direct branch/call entries and code-address constructions are
                // independent entries even when their source is in another section.
                if(image.entryRva in interior||index.directIncoming.any { (to,from)->
                    to in interior&&from.any { it !in setup..<end }
                }||index.code.any { (pc,x)->pc !in setup..<end&&A64.blTarget(pc,x)?.let { it in interior }==true }||
                    index.refs.any { interiorCodeEntry(it.address)&&it.pc !in setup..<end })return null
                // Both absolute-VA and RVA function tables are used by these
                // merged firmware sections. An exact stored pointer is evidence;
                // arbitrary numeric overlaps or disassembly patterns are not.
                for(section in image.sections)for(raw in section.raw..section.raw+section.rawSize-8 step 4) {
                    val value=image.bytes.u64(raw)
                    if(interiorCodeEntry(value)||interiorCodeEntry(value-image.imageBase))return null
                }
                // ARM64 exception-directory entries can supply an independent
                // function entry even when no ordinary pointer references it.
                val pe=image.bytes.u32(0x3c).toInt();val optional=pe+24
                if(image.bytes.u16(pe+20)>=112+4*8&&image.bytes.u32(optional+108)>3) {
                    val table=image.bytes.u32(optional+112+3*8);val size=image.bytes.u32(optional+116+3*8)
                    if(size!=0L) {
                        if(size%8!=0L||size>Int.MAX_VALUE)return null
                        val raw=image.rvaToOffset(table,size.toInt())?:return null
                        for(n in 0 until size.toInt() step 8)if(image.bytes.u32(raw+n) in interior)return null
                    }
                }
                return FastbootScopePrefix(setup,words,at,logger,end,format.second,format.first,true,
                    "Setup to its first common logger call contains only integer argument construction; "+
                    "the Launching fastboot address reaches X${format.first}. No interior direct/code-address/stored-pointer/exception entry; "+
                    "all original words, including the logger call, relocate together and resume immediately after that call.")
            }
            // Only register-only parameter construction precedes the first call.
            // In particular no load/store, SP, FP/SIMD, system operation or native
            // side effect is silently moved across the scope update.
            val d=w and 31;val n=(w ushr 5)and 31
            val address=A64.adr(at,w)!=null||A64.adrp(at,w)!=null
            val moveWide=w and 0x1f800000==0x12800000
            val moveRegister=w and 0x7fe0ffe0==0x2a0003e0
            val addImmediate=w and 0x1f000000==0x11000000&&w and 0x20000000==0&&d<31&&n<31
            val logicalImmediate=w and 0x1f800000==0x12000000&&w and 0x60000000!=0x60000000
            if(!address&&!moveWide&&!moveRegister&&!addImmediate&&!logicalImmediate&&w!=0xd503201f.toInt())return null
            val e=effect(w)?:return null
            if(e.memory.isNotEmpty()||e.writes.any { it !in 0..18 })return null
            words+=w;at+=4
        }
        return null
    }

    private data class Access(val base:Int,val offset:Int,val width:Int,val load:Boolean,
        val index:Int = -1,val indexScale:Int = 1)
    private data class Effect(val reads:Set<Int> = emptySet(),val writes:Set<Int> = emptySet(),
        val memory:List<Access> = emptyList())
    private data class ReadOnlyLeaf(val effect:Effect,val addressArguments:Set<Int>)

    /** Closed integer leaf CFG, including compiler-generated text-width loops.
     * No call, indirect transfer, stack access, store, system/FP state change or
     * nonvolatile GP write is accepted. Address provenance must come from a PE
     * literal or a caller argument which the caller separately proves is a PE
     * address; loading a value does not invent pointer provenance. */
    private fun readOnlyLeaf(image:PeImage,start:Long):ReadOnlyLeaf? {
        val graph=linkedMapOf<Long,List<Long>>();val effects=linkedMapOf<Long,Effect>()
        val pending=java.util.ArrayDeque<Long>();pending.add(start);var returns=0
        while(pending.isNotEmpty()) {
            val p=pending.removeFirst();if(p in graph)continue
            val w=image.word(p)?.takeIf { image.isCode(p) }?:return null
            if(w in setOf(0xd503233f.toInt(),0xd503237f.toInt(),0xd50323bf.toInt(),0xd50323ff.toInt())||
                A64.blTarget(p,w)!=null||w and 0xfffffc1f.toInt() in setOf(0xd61f0000.toInt(),0xd63f0000.toInt()))return null
            if(w and 0xffc00000.toInt()==0xd5000000.toInt()&&!landing(w)&&w!=0xd503201f.toInt())return null
            if(w and 0x0e000000==0x0e000000||w and 0x04000000!=0&&w and 0x3a000000==0x28000000)return null
            if(w and 0x1f000000==0x11000000&&((w ushr 5)and 31==31||w and 31==31&&w and 0x20000000==0))return null
            if(w and 0x1f000000==0x0b000000&&w and 0x00200000!=0&&
                ((w ushr 5)and 31==31||w and 31==31&&w and 0x20000000==0))return null
            if(w and 0x3b000000 in setOf(0x38000000,0x39000000)&&(w ushr 5)and 31==31)return null
            val ret=w==0xd65f03c0.toInt()
            val e=if(ret)Effect() else effect(w)?:return null
            if(e.writes.any { it in 19..30 }||e.memory.any { !it.load||it.base==31 })return null
            val branch=A64.bTarget(p,w)
            val next=if(ret){returns++;emptyList()} else if(branch!=null)listOf(branch)
                else listOfNotNull(p+4,target(p,w)).distinct()
            graph[p]=next;effects[p]=e;next.forEach(pending::add)
        }
        if(returns==0)return null
        val predecessors=HashMap<Long,MutableList<Long>>()
        graph.forEach { (p,next)->next.forEach { predecessors.getOrPut(it){arrayListOf()}.add(p) } }
        val universe=(0..30).toSet()+32;val defined=graph.keys.associateWith { universe }.toMutableMap()
        defined[start]=emptySet();var changed=true
        while(changed) {
            changed=false
            for(p in graph.keys) {
                val values=predecessors[p].orEmpty().map { defined[it].orEmpty()+effects[it]!!.writes }
                val before=if(p==start)emptySet() else values.reduceOrNull { a,b->a intersect b }?:emptySet()
                if(before!=defined[p]){defined[p]=before;changed=true}
            }
        }
        // Each nonempty set names the original address roots. -1 denotes a
        // mapped ADR/ADRP literal. Empty means scalar/unknown, never an address.
        val addresses=HashMap<Long,Array<Set<Int>>>()
        addresses[start]=Array(31){setOf(it)};pending.add(start)
        while(pending.isNotEmpty()) {
            val p=pending.removeFirst();val before=addresses[p]?:continue
            val w=image.word(p)!!;val after=before.copyOf();val d=w and 31;val n=(w ushr 5)and 31;val m=(w ushr 16)and 31
            effects[p]!!.writes.filter { it in 0..30 }.forEach { after[it]=emptySet() }
            if(w and 0xffe0ffe0.toInt()==0xaa0003e0.toInt()&&d<31&&m<31)after[d]=before[m]
            val literal=A64.adr(p,w)?:A64.adrp(p,w)
            if(literal!=null&&literal.first<31&&image.rvaToOffset(literal.second,1)!=null)after[literal.first]=setOf(-1)
            if(w and 0xbf000000.toInt()==0x91000000.toInt()&&d<31&&n<31)after[d]=before[n]
            if(w and 0xff000000.toInt()==0x8b000000.toInt()&&d<31&&n<31&&m<31) {
                val a=before[n];val b=before[m]
                after[d]=when { a.isNotEmpty()&&b.isEmpty()->a;a.isEmpty()&&b.isNotEmpty()->b;else->emptySet() }
            }
            val singleWriteback=w and 0x3b000000==0x38000000&&w and 0x00200000==0&&(w ushr 10)and 3 in setOf(1,3)
            val pairWriteback=w and 0x3a000000==0x28000000&&(w ushr 23)and 3 in setOf(1,3)
            if((singleWriteback||pairWriteback)&&n<31) {
                if(n==d||pairWriteback&&n==((w ushr 10)and 31))return null
                after[n]=before[n]
            }
            for(next in graph[p].orEmpty()) {
                val old=addresses[next]
                val merged=if(old==null)after.copyOf() else Array(31) { r->
                    if(old[r].isNotEmpty()&&after[r].isNotEmpty())old[r]+after[r] else emptySet() }
                if(old==null||old.indices.any { old[it]!=merged[it] }){addresses[next]=merged;pending.add(next)}
            }
        }
        val addressArgs=linkedSetOf<Int>()
        for((p,e) in effects)for(access in e.memory) {
            val roots=addresses[p]?.getOrNull(access.base)?.takeIf { it.isNotEmpty() }?:return null
            addressArgs+=roots.filter { it>=0 }
        }
        val reads=effects.flatMap { (p,e)->e.reads-defined[p].orEmpty() }.filter { it!=30 }.toSet()
        // A conditional write is not a definite definition at the caller.
        // Keeping only definitions present at every RET preserves any earlier
        // caller dependency when a path leaves a volatile register untouched.
        val writes=graph.keys.filter { image.word(it)==0xd65f03c0.toInt() }.map { defined[it].orEmpty() }
            .reduce { a,b->a intersect b } intersect effects.values.flatMap { it.writes }.toSet()
        return ReadOnlyLeaf(Effect(reads,writes+30),addressArgs)
    }

    /** GP aliases share an identity. SP is deliberately retained in memory operands,
     * but not admitted as an arbitrary integer live-in. Unrecognised instructions
     * make the ABI proof fail; they are never treated as a NOP. */
    private fun effect(w:Int):Effect? {
        val d=w and 31;val n=(w ushr 5)and 31;val m=(w ushr 16)and 31
        fun e(read:List<Int> = emptyList(),write:List<Int> = emptyList(),mem:List<Access> = emptyList())=
            Effect(read.filter { it!=31 }.toSet(),write.filter { it!=31 }.toSet(),mem)
        if(landing(w)||w in setOf(0xd503201f.toInt(),0xd50323bf.toInt(),0xd50323ff.toInt()))return e()
        if(A64.adr(0,w)!=null||A64.adrp(0,w)!=null)return e(write=listOf(d))
        if(A64.bTarget(0,w)!=null)return e()
        if(w and 0xff000010.toInt()==0x54000000)return Effect(setOf(32))
        if(w and 0x7e000000 in setOf(0x34000000,0x36000000))return e(listOf(d))
        if(w and 0xfffffc1f.toInt() in setOf(0xd65f0000.toInt(),0xd61f0000.toInt()))return e(listOf(n))
        if(w in setOf(0xd65f0bff.toInt(),0xd65f0fff.toInt()))return e(listOf(30))
        if(w and 0xffc00000.toInt()==0xd5000000.toInt())return if(w and 0x00200000!=0)e(write=listOf(d)) else e(listOf(d))
        if(w and 0x3a000000==0x28000000) {
            val vector=w and 0x04000000!=0;val load=w and 0x00400000!=0
            val size=if(vector)4 shl(w ushr 30) else if(w ushr 30==2)8 else 4
            val second=(w ushr 10)and 31;val mode=(w ushr 23)and 3
            val imm=signed(((w ushr 15)and 127).toLong(),7).toInt()*size
            val off=if(mode==1)0 else imm
            return e(listOf(n)+if(!vector&&!load)listOf(d,second) else emptyList(),
                (if(mode in setOf(1,3))listOf(n) else emptyList())+if(!vector&&load)listOf(d,second) else emptyList(),
                listOf(Access(n,off,size,load),Access(n,off+size,size,load)))
        }
        if(w and 0x3b000000 in setOf(0x39000000,0x38000000)) {
            val vector=w and 0x04000000!=0;val op=(w ushr 22)and 3
            val prefetch=!vector&&w ushr 30==3&&op==2
            val load=if(vector)op and 1!=0 else op!=0
            val size=if(vector&&op>=2)16 else 1 shl(w ushr 30)
            val unsigned=w and 0x3b000000==0x39000000;val indexed=!unsigned&&w and 0x00200000!=0
            val mode=(w ushr 10)and 3;if(indexed&&mode!=2)return null
            val wb=!unsigned&&!indexed&&mode in setOf(1,3)
            val off=if(unsigned)((w ushr 10)and 4095)*size else if(mode==1)0 else signed(((w ushr 12)and 511).toLong(),9).toInt()
            // A register-indexed local stack access needs a separate bounded-index
            // proof. Keep its GP effects, but make its unknown memory range explicit.
            return e(listOf(n)+(if(indexed)listOf(m) else emptyList())+
                if(!vector&&!load&&!prefetch)listOf(d) else emptyList(),
                (if(wb)listOf(n) else emptyList())+if(!vector&&load&&!prefetch)listOf(d) else emptyList(),
                if(prefetch)emptyList() else listOf(Access(n,if(indexed)0 else off,size,load,
                    if(indexed)m else -1,if(indexed&&w and 0x1000!=0)size else 1)))
        }
        if(w and 0x3b000000==0x18000000)return e(write=if(w and 0x04000000==0&&w ushr 30!=3)listOf(d) else emptyList())
        if(w and 0x1f800000==0x12800000)return e(if(w and 0x60000000==0x60000000)listOf(d) else emptyList(),listOf(d))
        if(w and 0x1f000000==0x11000000)return e(listOf(n),listOf(d)+if(w and 0x20000000!=0)listOf(32) else emptyList())
        if(w and 0x1f800000==0x12000000)return e(listOf(n),listOf(d)+if((w ushr 29)and 3==3)listOf(32) else emptyList())
        if(w and 0x1f800000==0x13000000)return e(listOf(n)+if(w and 0x60000000==0x20000000)listOf(d) else emptyList(),listOf(d))
        if(w and 0x1f800000==0x13800000)return e(listOf(n,m),listOf(d))
        if(w and 0x1fe00000==0x1a800000)return e(listOf(n,m,32),listOf(d))
        if(w and 0x1fe00000==0x1a400000)return e((if(w and 0x800!=0)listOf(n) else listOf(n,m))+32,listOf(32))
        if(w and 0x1f000000==0x0b000000)return e(listOf(n,m),listOf(d)+if(w and 0x20000000!=0)listOf(32) else emptyList())
        if(w and 0x1f000000==0x0a000000)return e(listOf(n,m),listOf(d)+if((w ushr 29)and 3==3)listOf(32) else emptyList())
        if(w and 0x1f000000==0x1b000000)return e(listOf(n,m,(w ushr 10)and 31),listOf(d))
        if(w and 0x1fe00000==0x1ac00000)return e(listOf(n,m),listOf(d)) // UDIV/SDIV/variable shifts
        if(w and 0x1fe00000==0x1ac00000||w and 0x5fe00000==0x5ac00000)return e(listOf(n),listOf(d))
        // FMOV between GP and scalar SIMD; ordinary SIMD instructions do not read
        // or write GP state. Integer scalar operands are handled above.
        if(w and 0x5f20fc00==0x1e200000) {
            val op=(w ushr 16)and 31
            return if(op in setOf(2,3,7))e(listOf(n)) else e(write=listOf(d))
        }
        if(w and 0x5f200000==0x1e000000)return if((w ushr 16)and 7 in setOf(2,3))e(listOf(n)) else e(write=listOf(d))
        if(w and 0x0e000000==0x0e000000) {
            // The SIMD-copy family distinguishes DUP element, DUP general, INS
            // general and UMOV/SMOV by op/imm4. Element-only copies touch no GP.
            if(w and 0x0f200400==0x0e000400) {
                val op=(w ushr 29)and 1;val copy=(w ushr 11)and 15
                if(op==0&&copy in setOf(1,3))return e(listOf(n))
                if(op==0&&copy in setOf(5,7))return e(write=listOf(d))
            }
            return e()
        }
        return null
    }

    private fun recoveryPrerequisites(index:Index,main:FunctionRange,checkpoint:Long?,setup:Long,
        ret:Long,frame:MainFrame,entry:EntryPrefix,canary:Canary?,roleGlobals:Map<Int,Long>,
        env:KblEnvironmentFacts.Facts?,cardInfo:CardInfo?):RecoveryEntryPrerequisites {
        val image=index.image;val reasons=linkedSetOf<String>()
        val at=checkpoint?:setup.also { reasons+="缺少 detector-init 成功日志后的早检查点" }
        // The wrapper reaches this checkpoint in a fresh main invocation. Later
        // boot-selection backedges are not predecessors of that first arrival.
        // Including them would merge a later epilogue's restored caller values
        // into these still-live native initialization registers.
        val prefix=A64ValueOrigins(image,main.start,at+4)
        val preparationNotes=ArrayList<String>()
        val preparation=if(env!=null&&cardInfo!=null)preparationRoute(index,main,at,prefix,env.bootServicesGlobal,cardInfo,preparationNotes) else null
        if(env!=null&&cardInfo!=null&&preparation==null)reasons+="native Fastboot 协议准备路径未唯一证明，禁止绕过其初始化副作用 (${preparationNotes.joinToString()})"
        val recoveryEntry=preparation?.entryRva?:setup
        val checkpointSp=prefix.stackPointerOffset(at)
        if(checkpointSp?.toLong()!=-frame.bytes)reasons+="早检查点 SP 未证明位于完整 main frame 底部"
        if(!frame.returnRestoresFrame||!entry.tailRestoresEntryFrame)reasons+="native main/EFI frame 保存恢复不成对"
        // This is an analysis-only view. The original image bytes are never changed.
        // Entering the native setup with symbolic incoming registers exposes every
        // read-before-definition, including backwards error funnels. A valid
        // native guard excludes only its own failure edge, not arbitrary errors.
        val bytes=image.bytes.copyOf()
        fun analysisWord(pc:Long,w:Int){val raw=image.rvaToOffset(pc,4)?:return;bytes.putWord(raw,w)}
        val summarizedAdapters=linkedMapOf<Long,Int>()
        // The existing core's command-registration adapter is outside main but
        // preserves its complete live machine state. Re-prove its native table,
        // register function and DeviceInfo dataflow, then require its entire
        // generated block (including handler/data) to match before summarizing
        // the registration wrapper by its original conditional branch. The real
        // firmware and its command registration remain untouched.
        val existingRegistration=runCatching {
            SuperUnlockPatcher.analyze(image,FlashingUnlockPatcher.analyze(image))
        }.getOrNull()
        existingRegistration?.let { p->
            val adapter=p.existingAllocationRva
            if(adapter!=null&&p.registrationGateRva in main.start..<main.end&&
                p.registrationContinueRva in main.start..<main.end&&p.registrationExitRva in main.start..<main.end&&
                image.word(p.registrationGateRva)==A64.branch(p.registrationGateRva,adapter)) {
                // SuperUnlockPatcher.analyze verifies all bytes against the
                // current state-preserving SuperUnlockCode emitter. A damaged
                // or unrelated external branch never reaches this case.
                analysisWord(p.registrationGateRva,p.registrationOriginalWord)
                summarizedAdapters[p.registrationGateRva]=p.registrationOriginalWord
            }
        }
        analysisWord(main.start,A64.branch(main.start,recoveryEntry))
        preparation?.let { route->
            if(route.originalSuccessWord and 0x01000000!=0) {
                // CBNZ branches to the original error path; its zero-success
                // fallthrough is exclusive to this gate. Keep the error edge.
                analysisWord(route.successGateRva+4,A64.branch(route.successGateRva+4,setup))
            } else {
                val displacement=(setup-route.successGateRva)/4
                if(setup-route.successGateRva< -0x100000||setup-route.successGateRva>=0x100000)
                    reasons+="native preparation 成功目标超出条件分支范围"
                else analysisWord(route.successGateRva,(route.originalSuccessWord and 0xff00001f.toInt())or((displacement.toInt()and 0x7ffff)shl 5))
            }
        }
        canary?.let { analysisWord(it.failureBranchRva,0xd503201f.toInt()) }
        val view=PeImage.parse(bytes);val native=A64ValueOrigins(view,main.start,main.end)
        if(preparation!=null&&(native.stackPointerOffset(preparation.successGateRva)!=0||native.stackPointerOffset(setup)!=0))
            reasons+="native preparation 成功出口与 Fastboot setup 的 SP 不一致"
        val graph=linkedMapOf<Long,List<Long>>()
        val effects=linkedMapOf<Long,Effect>()
        val leafCache=HashMap<Long,Effect?>()
        val readOnlyCache=HashMap<Long,ReadOnlyLeaf?>()
        fun callEffect(pc:Long,w:Int):Effect? {
            val to=A64.blTarget(pc,w)
            if(to==null)return Effect(setOf((w ushr 5)and 31),(0..18).toSet()+setOf(30,32))
            if(image.word(to) in setOf(0xd503233f.toInt(),0xd503237f.toInt()))return Effect(writes=(0..18).toSet()+setOf(30,32))
            if(leafCache.containsKey(to))return leafCache[to]
            val leaf=linearLeaf(image,to)
            if(leaf==null) {
                val pure=readOnlyCache.getOrPut(to){readOnlyLeaf(image,to)}?:return null
                if(pure.addressArguments.any { r->native.globalAddress(pc,r)?.let { image.rvaToOffset(it,1)!=null }!=true })return null
                return pure.effect
            }
            var defined=emptySet<Int>();val reads=linkedSetOf<Int>();val accesses=ArrayList<Access>()
            val leafFlow=A64ValueOrigins(image,to,leaf.last().first+4)
            for((lp,lw) in leaf) {
                if(lw and 0xfffffc1f.toInt()==0xd65f0000.toInt())continue
                val e=effect(lw)?:run { leafCache[to]=null;return null }
                reads+=e.reads-defined;defined=defined+e.writes
                for(m in e.memory) {
                    val access=if(m.index<0)m else m.copy(index=(leafFlow.origins(lp,m.index)?.singleOrNull() as? A64ValueOrigin.EntryArgument)?.register?:-2)
                    val stack=leafFlow.stackAddress(lp,m.base)
                    if(stack!=null)accesses+=access.copy(base=31,offset=(leafFlow.stackPointerOffset(lp)?:0)+stack+m.offset)
                    else {
                        val sources=(0..30).mapNotNull { r->leafFlow.objectOffset(lp,m.base,r)?.let { r to it } }
                        sources.singleOrNull()?.let { (r,off)->accesses+=access.copy(base=r,offset=off+m.offset) }
                    }
                }
            }
            return Effect(reads,defined+30,accesses).also { leafCache[to]=it }
        }
        val pending=java.util.ArrayDeque<Long>();pending.add(recoveryEntry)
        while(pending.isNotEmpty()) {
            val pc=pending.removeFirst();if(pc in graph)continue
            if(pc !in main.start..<main.end||!view.isCode(pc)){reasons+="native Fastboot CFG 离开主函数边界";continue}
            val w=view.word(pc)?:continue
            val call=A64.blTarget(pc,w)!=null||w and 0xfffffc1f.toInt()==0xd63f0000.toInt()
            val e=if(call)callEffect(pc,w) else effect(w)
            if(e==null)reasons+="native ABI 指令/outlined helper 未建模 @${pc.toString(16)}"
            effects[pc]=e?:Effect()
            val branch=A64.bTarget(pc,w)
            val exits=w and 0xfffffc1f.toInt()==0xd65f0000.toInt()||w in setOf(0xd65f0bff.toInt(),0xd65f0fff.toInt())
            val indirect=w and 0xfffffc1f.toInt()==0xd61f0000.toInt()
            if(indirect)reasons+="native Fastboot 存在未证明的间接 CFG 边 @${pc.toString(16)}"
            val rawNext=when {
                exits||indirect->emptyList()
                branch!=null->listOf(branch)
                target(pc,w)!=null->listOf(pc+4,target(pc,w)!!)
                else->listOf(pc+4)
            }.distinct()
            if(rawNext.any { it !in main.start..<main.end })
                reasons+="native Fastboot CFG 存在未建模的主函数外边界 @${pc.toString(16)}"
            val next=rawNext.filter { it in main.start..<main.end }
            graph[pc]=next;next.forEach(pending::add)
        }
        if(preparation==null&&graph.keys.any { p->view.word(p)?.let { it and 0xfffffc1f.toInt()==0xd63f0000.toInt() }==true })
            reasons+="native Fastboot 含协议调用，必须保留其已证明的原生 preparation 路径"
        // Must-def dataflow, initialized to top for loops and intersected at joins.
        // Reporting uses the fixed point, never transient work-list states.
        val incoming=HashMap<Long,MutableList<Long>>()
        graph.forEach { (p,next)->next.forEach { incoming.getOrPut(it){arrayListOf()}.add(p) } }
        val universe=(0..30).toSet()+32;val definitions=graph.keys.associateWith { universe }.toMutableMap()
        definitions[recoveryEntry]=emptySet()
        var changed=true
        while(changed) {
            changed=false
            for(pc in graph.keys) {
                val candidates=incoming[pc].orEmpty().map { p->definitions[p].orEmpty()+effects[p]!!.writes }
                val before=if(pc==recoveryEntry)emptySet() else candidates.reduceOrNull { a,b->a intersect b }?:emptySet()
                if(before!=definitions[pc]){definitions[pc]=before;changed=true}
            }
        }
        val live=effects.flatMap { (pc,e)->e.reads-definitions[pc].orEmpty() }.filter { it!=30 }.toSortedSet()
        val globals=linkedMapOf<Int,Long>();val constants=linkedMapOf<Int,Long>();val preserved=linkedSetOf<Int>()
        fun cardStatusSurvives():Boolean {
            val card=cardInfo?:return false
            val nodes=linkedMapOf<Long,List<Long>>();val q=java.util.ArrayDeque<Long>();q.add(main.start)
            while(q.isNotEmpty()) {
                val p=q.removeFirst();if(p in nodes||p !in main.start..at)continue
                val w=image.word(p)?:return false
                val end=p==at||w and 0xfffffc1f.toInt() in setOf(0xd65f0000.toInt(),0xd61f0000.toInt())
                val b=A64.bTarget(p,w)
                val next=if(end)emptyList() else if(b!=null)listOf(b) else listOfNotNull(p+4,target(p,w))
                nodes[p]=next.filter { it in main.start..at }.distinct();nodes[p]!!.forEach(q::add)
            }
            val reverse=HashMap<Long,MutableList<Long>>()
            nodes.forEach { (p,next)->next.forEach { reverse.getOrPut(it){arrayListOf()}.add(p) } }
            val relevant=HashSet<Long>();q.add(at)
            while(q.isNotEmpty()){val p=q.removeFirst();if(relevant.add(p))reverse[p].orEmpty().forEach(q::add)}
            if(card.resultStoreRva !in relevant)return false
            // The CardInfo result copy must dominate the checkpoint in this
            // fresh-entry prefix, and retain its specific status role. A value
            // need not have a compile-time number to be valid preserved input.
            q.add(main.start);val avoid=HashSet<Long>()
            while(q.isNotEmpty()){val p=q.removeFirst();if(p==card.resultStoreRva||!avoid.add(p))continue
                if(p==at)return false;nodes[p].orEmpty().forEach(q::add)}
            return relevant.filter { it>card.resultStoreRva&&it<at }.none { p->
                val w=image.word(p)?:return@none true
                val call=A64.blTarget(p,w)!=null||w and 0xfffffc1f.toInt()==0xd63f0000.toInt()
                (if(call)callEffect(p,w) else effect(w))?.writes?.contains(card.statusRegister)!=false
            }
        }
        for(r in live) {
            if(r==32){reasons+="native Fastboot 在首次赋值前读取 NZCV";continue}
            if(r==cardInfo?.statusRegister&&cardStatusSurvives()){preserved+=r;continue}
            // A compiler may reuse this register for BSS on an earlier branch.
            // A native protocol Revision comparison gives a stronger semantic
            // role than that incidental checkpoint value. Recover its threshold
            // from another proved Revision comparison's MOVZ/MOVK definition.
            fun revisionCompare(pc:Long,w:Int):Boolean {
                if(w and 0xffe0fc1f.toInt()!=0xeb00001f.toInt())return false
                val operands=setOf((w ushr 5)and 31,(w ushr 16)and 31)
                if(r !in operands)return false
                val other=(operands-r).singleOrNull()?:return false
                val loadPc=pc-4;val load=memory(image.word(loadPc)?:0)?:return false
                val next=image.word(pc+4)?:return false
                return load.load&&load.size==8&&load.offset==0&&load.rt==other&&
                    next and 0xff000010.toInt()==0x54000000&&(next and 15)in setOf(2,3,8,9)
            }
            val nativeRevision=effects.keys.any { p->revisionCompare(p,image.word(p)?:0)&&r !in definitions[p].orEmpty() }
            val revision=if(nativeRevision)index.body(main).filter { it.first<at&&revisionCompare(it.first,it.second) }
                .mapNotNull { constantAt(image,prefix,it.first,r) }.distinct().singleOrNull() else null
            val constant=constantAt(image,prefix,at,r)?:revision
            if(r !in roleGlobals&&constant!=null){constants[r]=constant;continue}
            if(nativeRevision&&r !in roleGlobals){reasons+="native Revision live-in X$r 的唯一阈值常量未证明";continue}
            val global=roleGlobals[r]?:prefix.globalAddress(at,r)?:index.globalAt(at,r,main.start)
            if(global!=null&&global>=0){globals[r]=global;continue}
            if(constant!=null){constants[r]=constant;continue}
            reasons+="native Fastboot live-in X$r 没有可重物化的全局地址或常量"
        }
        // Stack proof: every local read must be produced on the native path or
        // already exist in the fresh main frame at the checkpoint. UEFI output
        // arguments follow their standard ABI; they are not pre-existing inputs.
        val stackReads=linkedMapOf<Long,List<Pair<Int,Int>>>();val stackWrites=linkedMapOf<Long,Set<Int>>()
        val nativeOutputs=linkedMapOf<Pair<Long,Long>,Set<Int>>()
        fun globalOf(pc:Long,r:Int,active:MutableSet<Pair<Long,Int>> = HashSet()):Long? {
            native.globalAddress(pc,r)?.let { return it }
            if(!active.add(pc to r)||active.size>128)return null
            val o=native.origins(pc,r)?.singleOrNull()
            val result=when(o) {
                is A64ValueOrigin.EntryArgument->globals[o.register]
                is A64ValueOrigin.Definition->{
                    val w=image.word(o.pc)?:return null
                    A64.adr(o.pc,w)?.second?:A64.adrp(o.pc,w)?.second?:
                    A64.add(w)?.takeIf { it.rd==r }?.let { globalOf(o.pc,it.rn,active)?.plus(it.immediate) }?:
                    if(w and 0xffe0ffe0.toInt()==0xaa0003e0.toInt())globalOf(o.pc,(w ushr 16)and 31,active)
                    else if(w and 0x3b000000==0x38000000&&(w ushr 5)and 31==r&&
                        (w ushr 10)and 3 in setOf(1,3)&&w and 0x00200000==0)
                        globalOf(o.pc,r,active)?.plus(signed(((w ushr 12)and 511).toLong(),9))
                    else null
                }
                else->null
            }
            active.remove(pc to r);return result
        }
        data class Method(val offset:Int,val tableSlot:Long?,val stackSlot:Int?)
        fun localDefinition(pc:Long,r:Int):Long? {
            for(p in pc-4 downTo maxOf(main.start,pc-96) step 4) {
                val w=image.word(p)?:return null
                if(target(p,w)!=null||A64.blTarget(p,w)!=null||w and 0xfffffc1f.toInt()==0xd63f0000.toInt())return null
                val e=effect(w)?:return null
                if(r in e.writes)return p
            }
            return null
        }
        fun methodAt(pc:Long,w:Int):Method? {
            val source=localDefinition(pc,(w ushr 5)and 31)?:return null
            val method=memory(image.word(source)?:0)?.takeIf { it.load&&it.size==8 }?:return null
            val root=localDefinition(source,method.rn)?:return null
            val rootMem=memory(image.word(root)?:0)?.takeIf { it.load&&it.size==8 }?:return null
            val stack=native.stackAddress(root,rootMem.rn)?.let { it+(native.stackPointerOffset(root)?:0)+rootMem.offset }
            return Method(method.offset,globalOf(root,rootMem.rn)?.plus(rootMem.offset),stack)
        }
        val protocolSlots=effects.keys.mapNotNull { pc->
            val w=view.word(pc)!!;if(w and 0xfffffc1f.toInt()!=0xd63f0000.toInt())return@mapNotNull null
            val m=methodAt(pc,w)
            if(env!=null&&m?.tableSlot==env.bootServicesGlobal&&m.offset==0x140)globalOf(pc,2) else null
        }.toSet()
        val protocolStackSlots=effects.keys.mapNotNull { pc->
            val w=view.word(pc)!!;if(w and 0xfffffc1f.toInt()!=0xd63f0000.toInt())return@mapNotNull null
            val m=methodAt(pc,w)
            if(env!=null&&m?.tableSlot==env.bootServicesGlobal&&m.offset==0x140)
                native.stackAddress(pc,2)?.plus(native.stackPointerOffset(pc)?:0) else null
        }.toSet()
        fun successEdge(call:Long):Pair<Long,Long>? {
            // Function-return provenance is intentionally Unknown in the generic
            // value solver. Track this short ABI return window explicitly instead
            // of pretending it is a Definition(call, X0).
            val resultRegisters=linkedSetOf(0)
            for(pc in call+4..call+40 step 4) {
                val w=view.word(pc)?:return null
                if(A64.blTarget(pc,w)!=null||w and 0xfffffc1f.toInt()==0xd63f0000.toInt())return null
                val r=w and 31
                if(isEfiStatusTest(w,r)&&r in resultRegisters)
                    return pc to if(w and 0x01000000!=0)pc+4 else target(pc,w)!!
                if(w and 0xfe000000.toInt()==0xb4000000.toInt()&&r in resultRegisters)
                    return pc to if(w and 0x01000000==0)target(pc,w)!! else pc+4
                if(target(pc,w)!=null)return null
                val copied=if(w and 0xffe0ffe0.toInt()==0xaa0003e0.toInt()&&((w ushr 16)and 31)in resultRegisters)r else null
                val overwritten=effect(w)?.writes?:return null
                resultRegisters.removeAll(overwritten)
                if(copied!=null)resultRegisters+=copied
            }
            return null
        }
        fun address(pc:Long,m:Access):Int? {
            val sp=native.stackPointerOffset(pc)?:return null
            val base=native.stackAddress(pc,m.base)?:return null
            return sp+base+m.offset
        }
        fun fixedCounterRange(pc:Long,register:Int):IntRange? {
            if(register<0)return null
            val back=image.word(pc+4)?.let { A64.bTarget(pc+4,it) }?:return null
            if(back !in maxOf(main.start,pc-64)..<pc)return null
            val cmp=image.word(back)?.let(A64::cmpImmediate)?:return null
            if(cmp.first!=register||cmp.second<=0)return null
            val condition=image.word(back+4)?:return null
            if(condition and 0xff00001f.toInt()!=0x54000000||target(back+4,condition)?.let { it<=pc }!=false)return null
            val zero=image.word(back-4)?:return null
            if(A64.movXZero(zero)!=register&&A64.movWImmediate(zero)!=(register to 0))return null
            if(incoming[back].orEmpty().toSet()!=setOf(back-4,pc+4))return null
            if((back+8..<pc step 4).any { p->
                (effects[p]?.writes?.contains(register)?:true)||incoming[p].orEmpty().any { it!=p-4 }
            })return null
            val call=image.word(pc)?.let { A64.blTarget(pc,it) }?:return null
            val leaf=linearLeaf(image,call)?:return null
            val increments=leaf.filter { (_,w)->w and 0x1f000000==0x11000000&&w and 0x60000000==0&&
                w and 31==register&&(w ushr 5)and 31==register&&((w ushr 10)and 4095)==1&&w and 0x400000==0 }
            if(increments.size!=1||leaf.any { it!=increments[0]&&effect(it.second)?.writes?.contains(register)==true })return null
            return 0 until cmp.second
        }
        fun nativeDominates(candidate:Long,destination:Long):Boolean {
            val q=java.util.ArrayDeque<Long>();q.add(recoveryEntry);val visited=HashSet<Long>()
            while(q.isNotEmpty()) {
                val p=q.removeFirst();if(p==candidate||!visited.add(p))continue
                if(p==destination)return false
                graph[p].orEmpty().forEach(q::add)
            }
            return true
        }
        val lengthFunctions=HashMap<Long,Boolean>()
        fun byteLengthFunction(start:Long):Boolean=lengthFunctions.getOrPut(start) {
            val function=index.owner(start)?.takeIf { it.start==start }?:return@getOrPut false
            val code=index.body(function);val f=A64ValueOrigins(image,function.start,function.end)
            val scans=code.mapNotNull { (p,w)->effect(w)?.memory?.singleOrNull()?.takeIf {
                it.load&&it.width==1&&it.index>=0&&it.indexScale==1&&f.addressDerivesFromEntry(p,it.base,0)
            }?.let { Triple(p,w,it) } }
            val scan=scans.singleOrNull()?:return@getOrPut false
            val byte=scan.second and 31;val counter=scan.third.index
            val zeroBranch=image.word(scan.first+4)?:return@getOrPut false
            if((zeroBranch and 0x7f00001f)!=(0x34000000 or byte))return@getOrPut false
            val exit=target(scan.first+4,zeroBranch)?:return@getOrPut false
            val back=code.singleOrNull { (p,w)->p in scan.first..<exit&&A64.bTarget(p,w)==scan.first }?:return@getOrPut false
            val increment=image.word(back.first-4)?:return@getOrPut false
            if((increment and 0xffc003ff.toInt())!=(0x91000000.toInt()or(counter shl 5)or counter)||
                (increment ushr 10)and 4095!=1)return@getOrPut false
            if(code.none { (p,w)->p<scan.first&&(A64.movXZero(w)==counter||A64.movWImmediate(w)==(counter to 0)) })return@getOrPut false
            if(code.any { (p,w)->effect(w)?.memory.orEmpty().any { !it.load&&f.addressDerivesFromEntry(p,it.base,0) } })return@getOrPut false
            val copy=code.firstOrNull { (p,w)->p>=exit&&w and 0xffe0ffff.toInt()==0xaa0003e0.toInt()&&
                (w ushr 16)and 31==counter }?:return@getOrPut false
            val end=code.filter { (_,w)->w and 0xfffffc1f.toInt()==0xd65f0000.toInt() }.singleOrNull()
                ?.takeIf { it.first>copy.first }?:return@getOrPut false
            val pending=java.util.ArrayDeque<Long>();pending.add(start);val visited=HashSet<Long>()
            while(pending.isNotEmpty()) {
                val p=pending.removeFirst();if(p==copy.first||!visited.add(p)||p !in function.start..<function.end)continue
                if(p==end.first)return@getOrPut false
                val w=image.word(p)?:return@getOrPut false
                if(w and 0xfffffc1f.toInt()==0xd61f0000.toInt())return@getOrPut false
                val b=A64.bTarget(p,w)
                if(b!=null)pending.add(b) else {pending.add(p+4);target(p,w)?.let(pending::add)}
            }
            (copy.first+4..<end.first step 4).all { p->val w=image.word(p)?:return@all false
                val call=A64.blTarget(p,w)!=null||w and 0xfffffc1f.toInt()==0xd63f0000.toInt()
                (if(call)callEffect(p,w) else effect(w))?.writes?.contains(0)==false }
        }
        fun boundedStringIndex(pc:Long,m:Access,slot:Int):IntRange? {
            if(m.index<0||m.indexScale!=1||!m.load||m.width!=1)return null
            val cmp=image.word(pc-8)?:return null;val guard=image.word(pc-4)?:return null
            if(cmp and 0xffe0fc1f.toInt()!=0xeb00001f.toInt()||guard and 0xff000010.toInt()!=0x54000000)return null
            val a=(cmp ushr 5)and 31;val b=(cmp ushr 16)and 31
            val bound=when { b==m.index&&(guard and 15)==9->a;a==m.index&&(guard and 15)==2->b;else->return null }
            if(target(pc-4,guard)?.let { it<=pc }!=false||incoming[pc].orEmpty()!=listOf(pc-4))return null
            val lastCall=graph.keys.filter { it<pc-8 }.sortedDescending().firstOrNull { p->
                val to=image.word(p)?.let { A64.blTarget(p,it) }?:return@firstOrNull false
                byteLengthFunction(to)&&native.stackAddress(p,0)?.plus(native.stackPointerOffset(p)?:return@firstOrNull false)==slot&&
                    image.word(p+4)?.let { w->w and 0xffe0ffe0.toInt()==0xaa0003e0.toInt()&&w and 31==bound&&(w ushr 16)and 31==0 }==true&&
                    (p+8..<pc-8 step 4).all { q->effects[q]?.writes?.contains(bound)==false }
            }?:return null
            val lengthTarget=A64.blTarget(lastCall,image.word(lastCall)!!)!!
            for(call in graph.keys.filter { it<lastCall&&A64.blTarget(it,image.word(it)!!)==lengthTarget }) {
                if(native.stackAddress(call,0)?.plus(native.stackPointerOffset(call)?:continue)!=slot)continue
                val aliases=linkedSetOf(0)
                for(p in call+4..minOf(lastCall-4,call+96) step 4) {
                    val w=image.word(p)?:break
                    if(A64.blTarget(p,w)!=null||w and 0xfffffc1f.toInt()==0xd63f0000.toInt())break
                    val rn=(w ushr 5)and 31;val rd=w and 31
                    // UBFM's LSL alias, including register changes and W/X width.
                    val width=if(w ushr 31!=0)64 else 32;val immr=(w ushr 16)and 63;val imms=(w ushr 10)and 63
                    if(w and 0x7f800000==0x53000000&&rn in aliases&&immr==imms+1&&imms<width-1) {
                        val shift=width-1-imms;val check=image.word(p+4)?.let(A64::cmpImmediate)
                        val condition=image.word(p+8)?:break
                        if(check?.first!=rd||check.second<=0||condition and 0xff000010.toInt()!=0x54000000)break
                        val success=when(condition and 15){3->target(p+8,condition);2->p+12;else->null}?:break
                        val high=(check.second-1)ushr shift
                        if(high<=0||high>frame.bytes||!nativeDominates(success,pc))break
                        var changedBuffer=false
                        for(q in graph.keys.filter { it in success..<pc }) {
                            val e=effects[q]?:continue
                            if(e.memory.any { a->!a.load&&address(q,a)?.let { it<slot+high&&slot<it+a.width }==true }){changedBuffer=true;break}
                            val target=image.word(q)?.let { A64.blTarget(q,it) }
                            if(target!=null&&target!=lengthTarget&&(0..7).any { r->
                                native.stackAddress(q,r)?.plus(native.stackPointerOffset(q)?:0)?.let { it in slot..<slot+high }==true
                            }){changedBuffer=true;break}
                        }
                        if(!changedBuffer)return 0 until high
                        break
                    }
                    val copy=if(w and 0xffe0ffe0.toInt()==0xaa0003e0.toInt()&&((w ushr 16)and 31)in aliases)rd else null
                    aliases.removeAll(effect(w)?.writes?:break);if(copy!=null)aliases+=copy
                }
            }
            return null
        }
        for((pc,e) in effects) {
            val reads=ArrayList<Pair<Int,Int>>();val writes=linkedSetOf<Int>()
            for(m in e.memory) {
                val slot=address(pc,m)?:continue
                val range=if(m.index==-1)0..0 else fixedCounterRange(pc,m.index)?:boundedStringIndex(pc,m,slot)
                if(range==null){reasons+="native stack 索引范围未证明 @${pc.toString(16)}";continue}
                val low=slot+range.first*m.indexScale;val high=slot+range.last*m.indexScale+m.width
                if(low<0||high.toLong()>frame.bytes){reasons+="native stack 访问越出 main frame @${pc.toString(16)}";continue}
                if(m.load)reads+=low to(high-low)
                else if(m.index==-1)writes+=(low..<high) // a loop iteration does not initialize its whole range
            }
            stackReads[pc]=reads;stackWrites[pc]=writes
            val w=view.word(pc)!!
            if(w and 0xfffffc1f.toInt()==0xd63f0000.toInt()) {
                val method=methodAt(pc,w);val tableSlot=method?.tableSlot
                val out=when {
                    env!=null&&tableSlot==env.bootServicesGlobal&&method?.offset==0x140->mapOf(2 to 8) // LocateProtocol
                    env!=null&&tableSlot==env.bootServicesGlobal&&method?.offset==0x170->mapOf(5 to 8) // CreateEventEx
                    env!=null&&tableSlot==env.bootServicesGlobal&&method?.offset==0x38->mapOf(0 to 8,2 to 8,3 to 8,4 to 4) // GetMemoryMap
                    env!=null&&tableSlot==env.runtimeServicesGlobal&&method?.offset==0x48->mapOf(2 to 4,3 to 8,4 to 4) // GetVariable scalar use
                    // A protocol AllocateBuffer method taking size and an out
                    // pointer is identified by its checked EFI-status and an
                    // immediate native load of that exact pointer, not a GUID.
                    method?.offset==0x20&&tableSlot in protocolSlots&&isEfiStatusTest(image.word(pc+4)?:0,0)->mapOf(1 to 8)
                    // Protocol state query: native success code consumes a scalar
                    // in the exact out-slot supplied as argument one.
                    method?.offset==0x38&&method.stackSlot in protocolStackSlots&&isEfiStatusTest(image.word(pc+4)?:0,0)->mapOf(1 to 4)
                    else->emptyMap()
                }
                val outputs=linkedSetOf<Int>()
                for((arg,width) in out) {
                    val sp=native.stackPointerOffset(pc)?:continue
                    val slot=native.stackAddress(pc,arg)?.plus(sp)?:continue
                    if(slot>=0&&slot+width<=frame.bytes)outputs+=(slot..<slot+width)
                }
                successEdge(pc)?.let { edge->nativeOutputs[edge]=nativeOutputs[edge].orEmpty()+outputs }
            }
        }
        fun bits(values:Iterable<Int>)=java.util.BitSet().also { b->values.forEach(b::set) }
        fun copy(b:java.util.BitSet)=b.clone() as java.util.BitSet
        val allBytes=java.util.BitSet(frame.bytes.toInt()).also { it.set(0,frame.bytes.toInt()) }
        val writeBits=stackWrites.mapValues { bits(it.value) }
        val outputBits=nativeOutputs.mapValues { bits(it.value) }
        val written=graph.keys.associateWith { copy(allBytes) }.toMutableMap();written[recoveryEntry]=java.util.BitSet()
        changed=true
        while(changed) {
            changed=false
            for(pc in graph.keys) {
                val states=incoming[pc].orEmpty().map { p->copy(written[p]!!).also { b->
                    writeBits[p]?.let(b::or);outputBits[p to pc]?.let(b::or) } }
                val state=if(pc==recoveryEntry)java.util.BitSet() else states.reduceOrNull { a,b->a.also { it.and(b) } }?:java.util.BitSet()
                if(state!=written[pc]){written[pc]=state;changed=true}
            }
        }
        val stackInputs=linkedMapOf<Int,Int>()
        // The value solver rejects every stack byte after an unrelated store
        // through an ambiguous pointer. That conservatism is useful for value
        // equality, but does not erase native prologue saves. For initializedness
        // prove the actual SP stores dominate the checkpoint. Caller-save state
        // and the guard are then read from this fresh native frame unchanged.
        fun dominatesCheckpoint(candidate:Long):Boolean {
            val todo=java.util.ArrayDeque<Long>();todo.add(main.start);val seen=HashSet<Long>()
            while(todo.isNotEmpty()) {
                val p=todo.removeFirst();if(p==candidate||!seen.add(p))continue
                if(p==at)return false
                if(p !in main.start..<main.end)continue
                val w=image.word(p)?:return false
                if(w and 0xfffffc1f.toInt()==0xd61f0000.toInt())return false
                if(w and 0xfffffc1f.toInt()==0xd65f0000.toInt()||w in setOf(0xd65f0bff.toInt(),0xd65f0fff.toInt()))continue
                val b=A64.bTarget(p,w)
                if(b!=null)todo.add(b) else {todo.add(p+4);target(p,w)?.let(todo::add)}
            }
            return true
        }
        val initializedAtCheckpoint=linkedSetOf<Int>()
        for((p,w) in index.body(main).filter { it.first<at }) {
            val call=A64.blTarget(p,w)!=null||w and 0xfffffc1f.toInt()==0xd63f0000.toInt()
            val stores=(if(call)callEffect(p,w) else effect(w))?.memory.orEmpty().filter { !it.load&&it.index==-1 }
            if(stores.isEmpty()||!dominatesCheckpoint(p))continue
            val sp=prefix.stackPointerOffset(p)?:continue
            for(m in stores) {
                val base=prefix.stackAddress(p,m.base)?:continue
                val slot=(sp+base+m.offset+frame.bytes).toInt()
                if(slot>=0&&slot+m.width<=frame.bytes)initializedAtCheckpoint+=(slot..<slot+m.width)
            }
        }
        for((pc,reads) in stackReads)for((slot,width) in reads) {
            if((slot..<slot+width).all { written[pc]?.get(it)==true })continue
            stackInputs[slot]=maxOf(stackInputs[slot]?:0,width)
            val origins=prefix.stackOrigins(at,slot,width)
            if((origins.isNullOrEmpty()||A64ValueOrigin.Unknown in origins)&&
                !(slot..<slot+width).all { it in initializedAtCheckpoint })
                reasons+="native stack 输入 SP+${slot.toString(16)}/$width 未在早检查点初始化"
        }
        val note=if(reasons.isEmpty())"native CFG 的全部 GP live-in、固定 main frame 与 stack 输入已证明；UEFI 输出继续由原生协议填充。"
            else reasons.joinToString("；")
        return RecoveryEntryPrerequisites(at,reasons.isEmpty(),globals,note,constants,live,checkpointSp,stackInputs,reasons.toList(),recoveryEntry,preparation,preserved,summarizedAdapters)
    }

    /** Keep the existing protocol initialization. Its CardInfo-error entry is
     * also reached by the native Fastboot boot-reason path. The force route
     * keeps every protocol call and error edge, redirecting only the final
     * successful LocateProtocol branch after those side effects are complete. */
    internal fun discoverPreparationRoute(image:PeImage,main:FunctionRange,checkpoint:Long,
        bootServicesGlobal:Long,card:CardInfo):PreparationRoute? = preparationRoute(Index(image),main,checkpoint,
            A64ValueOrigins(image,main.start,checkpoint+4),bootServicesGlobal,card,arrayListOf())

    private fun preparationRoute(index:Index,main:FunctionRange,checkpoint:Long,prefix:A64ValueOrigins,
        bootServicesGlobal:Long,card:CardInfo,notes:MutableList<String>):PreparationRoute? {
        val image=index.image;val body=index.body(main)
        fun locateCalls(from:Long,until:Long):List<Long> {
            val loads=body.filter { it.first in from..<until }.mapNotNull { (p,w)->memory(w)?.takeIf { it.load&&it.size==8 }?.let { p to it } }
            return loads.filter { it.second.offset==0x140 }.mapNotNull { (methodPc,method)->
                val root=loads.lastOrNull { (p,m)->p<methodPc&&m.rt==method.rn&&
                    StraightLineLiveness.preserves(image,p+4,methodPc,setOf(m.rt)) }?:return@mapNotNull null
                val rootBase=prefix.globalAddress(root.first,root.second.rn)?:index.globalAt(root.first,root.second.rn,main.start)?:return@mapNotNull null
                if(rootBase+root.second.offset!=bootServicesGlobal)return@mapNotNull null
                val call=body.firstOrNull { (p,w)->p in methodPc+4..methodPc+32&&
                    w and 0xfffffc1f.toInt()==0xd63f0000.toInt()&&((w ushr 5)and 31)==method.rt&&
                    StraightLineLiveness.preserves(image,methodPc+4,p,setOf(method.rt)) }?:return@mapNotNull null
                val args=A64.argumentState(image,root.first,call.first)
                if(args.registers[1]!=A64.Value.Constant(0)||(args.registers[2] !is A64.Value.Stack))return@mapNotNull null
                call.first
            }.distinct()
        }
        fun returnAliasAt(call:Long,pc:Long,register:Int):Boolean {
            val aliases=linkedSetOf(0)
            for(p in call+4..<pc step 4) {
                val w=image.word(p)?:return false
                if(target(p,w)!=null||A64.blTarget(p,w)!=null||w and 0xfffffc1f.toInt()==0xd63f0000.toInt())return false
                val copy=if(w and 0xffe0ffe0.toInt()==0xaa0003e0.toInt()&&((w ushr 16)and 31)in aliases)w and 31 else null
                aliases.removeAll(effect(w)?.writes?:return false);if(copy!=null)aliases+=copy
            }
            return register in aliases
        }
        val entries=body.filter { (p,w)->p in card.resultStoreRva+4..card.resultStoreRva+80&&
            isEfiStatusTest(w,w and 31)&&w and 0x01000000!=0&&returnAliasAt(card.locateCallRva,p,w and 31)
        }.mapNotNull { (p,w)->target(p,w) }.distinct()
        notes+="CardInfo failure entries=${entries.map { it.toString(16) }}"
        val routes=entries.mapNotNull { start->
            if(start>=checkpoint)return@mapNotNull null
            val compares=body.filter { (p,w)->p in start..<minOf(checkpoint,start+512)&&w and 0xffe0fc1f.toInt()==0xeb00001f.toInt() }
                .filter { (p,w)->
                    val m=memory(image.word(p-4)?:0);val next=image.word(p+4)?:0
                    val operands=setOf((w ushr 5)and 31,(w ushr 16)and 31)
                    m?.load==true&&m.size==8&&m.offset==0&&m.rt in operands&&
                        next and 0xff000010.toInt()==0x54000000&&(next and 15)in setOf(2,3,8,9)&&
                        (operands-m.rt).singleOrNull()?.let { constantAt(image,prefix,p,it)!=null }==true
                }
            notes+="entry ${start.toString(16)} Revision comparisons=${compares.map { it.first.toString(16) }}"
            val compare=compares.singleOrNull()?.first?:return@mapNotNull null
            val firstCalls=locateCalls(start,compare);notes+="first LocateProtocol=${firstCalls.map { it.toString(16) }}"
            if(firstCalls.size!=1)return@mapNotNull null
            val lastCalls=locateCalls(compare+4,minOf(checkpoint,compare+256));notes+="last LocateProtocol=${lastCalls.map { it.toString(16) }}"
            val last=lastCalls.singleOrNull()?:return@mapNotNull null
            val gate=body.firstOrNull { (p,w)->p in last+4..last+24&&w and 0xfe000000.toInt()==0xb4000000.toInt()&&
                returnAliasAt(last,p,w and 31) }?:return@mapNotNull null
            val zeroFallthrough=gate.second and 0x01000000!=0
            if(zeroFallthrough&&index.directIncoming[gate.first+4].orEmpty().isNotEmpty())return@mapNotNull null
            val destination=if(zeroFallthrough)gate.first+4 else target(gate.first,gate.second)?:return@mapNotNull null
            PreparationRoute(start,gate.first,gate.second,gate.second and 31,destination,
                proof="CardInfo 错误入口→原生 LocateProtocol/协议方法/Revision 分支→末次 LocateProtocol 的 Xstatus==0 成功分流；协议初始化原样保留。")
        }
        return routes.singleOrNull()
    }

    private fun isEfiStatusTest(w:Int,register:Int)=isTest(w)&&testBit(w)==63&&w and 31==register

    /** Follow a unique reaching definition, including MOVK's preceding value.
     * This is independent of register allocation, constant value, or PE layout. */
    private fun constantAt(image:PeImage,flow:A64ValueOrigins,pc:Long,register:Int,
        active:MutableSet<Pair<Long,Int>> = HashSet()):Long? {
        if(register==31)return 0L
        val key=pc to register;if(!active.add(key)||active.size>128)return null
        val origin=flow.origins(pc,register)?.singleOrNull()
        val result=when(origin) {
            A64ValueOrigin.Zero->0L
            is A64ValueOrigin.Definition->{
                val w=image.word(origin.pc)?:return null
                val d=w and 31;val n=(w ushr 5)and 31
                fun prior(r:Int)=constantAt(image,flow,origin.pc,r,active)
                val value=when {
                    w and 0x1f800000==0x12800000->{
                        val shift=((w ushr 21)and 3)*16;val imm=((w ushr 5)and 65535).toLong() shl shift
                        when((w ushr 29)and 3){0->imm.inv();2->imm;3->prior(d)?.let { (it and(65535L shl shift).inv())or imm };else->null}
                    }
                    w and 0xffe0ffe0.toInt() in setOf(0xaa0003e0.toInt(),0x2a0003e0)->prior((w ushr 16)and 31)
                    w and 0x1f000000==0x11000000->{val imm=(((w ushr 10)and 4095).toLong() shl if(w and 0x400000!=0)12 else 0)
                        prior(n)?.let { if(w and 0x40000000!=0)it-imm else it+imm } }
                    else->null
                }
                value?.let { if(w ushr 31==0)it and 0xffffffffL else it }
            }
            else->null
        }
        active.remove(key);return result
    }

    private fun entryPrefix(image:PeImage,entry:FunctionRange,tail:Long):EntryPrefix {
        val landings=ArrayList<Int>();var hook=image.entryRva
        while(image.isCode(hook)&&image.word(hook)?.let(::landing)==true){landings+=image.word(hook)!!;hook+=4}
        val word=image.word(hook)?:fail("EFI entry replay word")
        val count=(hook-image.entryRva+4).toInt()
        val off=image.rvaToOffset(image.entryRva,count)?:fail("EFI entry prefix raw mapping")
        val prologue=(entry.start..<minOf(entry.end,entry.start+128) step 4)
            .mapNotNull { p->image.word(p)?.let { p to it } }.takeWhile { (p,w)->p==entry.start||target(p,w)==null }
        val allocated=-prologue.mapNotNull { spDelta(it.second)?.takeIf { n->n<0 } }.sum()
        val restored=(maxOf(entry.start,tail-64)..tail step 4).sumOf { p->image.word(p)?.let(::spDelta)?.coerceAtLeast(0)?:0L }
        val relative=A64.adr(hook,word)!=null||A64.adrp(hook,word)!=null||target(hook,word)!=null||
            A64.blTarget(hook,word)!=null||word and 0x3b000000==0x18000000
        return EntryPrefix(hook,word,hook+4,landings,landings+word,image.bytes.copyOfRange(off,off+count),
            relative,allocated,allocated==restored)
    }

    private fun canary(index:Index,main:FunctionRange,ret:Long,commonExit:Long?):Canary? {
        val image=index.image;val body=index.body(main)
        val found=ArrayList<Canary>();var hasGuardShape=false
        fun link(read:Pair<Long,Mem>,global:Long,width:Int,compare:Long,call:Long?,branch:Long,checkingRead:Long):Canary? {
            if(!image.isWritable(global,width))return null
            val save=body.filter { it.first<read.first }.mapNotNull { (p,x)->memory(x)?.takeIf {
                !it.load&&it.rn==31&&it.offset==read.second.offset&&it.size==width }?.let { p to it } }
                .singleOrNull()?:return null
            val producer=body.filter { it.first in maxOf(main.start,save.first-64)..<save.first }.mapNotNull { (p,x)->memory(x)?.takeIf {
                it.load&&it.rt==save.second.rt&&it.size==width&&
                    index.globalAt(p,it.rn,main.start)?.plus(it.offset)==global }?.let { p to it } }
                .lastOrNull()?:return null
            val reset=body.filter { it.first in (commonExit?:maxOf(main.start,ret-256))..<ret }.mapNotNull { (p,x)->memory(x)?.takeIf {
                !it.load&&it.size==width&&index.globalAt(p,it.rn,maxOf(main.start,p-32))?.plus(it.offset)==global }?.let { p to it } }
                .singleOrNull()?:return null
            return Canary(global,width,read.second.offset,save.first,read.first,producer.first,
                compare,call,branch,reset.first,checkingRead)
        }
        for((call,w) in body.filter { it.first in maxOf(main.start,ret-96)..<ret }) {
            val helper=A64.blTarget(call,w)?:continue
            val next=image.word(call+4)?:continue
            if(next and 0xff00001f.toInt()!=0x54000001)continue
            val leaf=linearLeaf(image,helper)?:continue
            val compare=leaf.singleOrNull { (_,x)->x and 0x7fe0fc1f==0x6b00001f }?:continue
            hasGuardShape=true
            val operands=setOf((compare.second ushr 5)and 31,(compare.second ushr 16)and 31)
            val guardLoad=leaf.mapNotNull { (p,x)->memory(x)?.takeIf { it.load&&it.size in setOf(4,8)&&it.rt in operands }?.let { p to it } }
                .singleOrNull()?:continue
            val callerLoad=body.filter { it.first in maxOf(main.start,call-32)..<call }.mapNotNull { (p,x)->memory(x)?.takeIf {
                it.load&&it.rn==31&&it.rt in operands&&it.rt!=guardLoad.second.rt&&it.size==guardLoad.second.size }?.let { p to it } }
                .singleOrNull()?:continue
            val base=index.globalAt(call,guardLoad.second.rn,maxOf(main.start,call-32))?:continue
            link(callerLoad,base+guardLoad.second.offset,guardLoad.second.size,compare.first,call,call+4,guardLoad.first)?.let(found::add)
        }
        // The same data relationship also accepts an inlined comparison after
        // compiler outlining changes; there is no helper-address dependency.
        for((branch,w) in body.filter { it.first in maxOf(main.start,ret-96)..<ret && it.second and 0xff00001f.toInt()==0x54000001 }) {
            val pc=branch-4;val compare=image.word(pc)?:continue
            if(compare and 0x7fe0fc1f!=0x6b00001f)continue
            val operands=setOf((compare ushr 5)and 31,(compare ushr 16)and 31)
            val loads=body.filter { it.first in maxOf(main.start,pc-32)..<pc }.mapNotNull { (p,x)->memory(x)?.takeIf {
                it.load&&it.size in setOf(4,8)&&it.rt in operands&&
                    StraightLineLiveness.preserves(image,p+4,pc,setOf(it.rt)) }?.let { p to it } }
            val saved=loads.singleOrNull { it.second.rn==31 }?:continue
            val guard=loads.singleOrNull { it.second.rn!=31&&it.second.rt!=saved.second.rt&&it.second.size==saved.second.size }?:continue
            hasGuardShape=true
            val base=index.globalAt(guard.first,guard.second.rn,maxOf(main.start,guard.first-32))?:continue
            link(saved,base+guard.second.offset,guard.second.size,pc,null,branch,guard.first)?.let(found::add)
        }
        if(found.isEmpty()&&hasGuardShape)fail("main canary 保存/恢复/比较关系")
        return found.distinct().singleOrNull().also { if(found.distinct().size>1)fail("main canary 歧义") }
    }

    private fun linearLeaf(image:PeImage,start:Long):List<Pair<Long,Int>>? {
        if(image.word(start)?.let { it in setOf(0xd503233f.toInt(),0xd503237f.toInt()) }==true)return null
        val out=ArrayList<Pair<Long,Int>>();var p=start
        while(image.isCode(p)) {
            val w=image.word(p)?:return null;out+=p to w
            if(w and 0xfffffc1f.toInt()==0xd65f0000.toInt())return out
            if(A64.blTarget(p,w)!=null||target(p,w)!=null||w and 0xfffffc1f.toInt() in setOf(0xd61f0000.toInt(),0xd63f0000.toInt()))return null
            p+=4
        }
        return null
    }
    private fun landing(w:Int)=w in setOf(0xd503233f.toInt(),0xd503237f.toInt(),
        0xd503245f.toInt(),0xd503249f.toInt(),0xd50324df.toInt())

    private fun incomingGlobals(index:Index,main:FunctionRange,setup:Long,setupEnd:Long,
        commonExit:Long?,bootServicesSlot:Long):Map<Int,Long> {
        val image=index.image
        val body=index.body(main).filter { it.first in setup..<setupEnd }
        val flow=A64ValueOrigins(image,setup,main.end)
        val loads=body.mapNotNull { (pc,w)->memory(w)?.takeIf { it.load&&it.size==8 }?.let { pc to it } }
        val globals=linkedMapOf<Int,Long>()
        fun record(register:Int,value:Long) {
            if(register !in 19..29 || value<0)fail("Fastboot incoming gBS 基址")
            if(globals[register]?.let { it!=value }==true)fail("Fastboot incoming 基址冲突")
            globals[register]=value
        }
        for((rootPc,root) in loads) {
            if(!flow.derivesFromEntry(rootPc,root.rn,root.rn))continue
            for((methodPc,method) in loads.filter { it.first>rootPc && it.second.offset in setOf(0x68,0x70,0x140) }) {
                if(method.rn!=root.rt||!StraightLineLiveness.preserves(image,rootPc+4,methodPc,setOf(root.rt)))continue
                val call=body.firstOrNull { (pc,w)->pc>methodPc&&w and 0xfffffc1f.toInt()==0xd63f0000.toInt() &&
                    (w ushr 5)and 31==method.rt&&StraightLineLiveness.preserves(image,methodPc+4,pc,setOf(method.rt)) }?:continue
                val locate=method.offset==0x140&&flow.origins(call.first,1)==setOf(A64ValueOrigin.Zero)&&
                    flow.stackAddress(call.first,2)!=null
                val event=method.offset in setOf(0x68,0x70)&&root.offset==0
                if(locate||event)record(root.rn,bootServicesSlot-root.offset)
            }
        }
        // The error funnel's flag base also has an explicit initializer on the
        // ordinary Fastboot-cleanup edge. Reuse that proved address if unique.
        if(commonExit!=null) {
            val first=memory(image.word(commonExit)?:0)
            if(first?.load==true&&first.size==1) {
                val bases=index.directIncoming[commonExit].orEmpty().mapNotNull { edge->
                    index.globalAt(edge,first.rn,maxOf(main.start,edge-64))
                }.filter { image.isWritable(it+first.offset,1) }.distinct()
                bases.singleOrNull()?.let { record(first.rn,it) }
            }
        }
        return globals
    }

    private fun cardInfo(index: Index, main: FunctionRange, status: Int): CardInfo? {
        val guid = byteArrayOf(0xd2.toByte(),0xf7.toByte(),0xc1.toByte(),0x85.toByte(),0xe6.toByte(),0xbc.toByte(),0x31,0x4f,0x8f.toByte(),0x4d,0xd3.toByte(),0x7e,0x03,0xd0.toByte(),0x5e,0xaa.toByte())
        val addresses = index.image.bytes.occurrences(guid).mapNotNull(index.image::offsetToRva).toSet()
        val words = index.body(main)
        return index.refs.filter { it.pc in main.start..<main.end && it.address in addresses }.flatMap { ref ->
            words.filter { (pc, w) -> pc > ref.pc && pc <= ref.pc+48 && w and 0xfffffc1f.toInt() == 0xd63f0000.toInt() }
                .mapNotNull { (call, _) -> words.firstOrNull { (pc, w) -> pc in call+4..call+16 &&
                    w and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt() && (w ushr 16) and 31 == 0 && w and 31 == status }
                    ?.let { CardInfo(call, it.first, status, it.first+4) } }
        }.distinct().singleOrNull()
    }

    private fun continueFlow(index: Index, main: FunctionRange): ContinueFlow {
        val image = index.image
        val names = image.bytes.occurrences("continue\u0000".toByteArray(Charsets.US_ASCII)).mapNotNull(image::offsetToRva).toSet()
        fun pointer(v: Long): Long? = listOf(v, v-image.imageBase).distinct().singleOrNull { image.isCode(it) }
        val handlers = image.sections.flatMap { section -> (section.raw until section.raw+section.rawSize-15 step 8).mapNotNull { at ->
            val name = image.bytes.u64(at)
            if (name !in names && name-image.imageBase !in names) null else pointer(image.bytes.u64(at+8))
        } }.distinct()
        val handler = handlers.singleOrNull()?.let(index::owner) ?: fail("continue 命令处理器")
        val words = index.body(handler)
        val mainCalls = index.body(main).mapNotNull { (pc, w) -> A64.blTarget(pc, w) }.toSet()
        val flow = A64ValueOrigins(image, handler.start, handler.end)
        val stores = words.mapNotNull { (pc, w) -> val m = memory(w) ?: return@mapNotNull null
            if (m.load || m.size != 1 || A64.immediateBefore(image, pc, m.rt, 12) != 1) return@mapNotNull null
            val address = flow.globalAddress(pc, m.rn)?.plus(m.offset) ?: index.globalAt(pc, m.rn, handler.start)?.plus(m.offset)
            address?.takeIf { image.isWritable(it,1) }?.let { Triple(pc, m, it) }
        }
        val choices = stores.mapNotNull { (store, _, flag) ->
            val boot = words.firstOrNull { (pc, w) -> pc > store && A64.blTarget(pc, w) in mainCalls } ?: return@mapNotNull null
            val auth = words.filter { (pc, w) -> pc < store && A64.blTarget(pc, w) in mainCalls &&
                image.word(pc+4)?.let { it and 0x7e00001f == 0x34000000 } == true }.singleOrNull() ?: return@mapNotNull null
            val poll = index.body(main).filter { (pc, w) -> memory(w)?.let { m ->
                m.load && m.size == 1 && (index.globalAt(pc, m.rn, main.start)?.plus(m.offset) == flag) } == true }.singleOrNull()
                ?: return@mapNotNull null
            ContinueFlow(handler.start, auth.first, A64.blTarget(auth.first,auth.second)!!,
                boot.first, A64.blTarget(boot.first,boot.second)!!, flag,store,poll.first)
        }
        return choices.distinct().singleOrNull() ?: fail("continue 的认证/BootLinux/退出标志数据流")
    }

    private fun frame(image: PeImage, main: FunctionRange, ret: Long): MainFrame {
        val startWords = (0 until 32).mapNotNull { n -> val pc=main.start+n*4; image.word(pc)?.let { pc to it } }
        val prologue = startWords.takeWhile { (pc,w) -> pc==main.start || target(pc,w)==null }
        val decreases = prologue.mapNotNull { (pc,w) -> spDelta(w)?.takeIf { it<0 }?.let { pc to it } }
        val size = -decreases.sumOf { it.second }
        if (size<=0 || size and 15L!=0L) fail("主函数 frame 分配")
        val restored = (maxOf(main.start,ret-64)..ret step 4).sumOf { pc -> image.word(pc)?.let(::spDelta)?.coerceAtLeast(0) ?: 0 }
        val dynamic = image.instructions().any { (pc,w) -> pc in main.start..<ret &&
            A64.add(w)?.let { it.rd == 31 && it.rn != 31 } == true }
        return MainFrame(size, restored==size, dynamic, decreases.map { it.first })
    }
    private fun spDelta(w: Int): Long? {
        if (w and 0x1f000000 == 0x11000000 && w and 31 == 31 && (w ushr 5) and 31 == 31 &&
            w and 0xa0000000.toInt() == 0x80000000.toInt()) {
            val n=((w ushr 10)and 4095).toLong() shl if(w and 0x00400000!=0)12 else 0
            return if(w and 0x40000000!=0)-n else n
        }
        if (w and 0x3a000000 == 0x28000000 && (w ushr 5) and 31 == 31 && (w ushr 23) and 3 in setOf(1,3)) {
            val scale=if(w and 0x04000000!=0)4 shl(w ushr 30) else if(w ushr 31!=0)8 else 4
            return signed(((w ushr 15)and 127).toLong(),7)*scale
        }
        return null
    }
    private fun memory(w: Int): Mem? {
        val op=w and 0xffc00000.toInt()
        if(op !in setOf(0xf9400000.toInt(),0xb9400000.toInt(),0x39400000,0xf9000000.toInt(),0xb9000000.toInt(),0x39000000)) {
            if(w and 0x3b000000!=0x38000000||w and 0x04200000!=0)return null
            val size=1 shl(w ushr 30);val mode=(w ushr 10)and 3
            val off=if(mode==1)0 else signed(((w ushr 12)and 511).toLong(),9).toInt()
            return Mem(w and 31,(w ushr 5)and 31,off,size,(w ushr 22)and 3!=0)
        }
        val size=when(w ushr 30){3->8;2->4;else->1}
        return Mem(w and 31,(w ushr 5)and 31,((w ushr 10)and 4095)*size,size,w and 0x400000!=0)
    }
    private fun isTest(w:Int)=w and 0x7e000000==0x36000000
    private fun testBit(w:Int)=((w ushr 19)and 31)+((w ushr 31)shl 5)
    private fun target(pc:Long,w:Int):Long?=A64.bTarget(pc,w)?:when {
        w and 0xff000010.toInt()==0x54000000 || w and 0x7e000000==0x34000000 -> pc+(signed(((w ushr 5)and 0x7ffff).toLong(),19)shl 2)
        isTest(w)->pc+(signed(((w ushr 5)and 0x3fff).toLong(),14)shl 2)
        else->null
    }
    private fun signed(v:Long,bits:Int)=(v shl(64-bits))shr(64-bits)
    private fun fail(s:String):Nothing=throw PatchException("Fastboot recovery：$s 未唯一证明。")
}
