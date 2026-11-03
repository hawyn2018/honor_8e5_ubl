package net.hawyn.ablefisp.patch

/** Semantic proof, independent of model names, offsets and the state-patch prerequisites. */
object OtaAblLoaderInspector {
    data class Result(val loadsEfisp: Boolean, val reason: String)
    fun inspect(input: ByteArray): Result = try {
        val pe = PeImage.parse(FirmwareExtractor.extract(input.copyOf()).output)
        val found = SemanticPatcher.hasEfispLoader(pe)
        Result(found, if (found) "唯一 efisp 参数引用、分区查找/读取成功路径及 EFI LoadImage 调用已形成完整证据"
            else "未形成唯一完整的 efisp 加载语义；使用内置父 ABL")
    } catch (e: Exception) { Result(false, "当前 ABL 加载语义分析未完成：${e.message.orEmpty()}；使用内置父 ABL") }
}
