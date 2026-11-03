package dev.honor.bootforge

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.honor.bootforge.core.LicenseConsentPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Retains an in-flight acknowledgment across Activity recreation. */
class LicenseConsentModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(LicenseConsentPolicy.PREFERENCES, 0)
    private val policy = LicenseConsentPolicy(
        readLicenseId = { preferences.getString(LicenseConsentPolicy.ACCEPTED_ID, null) },
        persistLicenseId = { id ->
            val editor = preferences.edit()
            if (id == null) editor.remove(LicenseConsentPolicy.ACCEPTED_ID)
            else editor.putString(LicenseConsentPolicy.ACCEPTED_ID, id)
            editor.commit()
        },
    )
    var accepted by mutableStateOf(policy.accepted)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set

    fun accept() {
        if (accepted || saving) return
        saving = true
        error = ""
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { policy.accept() }
                if (saved) accepted = true
                else error = "同意状态保存失败，请重试。"
            } finally {
                saving = false
            }
        }
    }
}
