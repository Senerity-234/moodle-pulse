package com.moodlepulse.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.moodlepulse.app.MoodlePulseApp
import com.moodlepulse.app.R
import com.moodlepulse.app.model.DeadlineModuleType
import com.moodlepulse.app.model.WidgetFilterMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MoodleWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return MoodleWidgetFactory(applicationContext)
    }
}

private data class WidgetRowModel(
    val tag: String,
    val tagColor: Int,
    val accentColor: Int,
    val courseName: String,
    val timeLabel: String,
    val timeColor: Int,
    val title: String,
    val summary: String,
    val url: String
)

private class MoodleWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<WidgetRowModel> = emptyList()

    private val dateTimeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault())

    private val dateOnlyFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM/dd").withZone(ZoneId.systemDefault())

    override fun onCreate() {
        loadRows()
    }

    override fun onDataSetChanged() {
        loadRows()
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val item = rows.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_moodle_item)

        val views = RemoteViews(context.packageName, R.layout.widget_moodle_item)
        views.setTextViewText(R.id.item_tag, item.tag)
        views.setTextColor(R.id.item_tag, item.tagColor)
        views.setInt(R.id.item_accent_bar, "setBackgroundColor", item.accentColor)
        views.setTextViewText(R.id.item_course, item.courseName)
        views.setTextViewText(R.id.item_time, item.timeLabel)
        views.setTextColor(R.id.item_time, item.timeColor)
        views.setTextViewText(R.id.item_title, item.title)
        views.setTextViewText(R.id.item_summary, item.summary)

        val fillInIntent = Intent().apply {
            putExtra(MoodleWidgetProvider.EXTRA_ITEM_URL, item.url)
        }
        views.setOnClickFillInIntent(R.id.widget_item_root, fillInIntent)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false

    private fun loadRows() {
        val app = context.applicationContext as MoodlePulseApp
        if (app.tokenStore.getMoodleToken().isNullOrBlank()) {
            rows = emptyList()
            return
        }

        val settings = app.cacheRepository.getSettings()
        val data = app.cacheRepository.getDashboardData()
        val enabledCourseIds = data.courses.filter { it.isEnabled }.map { it.id }.toSet()
        val nowSec = System.currentTimeMillis() / 1000L

        val assignmentRows = data.assignments
            .filter { item ->
                (enabledCourseIds.isEmpty() || item.courseId in enabledCourseIds) &&
                    item.dueAtSec >= nowSec - 86400L
            }
            .sortedWith(compareBy({ it.submitted }, { it.dueAtSec }))
            .take(15)
            .map { item ->
                val remainingSec = item.dueAtSec - nowSec
                val (accent, timeColor) = when {
                    item.submitted -> Color.parseColor("#22C55E") to Color.parseColor("#86EFAC")
                    remainingSec <= 0L -> Color.parseColor("#EF4444") to Color.parseColor("#FCA5A5")
                    remainingSec <= 86400L -> Color.parseColor("#F97316") to Color.parseColor("#FDBA74")
                    remainingSec <= 3L * 86400L -> Color.parseColor("#F59E0B") to Color.parseColor("#FDE047")
                    else -> Color.parseColor("#3B82F6") to Color.parseColor("#93C5FD")
                }

                val tag = when {
                    item.submitted -> "已繳"
                    item.moduleType == DeadlineModuleType.QUIZ -> "測驗"
                    else -> "作業"
                }

                WidgetRowModel(
                    tag = tag,
                    tagColor = accent,
                    accentColor = accent,
                    courseName = item.courseName,
                    timeLabel = formatCountdown(remainingSec, item.submitted),
                    timeColor = timeColor,
                    title = item.title,
                    summary = "${dateTimeFormat.format(Instant.ofEpochSecond(item.dueAtSec))} 截止",
                    url = item.url
                )
            }

        val announcementRows = data.announcements
            .filter { item ->
                item.courseId == 0L || enabledCourseIds.isEmpty() || item.courseId in enabledCourseIds
            }
            .sortedByDescending { it.postedAtSec }
            .take(12)
            .map { item ->
                val primaryTag = item.tags.firstOrNull() ?: "通知"
                WidgetRowModel(
                    tag = primaryTag,
                    tagColor = Color.parseColor("#C084FC"),
                    accentColor = Color.parseColor("#A855F7"),
                    courseName = item.courseName,
                    timeLabel = if (item.postedAtSec > 0L) {
                        dateOnlyFormat.format(Instant.ofEpochSecond(item.postedAtSec))
                    } else {
                        "近期"
                    },
                    timeColor = Color.parseColor("#CBD5E1"),
                    title = item.title,
                    summary = item.summary,
                    url = item.url
                )
            }

        rows = when (settings.widgetFilterMode) {
            WidgetFilterMode.ALL -> {
                // Prioritize urgent/upcoming assignments first, followed by recent announcements
                (assignmentRows.take(6) + announcementRows.take(6)).ifEmpty { announcementRows }
            }
            WidgetFilterMode.ASSIGNMENTS_ONLY -> assignmentRows
            WidgetFilterMode.ANNOUNCEMENTS_ONLY -> announcementRows
        }
    }

    private fun formatCountdown(remainingSec: Long, submitted: Boolean): String {
        if (submitted) return "已完成"
        if (remainingSec <= 0L) return "已逾期"
        val hours = remainingSec / 3600L
        val days = hours / 24L
        return when {
            days >= 1L -> "剩 ${days} 天"
            hours >= 1L -> "剩 ${hours} 小時"
            else -> "即將截止"
        }
    }
}
