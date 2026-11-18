package com.moodlepulse.app.calendar

import com.moodlepulse.app.model.AssignmentItem
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object IcsExporter {

    private val icsUtcFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun buildIcsContent(
        assignments: List<AssignmentItem>,
        siteName: String = "MoodlePulse"
    ): String {
        val nowStamp = icsUtcFormat.format(Instant.now())
        val sb = StringBuilder()
        sb.appendLine("BEGIN:VCALENDAR")
        sb.appendLine("VERSION:2.0")
        sb.appendLine("PRODID:-//MoodlePulse//Course Deadlines//ZH-TW")
        sb.appendLine("CALSCALE:GREGORIAN")
        sb.appendLine("METHOD:PUBLISH")
        sb.appendLine("X-WR-CALNAME:${escapeIcs("$siteName 作業期限")}")

        for (item in assignments.sortedBy { it.dueAtSec }) {
            if (item.dueAtSec <= 0L) continue
            val endInstant = Instant.ofEpochSecond(item.dueAtSec)
            val startInstant = Instant.ofEpochSecond((item.dueAtSec - 1800L).coerceAtLeast(0L))
            val prefix = if (item.submitted) "✅ " else "⏰ "
            val summary = escapeIcs("$prefix[${item.courseName}] ${item.title}")
            val descBuilder = StringBuilder()
            descBuilder.append("課程：${item.courseName}\\n")
            if (item.descriptionSummary.isNotBlank()) {
                descBuilder.append("說明：${item.descriptionSummary.take(240)}\\n")
            }
            if (item.url.isNotBlank()) {
                descBuilder.append("Moodle 連結：${item.url}\\n")
            }
            descBuilder.append("[MoodlePulse:${item.key}]")

            sb.appendLine("BEGIN:VEVENT")
            sb.appendLine("UID:${escapeIcs(item.key)}@moodlepulse.app")
            sb.appendLine("DTSTAMP:$nowStamp")
            sb.appendLine("DTSTART:${icsUtcFormat.format(startInstant)}")
            sb.appendLine("DTEND:${icsUtcFormat.format(endInstant)}")
            sb.appendLine("SUMMARY:$summary")
            sb.appendLine("DESCRIPTION:${escapeIcs(descBuilder.toString())}")
            if (item.url.isNotBlank()) {
                sb.appendLine("URL:${item.url}")
            }
            sb.appendLine("BEGIN:VALARM")
            sb.appendLine("TRIGGER:-P1D")
            sb.appendLine("ACTION:DISPLAY")
            sb.appendLine("DESCRIPTION:Moodle 作業明天截止")
            sb.appendLine("END:VALARM")
            sb.appendLine("END:VEVENT")
        }

        sb.appendLine("END:VCALENDAR")
        return sb.toString()
    }

    private fun escapeIcs(raw: String): String {
        return raw
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
    }
}
