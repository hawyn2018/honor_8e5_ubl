package dev.honor.bootforge.core

/** Android shells require Unix line endings, including after a Windows source checkout. */
internal fun normalizeShellScript(text: String): String =
    text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
