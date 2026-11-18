package com.moodlepulse.app.calendar

import com.moodlepulse.app.model.AssignmentItem
import com.moodlepulse.app.model.DeadlineModuleType
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsExporterTest {

    @Test
    fun buildIcsContent_producesValidVCalendarWithAlarm() {
        val items = listOf(
            AssignmentItem(
                key = "assign:10:200",
                courseId = 10L,
                courseName = "作業系統",
                moduleType = DeadlineModuleType.ASSIGNMENT,
                title = "Lab 2: Thread Scheduler",
                dueAtSec = 1795000000L,
                descriptionSummary = "請上傳 zip 壓縮檔",
                url = "https://moodle.example.edu/mod/assign/view.php?id=99",
                submitted = false
            )
        )

        val ics = IcsExporter.buildIcsContent(items, "測試大學 Moodle")
        assertTrue(ics.contains("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("BEGIN:VEVENT"))
        assertTrue(ics.contains("UID:assign:10:200@moodlepulse.app"))
        assertTrue(ics.contains("SUMMARY:⏰ [作業系統] Lab 2: Thread Scheduler"))
        assertTrue(ics.contains("BEGIN:VALARM"))
        assertTrue(ics.contains("END:VCALENDAR"))
    }
}
