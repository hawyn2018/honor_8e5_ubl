package net.hawyn.ablefisp.patch

/** Facts for child-image boot failures. Nothing here patches a shared reset service.
 * Callback sites deliberately have a different execution contract from normal calls.
 */
internal object AutoBootFailureFacts {
    enum class Kind { BOOT_STATE_SHUTDOWN, FATAL_BOOT_REPORT, AVB_ABORT, WARNING_TIMEOUT_SHUTDOWN }
    enum class ExecutionContext { SYNC, CALLBACK }
    data class Site(
        val kind: Kind, val hookRva: Long, val originalWord: Int,
        val ownerStart: Long, val ownerEnd: Long, val executionContext: ExecutionContext,
        val naturalReturnRva: Long?, val targetCallee: Long,
        val nativeFailureEvidence: List<Long>, val normalContinueGuard: List<Long>,
        /** The site is not an explicit native command action. Boot-attempt scope is still required. */
        val knownManualExclusion: Boolean,
        val requiresDeferredPoll: Boolean = false, val safeWaitResumeRva: Long? = null,
        val existingPolicyStubRva: Long? = null, val callbackReturnAbiProven: Boolean = false,
    )
    data class SafeWaitResume(val hookRva: Long, val originalWord: Int, val ownerStart: Long,
        val ownerEnd: Long, val loopLoadRva: Long, val doneRva: Long, val eventSlotRva: Long,
        val cleanupRva: Long = 0L, val cleanupEvidence: List<Long> = emptyList())
    data class ManualAction(val command: String, val handlerRva: Long, val actionCallRvas: List<Long>)
    data class Facts(val sites: List<Site>, val safeWaitResumes: List<SafeWaitResume>,
        val manualActionSites: List<ManualAction>, val notes: List<String>)
    private data class Function(val start: Long, val end: Long)
    private data class Method(val pc: Long, val field: Int, val global: Long)
    private data class TerminalAction(val function: Function, val tail: Long, val target: Long, val evidence: List<Long>)
    private data class Reporter(val function: Function, val evidence: List<Long>, val actions: List<TerminalAction>)
    private class Index(val image: PeImage) {
        val code = image.instructions().toList()
        val refs = A64.references(image)
        val starts = code.filter { it.second in PAC }.map { it.first }.sorted()
        val incoming = code.mapNotNull { (pc,w) -> branch(pc,w)?.let { it to pc } }.groupBy({it.first},{it.second})
        private val addresses = HashMap<Pair<Long,Int>,Long?>()
        fun owner(pc: Long): Function? {
            val found=starts.binarySearch(pc)
            val at=if(found>=0)found else -found-2
            if(at<0 || at+1>=starts.size)return null
            return Function(starts[at],starts[at+1])
        }
        fun body(f: Function) = code.filter { it.first in f.start..<f.end }
        fun messages(part: String, f: Function? = null) = refs.filter {
            (f == null || it.pc in f.start..<f.end) && image.ascii(it.address)?.contains(part, true) == true
        }
        fun preservesCall(word: Int, pc: Long, register: Int): Boolean {
            if (register < 19) return false
            if (word and 0xfffffc1f.toInt()==0xd63f0000.toInt()) return true // UEFI/AAPCS indirect call.
            val target = A64.blTarget(pc,word) ?: return false
            if (image.word(target) in PAC) return true
            // Outlined epilogues are not normal ABI functions. Prove their writes.
            var at = target
            while (image.isCode(at) && at < (owner(target)?.end ?: target + 128)) {
                val w = image.word(at) ?: return false
                if (ret(w)) return true
                if (StraightLineLiveness.mayWriteRegister(w,register) || A64.blTarget(at,w)!=null || branch(at,w)!=null) return false
                at += 4
            }
            return false
        }
        fun definition(pc: Long, register: Int, lower: Long): Long? {
            if (register !in 0..30) return null
            for (at in pc-4 downTo lower step 4) {
                val w=image.word(at) ?: return null
                if (isCall(w)) { if (!preservesCall(w,at,register)) return null; continue }
                if (StraightLineLiveness.mayWriteRegister(w,register)) return at
            }
            return null
        }
        fun address(pc: Long, register: Int, lower: Long, depth: Int = 0): Long? {
            if (depth > 24 || register !in 0..30) return null
            val key=pc to register
            if (key in addresses) return addresses[key]
            val at=definition(pc,register,lower) ?: return null
            val w=image.word(at) ?: return null
            val value=A64.adr(at,w)?.takeIf { it.first==register }?.second
                ?: A64.adrp(at,w)?.takeIf { it.first==register }?.second
                ?: A64.add(w)?.takeIf { it.rd==register && it.rn!=31 }?.let { address(at,it.rn,lower,depth+1)?.plus(it.immediate) }
                ?: move(w,register)?.let { address(at,it,lower,depth+1) }
            // No external path may enter after the address definition.
            val bypass=incoming.any { (to,from) -> to in at+4..<pc && from.any { it !in at..<pc } }
            return value.takeUnless { bypass }.also { addresses[key]=it }
        }
        fun method(pc: Long, global: Long): Method? {
            val w=image.word(pc) ?: return null
            if (w and 0xfffffc1f.toInt()!=0xd63f0000.toInt()) return null
            val f=owner(pc) ?: return null
            val a=loadDefinition(pc,(w ushr 5) and 31,f.start) ?: return null
            val field=StateBranchEvaluator.access(image.word(a) ?: return null) ?: return null
            if (!field.load || field.width!=8) return null
            val b=loadDefinition(a,field.rn,f.start) ?: return null
            val table=StateBranchEvaluator.access(image.word(b) ?: return null) ?: return null
            if (!table.load || table.width!=8 || address(b,table.rn,f.start)?.plus(table.offset)!=global) return null
            return Method(pc,field.offset,global)
        }
        fun loadDefinition(pc:Long,register:Int,lower:Long):Long? {
            var use=pc;var reg=register
            while(use>lower) {
                val at=definition(use,reg,lower) ?: return null
                val raw=image.word(at) ?: return null
                val copied=move(raw,reg)
                if(copied==null)return at
                use=at;reg=copied
            }
            return null
        }
        fun constant(pc:Long,register:Int,lower:Long):Int? {
            var use=pc;var reg=register
            while(use>lower) {
                if(reg==31)return 0
                val at=definition(use,reg,lower) ?: return null
                val w=image.word(at) ?: return null
                A64.movWImmediate(w)?.takeIf { it.first==reg }?.let { return it.second }
                if(w and 0xffe0ffe0.toInt() !in setOf(0xaa0003e0.toInt(),0x2a0003e0) || w and 31!=reg)return null
                use=at;reg=(w ushr 16) and 31
            }
            return null
        }
        fun pointerStores(slot: Long): List<Pair<Long,Long>> = code.flatMap { (pc,w) ->
            val f=owner(pc) ?: return@flatMap emptyList()
            val a=StateBranchEvaluator.access(w)
            val stores=if(a!=null && !a.load && a.width==8) listOf(Triple(a.rt,a.rn,a.offset))
                else if(w and 0xffc00000.toInt()==0xa9000000.toInt()) {
                    val off=signExtend(((w ushr 15) and 127).toLong(),7).toInt()*8
                    listOf(Triple(w and 31,(w ushr 5) and 31,off),Triple((w ushr 10) and 31,(w ushr 5) and 31,off+8))
                } else return@flatMap emptyList()
            stores.mapNotNull { (rt,rn,off) ->
                if(rt==31 || address(pc,rn,f.start)?.plus(off)!=slot) null
                else address(pc,rt,f.start)?.takeIf { image.isCode(it) }?.let { pc to it }
            }
        }
    }
    private val PAC=setOf(0xd503233f.toInt(),0xd503237f.toInt())

    fun analyze(image: PeImage, recovery: FastbootRecoveryFacts.Facts? = null): Facts {
        val i=Index(image)
        val relevant=i.messages("avb_abort!")+i.messages("Boot State is")+i.messages("bootfail_errno")
        if(relevant.isEmpty())return Facts(emptyList(),emptyList(),emptyList(),listOf("未发现对应的自动启动故障功能。"))
        val env=KblEnvironmentFacts.analyze(image)
        val resetMethods=i.code.mapNotNull { (pc,_) -> i.method(pc,env.runtimeServicesGlobal)?.takeIf { it.field==0x68 } }
        val resetOwners=resetMethods.mapNotNull { m -> i.owner(m.pc)?.let { it to m } }.groupBy({it.first},{it.second})
        val shutdown=resetOwners.filter { (_,methods) -> methods.all {
            i.constant(it.pc,0,requireNotNull(i.owner(it.pc)).start)==2
        } }.keys.map { it.start }.toSet()
        val resetTargets=resetOwners.keys.map { it.start }.toSet()
        if(shutdown.isEmpty())fail("自动故障处理的 RuntimeServices ResetSystem/Shutdown 关系")
        val commands=commands(i)
        val manualRoots=commands.values.flatten().toSet()
        val manual=commands.flatMap { (name,handlers) -> handlers.mapNotNull { h -> i.owner(h)?.let { f ->
            ManualAction(name,h,i.body(f).filter { (pc,w) -> target(pc,w) in resetTargets }.map { it.first })
        } } }
        val sites=ArrayList<Site>(); val polls=ArrayList<SafeWaitResume>(); val notes=ArrayList<String>()
        fun site(kind:Kind,f:Function,pc:Long,target:Long,proof:List<Long>,guards:List<Long> = emptyList(),
                 context:ExecutionContext=ExecutionContext.SYNC,stub:Long?=null,abi:Boolean=false,
                 poll:SafeWaitResume?=null) {
            if(f.start in manualRoots)return
            val w=image.word(pc) ?: fail("自动故障 hook 指令")
            sites+=Site(kind,pc,w,f.start,f.end,context,
                if(A64.blTarget(pc,w)!=null)pc+4 else null,target,proof.distinct(),guards.distinct(),true,
                context==ExecutionContext.CALLBACK && poll==null,poll?.hookRva,stub,abi)
        }

        // Shutdown only on the two fatal boot-state legs, never the common service.
        val boots=i.messages("Boot State is").mapNotNull { i.owner(it.pc) }.distinct()
        for(f in boots) {
            val body=i.body(f)
            val warnings=i.messages("will shutdown",f)
            if(warnings.isEmpty())continue
            val first=warnings.minOf { it.pc }
            val comparisons=body.filter { (pc,w) -> pc in maxOf(f.start,first-160)..<first && A64.cmpImmediate(w)?.second in setOf(1,2,3) }
            val groups=comparisons.groupBy { A64.cmpImmediate(it.second)!!.first }.filter { (_,xs) -> xs.map { A64.cmpImmediate(it.second)!!.second }.toSet().containsAll(setOf(1,2,3)) }
            val group=groups.values.singleOrNull() ?: fail("BootState 错误状态与正常继续分支")
            val guards=group.mapNotNull { (pc,_) -> body.firstOrNull { (at,w) -> at in pc+4..pc+12 && conditional(at,w)!=null &&
                StraightLineLiveness.preserves(image,pc+4,at,flags=true) }?.first }
            if(guards.size!=group.size)fail("BootState 条件分支")
            val calls=body.filter { (pc,w) -> pc>=first && pc<=warnings.maxOf { it.pc }+96 && A64.blTarget(pc,w) in shutdown }
            if(calls.size!=1)fail("BootState 错误状态的专用 Shutdown 调用")
            val call=calls.single()
            // The same loaded boot-state field, rather than three unrelated literals.
            val fields=group.map { (pc,w) ->
                val load=i.definition(pc,A64.cmpImmediate(w)!!.first,f.start) ?: fail("BootState 来源")
                val m=StateBranchEvaluator.access(image.word(load) ?: 0) ?: fail("BootState 字段读取")
                if(!m.load || m.width!=4)fail("BootState 字段宽度")
                m.rn to m.offset
            }
            if(fields.distinct().size!=1)fail("BootState 字段身份不一致")
            site(Kind.BOOT_STATE_SHUTDOWN,f,call.first,requireNotNull(A64.blTarget(call.first,call.second)),warnings.map { it.pc }+group.map { it.first },guards)
        }

        // A named fatal abort with a proved shutdown tail, limited at runtime to boot attempts.
        for(f in i.messages("avb_abort!").mapNotNull { i.owner(it.pc) }.distinct()) {
            val tails=i.body(f).filter { (pc,w) -> A64.bTarget(pc,w) in shutdown }
            if(tails.size!=1 || !restoredTail(i,f,tails.single().first))fail("AVB abort 的已拆栈 Shutdown 尾调用")
            val t=tails.single()
            site(Kind.AVB_ABORT,f,t.first,requireNotNull(A64.bTarget(t.first,t.second)),i.messages("avb_abort!",f).map { it.pc })
        }

        val reporters=i.messages("bootfail_errno").filter { image.ascii(it.address)?.contains("suggest_method")==true }
            .mapNotNull { i.owner(it.pc) }.distinct().mapNotNull { f -> reporter(i,f,resetTargets) }
        if(i.messages("bootfail_errno").isNotEmpty() && reporters.isEmpty())fail("BootFailure reporter 的实际终止动作")
        for(report in reporters)for(action in report.actions) {
            // The reporter also has ordinary early returns (not initialized,
            // detail-copy failure). Its actual reset-action tail is terminal;
            // its caller and the existing allow-error policy are not modified.
            site(Kind.FATAL_BOOT_REPORT,action.function,action.tail,action.target,
                report.evidence+action.evidence)
        }

        // Prove callback registration through UEFI CreateEvent, then the callback's
        // *already restored* tail. Callback emitter must defer+RET, never longjmp.
        val callbacks=i.code.mapNotNull { (pc,_) ->
            if(i.method(pc,env.bootServicesGlobal)?.field!=0x50)return@mapNotNull null
            val f=i.owner(pc) ?: return@mapNotNull null
            val a=A64.argumentState(image,maxOf(f.start,pc-96),pc).registers[2] as? A64.Value.Address ?: return@mapNotNull null
            i.owner(a.rva)?.takeIf { it.start==a.rva }?.let { it to pc }
        }.distinct()
        for((f,registration) in callbacks) {
            val body=i.body(f)
            for((pc,w) in body.filter { (pc,w) -> A64.bTarget(pc,w) in shutdown }) {
                if(!restoredTail(i,f,pc))fail("按键计时回调的 RET ABI")
                val cleanup=body.filter { it.first<pc }.mapNotNull { (at,raw) -> A64.blTarget(at,raw)?.let { at to it } }
                    .filter { (_,t) -> i.owner(t)?.let { i.messages("Exit key detection timer",it).isNotEmpty() }==true }.lastOrNull() ?: continue
                val cleanupOwner=i.owner(cleanup.second) ?: continue
                val close=i.body(cleanupOwner).filter { (at,_) -> i.method(at,env.bootServicesGlobal)?.field==0x70 }
                val closedEvents=close.mapNotNull { (at,_) ->
                    val load=i.loadDefinition(at,0,cleanupOwner.start) ?: return@mapNotNull null
                    val m=StateBranchEvaluator.access(image.word(load) ?: return@mapNotNull null) ?: return@mapNotNull null
                    if(!m.load || m.width!=8)return@mapNotNull null
                    val slot=i.address(load,m.rn,cleanupOwner.start)?.plus(m.offset) ?: return@mapNotNull null
                    if(!image.isWritable(slot,8))return@mapNotNull null
                    val clears=i.body(cleanupOwner).filter { (q,raw) ->
                        val zero=StateBranchEvaluator.access(raw)
                        q in at+4..at+64 && zero!=null && !zero.load && zero.width==8 && zero.rt==31 &&
                            i.address(q,zero.rn,cleanupOwner.start)?.plus(zero.offset)==slot
                    }
                    clears.singleOrNull()?.let { Triple(it.first,slot,at) }
                }
                if(closedEvents.size!=1)fail("按键计时回调的 CloseEvent 参数与同事件清零关系")
                val event=closedEvents.single()
                val timeout=body.filter { it.first in maxOf(f.start,cleanup.first-64)..<cleanup.first }
                    .lastOrNull { (at,raw) -> conditional(at,raw)?.let { it>cleanup.first }==true } ?: continue
                val bootFailureOwners=sites.filter { it.kind==Kind.BOOT_STATE_SHUTDOWN }.map { it.ownerStart }.toSet()
                val cleanupProof=listOf(cleanupOwner.start,event.third,event.first)+
                    i.messages("Exit key detection timer",cleanupOwner).map { it.pc }
                val found=boots.filter { it.start in bootFailureOwners }.flatMap {
                    waitPolls(i,it,event.second,cleanupOwner.start,cleanupProof)
                }.distinctBy { it.hookRva }
                val poll=found.singleOrNull()
                if(found.size>1)fail("按键错误计时的同步轮询恢复点不唯一")
                if(poll!=null)polls+=poll else notes+="计时回调已有可返回 ABI，但同步 pending 轮询点尚未证明。"
                // These original descriptor-type/default-action branches stay in
                // place. A normal orange countdown can clean the event and RET
                // without ever reaching this specifically terminal tail.
                val actionGuards=body.filter { (at,raw) -> at in cleanup.first+4..<pc && conditional(at,raw)!=null }.map { it.first }
                site(Kind.WARNING_TIMEOUT_SHUTDOWN,f,pc,requireNotNull(A64.bTarget(pc,w)),
                    listOf(registration,timeout.first,cleanup.first,event.first,event.third)+actionGuards,
                    listOf(timeout.first)+actionGuards,ExecutionContext.CALLBACK,abi=true,poll=poll)
            }
        }
        if(recovery!=null)notes+="continue 的 BootLinux 错误与首次引导使用相同 boot-attempt 恢复范围；显式重启和成功启动保留。"
        notes+="仅返回功能/数据流证据；同步站点使用 frame-safe 请求，UEFI 回调仅 defer+RET，轮询安全点才执行恢复。"
        return Facts(sites.distinctBy { it.hookRva }.sortedBy { it.hookRva },polls.distinctBy { it.hookRva },manual,notes)
    }

    private fun reporter(i:Index,f:Function,resetTargets:Set<Long>):Reporter? {
        val image=i.image; val body=i.body(f)
        val terminalMessages=i.messages("system reboot",f)
        if(terminalMessages.isEmpty())return null
        val flow=A64ValueOrigins(image,f.start,f.end)
        val guards=body.filter { (at,raw) -> raw and 0x7f000000==0x35000000 &&
            flow.derivesFromEntry(at,raw and 31,0) &&
            conditional(at,raw)?.let { to -> terminalMessages.any { it.pc in at+4..<to } }==true }
        if(guards.size!=1)return null
        val processors=body.mapNotNull { (pc,w) -> A64.blTarget(pc,w)?.let(i::owner) }
            .filter { i.messages("execute_recovery_method",it).isNotEmpty() }.distinct()
        val actions=ArrayList<TerminalAction>()
        for(owner in listOf(f)+processors)for((pc,w) in i.body(owner)) {
            val a=StateBranchEvaluator.access(w) ?: continue
            if(!a.load || a.width!=8 || a.rt==31)continue
            val slot=i.address(pc,a.rn,owner.start)?.plus(a.offset) ?: continue
            if(!image.isWritable(slot,8))continue
            val consumed=pointerConsumer(i,owner,pc,a.rt) ?: continue
            val initializers=i.pointerStores(slot).toMutableList()
            image.rvaToOffset(slot,8)?.let { raw ->
                val value=image.bytes.u64(raw)
                listOf(value,value-image.imageBase).distinct().filter { image.isCode(it) && i.owner(it)?.start==it }
                    .forEach { initializers+=slot to it }
            }
            for((init,target) in initializers.distinct()) {
                val callback=i.owner(target)?.takeIf { it.start==target } ?: continue
                val tails=i.body(callback).filter { (at,raw) -> A64.bTarget(at,raw) in resetTargets }
                for((tail,raw) in tails) {
                    if(!restoredTail(i,callback,tail))continue
                    actions+=TerminalAction(callback,tail,requireNotNull(A64.bTarget(tail,raw)),
                        listOf(pc,consumed,init,target))
                }
            }
        }
        return actions.takeIf { it.isNotEmpty() }?.let {
            Reporter(f,terminalMessages.map { it.pc }+guards.map { it.first },it.distinctBy { it.tail })
        }
    }
    /** Follow an actually loaded action pointer to its indirect invocation.
     * A shared BLR may have several predecessor definitions; each load is proved
     * independently, including a compiler's direct branch to a common call block.
     */
    private fun pointerConsumer(i:Index,f:Function,load:Long,register:Int):Long? {
        data class State(val pc:Long,val aliases:Set<Int>)
        val pending=java.util.ArrayDeque<State>();val seen=HashSet<State>()
        pending+=State(load+4,setOf(register))
        while(pending.isNotEmpty()) {
            val s=pending.removeFirst()
            if(s.pc !in f.start..<f.end || s.aliases.isEmpty() || !seen.add(s))continue
            val w=i.image.word(s.pc) ?: continue
            val indirect=w and 0xfffffc1f.toInt()
            if(indirect in setOf(0xd63f0000.toInt(),0xd61f0000.toInt()) && (w ushr 5) and 31 in s.aliases)return s.pc
            if(ret(w) || indirect==0xd61f0000.toInt())continue
            A64.bTarget(s.pc,w)?.let { pending+=State(it,s.aliases);continue }
            conditional(s.pc,w)?.let { pending+=State(it,s.aliases);pending+=State(s.pc+4,s.aliases);continue }
            val aliases=s.aliases.toMutableSet()
            if(isCall(w))aliases.removeAll { !i.preservesCall(w,s.pc,it) }
            else {
                val copied=if(w and 0xffe0ffe0.toInt()==0xaa0003e0.toInt())(w ushr 16) and 31 else null
                aliases.removeAll { StraightLineLiveness.mayWriteRegister(w,it) }
                if(copied in s.aliases && w and 31!=31)aliases+=w and 31
            }
            pending+=State(s.pc+4,aliases)
        }
        return null
    }
    private fun restoredTail(i:Index,f:Function,pc:Long):Boolean {
        val image=i.image; val body=i.body(f)
        val sub=body.take(8).singleOrNull { (_,w) -> w and 0xffc003ff.toInt()==0xd10003ff.toInt() } ?: return false
        val frame=((sub.second ushr 10) and 4095).toLong()
        if(frame==0L)return false
        val restored=body.filter { it.first in maxOf(f.start,pc-24)..<pc }
        val add=restored.singleOrNull { (_,w) -> A64.add(w)?.let { it.rd==31 && it.rn==31 && it.immediate==frame }==true } ?: return false
        if(image.word(pc-4) !in setOf(0xd50323bf.toInt(),0xd50323ff.toInt()))return false
        val loadLr=restored.lastOrNull { (at,w) -> at<add.first && StateBranchEvaluator.access(w)?.let {
            it.load && it.width==8 && it.rn==31 && it.rt==30
        }==true } ?: return false
        fun slots(w:Int,load:Boolean):List<Pair<Int,Int>> {
            val a=StateBranchEvaluator.access(w)
            if(a!=null && a.load==load && a.width==8 && a.rn==31)return listOf(a.rt to a.offset)
            if(w and 0xffc00000.toInt()!=(if(load)0xa9400000.toInt() else 0xa9000000.toInt()) || (w ushr 5) and 31!=31)return emptyList()
            val off=signExtend(((w ushr 15) and 127).toLong(),7).toInt()*8
            return listOf((w and 31) to off,((w ushr 10) and 31) to off+8)
        }
        val saved=body.filter { it.first in sub.first+4..sub.first+32 }.flatMap { slots(it.second,false) }
            .filter { it.first in 19..30 }.toSet()
        if(saved.none { it.first==30 })return false
        val loaded=body.filter { it.first in maxOf(f.start,pc-64)..<add.first }.flatMap { (at,w) ->
            val direct=slots(w,true)
            val target=A64.blTarget(at,w)
            val restored=if(target==null || image.word(target)!=0xd503245f.toInt()) direct else {
                val helper=ArrayList<Pair<Int,Int>>()
                var q=target+4;var valid=false
                while(image.isCode(q) && q<target+128) {
                    val raw=image.word(q) ?: break
                    if(ret(raw)){valid=true;break}
                    val restoredSlots=slots(raw,true)
                    if(restoredSlots.isEmpty())break
                    helper+=restoredSlots;q+=4
                }
                direct+if(valid)helper else emptyList()
            }
            restored.map { at to it }
        }
        if((30 to StateBranchEvaluator.access(loadLr.second)!!.offset) !in saved)return false
        // A restoration somewhere is insufficient: no later write may destroy
        // the restored value. AUTIASP/AUTIBSP is the one separately proved final
        // transformation of LR and is deliberately outside this linear window.
        return saved.all { slot ->
            val last=loaded.lastOrNull { it.second==slot } ?: return@all false
            StraightLineLiveness.preserves(image,last.first+4,pc-4,setOf(slot.first))
        }
    }
    private fun waitPolls(i:Index,f:Function,event:Long,cleanup:Long,cleanupProof:List<Long>):List<SafeWaitResume> {
        val image=i.image; val body=i.body(f); val result=ArrayList<SafeWaitResume>()
        for((pc,w) in body) {
            val m=StateBranchEvaluator.access(w) ?: continue
            if(!m.load || m.width!=8 || i.address(pc,m.rn,f.start)?.plus(m.offset)!=event)continue
            val zero=body.firstOrNull { (at,raw) -> at in pc+4..pc+16 && raw and 0xff00001f.toInt()==(0xb4000000.toInt() or m.rt) &&
                StraightLineLiveness.preserves(image,pc+4,at,setOf(m.rt)) } ?: continue
            val done=conditional(zero.first,zero.second) ?: continue
            if(done !in f.start..<f.end)continue
            val back=body.firstOrNull { (at,raw) -> at in zero.first+4..zero.first+48 && A64.bTarget(at,raw)==pc } ?: continue
            val calls=body.filter { (at,raw) -> at in zero.first+4..<back.first && A64.blTarget(at,raw)!=null }
            if(calls.size!=1)continue
            result+=SafeWaitResume(back.first,back.second,f.start,f.end,pc,done,event,cleanup,cleanupProof)
        }
        return result
    }
    private fun commands(i:Index):Map<String,List<Long>> {
        val names=listOf("reboot","reboot-bootloader","reboot-recovery","reboot-fastboot","oem shutdown","oem edl","set_active","oem frp-unlock","flash:slockv1")
        return names.associateWith { name ->
            val strings=i.image.bytes.occurrences((name+"\u0000").toByteArray(Charsets.US_ASCII)).mapNotNull(i.image::offsetToRva).toSet()
            i.image.sections.filter { !it.executable }.flatMap { s ->
                (0..maxOf(-1,s.rawSize-16) step 8).mapNotNull { off ->
                    val at=s.raw+off
                    if(i.image.bytes.u64(at) !in strings)return@mapNotNull null
                    i.image.bytes.u64(at+8).takeIf { t -> i.image.isCode(t) && i.owner(t)?.start==t }
                }
            }.distinct()
        }.filterValues { it.isNotEmpty() }
    }
    private fun isCall(w:Int)=w and 0xfc000000.toInt()==0x94000000.toInt() || w and 0xfffffc1f.toInt()==0xd63f0000.toInt()
    private fun move(w:Int,d:Int):Int?=if(w and 0xffe0ffe0.toInt()==0xaa0003e0.toInt() && w and 31==d)(w ushr 16) and 31 else null
    private fun ret(w:Int)=w and 0xfffffc1f.toInt()==0xd65f0000.toInt() || w in setOf(0xd65f0bff.toInt(),0xd65f0fff.toInt())
    private fun target(pc:Long,w:Int)=A64.blTarget(pc,w) ?: A64.bTarget(pc,w)
    private fun branch(pc:Long,w:Int)=target(pc,w) ?: conditional(pc,w)
    private fun conditional(pc:Long,w:Int):Long?=when {
        w and 0xff000010.toInt()==0x54000000 -> pc+(signExtend(((w ushr 5) and 0x7ffff).toLong(),19) shl 2)
        w and 0x7e000000==0x34000000 -> pc+(signExtend(((w ushr 5) and 0x7ffff).toLong(),19) shl 2)
        w and 0x7e000000==0x36000000 -> pc+(signExtend(((w ushr 5) and 0x3fff).toLong(),14) shl 2)
        else -> null
    }
    private fun fail(role:String):Nothing=throw PatchException("自动启动故障恢复：$role 未完整且唯一匹配。",PatchFailureKind.INCOMPLETE_CORE_FEATURE)
}
