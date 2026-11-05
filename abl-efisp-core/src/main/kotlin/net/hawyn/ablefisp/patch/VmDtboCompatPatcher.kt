package net.hawyn.ablefisp.patch

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Independent, reversible pre-handoff selection layer. It does not patch the
 * authenticated partition reader, VM launch, reset service or kernel. */
internal object VmDtboCompatPatcher {
    private val magic="HNVMSEL1".toByteArray(Charsets.US_ASCII)
    data class Result(val bytes:ByteArray,val patches:List<PatchMatch>,val notes:List<String>,val facts:VmDtboCompatFacts.Facts?=null)
    fun remove(input:ByteArray):ByteArray {
        val image=PeImage.parse(input);val section=image.sections.last()
        if(section.name!=".textvm")return input
        val end=section.raw+section.rawSize
        if(end>input.size||section.rawSize<16||!input.copyOfRange(end-8,end).contentEquals(magic))
            throw PatchException("VM DTBO 自有恢复记录缺失。")
        val length=input.word(end-12)
        if(length<84||length>section.rawSize-12)throw PatchException("VM DTBO 自有恢复记录长度无效。")
        val payload=input.copyOfRange(end-12-length,end-12)
        val stream=DataInputStream(payload.inputStream())
        if(stream.readInt()!=1)throw PatchException("VM DTBO 自有恢复记录版本无效。")
        val originalLength=stream.readInt();val headerLength=stream.readInt();val hookOffset=stream.readInt()
        val originalWord=stream.readInt();val originalSha=ByteArray(32).also(stream::readFully)
        val moduleSha=ByteArray(32).also(stream::readFully)
        if(originalLength !in image.headersSize..section.raw||headerLength!=image.headersSize||hookOffset !in headerLength..originalLength-4||stream.available()!=headerLength)
            throw PatchException("VM DTBO 自有恢复记录范围无效。")
        val moduleEnd=end-12-length
        if(input.copyOfRange(section.raw,moduleEnd).sha256()!=moduleSha.hex(false))throw PatchException("VM DTBO 自有模块摘要不一致。")
        val restored=input.copyOf(originalLength);stream.readFully(restored,0,headerLength);restored.putWord(hookOffset,originalWord)
        if(restored.sha256()!=originalSha.hex(false))throw PatchException("VM DTBO 原生映射回读摘要不一致。")
        PeImage.parse(restored);return restored
    }
    fun patch(input:ByteArray):Result {
        val original=remove(input);val image=PeImage.parse(original)
        val facts=try { VmDtboCompatFacts.analyze(image) }catch(e:PatchException) {
            return Result(original,emptyList(),listOf("VM DTBO 启动前兼容接口未完整匹配，保留原生逻辑：${e.message}"))
        }
        val module=VmDtboCompatModule.bytes
        check(module.sha256()==VmDtboCompatModule.SHA256)
        val reserve=module.size+1024+image.headersSize+128
        val headerSpace=try { VmDtboHeaderSpace.prepare(image) }
        catch(e:PatchException) { return Result(original,emptyList(),listOf("VM DTBO 节表空间未完整证明，保留原生逻辑：${e.message}")) }
        val mapping=try { PeCodeSectionAppender.append(headerSpace.image,reserve,listOf(facts.hook,facts.printLoad,facts.boardGetter,facts.logger)) }
        catch(e:PatchException) { return Result(original,emptyList(),listOf("VM DTBO 独立RX空间未建立，保留原生逻辑：${e.message}")) }
        val output=mapping.bytes.copyOf();val expanded=PeImage.parse(output);val section=expanded.sections.last()
        val header=output.u32(0x3c).toInt()+24+output.u16(output.u32(0x3c).toInt()+20)+(expanded.sections.size-1)*40
        output.fill(0,header,header+8);".textvm".toByteArray(Charsets.US_ASCII).copyInto(output,header)
        val rx=mapping.newSectionRva;val code=Code(rx,module)
        code.align();val thunk=code.pc
        code.save()
        code.branch(facts.boardGetter,true)
        code.emit(0xb90107e0.toInt()) // retain actual board variant at frame+260
        code.emit(0x2a0003e2) // w2 = actual native board variant
        code.load64FromFrame(0,Code.FRAME+facts.tableStack)
        code.load32FromFrame(1,Code.FRAME+facts.lengthStack)
        code.address(3,facts.revisionAddress);code.emit(0xb9400063.toInt())
        code.emit(0xb9010be3.toInt()) // retain native board second cell at frame+264
        code.address(4,facts.indexAddress)
        val domainsAddressPatch=code.pc;code.emit(0xd503201f.toInt());code.emit(0xd503201f.toInt())
        code.emit(0x52800026) // normal-after-keys, no global entry/init scan
        code.branch(rx+VmDtboCompatModule.ENTRY_OFFSET,true)
        code.emit(0xb90103e0.toInt()) // status at frame+256
        code.emit(0x2a0003e2) // log status
        code.emit(0xb94107e3.toInt());code.emit(0xb9410be4.toInt())
        code.emit(0x52800800) // native diagnostic severity 0x40
        val logAddressPatch=code.pc;code.emit(0xd503201f.toInt());code.emit(0xd503201f.toInt())
        code.branch(facts.logger,true)
        code.restore()
        // Native selection and all fallback effects have already executed.
        // Replay its original pair LDP exactly once for every selector result.
        code.replay(image,facts.hook);code.branch(facts.hook+4)
        code.align();val domains=code.pc;facts.domains.forEach(code::emit)
        val message=code.pc;code.data("VM DTBO pre-handoff status=%d board=%x board-cell1=%x (0=kept,1=matched,<0=native)\n\u0000".toByteArray(Charsets.US_ASCII))
        code.addressAt(domainsAddressPatch,5,domains);code.addressAt(logAddressPatch,1,message)
        val codeBytes=code.bytes()
        if(codeBytes.size>section.rawSize-image.headersSize-128)throw PatchException("VM DTBO RX容量回读失败。")
        codeBytes.copyInto(output,section.raw)
        val hookOffset=requireNotNull(image.rvaToOffset(facts.hook,4));output.putWord(hookOffset,A64.branch(facts.hook,thunk))
        val end=section.raw+section.rawSize
        // Hash includes all module code and zero padding, excluding the receipt.
        val receiptBytes=84+image.headersSize
        val receiptAt=end-12-receiptBytes
        val payload=ByteArrayOutputStream().also { buffer->DataOutputStream(buffer).use { s->
            s.writeInt(1);s.writeInt(original.size);s.writeInt(image.headersSize);s.writeInt(hookOffset)
            s.writeInt(requireNotNull(image.word(facts.hook)))
            s.write(original.sha256().chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            s.write(output.copyOfRange(section.raw,receiptAt).sha256().chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            s.write(original,0,image.headersSize)
        } }.toByteArray()
        check(payload.size==receiptBytes);payload.copyInto(output,receiptAt);output.putWord(end-12,payload.size);magic.copyInto(output,end-8)
        check(remove(output).contentEquals(original))
        // Report exact changed header runs, including optional NT relocation;
        // no byte outside this header block or the native hook is edited.
        val headerMatches=arrayListOf<PatchMatch>();var at=0
        while(at<image.headersSize) {
            if(original[at]==output[at]) { at++;continue }
            val start=at;while(at<image.headersSize&&original[at]!=output[at])at++
            headerMatches+=PatchMatch("VM DTBO：PE映射元数据",start.toLong(),start,
                original.copyOfRange(start,at).hex(),output.copyOfRange(start,at).hex(),false)
        }
        val matches=headerMatches+PatchMatch("VM DTBO：正常BootLinux启动前身份选择",facts.hook,hookOffset,
            original.copyOfRange(hookOffset,hookOffset+4).hex(),output.copyOfRange(hookOffset,hookOffset+4).hex(),false)
        return Result(output,matches,listOf(
            "已按原生读盘长度、DTBO循环、VM索引全局和启动参数消费者安装启动前兼容接口。",
            "仅原生BootLinux路径解析已读DTBO；Fastboot命令循环不执行此接口、不新增DTBO读取。",
            "精确硬件板型优先；旧DTBO无此硬件tuple时验证原生角色/安全域/host VMID，以其实际DTBO板型tuple为锚唯一补齐互补角色；冲突、无锚或多候选保留原生配对。",
            "畸形、歧义或无匹配时返回原生处理并输出诊断；只在交给内核之前选择，不截获内核HTVM panic。"
        )+(if(headerSpace.relocated)listOf("文字段已使用原节表空位；在现有SizeOfHeaders内动态定位完整全零且无目录/符号占用的空间，移动NT头后追加VM独立RX；原节数据/RVA/raw偏移不动，恢复记录保存完整旧头。")else emptyList())+facts.evidence,facts)
    }

    private class Code(val base:Long,module:ByteArray) {
        private val out=ByteArrayOutputStream().apply { write(module) }
        val pc get()=base+out.size()
        fun bytes()=out.toByteArray()
        fun emit(w:Int) { out.write(w);out.write(w ushr 8);out.write(w ushr 16);out.write(w ushr 24) }
        fun data(b:ByteArray)=out.write(b)
        fun align(){while(out.size()%16!=0)out.write(0)}
        fun replace(at:Long,w:Int) { val b=bytes();b.putWord((at-base).toInt(),w);out.reset();out.write(b) }
        fun branch(to:Long,link:Boolean=false)=emit(A64.branch(pc,to) or if(link)0x80000000.toInt() else 0)
        fun addressAt(at:Long,reg:Int,to:Long) {
            val delta=(to and -4096L)-(at and -4096L);val imm=delta shr 12
            require(imm in -0x100000L..<0x100000L)
            replace(at,0x90000000.toInt() or ((imm.toInt() and 3) shl 29) or (((imm.toInt() ushr 2) and 0x7ffff) shl 5) or reg)
            replace(at+4,0x91000000.toInt() or ((to.toInt() and 4095) shl 10) or (reg shl 5) or reg)
        }
        fun address(reg:Int,to:Long){val at=pc;emit(0);emit(0);addressAt(at,reg,to)}
        fun replay(image:PeImage,at:Long) {
            val w=requireNotNull(image.word(at));val page=A64.adrp(at,w)
            if(page!=null) {
                val delta=((page.second and -4096L)-(pc and -4096L)) shr 12
                require(delta in -0x100000L..<0x100000L)
                emit(0x90000000.toInt() or ((delta.toInt() and 3) shl 29) or (((delta.toInt() ushr 2) and 0x7ffff) shl 5) or page.first)
            }else emit(w)
        }
        fun load(reg:Int,offset:Int)=emit(0xf9400000.toInt() or ((offset/8) shl 10) or (31 shl 5) or reg)
        fun load64FromFrame(reg:Int,offset:Int) {
            require(offset>=0)
            if(offset%8==0&&offset/8<=4095)load(reg,offset)
            else {
                if(offset ushr 12>4095)throw PatchException("VM DTBO 原生table frame偏移超出ADD可编码范围。")
                emit(0x91400000.toInt() or ((offset ushr 12) shl 10) or (31 shl 5) or reg)
                emit(0x91000000.toInt() or ((offset and 4095) shl 10) or (reg shl 5) or reg)
                emit(0xf9400000.toInt() or (reg shl 5) or reg)
            }
        }
        fun load32FromFrame(reg:Int,offset:Int) {
            require(offset>=0)
            if(offset%4==0&&offset/4<=4095)emit(0xb9400000.toInt() or ((offset/4) shl 10) or (31 shl 5) or reg)
            else {
                // ISA immediate capacity, not a firmware/model size policy.
                if(offset ushr 12>4095)throw PatchException("VM DTBO 原生长度frame偏移超出两条ADD的可编码范围。")
                emit(0x91400000.toInt() or ((offset ushr 12) shl 10) or (31 shl 5) or reg)
                emit(0x91000000.toInt() or ((offset and 4095) shl 10) or (reg shl 5) or reg)
                emit(0xb9400000.toInt() or (reg shl 5) or reg)
            }
        }
        fun save(){
            emit(0xd10443ff.toInt()) // sub sp,sp,#272
            for(r in 0..28 step 2)emit(0xa9000000.toInt() or ((r/2*2) shl 15) or ((r+1) shl 10) or (31 shl 5) or r)
            emit(0xf9007bfe.toInt());emit(0xd53b4209.toInt());emit(0xf9007fe9.toInt())
        }
        fun restore(){
            load(9,248);emit(0xd51b4209.toInt())
            for(r in 0..28 step 2)emit(0xa9400000.toInt() or ((r/2*2) shl 15) or ((r+1) shl 10) or (31 shl 5) or r)
            load(30,240);emit(0x910443ff.toInt())
        }
        companion object { const val FRAME=272 }
    }
}
