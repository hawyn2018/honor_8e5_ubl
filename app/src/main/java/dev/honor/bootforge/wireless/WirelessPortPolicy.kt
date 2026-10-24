package dev.honor.bootforge.wireless

/** Keeps a user-entered endpoint independent of asynchronous mDNS updates. */
internal object WirelessPortPolicy {
    fun digits(value: String, limit: Int): String = buildString {
        for (character in value) {
            val digit = character.digitToIntOrNull() ?: continue
            append('0' + digit)
            if (length == limit) break
        }
    }

    fun port(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..65535 }

    fun discovered(current: String, manual: Boolean, discovered: Int?): String =
        if (manual && current.isNotBlank()) current
        else discovered?.takeIf { it in 1..65535 }?.toString().orEmpty()

    fun connectionPort(current: String, manual: Boolean, discovered: Int?, afterPairing: Boolean): Int? =
        if (manual) port(current)
        else discovered?.takeIf { it in 1..65535 } ?: if (afterPairing) null else port(current)

    fun pairingPort(current: String, manual: Boolean, discovered: Int?): Int? =
        if (manual) port(current) else discovered?.takeIf { it in 1..65535 }
}
