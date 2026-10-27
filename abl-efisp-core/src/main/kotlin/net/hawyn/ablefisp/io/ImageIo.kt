package net.hawyn.ablefisp.io

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Bounded file/pipe readers shared by the document import and root extraction flows. */
object ImageIo {
    const val MAX_INPUT_BYTES: Int = 64 * 1024 * 1024

    fun readBounded(input: InputStream, maxBytes: Int = MAX_INPUT_BYTES): ByteArray {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (count == 0) {
                val one = input.read()
                if (one < 0) return output.toByteArray()
                if (output.size() == maxBytes) throw AblReadException("输入超过 ${maxBytes / 1024 / 1024} MiB 限制")
                output.write(one)
            } else {
                if (count > maxBytes - output.size()) throw AblReadException("输入超过 ${maxBytes / 1024 / 1024} MiB 限制")
                output.write(buffer, 0, count)
            }
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

class AblReadException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
