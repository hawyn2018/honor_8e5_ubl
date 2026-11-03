package net.hawyn.ablefisp.patch

/** Adds the explicit-start KBL adapter and child-local boot recovery.
 * The old engine is deliberately preserved for independent regression checks. */
object KblPatchEngine {
    @JvmOverloads
    fun analyzeAndPatch(input:ByteArray, mode:PatchMode=PatchMode.FREEDOM,
                        driver:ByteArray=BundledKblDriver.load()):PatchResult {
        val snapshot=input.copyOf()
        val extracted=PatchEngine.extract(snapshot)
        // Ownership is nested: recovery receipts describe the KBL-bearing PE.
        // Restore that exact layer before reading KBL's own restoration receipt.
        val withoutRecovery=FastbootRecoveryPatcher.remove(extracted.output)
        val removed=KblPatcher.removeAdapterDetails(withoutRecovery)
        val compatible=ExecutableSectionCompatibility.normalize(removed.bytes)
        val base=PatchEngine.analyzeAndPatch(compatible.bytes,mode)
        val kbl=KblPatcher.patch(base.output,driver)
        val recovery=FastbootRecoveryPatcher.patch(kbl.bytes)
        val unchanged=recovery.bytes.contentEquals(extracted.output)
        val rva=(kbl.metadata["registrationExitRva"] as? Number)?.toLong()
        val kblMatches=if(rva==null)emptyList()else {
            val original=PeImage.parse(base.output);val patched=PeImage.parse(kbl.bytes)
            val before=requireNotNull(original.rvaToOffset(rva,4));val after=requireNotNull(patched.rvaToOffset(rva,4))
            listOf(PatchMatch("KBL 显式启动命令注册",rva,after,
                base.output.copyOfRange(before,before+4).hex(),kbl.bytes.copyOfRange(after,after+4).hex(),
                unchanged))
        }
        val matches=(compatible.changes+base.patches+kblMatches+recovery.patches).map {
            if(unchanged)it.copy(alreadyApplied=true)else it
        }
        return base.copy(output=recovery.bytes,sourceFormat=extracted.sourceFormat,
            sourceSha256=snapshot.sha256(),outputSha256=recovery.bytes.sha256(),imageSize=recovery.bytes.size,
            patches=matches,alreadyPatched=unchanged,
            notes=base.notes.filterNot { it=="输出保留原有 PE 节布局、入口与加载长度。" }+
                (if(removed.rebuiltFromDamage)listOf("旧 KBL 自有空间或恢复记录存在损坏；已交叉核对原 PE 头与原挂接指令，丢弃旧 KBL 并重新生成。")else emptyList())+listOf(
                "新增代码使用 .text 风格节名，兼容按节名设置执行权限的厂商 UEFI。",
                "已追加独立 KBL 驱动；kblstart 仅启动运行环境，kbladb 显式启动运行环境及 ADB；screenoff/screenon 仅显式控制显示，不启动环境。",
                "KBL 使用独立 RX/RW/R 映射；原节地址、文件偏移及系统入口保持，PE 加载长度同步扩展。",
                "普通系统启动和其他 Fastboot 命令不调度 KBL；kblin 重接、kbloff 显式销毁。",
                "自定义程序使用 ARM64 GPR-only KBL SDK，由 ./路径 调用，保持实际 EL。",
                "同 EL 原生代码需要协作 yield；错误指针或不让出执行的程序没有硬件隔离保障。",
            )+recovery.notes)
    }
}

/** Generated runtime resource is rebuilt from runtime/uefi/build_driver.py. */
object BundledKblDriver {
    fun load():ByteArray {
        val stream=BundledKblDriver::class.java.getResourceAsStream("/kbl/kbl_driver.efi")
            ?:throw PatchException("KBL 驱动资源缺失，请重新完整构建应用。")
        val bytes=stream.use { it.readBytes() }
        val expected=BundledKblDriver::class.java.getResourceAsStream("/kbl/kbl_driver.sha256")
            ?.bufferedReader(Charsets.US_ASCII)?.use { it.readText().trim() }
            ?:throw PatchException("KBL 驱动摘要资源缺失。")
        return validateResource(bytes,expected)
    }
    internal fun validateResource(bytes:ByteArray,expected:String):ByteArray {
        if(bytes.size<512)throw PatchException("KBL 驱动资源长度无效。")
        if(!expected.matches(Regex("[0-9a-f]{64}"))||bytes.sha256()!=expected)
            throw PatchException("KBL 驱动资源 SHA-256 不一致。")
        KblPatcher.validateDriver(bytes)
        return bytes
    }
}
