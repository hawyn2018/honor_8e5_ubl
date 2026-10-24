package dev.honor.bootforge.wireless

/** Tracks service lifetimes independently of asynchronous Android NSD callbacks. */
internal class AdbDiscoveryPorts {
    data class Ticket(val session: Long, val kind: String, val key: String, val revision: Long)
    data class Change(val kind: String, val port: Int?)
    private data class Entry(val ticket: Ticket, var port: Int? = null)

    private var session = 0L
    private var revision = 0L
    private val entries = mutableMapOf<String, Entry>()
    private val selected = mutableMapOf<String, Int>()

    fun reset(session: Long) {
        this.session = session
        entries.clear()
        selected.clear()
    }

    fun found(kind: String, key: String, session: Long): Ticket? {
        if (session != this.session || entries.containsKey(key)) return null
        val ticket = Ticket(session, kind, key, ++revision)
        entries[key] = Entry(ticket)
        return ticket
    }

    fun isCurrent(ticket: Ticket) = session == ticket.session && entries[ticket.key]?.ticket == ticket

    fun resolved(ticket: Ticket, port: Int, local: Boolean): Change? {
        if (!isCurrent(ticket)) return null
        entries.getValue(ticket.key).port = port.takeIf { local && it in 1..65535 }
        return select(ticket.kind)
    }

    fun lost(key: String, session: Long): Change? {
        if (session != this.session) return null
        val removed = entries.remove(key) ?: return null
        return select(removed.ticket.kind)
    }

    /** Resolution failed: release the name so another discovery can try again. */
    fun failed(ticket: Ticket): Change? {
        if (!isCurrent(ticket)) return null
        entries.remove(ticket.key)
        return select(ticket.kind)
    }

    /** A continuous subscription lost its endpoint but can publish it again. */
    fun unavailable(ticket: Ticket): Change? {
        if (!isCurrent(ticket)) return null
        entries.getValue(ticket.key).port = null
        return select(ticket.kind)
    }

    fun candidates(kind: String): List<Int> = entries.values.asSequence()
        .filter { it.ticket.kind == kind && it.port != null }
        .sortedByDescending { it.ticket.revision }
        .mapNotNull { it.port }
        .distinct()
        .toList()

    private fun select(kind: String): Change? {
        // Discovery order identifies the newest pairing window. Resolution order
        // does not: a slower old callback must never replace a newer endpoint.
        val port = entries.values.asSequence()
            .filter { it.ticket.kind == kind && it.port != null }
            .maxByOrNull { it.ticket.revision }?.port
        val previous = selected[kind]
        if (port == null) selected.remove(kind) else selected[kind] = port
        return if (previous != port) Change(kind, port) else null
    }
}
