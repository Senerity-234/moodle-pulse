package com.moodlepulse.app.network

import com.moodlepulse.app.model.AnnouncementItem
import com.moodlepulse.app.model.AssignmentItem
import com.moodlepulse.app.model.CourseItem
import com.moodlepulse.app.model.DeadlineModuleType
import com.moodlepulse.app.model.MoodlePublicConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class MoodleApiException(
    val errorCode: String,
    override val message: String
) : IOException(message) {
    val isAuthError: Boolean
        get() = errorCode == "invalidtoken" || errorCode == "expiredtoken" || errorCode == "forcepasswordchangenotice"
}

data class MoodleSiteInfo(
    val userId: Long,
    val fullName: String,
    val siteName: String,
    val siteUrl: String
)

data class RawForumAnnouncement(
    val key: String,
    val courseId: Long,
    val courseName: String,
    val title: String,
    val author: String,
    val postedAtSec: Long,
    val htmlBody: String,
    val url: String,
    val isSystemNotification: Boolean = false
)

class MoodleApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getPublicConfig(baseUrl: String): MoodlePublicConfig = withContext(Dispatchers.IO) {
        val cleanBase = MoodleTokenParser.normalizeBaseUrl(baseUrl)
        val fallback = MoodlePublicConfig(
            wwwroot = cleanBase,
            siteName = cleanBase.removePrefix("https://"),
            typeOfLogin = 2,
            launchUrl = "$cleanBase/admin/tool/mobile/launch.php"
        )

        val payload = buildJsonArray {
            add(
                buildJsonObject {
                    put("index", 0)
                    put("methodname", "tool_mobile_get_public_config")
                    put("args", buildJsonObject {})
                }
            )
        }.toString()

        val request = Request.Builder()
            .url("$cleanBase/lib/ajax/service.php?info=tool_mobile_get_public_config")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use fallback
                val bodyStr = response.body?.string() ?: return@use fallback
                val rootArray = json.parseToJsonElement(bodyStr).jsonArray
                val first = rootArray.firstOrNull()?.jsonObject ?: return@use fallback
                if (first["error"]?.jsonPrimitive?.booleanOrNull == true) {
                    return@use fallback
                }
                val data = first["data"]?.jsonObject ?: return@use fallback
                val wwwroot = data.str("wwwroot")?.trimEnd('/')?.ifEmpty { cleanBase } ?: cleanBase
                val siteName = data.str("sitename")?.ifEmpty { fallback.siteName } ?: fallback.siteName
                val typeOfLogin = data.long("typeoflogin")?.toInt() ?: 2
                val launchUrl = data.str("launchurl")?.ifEmpty { null }
                    ?: "$wwwroot/admin/tool/mobile/launch.php"

                MoodlePublicConfig(
                    wwwroot = wwwroot,
                    siteName = siteName,
                    typeOfLogin = typeOfLogin,
                    launchUrl = launchUrl
                )
            }
        }.getOrDefault(fallback)
    }

    fun buildSsoLaunchUrl(
        config: MoodlePublicConfig,
        passport: String,
        urlScheme: String = "moodlepulse"
    ): String {
        val encodedService = URLEncoder.encode("moodle_mobile_app", StandardCharsets.UTF_8.name())
        val encodedPassport = URLEncoder.encode(passport, StandardCharsets.UTF_8.name())
        val encodedScheme = URLEncoder.encode(urlScheme, StandardCharsets.UTF_8.name())
        val separator = if (config.launchUrl.contains("?")) "&" else "?"
        return "${config.launchUrl}${separator}service=$encodedService&passport=$encodedPassport&urlscheme=$encodedScheme"
    }

    suspend fun getSiteInfo(baseUrl: String, token: String): MoodleSiteInfo {
        val obj = callRest(baseUrl, token, "core_webservice_get_site_info").jsonObject
        return MoodleSiteInfo(
            userId = obj.long("userid") ?: 0L,
            fullName = obj.str("fullname").orEmpty(),
            siteName = obj.str("sitename").orEmpty(),
            siteUrl = obj.str("siteurl")?.trimEnd('/').orEmpty().ifEmpty { baseUrl.trimEnd('/') }
        )
    }

    suspend fun getUserCourses(baseUrl: String, token: String, userId: Long): List<CourseItem> {
        val arr = callRest(
            baseUrl = baseUrl,
            token = token,
            wsFunction = "core_enrol_get_users_courses",
            params = mapOf("userid" to userId.toString())
        ).jsonArray

        val nowSec = System.currentTimeMillis() / 1000L
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.long("id") ?: return@mapNotNull null
            val hidden = obj.bool("hidden") == true
            val visible = obj.long("visible")?.let { it != 0L } ?: (obj.bool("visible") ?: true)
            if (hidden || !visible) return@mapNotNull null

            val shortName = obj.str("shortname").orEmpty().ifBlank { "Course $id" }
            val fullName = obj.str("displayname")
                ?.takeIf { it.isNotBlank() }
                ?: obj.str("fullname").orEmpty().ifBlank { shortName }
            val startDate = obj.long("startdate") ?: 0L
            val endDate = obj.long("enddate") ?: 0L

            val isCurrent = isLikelyCurrentSemester(startDate, endDate, nowSec)
            CourseItem(
                id = id,
                shortName = shortName,
                fullName = fullName,
                startDateSec = startDate,
                endDateSec = endDate,
                isCurrentSemester = isCurrent,
                isEnabled = isCurrent
            )
        }
    }

    suspend fun getAssignmentsForCourses(
        baseUrl: String,
        token: String,
        courses: List<CourseItem>
    ): List<AssignmentItem> {
        if (courses.isEmpty()) return emptyList()
        val courseById = courses.associateBy { it.id }
        val params = mutableMapOf("includenotenrolledcourses" to "0")
        courses.forEachIndexed { idx, course ->
            params["courseids[$idx]"] = course.id.toString()
        }

        val root = callRest(baseUrl, token, "mod_assign_get_assignments", params).jsonObject
        val courseArr = root["courses"] as? JsonArray ?: return emptyList()
        val cleanBase = baseUrl.trimEnd('/')

        val result = mutableListOf<AssignmentItem>()
        for (courseEl in courseArr) {
            val courseObj = courseEl as? JsonObject ?: continue
            val courseId = courseObj.long("id") ?: continue
            val courseName = courseById[courseId]?.fullName
                ?: courseObj.str("fullname")
                ?: courseObj.str("shortname")
                ?: "課程 $courseId"

            val assignmentsArr = courseObj["assignments"] as? JsonArray ?: continue
            for (assignEl in assignmentsArr) {
                val a = assignEl as? JsonObject ?: continue
                val assignId = a.long("id") ?: continue
                val cmid = a.long("cmid") ?: 0L
                val dueDate = a.long("duedate") ?: 0L
                val noSubmissions = (a.long("nosubmissions") ?: 0L) == 1L
                if (dueDate <= 0L || noSubmissions) continue

                val title = a.str("name")?.trim().orEmpty().ifEmpty { "未命名作業" }
                val url = if (cmid > 0L) {
                    "$cleanBase/mod/assign/view.php?id=$cmid"
                } else {
                    "$cleanBase/course/view.php?id=$courseId"
                }

                result += AssignmentItem(
                    key = "assign:$courseId:$assignId",
                    courseId = courseId,
                    courseName = courseName,
                    moduleType = DeadlineModuleType.ASSIGNMENT,
                    title = title,
                    dueAtSec = dueDate,
                    descriptionSummary = a.str("intro").orEmpty(),
                    url = url
                )
            }
        }
        return result
    }

    suspend fun checkSubmissionStatus(
        baseUrl: String,
        token: String,
        assignId: Long
    ): Pair<Boolean, String>? {
        return runCatching {
            val obj = callRest(
                baseUrl = baseUrl,
                token = token,
                wsFunction = "mod_assign_get_submission_status",
                params = mapOf("assignid" to assignId.toString())
            ).jsonObject
            val status = obj["lastattempt"]?.jsonObject
                ?.get("submission")?.jsonObject
                ?.str("status")
                .orEmpty()
            val submitted = status.equals("submitted", ignoreCase = true)
            submitted to status
        }.getOrNull()
    }

    suspend fun getUpcomingCalendarActionEvents(
        baseUrl: String,
        token: String,
        courses: List<CourseItem>,
        fromEpochSec: Long,
        toEpochSec: Long
    ): List<AssignmentItem> {
        val courseMap = courses.associateBy { it.id }
        val root = callRest(
            baseUrl = baseUrl,
            token = token,
            wsFunction = "core_calendar_get_action_events_by_timesort",
            params = mapOf(
                "timesortfrom" to fromEpochSec.toString(),
                "timesortto" to toEpochSec.toString(),
                "limitnum" to "50"
            )
        ).jsonObject

        val events = root["events"] as? JsonArray ?: return emptyList()
        val cleanBase = baseUrl.trimEnd('/')
        val results = mutableListOf<AssignmentItem>()

        for (el in events) {
            val ev = el as? JsonObject ?: continue
            val courseObj = ev["course"] as? JsonObject
            val courseId = courseObj?.long("id") ?: ev.long("courseid") ?: continue
            if (courseMap.isNotEmpty() && !courseMap.containsKey(courseId)) continue

            val moduleName = ev.str("modulename").orEmpty().lowercase()
            val instance = ev.long("instance") ?: ev.long("id") ?: continue
            val dueAt = ev.long("timesort") ?: ev.long("timestart") ?: continue
            val name = ev.str("name")?.trim().orEmpty().ifEmpty { "課程截止項目" }
            val courseName = courseMap[courseId]?.fullName
                ?: courseObj?.str("fullname")
                ?: "課程 $courseId"

            val moduleType = when (moduleName) {
                "assign" -> DeadlineModuleType.ASSIGNMENT
                "quiz" -> DeadlineModuleType.QUIZ
                else -> DeadlineModuleType.EVENT
            }
            val keyPrefix = when (moduleType) {
                DeadlineModuleType.ASSIGNMENT -> "assign"
                DeadlineModuleType.QUIZ -> "quiz"
                DeadlineModuleType.EVENT -> "event"
            }

            val url = ev.str("url")
                ?.takeIf { it.startsWith("http") }
                ?: ev["action"]?.jsonObject?.str("url")?.takeIf { it.startsWith("http") }
                ?: "$cleanBase/course/view.php?id=$courseId"

            results += AssignmentItem(
                key = "$keyPrefix:$courseId:$instance",
                courseId = courseId,
                courseName = courseName,
                moduleType = moduleType,
                title = name,
                dueAtSec = dueAt,
                descriptionSummary = ev.str("description").orEmpty(),
                url = url
            )
        }
        return results
    }

    suspend fun getCourseAnnouncements(
        baseUrl: String,
        token: String,
        courses: List<CourseItem>,
        maxDiscussionsPerForum: Int = 5
    ): List<RawForumAnnouncement> {
        if (courses.isEmpty()) return emptyList()
        val courseMap = courses.associateBy { it.id }
        val params = mutableMapOf<String, String>()
        courses.forEachIndexed { idx, course ->
            params["courseids[$idx]"] = course.id.toString()
        }

        val forumsArr = callRest(
            baseUrl = baseUrl,
            token = token,
            wsFunction = "mod_forum_get_forums_by_courses",
            params = params
        ).jsonArray

        val newsForums = forumsArr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val forumId = obj.long("id") ?: return@mapNotNull null
            val courseId = obj.long("course") ?: return@mapNotNull null
            val type = obj.str("type").orEmpty()
            val name = obj.str("name").orEmpty()
            val isNews = type == "news" ||
                name.contains("公告") ||
                name.contains("Announcement", ignoreCase = true)
            if (!isNews) return@mapNotNull null
            forumId to courseId
        }

        val cleanBase = baseUrl.trimEnd('/')
        val results = mutableListOf<RawForumAnnouncement>()

        for ((forumId, courseId) in newsForums) {
            val discussions = runCatching {
                callRest(
                    baseUrl = baseUrl,
                    token = token,
                    wsFunction = "mod_forum_get_forum_discussions",
                    params = mapOf(
                        "forumid" to forumId.toString(),
                        "page" to "0",
                        "perpage" to maxDiscussionsPerForum.toString()
                    )
                ).jsonObject["discussions"] as? JsonArray
            }.getOrNull() ?: continue

            val courseName = courseMap[courseId]?.fullName ?: "課程 $courseId"

            for (discEl in discussions) {
                val d = discEl as? JsonObject ?: continue
                val discussionId = d.long("discussion") ?: d.long("id") ?: continue
                val title = d.str("subject")
                    ?.takeIf { it.isNotBlank() }
                    ?: d.str("name")
                    ?: "課程公告"
                val messageHtml = d.str("message").orEmpty()
                val postedAt = d.long("timemodified") ?: d.long("created") ?: 0L
                val author = d.str("userfullname").orEmpty()

                results += RawForumAnnouncement(
                    key = "forum:$courseId:$discussionId",
                    courseId = courseId,
                    courseName = courseName,
                    title = title,
                    author = author,
                    postedAtSec = postedAt,
                    htmlBody = messageHtml,
                    url = "$cleanBase/mod/forum/discuss.php?d=$discussionId"
                )
            }
        }
        return results
    }

    suspend fun getPopupNotifications(
        baseUrl: String,
        token: String,
        userId: Long,
        limit: Int = 15
    ): List<RawForumAnnouncement> {
        if (userId <= 0L) return emptyList()
        val obj = runCatching {
            callRest(
                baseUrl = baseUrl,
                token = token,
                wsFunction = "message_popup_get_popup_notifications",
                params = mapOf(
                    "useridto" to userId.toString(),
                    "newestfirst" to "1",
                    "limit" to limit.toString(),
                    "offset" to "0"
                )
            ).jsonObject
        }.getOrNull() ?: return emptyList()

        val notifications = obj["notifications"] as? JsonArray ?: return emptyList()
        return notifications.mapNotNull { el ->
            val n = el as? JsonObject ?: return@mapNotNull null
            val id = n.long("id") ?: return@mapNotNull null
            val subject = n.str("subject")
                ?.takeIf { it.isNotBlank() }
                ?: n.str("shortenedsubject")
                ?: return@mapNotNull null
            val body = n.str("fullmessagehtml")
                ?.takeIf { it.isNotBlank() }
                ?: n.str("fullmessage")
                ?: subject
            val timeCreated = n.long("timecreated") ?: 0L
            val contextUrl = n.str("contexturl").orEmpty()

            RawForumAnnouncement(
                key = "notif:$id",
                courseId = 0L,
                courseName = "Moodle 系統通知",
                title = subject,
                author = "",
                postedAtSec = timeCreated,
                htmlBody = body,
                url = contextUrl,
                isSystemNotification = true
            )
        }
    }

    private suspend fun callRest(
        baseUrl: String,
        token: String,
        wsFunction: String,
        params: Map<String, String> = emptyMap()
    ): JsonElement = withContext(Dispatchers.IO) {
        val cleanBase = baseUrl.trimEnd('/')
        val formBuilder = FormBody.Builder()
            .add("wstoken", token)
            .add("moodlewsrestformat", "json")
            .add("wsfunction", wsFunction)
        params.forEach { (k, v) -> formBuilder.add(k, v) }

        val request = Request.Builder()
            .url("$cleanBase/webservice/rest/server.php")
            .post(formBuilder.build())
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string()
                ?: throw MoodleApiException("empty_response", "伺服器未回傳資料 (${response.code})")
            if (!response.isSuccessful) {
                throw MoodleApiException("http_${response.code}", "連線失敗 HTTP ${response.code}")
            }
            val parsed = json.parseToJsonElement(raw)
            if (parsed is JsonObject && (parsed.containsKey("exception") || parsed.containsKey("errorcode"))) {
                val code = parsed.str("errorcode") ?: "moodle_error"
                val msg = parsed.str("message") ?: "Moodle API 錯誤 ($code)"
                throw MoodleApiException(code, msg)
            }
            parsed
        }
    }

    private fun isLikelyCurrentSemester(startSec: Long, endSec: Long, nowSec: Long): Boolean {
        val twoWeeksSec = 14L * 86400L
        val fortyFiveDaysSec = 45L * 86400L
        val notEnded = endSec <= 0L || endSec >= (nowSec - twoWeeksSec)
        val startedOrSoon = startSec <= 0L || startSec <= (nowSec + fortyFiveDaysSec)
        return notEnded && startedOrSoon
    }

    private fun JsonObject.str(key: String): String? {
        val prim = this[key] as? JsonPrimitive ?: return null
        return if (prim.isString || prim.contentOrNull != null) {
            prim.contentOrNull?.takeIf { it != "null" && it != "false" }
        } else {
            null
        }
    }

    private fun JsonObject.long(key: String): Long? {
        val prim = this[key] as? JsonPrimitive ?: return null
        return prim.longOrNull
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val prim = this[key] as? JsonPrimitive ?: return null
        return prim.booleanOrNull
    }
}
