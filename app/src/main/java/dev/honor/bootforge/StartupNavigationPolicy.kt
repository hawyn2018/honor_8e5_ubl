package dev.honor.bootforge

enum class StartupDestination { PRIVILEGE, OTA }

/** Resolve a fresh launch once; later lifecycle resumes must preserve the user's page. */
object StartupNavigationPolicy {
    fun destination(externalRootGranted: Boolean, explicitRoot: Boolean = false): StartupDestination =
        if (externalRootGranted && !explicitRoot) StartupDestination.OTA else StartupDestination.PRIVILEGE
}
