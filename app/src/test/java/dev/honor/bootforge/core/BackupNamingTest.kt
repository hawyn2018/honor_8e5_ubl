package dev.honor.bootforge.core

import java.time.Instant
import java.util.Date
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupNamingTest {
    private val now = Date.from(Instant.parse("2026-10-23T16:05:06.789Z"))

    @Test fun nameUsesRealCalendarValuesAndTwentyFourHourTime() {
        assertEquals("ublbackup_20261023_160506", BackupNaming.folderName(now, TimeZone.getTimeZone("UTC")))
        assertEquals("ublbackup_20261024_000506", BackupNaming.folderName(now, TimeZone.getTimeZone("Asia/Shanghai")))
        val example = Date.from(Instant.parse("2026-10-24T04:17:05Z"))
        assertEquals("ublbackup_20261024_121705", BackupNaming.folderName(example, TimeZone.getTimeZone("Asia/Shanghai")))
    }

    @Test fun defaultTimezoneComesFromTheRunningDevice() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+09:00"))
            assertEquals("ublbackup_20261024_010506", BackupNaming.folderName(now))
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
