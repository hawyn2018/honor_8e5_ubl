package dev.honor.bootforge.wireless

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class WirelessUiState(
    val host: String = "127.0.0.1",
    val pairingPort: String = "",
    val connectPort: String = "",
    val pairingCode: String = "",
    val pairingPortManual: Boolean = false,
    val connectPortManual: Boolean = false,
    val paired: Boolean = false,
    val waiting: Boolean = false,
    val busy: Boolean = false,
    val shellReady: Boolean = false,
    val stage: String = "通过无线调试配对，获取本应用的 Shell 权限",
    val discovery: String = "等待发现无线调试服务",
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

object WirelessState {
    var state by mutableStateOf(WirelessUiState())
    internal var serviceOwner: Any? = null
    fun append(message: String) {
        state = state.copy(logs = state.logs + message.lines())
    }
}
