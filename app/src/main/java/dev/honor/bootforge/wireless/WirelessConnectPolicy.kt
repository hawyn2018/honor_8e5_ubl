package dev.honor.bootforge.wireless

/** Rotates advertised local endpoints without assuming discovery order means freshness. */
internal class WirelessConnectPolicy(startedAt: Long, timeoutMillis: Long = WAIT_MILLIS) {
    private val deadline = startedAt + timeoutMillis
    private var lastRefresh = startedAt
    private var sequence = 0L
    private val lastAttempt = mutableMapOf<Int, Long>()

    fun remaining(now: Long): Long = (deadline - now).coerceAtLeast(0)

    fun refreshDue(now: Long): Boolean {
        if (remaining(now) == 0L || now - lastRefresh < REFRESH_MILLIS) return false
        lastRefresh = now
        return true
    }

    fun select(candidates: List<Int>): Int? = candidates.asSequence()
        .filter { it in 1..65535 }
        .distinct()
        .minByOrNull { lastAttempt[it] ?: Long.MIN_VALUE }
        ?.also { lastAttempt[it] = ++sequence }

    companion object {
        const val WAIT_MILLIS = 45_000L
        private const val REFRESH_MILLIS = 8_000L

        fun candidates(current: String, manual: Boolean, discovered: List<Int>, afterPairing: Boolean): List<Int> {
            if (manual) return listOfNotNull(WirelessPortPolicy.port(current))
            val ports = discovered.filter { it in 1..65535 }.distinct()
            // A saved automatic hint belongs to an older session. Pairing only uses
            // ports resolved for this discovery; explicit manual input stays in control.
            return if (ports.isNotEmpty() || afterPairing) ports
                else listOfNotNull(WirelessPortPolicy.port(current))
        }
    }
}
