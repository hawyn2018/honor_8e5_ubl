package dev.honor.bootforge.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Formats the request's device time and timezone; defaults also resolve on the device. */
object BackupNaming {
    fun folderName(now: Date = Date(), timeZone: TimeZone = TimeZone.getDefault()): String =
        "ublbackup_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            this.timeZone = timeZone
        }.format(now)
}
