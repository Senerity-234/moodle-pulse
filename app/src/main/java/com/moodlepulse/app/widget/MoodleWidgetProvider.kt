package com.moodlepulse.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import com.moodlepulse.app.MoodlePulseApp
import com.moodlepulse.app.R
import com.moodlepulse.app.sync.MoodleSyncWorker
import com.moodlepulse.app.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MoodleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val app = context.applicationContext as MoodlePulseApp

        when (intent.action) {
            ACTION_REFRESH -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = getWidgetIds(context, manager)
                ids.forEach { id ->
                    val views = RemoteViews(context.packageName, R.layout.widget_moodle)
                    views.setTextViewText(R.id.widget_subtitle, "正在同步 Moodle 資料…")
                    manager.partiallyUpdateAppWidget(id, views)
                }
                MoodleSyncWorker.enqueueImmediateSync(context)
            }

            ACTION_CYCLE_FILTER -> {
                app.cacheRepository.updateSettings { current ->
                    current.copy(widgetFilterMode = current.widgetFilterMode.next())
                }
                notifyWidgetsDataChanged(context)
            }

            ACTION_OPEN_ITEM -> {
                val url = intent.getStringExtra(EXTRA_ITEM_URL).orEmpty()
                val targetIntent = if (url.startsWith("http://") || url.startsWith("https://")) {
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                }
                runCatching { context.startActivity(targetIntent) }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.moodlepulse.app.ACTION_REFRESH"
        const val ACTION_CYCLE_FILTER = "com.moodlepulse.app.ACTION_CYCLE_FILTER"
        const val ACTION_OPEN_ITEM = "com.moodlepulse.app.ACTION_OPEN_ITEM"
        const val EXTRA_ITEM_URL = "extra_item_url"

        private val timeFormat: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault())

        fun notifyWidgetsDataChanged(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = getWidgetIds(context, manager)
            if (ids.isEmpty()) return
            for (id in ids) {
                updateWidget(context, manager, id)
            }
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }

        private fun getWidgetIds(context: Context, manager: AppWidgetManager): IntArray {
            val component = ComponentName(context, MoodleWidgetProvider::class.java)
            return manager.getAppWidgetIds(component)
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val app = context.applicationContext as MoodlePulseApp
            val settings = app.cacheRepository.getSettings()
            val dashboard = app.cacheRepository.getDashboardData()
            val hasToken = !app.tokenStore.getMoodleToken().isNullOrBlank()

            val views = RemoteViews(context.packageName, R.layout.widget_moodle)

            val serviceIntent = Intent(context, MoodleWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list, serviceIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty_view)

            views.setTextViewText(
                R.id.widget_title,
                settings.siteName.ifBlank { context.getString(R.string.app_name) }
            )

            val subtitleText = when {
                !hasToken -> "尚未登入，點此開啟 App"
                dashboard.lastSyncError != null && dashboard.lastSyncEpochSec == 0L ->
                    "同步失敗，點擊右側重試"
                dashboard.lastSyncEpochSec > 0L ->
                    "${timeFormat.format(Instant.ofEpochSecond(dashboard.lastSyncEpochSec))} 更新"
                else -> "點擊右側同步最新資料"
            }
            views.setTextViewText(R.id.widget_subtitle, subtitleText)
            views.setTextViewText(R.id.widget_filter_chip, settings.widgetFilterMode.label())
            views.setTextViewText(
                R.id.widget_empty_view,
                if (hasToken) {
                    context.getString(R.string.widget_empty_no_items)
                } else {
                    context.getString(R.string.widget_empty_logged_out)
                }
            )

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPending = PendingIntent.getActivity(
                context,
                100,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title_group, openAppPending)
            views.setOnClickPendingIntent(R.id.widget_empty_view, openAppPending)

            val refreshIntent = Intent(context, MoodleWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPending = PendingIntent.getBroadcast(
                context,
                101,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)

            val cycleIntent = Intent(context, MoodleWidgetProvider::class.java).apply {
                action = ACTION_CYCLE_FILTER
            }
            val cyclePending = PendingIntent.getBroadcast(
                context,
                102,
                cycleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_filter_chip, cyclePending)

            val itemTemplateIntent = Intent(context, MoodleWidgetProvider::class.java).apply {
                action = ACTION_OPEN_ITEM
            }
            val templateFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val itemTemplatePending = PendingIntent.getBroadcast(
                context,
                103,
                itemTemplateIntent,
                templateFlags
            )
            views.setPendingIntentTemplate(R.id.widget_list, itemTemplatePending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
