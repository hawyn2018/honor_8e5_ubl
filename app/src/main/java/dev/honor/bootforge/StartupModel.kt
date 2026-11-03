package dev.honor.bootforge

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.honor.bootforge.core.ExternalRootProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Kept in memory across configuration changes; a new process always rechecks authorization. */
class StartupModel(application: Application) : AndroidViewModel(application) {
    val sessionKey: String = UUID.randomUUID().toString()
    var destination by mutableStateOf<StartupDestination?>(null)
        private set
    private var started = false
    private var checking: Job? = null

    fun start(explicitRoot: Boolean) {
        if (explicitRoot) { openPrivilege(); return }
        if (started) return
        started = true
        checking = viewModelScope.launch {
            val granted = try {
                withContext(Dispatchers.IO) {
                    ExternalRootProbe.check(File(getApplication<Application>().filesDir, "startup-external-root.log")).granted
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            if (destination == null) {
                if (granted) RunState.state = RunState.state.copy(rootStatus = "已提权")
                destination = StartupNavigationPolicy.destination(granted)
            }
        }
    }

    fun openPrivilege() {
        started = true
        checking?.cancel()
        destination = StartupNavigationPolicy.destination(externalRootGranted = false, explicitRoot = true)
    }

    /** Explicit UI navigation only; it neither starts nor authorizes an OTA transaction. */
    fun openOta() {
        started = true
        checking?.cancel()
        destination = StartupDestination.OTA
    }
}
