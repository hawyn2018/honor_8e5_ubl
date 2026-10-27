package net.hawyn.ablefisp.text

data class TextEntry(
    val id: String,
    val title: String,
    val text: String,
    val rva: Long,
    val fileOffset: Int,
    val maxBytes: Int,
    val editable: Boolean = true,
    val reason: String? = null,
)

data class TextScanResult(val entries: List<TextEntry>, val notes: List<String>)

data class TextChange(
    val id: String,
    val title: String,
    val oldText: String,
    val newText: String,
    val oldRva: Long,
    val newRva: Long,
    val fileOffset: Int,
)

data class TextPatchResult(
    val output: ByteArray,
    val changes: List<TextChange>,
    val notes: List<String>,
)

class TextEditException(message: String) : IllegalArgumentException(message)
