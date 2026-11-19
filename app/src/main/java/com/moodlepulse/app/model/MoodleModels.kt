package com.moodlepulse.app.model

import kotlinx.serialization.Serializable

@Serializable
enum class SsoLoginMethod {
    IN_APP_WEBVIEW,
    EXTERNAL_BROWSER_CUSTOM_TAB
}

@Serializable
enum class WidgetFilterMode {
    ALL,
    ASSIGNMENTS_ONLY,
    ANNOUNCEMENTS_ONLY;

    fun next(): WidgetFilterMode = when (this) {
        ALL -> ASSIGNMENTS_ONLY
        ASSIGNMENTS_ONLY -> ANNOUNCEMENTS_ONLY
        ANNOUNCEMENTS_ONLY -> ALL
    }

    fun label(): String = when (this) {
        ALL -> "全部"
        ASSIGNMENTS_ONLY -> "作業"
        ANNOUNCEMENTS_ONLY -> "通知"
    }
}

@Serializable
data class AppSettings(
    val baseUrl: String = "",
    val siteName: String = "",
    val userId: Long = 0L,
    val userFullName: String = "",
    val preferredSsoMethod: SsoLoginMethod = SsoLoginMethod.IN_APP_WEBVIEW,
    val syncIntervalHours: Long = 4L,
    val autoCalendarSyncEnabled: Boolean = true,
    val targetCalendarId: Long = -1L,
    val targetCalendarName: String = "",
    val reminderMinutesBefore: Int = 1440,
    val secondReminderMinutesBefore: Int = 120,
    val includeSubmittedInCalendar: Boolean = true,
    val widgetFilterMode: WidgetFilterMode = WidgetFilterMode.ALL,
    val useGeminiSummary: Boolean = false,
    val githubUpdateRepo: String = "Senerity-234/moodle-pulse",
    val autoCheckAppUpdate: Boolean = true,
    val dismissedUpdateTag: String = ""
)

@Serializable
data class CourseItem(
    val id: Long,
    val shortName: String,
    val fullName: String,
    val startDateSec: Long = 0L,
    val endDateSec: Long = 0L,
    val isCurrentSemester: Boolean = true,
    val isEnabled: Boolean = true
)

@Serializable
enum class DeadlineModuleType {
    ASSIGNMENT,
    QUIZ,
    EVENT
}

@Serializable
data class AssignmentItem(
    val key: String,
    val courseId: Long,
    val courseName: String,
    val moduleType: DeadlineModuleType,
    val title: String,
    val dueAtSec: Long,
    val descriptionSummary: String = "",
    val url: String = "",
    val submitted: Boolean = false,
    val submissionStatus: String = ""
)

@Serializable
data class AnnouncementItem(
    val key: String,
    val courseId: Long,
    val courseName: String,
    val title: String,
    val author: String = "",
    val postedAtSec: Long,
    val summary: String,
    val fullText: String,
    val tags: List<String> = emptyList(),
    val url: String = "",
    val isSystemNotification: Boolean = false
)

@Serializable
data class CalendarSyncRecord(
    val assignmentKey: String,
    val calendarId: Long,
    val eventId: Long,
    val syncedDueAtSec: Long,
    val syncedTitle: String
)

@Serializable
data class CachedDashboardData(
    val courses: List<CourseItem> = emptyList(),
    val assignments: List<AssignmentItem> = emptyList(),
    val announcements: List<AnnouncementItem> = emptyList(),
    val calendarSyncRecords: List<CalendarSyncRecord> = emptyList(),
    val lastSyncEpochSec: Long = 0L,
    val lastSyncError: String? = null
)

data class MoodlePublicConfig(
    val wwwroot: String,
    val siteName: String,
    val typeOfLogin: Int,
    val launchUrl: String
)

data class DeviceCalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val isPrimary: Boolean
)
