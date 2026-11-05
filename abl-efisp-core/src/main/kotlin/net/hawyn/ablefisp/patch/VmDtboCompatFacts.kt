package net.hawyn.ablefisp.patch

/** Coordinates are recovered from native table read -> selection -> bootargs
 * consumers. Firmware names, hashes and absolute firmware addresses play no role. */
internal object VmDtboCompatFacts {
    data class Facts(val hook:Long,val tableRegister:Int,val lengthStack:Int,val tableStack:Int,
        val indexAddress:Long,val indexRegister:Int,val printLoad:Long,val logger:Long,
        val boardGetter:Long,val revisionAddress:Long,val domains:List<Int>,
        val bootLinux:Long,val normalBoot:Long,val fastbootGate:Long,val readCall:Long,
        val evidence:List<String>)
    private data class Mem(val rt:Int,val rn:Int,val offset:Int,val size:Int,val load:Boolean)
    private fun mem(w:Int):Mem? {
        if(w and 0x3b000000!=0x39000000)return null
        val size=1 shl (w ushr 30)
        return Mem(w and 31,w ushr 5 and 31,(w ushr 10 and 4095)*size,size,w and 0x00400000!=0)
    }
    private fun fail(s:String):Nothing=throw PatchException("VM DTBO：$s")
    private fun target(pc:Long,w:Int):Long? = A64.bTarget(pc,w) ?: if(w and 0x7e000000==0x34000000 || w and 0xff000010.toInt()==0x54000000)
        pc+(signExtend((w ushr 5 and 0x7ffff).toLong(),19) shl 2)
        else if(w and 0x7e000000==0x36000000)pc+(signExtend((w ushr 5 and 0x3fff).toLong(),14) shl 2)else null
    private fun address(image:PeImage,pc:Long,register:Int):Long? =
        A64.registerAddress(image,maxOf(0,pc-32),pc,register)

    fun analyze(image:PeImage):Facts {
        val code=image.instructions().toList();val refs=A64.references(image)
        fun messages(text:String)=refs.filter { image.ascii(it.address)?.contains(text)==true }
        val print=messages("Find TVM - %d & OEMVM - %d").singleOrNull()?:fail("索引输出未唯一匹配")
        val loaded=A64.ldpW(image.word(print.pc-4)?:0)?.takeIf { it.rt==2&&it.rt2==3&&it.offset==0L }
            ?:fail("索引输出的成对读取")
        val setup=code.filter { (p,w)->p in print.pc-0x500..<print.pc && A64.adrp(p,w)?.first==loaded.rn &&
            A64.add(image.word(p+4)?:0)?.let { it.rd==loaded.rn&&it.rn==loaded.rn }==true }
            .map { (p,_)->p to A64.registerAddress(image,p,p+8,loaded.rn) }
            .filter { it.second?.let { a->image.isWritable(a,8) }==true }.singleOrNull()?:fail("索引全局地址初始化")
        val hook=setup.first;val pair=requireNotNull(setup.second)
        val printLoad=print.pc-4
        val logger=(print.pc..print.pc+24 step 4).mapNotNull { p->image.word(p)?.let { A64.blTarget(p,it) } }.firstOrNull()
            ?:fail("原生索引日志调用")
        if(A64.adrp(printLoad+4,image.word(printLoad+4)?:0)?.first!=1||
            A64.add(image.word(printLoad+8)?:0)?.let { it.rd==1&&it.rn==1 }!=true||
            (image.word(printLoad+12)?:0) and 0xff80001f.toInt()!=0x52800000||
            A64.blTarget(printLoad+16,image.word(printLoad+16)?:0)!=logger)
            fail("索引LDP后到原logger必须是无分支的整数参数前缀")
        for((role,fragment) in listOf(0 to "androidboot.hypervisor.tuivm_dtbo_idx=",1 to "androidboot.hypervisor.oemvm_dtbo_idx=")) {
            val consumer=messages(fragment).singleOrNull()?:fail("$fragment 消费者")
            val loads=code.filter { (p,w)->p in consumer.pc-24..consumer.pc+32&&mem(w)?.let {
                it.load&&it.size==4&&it.rt==4&&address(image,p,it.rn)?.plus(it.offset)==pair+role*4
            }==true }
            if(loads.size!=1)fail("VM索引与启动参数消费者未闭合")
        }
        val count=code.filter { (p,w)->p in hook-0x1000..<hook&&mem(w)?.let { it.load&&it.size==4&&it.offset==16&&it.rn==loaded.rn }==true }
            .filter { (p,w)->
                val m=requireNotNull(mem(w))
                // Count is endian-swapped, then compared to the live loop index.
                (p+4..minOf(p+32,hook-4) step 4).any { q->image.word(q)?.let { x->x and 0xfffffc00.toInt()==0x5ac00800&&x ushr 5 and 31==m.rt }==true }&&
                    (maxOf(p+16,hook-0x600)..hook-4 step 4).any { q->image.word(q)?.let { target(q,it)==hook }==true }
            }.maxByOrNull { it.first }?:fail("表entry-count与循环出口关系")
        val tableReg=requireNotNull(mem(count.second)).rn
        if(tableReg!=loaded.rn)fail("循环末尾表指针生命周期未证明")
        // Original role IDs come from the selector's ID comparison and the
        // destination store, independently tied to the two bootarg consumers.
        val domain=MutableList(2){-1}
        for((pc,w) in code.filter { it.first in count.first..<hook }) {
            val cmp=A64.cmpImmediate(w)?:continue
            val branch=image.word(pc+4)?:continue;val dest=target(pc+4,branch)?:continue
            val eq=A64.beqTarget(pc+4,branch)!=null
            val entry=if(eq)dest else pc+8
            val storePc=(entry..entry+8 step 4).firstOrNull { q->mem(image.word(q)?:0)?.let { !it.load&&it.size==4 }==true }?:continue
            val store=mem(image.word(storePc)?:0)?:continue
            if(store.load||store.size!=4)continue
            val base=address(image,storePc,store.rn) ?: A64.registerAddress(image,count.first,storePc,store.rn)
            val at=base?.plus(store.offset)?:continue
            val role=if(at==pair)0 else if(at==pair+4)1 else continue
            if(domain[role]!=-1&&domain[role]!=cmp.second)fail("安全域ID存在歧义")
            domain[role]=cmp.second
        }
        if(domain.any { it<0 }||domain[0]==domain[1])fail("原生VM角色和安全域ID来源")
        // Reader ABI must take a real in/out byte count on this main frame,
        // and its success edge must feed this exact table's magic/header check.
        val reads=code.mapNotNull { (pc,w)->
            if(pc !in hook-0x6000..<count.first)return@mapNotNull null
            val callee=A64.blTarget(pc,w)?:return@mapNotNull null
            val move=image.word(pc-4)?:0
            if(move!= (0xaa0003e0.toInt() or tableReg))return@mapNotNull null
            val success=target(pc+4,image.word(pc+4)?:0)?:return@mapNotNull null
            val st=mem(image.word(success)?:0)?:return@mapNotNull null
            if(st.load||st.rt!=tableReg||st.rn!=31||st.size!=8)return@mapNotNull null
            val header=(success+4..success+64 step 4).any { q->mem(image.word(q)?:0)?.let { it.load&&it.rn==tableReg&&it.offset==4&&it.size==4 }==true }
            val arg=A64.argumentState(image,pc-16,pc).registers[1] as? A64.Value.Stack
            if(!header||arg==null||arg.offset !in 0..Int.MAX_VALUE.toLong())return@mapNotNull null
            val lengthStore=(pc-64..pc-16 step 4).any { q->mem(image.word(q)?:0)?.let {
                !it.load&&it.rn==31&&it.offset.toLong()==arg.offset&&it.size==4
            }==true }
            if(!lengthStore)return@mapNotNull null
            Triple(pc,arg.offset.toInt(),callee)
        }
        val read=reads.singleOrNull()?:fail("已读DTBO缓冲区的实际长度ABI未唯一证明")
        val matchCall=code.filter { (p,w)->p in count.first..<hook&&A64.blTarget(p,w)!=null&&A64.immediateBefore(image,p,2,8)==0x38 }
            .singleOrNull()?:fail("原生板型匹配功能")
        val matcher=requireNotNull(A64.blTarget(matchCall.first,matchCall.second))
        val nextFunction=code.firstOrNull { (p,w)->p>matcher&&w in setOf(0xd503233f.toInt(),0xd503237f.toInt()) }?.first?:fail("板型功能范围")
        val boardRef=messages("qcom,board-id").singleOrNull { it.pc in matcher..<nextFunction&&image.ascii(it.address)=="qcom,board-id" }?:fail("板型属性来源")
        val boardCalls=(boardRef.pc..boardRef.pc+64 step 4).mapNotNull { p->image.word(p)?.let { A64.blTarget(p,it)?.let { t->p to t } } }
        if(boardCalls.size<2)fail("板型读取和native variant getter")
        val getter=boardCalls[1].second
        val revisionRef=messages("BoardPlatformHlossubtype =").singleOrNull { it.pc in matcher..<nextFunction }
            ?:fail("原生板型第二cell来源")
        val revisionLoad=(revisionRef.pc..revisionRef.pc+24 step 4).mapNotNull { p->mem(image.word(p)?:0)?.takeIf { it.load&&it.size==4&&it.rt==2 }?.let { p to it } }
            .singleOrNull()?:fail("板型第二cell日志数据流")
        val revisionBase=A64.registerAddress(image,revisionRef.pc-24,revisionLoad.first,revisionLoad.second.rn)
            ?:fail("板型第二cell全局地址")
        val revision=revisionBase+revisionLoad.second.offset
        if(!image.isWritable(revision,4))fail("板型第二cell不属于native数据区")
        // A selector inside BootLinux is unreachable while the native Fastboot
        // command loop is active. Explicit continue enters a new normal boot.
        val owner=code.lastOrNull { (p,w)->p<=count.first&&w in setOf(0xd503233f.toInt(),0xd503237f.toInt()) }?.first
        val boot=bootPath(image,code,refs,requireNotNull(owner))
        val lifetime=tableLifetime(image,code,requireNotNull(owner),read.first,count.first,printLoad,tableReg,read.second)
        return Facts(printLoad,tableReg,read.second,lifetime.first,pair,loaded.rn,printLoad,logger,getter,revision,domain,
            requireNotNull(owner),boot.first,boot.second,read.first,listOf(
                "native partition read @${read.first.toString(16)} -> table x$tableReg / frame+${read.second.toString(16)} bytes",
                "native selector/fallback @${hook.toString(16)} completes before post-fallback LDP hook @${printLoad.toString(16)}; pair @${pair.toString(16)} -> TVM/OEMVM bootargs",
                "qcom,board-id native getter @${getter.toString(16)}, second cell @${revision.toString(16)}",
                "selector owner is native BootLinux @${owner.toString(16)} after normal gate @${boot.first.toString(16)}",
                "join LDP -> ADRP/ADD X1 -> MOV W0 -> unconditional native logger BL: no SIMD inputs/branches; volatile SIMD dead across existing logger, D8-D15 low halves use native AAPCS64")+lifetime.second)
    }

    private fun tableLifetime(image:PeImage,code:List<Pair<Long,Int>>,owner:Long,reader:Long,countLoad:Long,
                              join:Long,tableReg:Int,lengthSlot:Int):Pair<Int,List<String>> {
        val end=code.firstOrNull { (p,w)->p>owner&&w in setOf(0xd503233f.toInt(),0xd503237f.toInt()) }?.first
            ?:fail("BootLinux生命周期范围")
        val body=code.filter { it.first in owner..<end }.toMap()
        val success=image.word(reader+4)?.let { A64.cbzX0(reader+4,it) }?:fail("DTBO reader成功边必须为CBZ X0")
        val spill=mem(image.word(success)?:0)?.takeIf { !it.load&&it.size==8&&it.rn==31&&it.rt==tableReg }
            ?:fail("成功读盘后的DTBO指针frame保存")
        fun successors(pc:Long):List<Long> {
            val w=body[pc]?:return emptyList()
            if(w and 0xfffffc1f.toInt() in setOf(0xd65f0000.toInt(),0xd61f0000.toInt())||w in setOf(0xd65f0bff.toInt(),0xd65f0fff.toInt()))return emptyList()
            val branch=target(pc,w)
            return if(A64.bTarget(pc,w)!=null)listOfNotNull(branch)else listOfNotNull(branch,pc+4)
        }
        fun reachable(start:Long,cut:Long?=null):Set<Long> {
            val seen=hashSetOf<Long>();val queue=java.util.ArrayDeque<Long>();queue.add(start)
            while(queue.isNotEmpty()) {
                val pc=queue.removeFirst();if(pc==cut||pc !in body||!seen.add(pc))continue
                successors(pc).forEach(queue::add)
            };return seen
        }
        val entryReach=reachable(owner)
        if(join !in entryReach||join in reachable(owner,success))fail("读盘成功spill未支配最终索引读取")
        if(entryReach.any { p->requireNotNull(body[p]) and 0xfffffc1f.toInt()==0xd61f0000.toInt() })
            fail("BootLinux含未证明目标的间接BR")
        val headerLoads=(success+4..success+112 step 4).mapNotNull { p->mem(image.word(p)?:0)?.takeIf {
            it.load&&it.size==4&&it.rn==tableReg&&it.offset in setOf(0,4,8,12,20)
        }?.let { it.offset to p } }.toMap()
        if(headerLoads.keys!=setOf(0,4,8,12,20))fail("原生DTBO magic/size/header/stride/offset验证输入")
        for(pc in headerLoads.values+countLoad)if(join in reachable(owner,pc))fail("原生表头/entry-count读取未支配索引汇合")
        val validationGates=headerLoads.values.map { load->
            (load+4..load+32 step 4).firstOrNull { pc->
                val w=body[pc]?:return@firstOrNull false
                val to=target(pc,w)?:return@firstOrNull false
                A64.bTarget(pc,w)==null&&join !in reachable(owner,pc)&&
                    ((join in reachable(to)) xor (join in reachable(pc+4)))
            }?:fail("原生表头加载后的成功/失败验证边未支配索引汇合")
        }
        if(validationGates.toSet().size!=headerLoads.size)fail("多个原生表头字段共用了未证明的验证gate")
        val incoming=code.filter { (p,w)->target(p,w)==join&&p !in body }
        if(incoming.isNotEmpty())fail("索引汇合存在BootLinux外部直接入边")
        val reverse=hashMapOf<Long,MutableList<Long>>()
        body.keys.forEach { pc->successors(pc).forEach { to->reverse.getOrPut(to){arrayListOf()}.add(pc) } }
        val ancestors=hashSetOf<Long>();val queue=java.util.ArrayDeque<Long>();queue.add(join)
        while(queue.isNotEmpty()){val p=queue.removeFirst();if(!ancestors.add(p))continue;reverse[p].orEmpty().forEach(queue::add)}
        val live=reachable(success).intersect(ancestors)
        val flow=A64ValueOrigins(image,success,join+4)
        if(flow.stackPointerOffset(join)!=0||flow.stackOrigins(join,spill.offset,8)!=setOf(A64ValueOrigin.EntryArgument(tableReg)))
            fail("原DTBO frame指针在最终读取前被覆盖或SP未保持")
        fun overlaps(at:Int,n:Int)=at<spill.offset+8&&spill.offset<at+n || at<lengthSlot+4&&lengthSlot<at+n
        for(pc in live) {
            if(pc==success)continue
            val w=requireNotNull(body[pc]);val access=StateBranchEvaluator.access(w)
            if(access!=null&&!access.load) {
                val stack=if(access.rn==31)0 else flow.stackAddress(pc,access.rn)
                if(stack!=null&&overlaps(stack+access.offset,access.width))fail("原DTBO指针或长度frame范围被写入")
            }
            if(w and 0x3a000000==0x28000000&&w and 0x00400000==0) {
                val rn=w ushr 5 and 31;val base=if(rn==31)0 else flow.stackAddress(pc,rn)
                val width=if(w and 0x04000000!=0)4 shl(w ushr 30)else if(w<0)8 else 4
                val offset=signExtend((w ushr 15 and 127).toLong(),7).toInt()*width
                if(base!=null&&overlaps(base+offset,width*2))fail("成对保存覆盖DTBO frame指针或长度")
            }
        }
        // The native read-failure cleanup identifies its actual FreePool
        // adapter. No edge retaining the DTBO until this join may invoke it.
        val frees=(reader+8..reader+64 step 4).mapNotNull { pc->
            val callee=image.word(pc)?.let { A64.blTarget(pc,it) }
            if(image.word(pc-4)==(0xaa0003e0.toInt() or (tableReg shl 16)))callee else null
        }.toSet()
        if(frees.isEmpty())fail("DTBO read-failure释放适配器未证明")
        if(live.any { pc->image.word(pc)?.let { A64.blTarget(pc,it) in frees }==true })fail("DTBO在最终索引读取前可能释放")
        return spill.offset to listOf(
            "reader success @${success.toString(16)} spills DTBO to SP+${spill.offset.toString(16)}; dominates post-fallback hook; ${validationGates.size} header validation success edges plus count loads dominate it",
            "${live.size} live CFG instructions retain original table/length frame slots; no SP change, unresolved BR or native table-free adapter before join; native call ABI retained")
    }

    /** Only the normal/Fastboot decision and a direct BootLinux edge are needed;
     * event, recovery reentry, canary and continue flag ABI are independent. */
    private fun bootPath(image:PeImage,code:List<Pair<Long,Int>>,refs:List<StringReference>,bootLinux:Long):Pair<Long,Long> {
        val starts=code.filter { it.second in setOf(0xd503233f.toInt(),0xd503237f.toInt()) }.map { it.first }
        fun owner(pc:Long)=starts.lastOrNull { it<=pc }
        val fatal=refs.singleOrNull { image.ascii(it.address)?.contains("No fastboot support for flashless")==true }
            ?:fail("Fastboot模式分支诊断")
        val main=owner(fatal.pc)?:fail("Fastboot主功能范围")
        val end=starts.firstOrNull { it>main }?:fail("Fastboot主功能末端")
        val body=code.filter { it.first in main..<end }
        val fatalGate=body.singleOrNull { (pc,w)->w and 0x7e000000==0x36000000&&w and 0x01000000!=0&&
            ((w ushr 19 and 31) or (w ushr 26 and 32))==63&&target(pc,w)?.let { it<=fatal.pc&&fatal.pc<=it+24 }==true }
            ?:fail("Fastboot CardInfo错误分支")
        val mode=body.singleOrNull { (pc,w)->w and 0x7e000000==0x36000000&&w and 0x01000000!=0&&
            ((w ushr 19 and 31) or (w ushr 26 and 32))==0&&target(pc,w)==fatalGate.first }
            ?:fail("正常/Fastboot模式消费者")
        val keyProviders=refs.filter { image.ascii(it.address)?.contains("Key Stroke Read")==true }.mapNotNull { owner(it.pc) }.distinct()
        val keyCall=body.singleOrNull { (pc,w)->pc<mode.first&&A64.blTarget(pc,w) in keyProviders }?:fail("模式决策前按键读取")
        if(keyCall.first>=mode.first)fail("按键与模式决策顺序")
        val normal=mode.first+4
        val calls=body.filter { (pc,w)->pc in normal..<fatalGate.first&&A64.blTarget(pc,w)==bootLinux }
        if(calls.size!=1)fail("正常分支到BootLinux的唯一直接调用")
        val call=calls.single().first
        // Bounded intraprocedural reachability, preserving conditional edges.
        fun reaches(start:Long,destination:Long):Boolean {
            val queue=java.util.ArrayDeque<Long>();val seen=hashSetOf<Long>();queue.add(start)
            while(queue.isNotEmpty()) {
                val pc=queue.removeFirst();if(pc==destination)return true
                if(pc !in main..<end||!seen.add(pc))continue
                val w=image.word(pc)?:continue
                if(w and 0xfffffc1f.toInt() in setOf(0xd65f0000.toInt(),0xd61f0000.toInt())||w in setOf(0xd65f0bff.toInt(),0xd65f0fff.toInt()))continue
                val to=target(pc,w)
                if(to!=null)queue.add(to)
                if(A64.bTarget(pc,w)==null)queue.add(pc+4)
            };return false
        }
        if(!reaches(normal,call))fail("正常分支到BootLinux的CFG")
        if(reaches(fatalGate.first,call))fail("Fastboot入口存在回到正常BootLinux的直接边")
        // Reaching the normal path through a dispatched explicit continue is
        // handled by a native handler outside main. Main's Fastboot loop itself
        // must never call the BootLinux function.
        if(body.any { (pc,w)->pc>=fatalGate.first&&A64.blTarget(pc,w)==bootLinux })fail("Fastboot主循环包含未隔离BootLinux调用")
        return normal to fatalGate.first
    }
}
