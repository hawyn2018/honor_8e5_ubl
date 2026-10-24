package dev.honor.bootforge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class BackupFileInfo(val name: String, val bytes: Long, val sha256: String)
data class BackupDiskStatus(val node: String, val status: String, val detail: String = "")

data class BackupUiState(
    val busy: Boolean = false,
    val stage: String = "尚未开始备份",
    val slot: String = "",
    val outputDirectory: String = "",
    val files: List<BackupFileInfo> = emptyList(),
    val error: String? = null,
    val progress: Float = 0f,
    val status: String = "idle",
    val warnings: List<String> = emptyList(),
    val diskStatuses: List<BackupDiskStatus> = emptyList(),
)

object BackupState {
    var state by mutableStateOf(BackupUiState())
}
