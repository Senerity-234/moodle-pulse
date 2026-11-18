package com.moodlepulse.app.summarizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementSummarizerTest {

    @Test
    fun summarizeOffline_prioritizesDatesAndExamNoticesOverGreetings() {
        val html = """
            <p>各位同學好，</p>
            <p>本週因為系上舉辦研討會，原定 11/25 (三) 14:00 的資料結構課程改為遠距線上上課。</p>
            <p>另外期中考範圍將涵蓋第 1 章到第 5 章，請務必準時於資訊館 201 教室應考。</p>
            <p>此信件為系統自動發送。</p>
        """.trimIndent()

        val result = AnnouncementSummarizer.summarizeOffline(
            title = "下週調課與期中考提醒",
            htmlBody = html
        )

        assertTrue(result.tags.contains("考試"))
        assertTrue(result.tags.contains("調課/停課"))
        assertFalse(result.summary.startsWith("各位同學好"))
        assertTrue(result.summary.contains("11/25") || result.summary.contains("期中考"))
    }
}
