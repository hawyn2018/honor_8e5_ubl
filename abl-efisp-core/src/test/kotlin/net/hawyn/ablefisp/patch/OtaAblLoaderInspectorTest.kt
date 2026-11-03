package net.hawyn.ablefisp.patch

import org.junit.Assert.*
import org.junit.Test

class OtaAblLoaderInspectorTest {
    @Test fun stringPresenceAndMalformedContainersDoNotProveLoader() {
        for (input in listOf(ByteArray(0), "efisp\u0000".toByteArray(Charsets.UTF_16LE), byteArrayOf(77, 90))) {
            val result = OtaAblLoaderInspector.inspect(input)
            assertFalse(result.loadsEfisp); assertTrue(result.reason.isNotEmpty())
        }
    }
}
