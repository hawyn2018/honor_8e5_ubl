package net.hawyn.ablefisp.patch

import java.security.MessageDigest

/** Recovery is for the tool's disposable trailing adapter, never arbitrary PE
 * code. Old driver/config hashes are not a prerequisite for discarding it.
 * Original headers/instructions require independent retained evidence before a
 * candidate can leave this class. Candidate placement comes from this PE only. */
internal object KblAdapterRecovery {
    private val marker = "KBL-PARENT-1".toByteArray(Charsets.US_ASCII)
    private val rxNames = setOf(".textkbl", ".kblrx")
    private val reserved = setOf(".kblrx", ".kblctx", ".kblimg")

    fun ownedSections(image:PeImage):List<PeSection> {
        val tail=image.sections.takeLast(3)
        if(tail.size==3) {
            val names=listOf(tail[0].name in rxNames,tail[1].name==".kblctx",tail[2].name==".kblimg")
            val metadata=tail[2].rawSize>=384 && image.bytes.copyOfRange(tail[2].raw+128,tail[2].raw+128+marker.size).contentEquals(marker)
            // One damaged section name can be replaced, but names alone never
            // identify an unrelated original .textkbl as disposable content.
            if(names.all{it} || (names.count{it}>=2 && metadata)) {
                if(image.sections.dropLast(3).any{it.name in reserved})fail("原节中存在重复 KBL 所有权标记")
                return tail
            }
        }
        if(image.sections.any{it.name in reserved})fail("KBL 节边界缺失，原始节与私有空间尚未形成可恢复关系")
        return emptyList()
    }

    fun remove(bytes:ByteArray,image:PeImage,own:List<PeSection>,priorFailure:String):KblPatcher.Removed {
        if(own.size!=3 || image.sections.size<=3 || own.last().rawSize<384)fail("KBL 所有权/恢复记录边界不足")
        val original=image.sections.dropLast(3)
        val raw=own.last().raw
        val active=bytes.u32(0x3c).asBoundedInt("活动 PE 头")
        val optional=active+24
        val optionalSize=bytes.u16(active+20)
        val count=original.size
        val headerBytes=24+optionalSize+count*40
        val expandedBytes=headerBytes+120
        val firstRaw=original.filter{it.rawSize>0}.minOfOrNull{it.raw}?:fail("原始 PE 没有文件映射")
        val headerLimit=minOf(image.headersSize,firstRaw)
        if(own.any{it.raw<firstRaw} || original.any{it.raw.toLong()+it.rawSize>own.first().raw})fail("KBL 空间与原始文件映射重叠")
        if(original.none{it.executable && image.entryRva in it.rva..<it.rva+it.rawSize})fail("原系统入口指向待移除 KBL 空间")

        var extent=maxOf(image.headersSize,original.maxOf{it.raw+it.rawSize})
        if(optionalSize>=152 && bytes.u32(optional+108)>4) {
            val certificate=bytes.u32(optional+144);val size=bytes.u32(optional+148)
            if(size>0) {
                if(certificate+size>own.first().raw)fail("证书边界侵入 KBL 空间")
                extent=maxOf(extent,(certificate+size).asBoundedInt("原证书范围"))
            }
        }
        if(extent>own.first().raw)fail("原文件有效长度超出 KBL 起点")
        val alignment=bytes.u32(optional+32)
        if(alignment==0L || alignment and (alignment-1)!=0L)fail("原 PE 节对齐无效")
        val minimumImage=align(original.maxOf{it.rva+maxOf(it.virtualSize,it.rawSize.toLong())},alignment)
        val codeDerived=bytes.u32(optional+4)-own[0].rawSize
        val dataDerived=bytes.u32(optional+8)-own.drop(1).sumOf{it.rawSize.toLong()}
        fun candidates(vararg values:Long)=values.filter{it in 0..0xffff_ffffL}.distinct()
        val codes=candidates(bytes.u32(raw+296),codeDerived)
        val initialized=candidates(bytes.u32(raw+300),dataDerived)
        val images=candidates(bytes.u32(raw+304),minimumImage,own.first().rva)
            .filter{it>=minimumImage && it<=own.first().rva && it%alignment==0L}
        val checksums=candidates(bytes.u32(raw+308),0)

        // A relocated adapter leaves the complete pre-append NT header intact.
        // Find it by original section layout, not the saved oldPe field alone.
        val retained=ArrayList<Int>()
        for(at in 0x40..headerLimit-headerBytes step 4) {
            if(at==active || bytes.word(at)!=0x4550 || bytes.u16(at+4)!=0xaa64 ||
                bytes.u16(at+6)!=count || bytes.u16(at+20)!=optionalSize || bytes.u16(at+24)!=0x20b)continue
            val table=at+24+optionalSize;val source=active+24+optionalSize
            if((0 until count*40).all{bytes[table+it]==bytes[source+it]})retained+=at
        }
        val savedPe=bytes.u32(raw+288)
        val positions=(listOf(active)+retained+listOfNotNull(savedPe.takeIf{it in 0x40L..Int.MAX_VALUE.toLong()}?.toInt()))
            .distinct().filter{it.toLong()+headerBytes<=headerLimit && (it==active || active+expandedBytes<=it)}

        // All original branches entering the disposable RX mapping are possible
        // hook sites. A damaged saved hook does not choose a different address.
        val hooks=original.asSequence().filter{it.executable}.flatMap{s->
            (s.rva until s.rva+s.rawSize step 4).asSequence().mapNotNull{pc->
                val word=image.word(pc)?:return@mapNotNull null
                val target=A64.bTarget(pc,word)?:A64.blTarget(pc,word)?:return@mapNotNull null
                pc.takeIf{target in own[0].rva..<own[0].rva+own[0].rawSize}
            }
        }.toList().distinct()
        if(hooks.isEmpty())fail("原 KBL 挂接分支未找到，保留输入供恢复")
        val oldTarget=bytes.u32(raw+144)
        val savedWord=bytes.word(raw+156)
        // The gate has a fallback B to the unchanged real FAIL BL, and a B to
        // that BL's continuation. This relationship proves the original edge
        // independently from metadata or driver bytes.
        val outsideTargets=(own[0].rva until own[0].rva+own[0].rawSize step 4).asSequence().mapNotNull{pc->
            image.word(pc)?.let{A64.bTarget(pc,it)}?.takeIf{target->original.any{s->s.executable && target in s.rva..<s.rva+s.rawSize}}
        }.toSet()
        val failCalls=outsideTargets.filter{target->target+4 in outsideTargets && image.word(target)?.let{A64.blTarget(target,it)}!=null}
        val originalHash=bytes.copyOfRange(raw+320,raw+352)
        val headerHash=bytes.copyOfRange(raw+352,raw+384)
        var exactImage:ByteArray?=null
        var weakImage:ByteArray?=null
        var weakHash:ByteArray?=null
        var ambiguousWeak=false
        var weakPriority=0
        val weakCandidates=LinkedHashMap<String,WeakCandidate>()
        val retainedCodes=(codes+retained.map{bytes.u32(it+28)}).distinct()
        val retainedData=(initialized+retained.map{bytes.u32(it+32)}).distinct()
        val retainedImages=(images+retained.map{bytes.u32(it+80)}).distinct()
            .filter{it>=minimumImage && it<=own.first().rva && it%alignment==0L}
        val retainedChecksums=(checksums+retained.map{bytes.u32(it+88)}).distinct()
        for(position in positions)for(code in retainedCodes)for(data in retainedData)for(sizeImage in retainedImages)for(checksum in retainedChecksums) {
            val header=bytes.copyOfRange(active,active+headerBytes)
            header[6]=count.toByte();header[7]=(count ushr 8).toByte()
            header.putWord(28,code.toInt());header.putWord(32,data.toInt())
            header.putWord(80,sizeImage.toInt());header.putWord(88,checksum.toInt())
            val headerEdits=if(position!=active) listOf(Edit(active,ByteArray(expandedBytes)),
                Edit(position,header),Edit(0x3c,ByteArray(4).also{it.putWord(0,position)}))
            else listOf(Edit(active,header),Edit(active+headerBytes,ByteArray(120)))
            val matchingHeader=hash(header).contentEquals(headerHash)
            val matchingRetained=position in retained && header.contentEquals(bytes.copyOfRange(position,position+headerBytes))
            for(hook in hooks) {
                val offset=image.rvaToOffset(hook,4)?:continue
                val words=(listOfNotNull(savedWord.takeIf{hook==oldTarget})+failCalls.mapNotNull{
                    try{A64.branch(hook,it)}catch(_:PatchException){null}
                }).distinct()
                for(word in words) {
                    val edits=(headerEdits+Edit(offset,ByteArray(4).also{it.putWord(0,word)})).sortedBy{it.offset}
                    val currentHash=digestCandidate(bytes,extent,edits)
                    val exact=currentHash.contentEquals(originalHash)
                    val target=A64.bTarget(hook,word)
                    val gateProof=target!=null && target in failCalls && hooks.size==1
                    if(!exact && (exactImage!=null || !((matchingHeader || matchingRetained) && gateProof)))continue
                    if(exact) {
                        val restored=materialize(bytes,extent,edits)
                        val parsed=try{PeImage.parse(restored)}catch(_:PatchException){continue}
                        if(parsed.sections==original && parsed.entryRva==image.entryRva && parsed.imageBase==image.imageBase)exactImage=restored
                    }else {
                        val candidate=WeakCandidate(currentHash,edits,hook,word,if(matchingRetained)2 else 1)
                        val key=currentHash.hex()
                        if((weakCandidates[key]?.priority?:0)<candidate.priority)weakCandidates[key]=candidate
                    }
                }
            }
        }
        // Complete original-file evidence wins before considering any weaker
        // candidate. A stale saved oldPe must not outvote an exact SHA match.
        if(exactImage==null)for(candidate in weakCandidates.values) {
            if(candidate.priority<weakPriority)continue
            val restored=materialize(bytes,extent,candidate.edits)
            val parsed=try{PeImage.parse(restored)}catch(_:PatchException){continue}
            if(parsed.sections!=original || parsed.entryRva!=image.entryRva || parsed.imageBase!=image.imageBase)continue
            // Disposable gate bytes cannot identify the native FAIL interface.
            // Reprove the dispatcher/function data flow on the restored base.
            if(!provesOriginalFailEdge(parsed,candidate.hook,candidate.word))continue
            if(candidate.priority>weakPriority)ambiguousWeak=false
            else if(weakHash!=null && !candidate.hash.contentEquals(weakHash))ambiguousWeak=true
            weakPriority=candidate.priority
            weakImage=restored;weakHash=candidate.hash
        }
        if(exactImage==null && ambiguousWeak)fail("KBL 原始头/挂接恢复存在多个有效候选")
        val result=exactImage?:weakImage?:fail("KBL 自有内容可重建，但原始头或挂接指令缺少一致证据（$priorFailure）")
        return KblPatcher.Removed(result,true,hash(result).contentEquals(originalHash),true)
    }

    private data class Edit(val offset:Int,val bytes:ByteArray)
    private data class WeakCandidate(val hash:ByteArray,val edits:List<Edit>,val hook:Long,val word:Int,val priority:Int)
    private fun materialize(bytes:ByteArray,extent:Int,edits:List<Edit>)=bytes.copyOf(extent).also { result->
        edits.forEach{it.bytes.copyInto(result,it.offset)}
    }
    /** Hash proposed small header/hook edits without allocating an entire PE
     * for every damaged metadata combination. Materialize only proved candidates. */
    private fun digestCandidate(bytes:ByteArray,extent:Int,edits:List<Edit>):ByteArray {
        val digest=MessageDigest.getInstance("SHA-256");var at=0
        for(edit in edits) {
            if(edit.offset<at || edit.offset.toLong()+edit.bytes.size>extent)fail("原头与挂接恢复区域重叠或越界")
            digest.update(bytes,at,edit.offset-at);digest.update(edit.bytes);at=edit.offset+edit.bytes.size
        }
        digest.update(bytes,at,extent-at);return digest.digest()
    }
    private fun provesOriginalFailEdge(image:PeImage,hook:Long,word:Int):Boolean = try {
        val plan=SuperUnlockPatcher.analyze(image,FlashingUnlockPatcher.analyze(image))
        if(plan==null)false else {
            val failCall=A64.bTarget(hook,word)
            if(failCall==null || image.word(failCall)?.let{A64.blTarget(failCall,it)}!=plan.fastbootFailRva)false
            else {
                val dispatch=KblDispatcherFacts.analyze(image,plan.nativeRegisterRva)
                val lazy=KblLazyEntryFacts.analyze(image,dispatch,plan.fastbootFailRva)
                lazy.hookRva==hook && lazy.originalWord==word && lazy.failCallRva==failCall
            }
        }
    }catch(_:PatchException){false}

    private fun align(value:Long,alignment:Long)=(value+alignment-1)/alignment*alignment
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun fail(message:String):Nothing=throw PatchException("KBL 重建：$message；原始输入保持不变。")
}
