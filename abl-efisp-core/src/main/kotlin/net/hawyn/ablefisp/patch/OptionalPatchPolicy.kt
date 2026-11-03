package net.hawyn.ablefisp.patch

/** Optional feature discovery is isolated from the required core and KBL. */
internal object OptionalPatchPolicy {
    fun <T> analyze(name: String, fallback: T, notes: MutableList<String>, discover: () -> T): T =
        try {
            discover()
        } catch (failure: PatchException) {
            notes += "已跳过可选功能「$name」：${failure.message.orEmpty()}；保留该项原有代码并继续其他修补。"
            fallback
        }
}
