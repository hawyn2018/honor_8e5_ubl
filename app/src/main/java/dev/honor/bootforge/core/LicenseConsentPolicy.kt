package dev.honor.bootforge.core

/** Records acknowledgment of the license, independently of application versions. */
internal class LicenseConsentPolicy(
    readLicenseId: () -> String?,
    private val persistLicenseId: (String?) -> Boolean,
) {
    companion object {
        const val LICENSE_ID = "AGPL-3.0"
        const val PREFERENCES = "license_consent"
        const val ACCEPTED_ID = "accepted_license_id"
    }

    private val previousLicenseId = runCatching { readLicenseId() }.getOrNull()

    @Volatile
    var accepted: Boolean = previousLicenseId == LICENSE_ID
        private set

    /** A failed disk write must never grant access, even if preferences changed in memory. */
    @Synchronized
    fun accept(): Boolean {
        if (accepted) return true
        if (!runCatching { persistLicenseId(LICENSE_ID) }.getOrDefault(false)) {
            // SharedPreferences updates its process-local map before commit
            // returns. Restore that map too, so a new Activity cannot accept a
            // failed write merely by constructing another model in this process.
            runCatching { persistLicenseId(previousLicenseId) }
            return false
        }
        accepted = true
        return true
    }
}
