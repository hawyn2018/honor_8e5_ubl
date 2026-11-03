package dev.honor.bootforge.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Formats the request's device time and timezone; defaults also resolve on the device. */
object BackupNaming {
    private val folderPattern = Regex("ublbackup_[0-9]{8}_[0-9]{6}")

    fun folderName(now: Date = Date(), timeZone: TimeZone = TimeZone.getDefault()): String =
        "ublbackup_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            this.timeZone = timeZone
        }.format(now)

    /** MediaStore paths and Android 9's filesystem destination share one validated layout. */
    fun relativePath(folder: String): String {
        require(folderPattern.matches(folder)) { "备份目录名称无效" }
        return "Download/hnubl/$folder/"
    }

    fun displayDirectory(folder: String): String = "内部存储/" + relativePath(folder).removeSuffix("/")
}
