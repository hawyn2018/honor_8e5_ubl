package dev.honor.bootforge

/** User-supplied integration identifiers; each option has its own adapter. */
enum class RootChain(val id: String) {
    CVE_2026_98260("98260"),
    CVE_2026_64650("64650");

    val integrationText: String get() = "已接入magicroot（cve-2026-$id）提权链"

    companion object {
        val DEFAULT = CVE_2026_98260
    }
}
