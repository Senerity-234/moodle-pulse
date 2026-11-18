package com.moodlepulse.app.sync

import android.content.Context
import com.moodlepulse.app.calendar.CalendarSyncManager
import com.moodlepulse.app.calendar.CalendarSyncOutcome
import com.moodlepulse.app.data.LocalCacheRepository
import com.moodlepulse.app.data.SecureTokenStore
import com.moodlepulse.app.model.AnnouncementItem
import com.moodlepulse.app.model.AssignmentItem
import com.moodlepulse.app.model.CourseItem
import com.moodlepulse.app.model.DeadlineModuleType
import com.moodlepulse.app.network.MoodleApiClient
import com.moodlepulse.app.network.MoodleApiException
import com.moodlepulse.app.summarizer.AnnouncementSummarizer
import com.moodlepulse.app.widget.MoodleWidgetProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

sealed class SyncOutcome {
    data class Success(
        val courseCount: Int,
        val assignmentCount: Int,
        val announcementCount: Int,
        val calendarOutcome: CalendarSyncOutcome?
    ) : SyncOutcome()

    data class Failure(
        val message: String,
        val authExpired: Boolean = false
    ) : SyncOutcome()
}

class MoodleSyncManager(
    private val context: Context,
    private val apiClient: MoodleApiClient,
    private val cacheRepository: LocalCacheRepository,
    private val tokenStore: SecureTokenStore,
    private val calendarSyncManager: CalendarSyncManager
) {

    suspend fun syncAll(): SyncOutcome {
        val settings = cacheRepository.getSettings()
        val token = tokenStore.getMoodleToken()
        if (settings.baseUrl.isBlank() || token.isNullOrBlank()) {
            return SyncOutcome.Failure("尚未登入 Moodle", authExpired = true)
        }

        return try {
            val siteInfo = if (settings.userId <= 0L) {
                apiClient.getSiteInfo(settings.baseUrl, token).also { info ->
                    cacheRepository.updateSettings {
                        it.copy(
                            userId = info.userId,
                            userFullName = info.fullName.ifBlank { it.userFullName },
                            siteName = info.siteName.ifBlank { it.siteName }
                        )
                    }
                }
            } else {
                null
            }

            val userId = siteInfo?.userId ?: cacheRepository.getSettings().userId
            val previousDashboard = cacheRepository.getDashboardData()
            val previousEnabledMap = previousDashboard.courses.associate { it.id to it.isEnabled }

            val fetchedCourses = apiClient.getUserCourses(settings.baseUrl, token, userId)
            val mergedCourses: List<CourseItem> = fetchedCourses.map { course ->
                val userChoice = previousEnabledMap[course.id]
                if (userChoice != null) course.copy(isEnabled = userChoice) else course
            }

            val activeCourses = mergedCourses.filter { it.isEnabled }
            val nowSec = System.currentTimeMillis() / 1000L

            val directAssignments = runCatching {
                apiClient.getAssignmentsForCourses(settings.baseUrl, token, activeCourses)
            }.getOrDefault(emptyList())

            val calendarEvents = runCatching {
                apiClient.getUpcomingCalendarActionEvents(
                    baseUrl = settings.baseUrl,
                    token = token,
                    courses = activeCourses,
                    fromEpochSec = nowSec - 7L * 86400L,
                    toEpochSec = nowSec + 120L * 86400L
                )
            }.getOrDefault(emptyList())

            val mergedAssignments = mergeAndCleanAssignments(directAssignments, calendarEvents)
            val enrichedAssignments = enrichSubmissionStatuses(
                baseUrl = settings.baseUrl,
                token = token,
                assignments = mergedAssignments,
                nowSec = nowSec
            )

            val rawAnnouncements = runCatching {
                apiClient.getCourseAnnouncements(settings.baseUrl, token, activeCourses)
            }.getOrDefault(emptyList())

            val rawNotifications = runCatching {
                apiClient.getPopupNotifications(settings.baseUrl, token, userId)
            }.getOrDefault(emptyList())

            val geminiKey = if (cacheRepository.getSettings().useGeminiSummary) {
                tokenStore.getGeminiApiKey()
            } else {
                null
            }

            val previousSummaryMap = previousDashboard.announcements.associateBy { it.key }
            val combinedRaw = (rawAnnouncements + rawNotifications)
                .distinctBy { it.key }
                .sortedByDescending { it.postedAtSec }
                .take(40)

            val summarizedAnnouncements = summarizeAnnouncements(
                rawItems = combinedRaw,
                previousMap = previousSummaryMap,
                geminiApiKey = geminiKey
            )

            val currentSettings = cacheRepository.getSettings()
            val calendarOutcome = if (currentSettings.autoCalendarSyncEnabled) {
                calendarSyncManager.syncAssignmentsToCalendar(
                    assignments = enrichedAssignments,
                    existingRecords = previousDashboard.calendarSyncRecords,
                    settings = currentSettings
                )
            } else {
                null
            }

            if (calendarOutcome != null &&
                calendarOutcome.resolvedCalendarId > 0L &&
                currentSettings.targetCalendarId <= 0L
            ) {
                cacheRepository.updateSettings {
                    it.copy(
                        targetCalendarId = calendarOutcome.resolvedCalendarId,
                        targetCalendarName = calendarOutcome.resolvedCalendarName
                    )
                }
            }

            cacheRepository.updateDashboardData { old ->
                old.copy(
                    courses = mergedCourses,
                    assignments = enrichedAssignments,
                    announcements = summarizedAnnouncements,
                    calendarSyncRecords = calendarOutcome?.updatedRecords ?: old.calendarSyncRecords,
                    lastSyncEpochSec = nowSec,
                    lastSyncError = null
                )
            }

            MoodleWidgetProvider.notifyWidgetsDataChanged(context)

            SyncOutcome.Success(
                courseCount = activeCourses.size,
                assignmentCount = enrichedAssignments.size,
                announcementCount = summarizedAnnouncements.size,
                calendarOutcome = calendarOutcome
            )
        } catch (e: MoodleApiException) {
            val errorMsg = if (e.isAuthError) {
                "登入憑證已過期或被撤銷，請重新透過學校 SSO 登入"
            } else {
                e.message
            }
            cacheRepository.updateDashboardData { it.copy(lastSyncError = errorMsg) }
            MoodleWidgetProvider.notifyWidgetsDataChanged(context)
            SyncOutcome.Failure(errorMsg, authExpired = e.isAuthError)
        } catch (t: Throwable) {
            val errorMsg = t.message ?: "同步時發生網路或系統錯誤"
            cacheRepository.updateDashboardData { it.copy(lastSyncError = errorMsg) }
            MoodleWidgetProvider.notifyWidgetsDataChanged(context)
            SyncOutcome.Failure(errorMsg, authExpired = false)
        }
    }

    fun syncCalendarOnly(): CalendarSyncOutcome {
        val settings = cacheRepository.getSettings()
        val data = cacheRepository.getDashboardData()
        val enabledIds = data.courses.filter { it.isEnabled }.map { it.id }.toSet()
        val enabledAssignments = data.assignments.filter { enabledIds.isEmpty() || it.courseId in enabledIds }

        val outcome = calendarSyncManager.syncAssignmentsToCalendar(
            assignments = enabledAssignments,
            existingRecords = data.calendarSyncRecords,
            settings = settings.copy(autoCalendarSyncEnabled = true)
        )

        cacheRepository.updateDashboardData {
            it.copy(calendarSyncRecords = outcome.updatedRecords)
        }
        return outcome
    }

    private fun mergeAndCleanAssignments(
        direct: List<AssignmentItem>,
        calendarEvents: List<AssignmentItem>
    ): List<AssignmentItem> {
        val map = LinkedHashMap<String, AssignmentItem>()
        for (item in direct) {
            map[item.key] = item.copy(
                descriptionSummary = AnnouncementSummarizer.htmlToCleanText(item.descriptionSummary)
                    .replace("\n", " ")
                    .take(180)
            )
        }
        for (ev in calendarEvents) {
            if (!map.containsKey(ev.key)) {
                map[ev.key] = ev.copy(
                    descriptionSummary = AnnouncementSummarizer.htmlToCleanText(ev.descriptionSummary)
                        .replace("\n", " ")
                        .take(180)
                )
            }
        }
        return map.values.sortedBy { it.dueAtSec }
    }

    private suspend fun enrichSubmissionStatuses(
        baseUrl: String,
        token: String,
        assignments: List<AssignmentItem>,
        nowSec: Long
    ): List<AssignmentItem> = coroutineScope {
        val semaphore = Semaphore(4)
        val windowStart = nowSec - 3L * 86400L
        val windowEnd = nowSec + 45L * 86400L

        assignments.map { item ->
            async {
                if (item.moduleType != DeadlineModuleType.ASSIGNMENT ||
                    item.dueAtSec !in windowStart..windowEnd
                ) {
                    return@async item
                }
                val assignId = item.key.substringAfterLast(':').toLongOrNull() ?: return@async item
                val statusPair = semaphore.withPermit {
                    apiClient.checkSubmissionStatus(baseUrl, token, assignId)
                }
                if (statusPair != null) {
                    item.copy(submitted = statusPair.first, submissionStatus = statusPair.second)
                } else {
                    item
                }
            }
        }.awaitAll()
    }

    private suspend fun summarizeAnnouncements(
        rawItems: List<com.moodlepulse.app.network.RawForumAnnouncement>,
        previousMap: Map<String, AnnouncementItem>,
        geminiApiKey: String?
    ): List<AnnouncementItem> = coroutineScope {
        val semaphore = Semaphore(3)
        rawItems.mapIndexed { index, raw ->
            async {
                val cached = previousMap[raw.key]
                if (cached != null &&
                    cached.postedAtSec == raw.postedAtSec &&
                    geminiApiKey.isNullOrBlank()
                ) {
                    return@async cached
                }

                // Limit optional Gemini calls to the 10 newest announcements to save quota & latency
                val keyForItem = if (index < 10) geminiApiKey else null
                val summarized = semaphore.withPermit {
                    AnnouncementSummarizer.summarizeWithOptionalAi(
                        title = raw.title,
                        htmlBody = raw.htmlBody,
                        geminiApiKey = keyForItem
                    )
                }

                AnnouncementItem(
                    key = raw.key,
                    courseId = raw.courseId,
                    courseName = raw.courseName,
                    title = raw.title,
                    author = raw.author,
                    postedAtSec = raw.postedAtSec,
                    summary = summarized.summary,
                    fullText = summarized.cleanFullText,
                    tags = summarized.tags,
                    url = raw.url,
                    isSystemNotification = raw.isSystemNotification
                )
            }
        }.awaitAll()
    }
}
