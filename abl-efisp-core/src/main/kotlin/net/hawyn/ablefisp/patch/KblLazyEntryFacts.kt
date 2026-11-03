package net.hawyn.ablefisp.patch

/** The lazy entry is an edge used only after the original command list is
 * exhausted. The original shared FAIL call and every known-handler path stay
 * intact. Coordinates and registers come from producer/consumer data flow. */
internal object KblLazyEntryFacts {
    data class Facts(val hookRva:Long,val originalWord:Int,val failCallRva:Long,
        val continueRva:Long,val commandRegister:Int,val functionRva:Long,
        val evidence:Map<String,Long>)
    private fun signed(v:Long,b:Int)=(v shl(64-b))shr(64-b)
    private fun cbTarget(pc:Long,w:Int)=pc+(signed(((w ushr 5)and 0x7ffff).toLong(),19)shl 2)
    private fun tbTarget(pc:Long,w:Int)=pc+(signed(((w ushr 5)and 0x3fff).toLong(),14)shl 2)
    private fun successors(pc:Long,w:Int):List<Long> = when {
        A64.bTarget(pc,w)!=null->listOf(A64.bTarget(pc,w)!!)
        w and 0x7e000000==0x34000000 || w and 0xff000010.toInt()==0x54000000->listOf(pc+4,cbTarget(pc,w))
        w and 0x7e000000==0x36000000->listOf(pc+4,tbTarget(pc,w))
        w and 0xfffffc1f.toInt() in setOf(0xd61f0000.toInt(),0xd65f0000.toInt())->emptyList()
        else->listOf(pc+4)
    }
    fun analyze(image:PeImage,dispatch:KblDispatcherFacts.Facts,failRva:Long):Facts {
        val code=image.instructions().toList();val starts=code.filter{
            it.second in setOf(0xd503233f.toInt(),0xd503237f.toInt())}.map{it.first}
        val start=starts.lastOrNull{it<=dispatch.dispatchCall}?:fail("dispatcher函数入口")
        val end=starts.firstOrNull{it>dispatch.dispatchCall}?:fail("dispatcher函数边界")
        val flow=A64ValueOrigins(image,start,end)
        val load=dispatch.evidence["headLoad"]?:fail("链表耗尽证据")
        val loadWord=image.word(load)?:fail("链表读取")
        val node=loadWord and 31
        val nodeFlow=A64ValueOrigins(image,load+4,end)
        val (emptyPc,empty)=code.filter { (pc,w)->pc in load+4..<dispatch.dispatchCall &&
            w and 0xff000000.toInt()==0xb4000000.toInt() &&
            nodeFlow.derivesFromEntry(pc,w and 31,node) &&
            nodeFlow.hasOnlyLinearEntry(load+4,pc) }
            .singleOrNull()?:fail("链表空分支与节点来源未唯一证明")
        val unknown=cbTarget(emptyPc,empty)
        if(unknown<=dispatch.dispatchCall || unknown>=end)fail("未知命令块边界")
        val addPc=dispatch.evidence["suffixAdd"]?:fail("原命令来源")
        val add=image.word(addPc)?:fail("suffix ADD")
        val command=(add ushr 5)and 31
        if(!flow.derivesFromEntry(addPc,command,1))fail("原命令不是dispatcher的buffer参数")
        // The producer has written buffer[min(packetBytes,64)]=0 before any
        // string consumers. This proves bounded complete C-string comparison,
        // not preservation of bytes following an embedded NUL in a USB packet.
        val terminators=code.filter{(pc,w)->pc in start..<load &&
            w and 0xffe0fc1f.toInt()==0x3820681f &&
            flow.derivesFromEntry(pc,(w ushr 5)and 31,1)}
        val terminator=terminators.singleOrNull{(pc,w)->
            val index=(w ushr 16)and 31
            code.any{(cp,cw)->cp in start..<pc && cw and 0xffe00c00.toInt()==0x9a800000.toInt() &&
                (cw ushr 12)and 15==3 && flow.derivesFrom(pc,index,cp,cw and 31) &&
                flow.derivesFromEntry(cp,(cw ushr 5)and 31,0) &&
                code.any{(mp,mw)->mp in start..<cp && mw and 0xffffffe0.toInt()==0x52800800 &&
                    flow.derivesFrom(cp,(cw ushr 16)and 31,mp,mw and 31)} &&
                code.any{(xp,xw)->xp in start..<cp && xw and 0xfffffc1f.toInt()==0xf101001f.toInt() &&
                    flow.derivesFromEntry(xp,(xw ushr 5)and 31,0) && flow.hasOnlyLinearEntry(xp,cp) &&
                    StraightLineLiveness.preserves(image,xp+4,cp,flags=true)}}
        }?:fail("buffer的64字节clamp和NUL写入未唯一证明")
        // A merely adjacent terminator is insufficient: every route from the
        // dispatcher entry to the unknown block must execute that write.
        val seen=HashSet<Long>();val pending=ArrayDeque<Long>();pending.add(start)
        while(pending.isNotEmpty()) {
            val pc=pending.removeFirst()
            if(pc==terminator.first || pc !in start..<end || !seen.add(pc))continue
            if(pc==unknown)fail("NUL写入未支配未知命令路径")
            image.word(pc)?.let{successors(pc,it).forEach(pending::add)}
        }
        val candidates=ArrayList<Facts>()
        for((pc,w) in code.filter{it.first in unknown..<end}) {
            val target=A64.bTarget(pc,w)?:continue
            if(!flow.hasOnlyLinearEntry(unknown,pc)||!flow.derivesFromEntry(pc,command,1))continue
            val call=image.word(target)?:continue
            if(A64.blTarget(target,call)!=failRva)continue
            // No conditional/indirect edge may enter this block halfway. BLs
            // (logging) return normally; a different direct B is not accepted.
            val body=code.filter{it.first in unknown..<pc}
            if(body.any{(_,x)->A64.bTarget(0,x)!=null || x and 0xff000010.toInt()==0x54000000 ||
                x and 0x7e000000==0x34000000 || x and 0x7e000000==0x36000000 ||
                x and 0xfffffc1f.toInt()==0xd61f0000.toInt()})continue
            var matchedExitPc=dispatch.dispatchCall+4
            while(matchedExitPc<end && image.word(matchedExitPc)==0xd503201f.toInt())matchedExitPc+=4
            val matchedExit=image.word(matchedExitPc)?.let{A64.bTarget(matchedExitPc,it)}?:continue
            if(matchedExit!=target+4)continue
            val incoming=code.flatMap{(at,x)->listOfNotNull(A64.bTarget(at,x),
                if(x and 0x7e000000==0x34000000)cbTarget(at,x)else null,
                if(x and 0xff000010.toInt()==0x54000000)cbTarget(at,x)else null,
                if(x and 0x7e000000==0x36000000)tbTarget(at,x)else null).map{at to it}}
                .filter{it.second in unknown..pc}
            if(incoming.any{it.first!=emptyPc && it.first !in unknown..pc})continue
            candidates+=Facts(pc,w,target,target+4,command,start,linkedMapOf(
                "emptyListBranch" to emptyPc,"unknownBlock" to unknown,"commandTerminator" to terminator.first,
                "commandCapacity" to 65L,"unknownEdge" to pc,"originalFailCall" to target,
                "knownHandlerCall" to dispatch.dispatchCall,"commandArgument" to 1L))
        }
        return candidates.distinct().singleOrNull()?:fail("仅unknown可达的原FAIL边未唯一证明")
    }
    private fun fail(s:String):Nothing=throw PatchException("KBL lazy entry：$s。")
}
