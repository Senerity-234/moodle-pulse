package com.moodlepulse.app.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.moodlepulse.app.model.AppSettings
import com.moodlepulse.app.model.AssignmentItem
import com.moodlepulse.app.model.CalendarSyncRecord
import com.moodlepulse.app.model.DeviceCalendarInfo
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.TimeZone

data class CalendarSyncOutcome(
    val syncedCount: Int,
    val createdCount: Int,
    val updatedCount: Int,
    val updatedRecords: List<CalendarSyncRecord>,
    val resolvedCalendarId: Long,
    val resolvedCalendarName: String,
    val skippedReason: String? = null
)

class CalendarSyncManager(private val context: Context) {

    fun hasCalendarPermissions(): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }

    fun listWritableCalendars(): List<DeviceCalendarInfo> {
        if (!hasCalendarPermissions()) return emptyList()

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.IS_PRIMARY
        )

        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

        val calendars = mutableListOf<DeviceCalendarInfo>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                val primaryIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val displayName = cursor.getString(nameIdx).orEmpty().ifBlank { "行事曆 #$id" }
                    val accountName = cursor.getString(accountIdx).orEmpty()
                    val isPrimary = cursor.getInt(primaryIdx) == 1
                    calendars += DeviceCalendarInfo(
                        id = id,
                        displayName = displayName,
                        accountName = accountName,
                        isPrimary = isPrimary
                    )
                }
            }
        }
        return calendars
    }

    fun syncAssignmentsToCalendar(
        assignments: List<AssignmentItem>,
        existingRecords: List<CalendarSyncRecord>,
        settings: AppSettings
    ): CalendarSyncOutcome {
        if (!settings.autoCalendarSyncEnabled) {
            return CalendarSyncOutcome(0, 0, 0, existingRecords, settings.targetCalendarId, settings.targetCalendarName, "已停用自動日曆同步")
        }
        if (!hasCalendarPermissions()) {
            return CalendarSyncOutcome(0, 0, 0, existingRecords, settings.targetCalendarId, settings.targetCalendarName, "尚未授予日曆讀寫權限")
        }

        val writableCalendars = listWritableCalendars()
        if (writableCalendars.isEmpty()) {
            return CalendarSyncOutcome(0, 0, 0, existingRecords, -1L, "", "手機上找不到可寫入的行事曆帳號")
        }

        val selectedCalendar = writableCalendars.firstOrNull { it.id == settings.targetCalendarId }
            ?: writableCalendars.firstOrNull { it.isPrimary && it.accountName.contains("@") }
            ?: writableCalendars.firstOrNull { it.isPrimary }
            ?: writableCalendars.first()

        val nowSec = System.currentTimeMillis() / 1000L
        val recordMap = existingRecords.associateBy { it.assignmentKey }.toMutableMap()
        val timeZoneId = TimeZone.getDefault().id

        var created = 0
        var updated = 0

        val candidates = assignments.filter { item ->
            item.dueAtSec > (nowSec - 7L * 86400L) &&
                (settings.includeSubmittedInCalendar || !item.submitted)
        }

        for (item in candidates) {
            val eventTitle = buildEventTitle(item)
            val eventDescription = buildEventDescription(item)
            val startMillis = (item.dueAtSec - 1800L).coerceAtLeast(0L) * 1000L
            val endMillis = item.dueAtSec * 1000L

            val existing = recordMap[item.key]
            val existingEventId = when {
                existing != null && existing.calendarId == selectedCalendar.id && eventExists(existing.eventId) ->
                    existing.eventId
                else -> findEventIdByMarker(selectedCalendar.id, item.key)
            }

            if (existingEventId != null) {
                val needsUpdate = existing == null ||
                    existing.syncedDueAtSec != item.dueAtSec ||
                    existing.syncedTitle != eventTitle

                if (needsUpdate) {
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.TITLE, eventTitle)
                        put(CalendarContract.Events.DESCRIPTION, eventDescription)
                        put(CalendarContract.Events.DTSTART, startMillis)
                        put(CalendarContract.Events.DTEND, endMillis)
                        put(CalendarContract.Events.EVENT_TIMEZONE, timeZoneId)
                    }
                    val updateUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingEventId)
                    context.contentResolver.update(updateUri, values, null, null)
                    updated++
                }

                recordMap[item.key] = CalendarSyncRecord(
                    assignmentKey = item.key,
                    calendarId = selectedCalendar.id,
                    eventId = existingEventId,
                    syncedDueAtSec = item.dueAtSec,
                    syncedTitle = eventTitle
                )
            } else {
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, selectedCalendar.id)
                    put(CalendarContract.Events.TITLE, eventTitle)
                    put(CalendarContract.Events.DESCRIPTION, eventDescription)
                    put(CalendarContract.Events.DTSTART, startMillis)
                    put(CalendarContract.Events.DTEND, endMillis)
                    put(CalendarContract.Events.EVENT_TIMEZONE, timeZoneId)
                    put(CalendarContract.Events.HAS_ALARM, 1)
                    if (item.url.isNotBlank()) {
                        put(CalendarContract.Events.CUSTOM_APP_URI, item.url)
                    }
                }

                val insertedUri = runCatching {
                    context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                }.getOrNull()

                val newEventId = insertedUri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() }
                if (newEventId != null && newEventId > 0L) {
                    insertReminder(newEventId, settings.reminderMinutesBefore)
                    if (settings.secondReminderMinutesBefore in 1 until settings.reminderMinutesBefore) {
                        insertReminder(newEventId, settings.secondReminderMinutesBefore)
                    }
                    created++
                    recordMap[item.key] = CalendarSyncRecord(
                        assignmentKey = item.key,
                        calendarId = selectedCalendar.id,
                        eventId = newEventId,
                        syncedDueAtSec = item.dueAtSec,
                        syncedTitle = eventTitle
                    )
                }
            }
        }

        return CalendarSyncOutcome(
            syncedCount = candidates.size,
            createdCount = created,
            updatedCount = updated,
            updatedRecords = recordMap.values.toList(),
            resolvedCalendarId = selectedCalendar.id,
            resolvedCalendarName = "${selectedCalendar.displayName} (${selectedCalendar.accountName})"
        )
    }

    fun writeIcsFileToCache(assignments: List<AssignmentItem>, siteName: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "moodle_deadlines.ics")
        val content = IcsExporter.buildIcsContent(assignments, siteName.ifBlank { "Moodle" })
        file.writeText(content, StandardCharsets.UTF_8)
        return file
    }

    private fun buildEventTitle(item: AssignmentItem): String {
        val statusPrefix = if (item.submitted) "✅ " else "⏰ "
        val shortCourse = item.courseName.take(18)
        return "$statusPrefix[$shortCourse] ${item.title}"
    }

    private fun buildEventDescription(item: AssignmentItem): String {
        return buildString {
            appendLine("課程：${item.courseName}")
            if (item.submissionStatus.isNotBlank()) {
                appendLine("狀態：${if (item.submitted) "已繳交" else "未繳交 (${item.submissionStatus})"}")
            }
            if (item.descriptionSummary.isNotBlank()) {
                appendLine("摘要：${item.descriptionSummary.take(260)}")
            }
            if (item.url.isNotBlank()) {
                appendLine("Moodle 連結：${item.url}")
            }
            append("[MoodlePulse:${item.key}]")
        }
    }

    private fun eventExists(eventId: Long): Boolean {
        val projection = arrayOf(CalendarContract.Events._ID)
        val selection = "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.DELETED} = 0"
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                arrayOf(eventId.toString()),
                null
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    private fun findEventIdByMarker(calendarId: Long, assignmentKey: String): Long? {
        val projection = arrayOf(CalendarContract.Events._ID)
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0 AND ${CalendarContract.Events.DESCRIPTION} LIKE ?"
        val markerArg = "%[MoodlePulse:$assignmentKey]%"
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                arrayOf(calendarId.toString(), markerArg),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }.getOrNull()
    }

    private fun insertReminder(eventId: Long, minutesBefore: Int) {
        if (minutesBefore <= 0) return
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        runCatching {
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
        }
    }
}
