package com.moodlepulse.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moodlepulse.app.MoodlePulseApp
import com.moodlepulse.app.model.AppSettings
import com.moodlepulse.app.model.CachedDashboardData
import com.moodlepulse.app.model.DeviceCalendarInfo
import com.moodlepulse.app.model.SsoLoginMethod
import com.moodlepulse.app.network.MoodleTokenParser
import com.moodlepulse.app.sync.MoodleSyncWorker
import com.moodlepulse.app.sync.SyncOutcome
import com.moodlepulse.app.widget.MoodleWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class DashboardFilter {
    ALL,
    PENDING_ASSIGNMENTS,
    ANNOUNCEMENTS
}

data class MainUiState(
    val isLoggedIn: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val dashboard: CachedDashboardData = CachedDashboardData(),
    val isSyncing: Boolean = false,
    val statusBanner: String? = null,
    val hasCalendarPermission: Boolean = false,
    val writableCalendars: List<DeviceCalendarInfo> = emptyList(),
    val hasGeminiKeySaved: Boolean = false,
    val selectedFilter: DashboardFilter = DashboardFilter.ALL
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MoodlePulseApp

    private val _isSyncing = MutableStateFlow(false)
    private val _statusBanner = MutableStateFlow<String?>(null)
    private val _selectedFilter = MutableStateFlow(DashboardFilter.ALL)
    private val _calendars = MutableStateFlow<List<DeviceCalendarInfo>>(emptyList())
    private val _hasCalendarPermission = MutableStateFlow(app.calendarSyncManager.hasCalendarPermissions())
    private val _isLoggedIn = MutableStateFlow(!app.tokenStore.getMoodleToken().isNullOrBlank())
    private val _hasGeminiKey = MutableStateFlow(!app.tokenStore.getGeminiApiKey().isNullOrBlank())

    val uiState: StateFlow<MainUiState> = combine(
        app.cacheRepository.settingsFlow,
        app.cacheRepository.dashboardFlow,
        _isSyncing,
        _statusBanner,
        _selectedFilter,
        _calendars,
        _hasCalendarPermission,
        _isLoggedIn,
        _hasGeminiKey
    ) { values ->
        val settings = values[0] as AppSettings
        val dashboard = values[1] as CachedDashboardData
        val syncing = values[2] as Boolean
        val banner = values[3] as String?
        val filter = values[4] as DashboardFilter
        @Suppress("UNCHECKED_CAST")
        val calendars = values[5] as List<DeviceCalendarInfo>
        val calPerm = values[6] as Boolean
        val loggedIn = values[7] as Boolean
        val hasGemini = values[8] as Boolean

        MainUiState(
            isLoggedIn = loggedIn,
            settings = settings,
            dashboard = dashboard,
            isSyncing = syncing,
            statusBanner = banner,
            hasCalendarPermission = calPerm,
            writableCalendars = calendars,
            hasGeminiKeySaved = hasGemini,
            selectedFilter = filter
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState(
            isLoggedIn = !app.tokenStore.getMoodleToken().isNullOrBlank(),
            settings = app.cacheRepository.getSettings(),
            dashboard = app.cacheRepository.getDashboardData(),
            hasCalendarPermission = app.calendarSyncManager.hasCalendarPermissions(),
            hasGeminiKeySaved = !app.tokenStore.getGeminiApiKey().isNullOrBlank()
        )
    )

    init {
        refreshCalendarPermissions()
    }

    fun refreshSessionState() {
        _isLoggedIn.value = !app.tokenStore.getMoodleToken().isNullOrBlank()
        _hasGeminiKey.value = !app.tokenStore.getGeminiApiKey().isNullOrBlank()
        refreshCalendarPermissions()
    }

    fun refreshCalendarPermissions() {
        val granted = app.calendarSyncManager.hasCalendarPermissions()
        _hasCalendarPermission.value = granted
        if (granted) {
            viewModelScope.launch(Dispatchers.IO) {
                val list = app.calendarSyncManager.listWritableCalendars()
                _calendars.value = list
            }
        } else {
            _calendars.value = emptyList()
        }
    }

    fun setFilter(filter: DashboardFilter) {
        _selectedFilter.value = filter
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun syncNow() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _statusBanner.value = null
            when (val outcome = app.syncManager.syncAll()) {
                is SyncOutcome.Success -> {
                    val calText = outcome.calendarOutcome?.let { cal ->
                        if (cal.skippedReason != null) {
                            "（日曆：${cal.skippedReason}）"
                        } else {
                            "｜日曆新增 ${cal.createdCount}、更新 ${cal.updatedCount}"
                        }
                    }.orEmpty()
                    _statusBanner.value =
                        "同步完成：${outcome.courseCount} 門課、${outcome.assignmentCount} 項期限、${outcome.announcementCount} 則通知$calText"
                }
                is SyncOutcome.Failure -> {
                    _statusBanner.value = outcome.message
                }
            }
            _isLoggedIn.value = !app.tokenStore.getMoodleToken().isNullOrBlank()
            _isSyncing.value = false
        }
    }

    fun syncCalendarNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = app.syncManager.syncCalendarOnly()
            _statusBanner.value = if (outcome.skippedReason != null) {
                "日曆同步略過：${outcome.skippedReason}"
            } else {
                "已同步到「${outcome.resolvedCalendarName}」：新增 ${outcome.createdCount} 筆、更新 ${outcome.updatedCount} 筆"
            }
        }
    }

    fun toggleCourseEnabled(courseId: Long, enabled: Boolean) {
        app.cacheRepository.updateDashboardData { data ->
            data.copy(
                courses = data.courses.map {
                    if (it.id == courseId) it.copy(isEnabled = enabled) else it
                }
            )
        }
        MoodleWidgetProvider.notifyWidgetsDataChanged(app)
    }

    fun resetCoursesToAutoCurrentSemester() {
        app.cacheRepository.updateDashboardData { data ->
            data.copy(
                courses = data.courses.map {
                    it.copy(isEnabled = it.isCurrentSemester)
                }
            )
        }
        MoodleWidgetProvider.notifyWidgetsDataChanged(app)
        _statusBanner.value = "已套用自動偵測的本學期課程"
    }

    fun updatePreferredSsoMethod(method: SsoLoginMethod) {
        app.cacheRepository.updateSettings { it.copy(preferredSsoMethod = method) }
    }

    fun updateAutoCalendarSync(enabled: Boolean) {
        app.cacheRepository.updateSettings { it.copy(autoCalendarSyncEnabled = enabled) }
    }

    fun selectTargetCalendar(cal: DeviceCalendarInfo) {
        app.cacheRepository.updateSettings {
            it.copy(
                targetCalendarId = cal.id,
                targetCalendarName = "${cal.displayName} (${cal.accountName})"
            )
        }
        _statusBanner.value = "已指定同步日曆：${cal.displayName}"
    }

    fun updateSyncIntervalHours(hours: Long) {
        app.cacheRepository.updateSettings { it.copy(syncIntervalHours = hours) }
        if (!app.tokenStore.getMoodleToken().isNullOrBlank()) {
            MoodleSyncWorker.schedulePeriodicSync(app, hours)
        }
        _statusBanner.value = "已設定每 $hours 小時自動背景同步"
    }

    fun saveGeminiSettings(apiKeyInput: String?, enabled: Boolean) {
        if (apiKeyInput != null) {
            app.tokenStore.saveGeminiApiKey(apiKeyInput)
        }
        val hasKey = !app.tokenStore.getGeminiApiKey().isNullOrBlank()
        _hasGeminiKey.value = hasKey
        app.cacheRepository.updateSettings { it.copy(useGeminiSummary = enabled && hasKey) }
        _statusBanner.value = if (enabled && hasKey) {
            "已啟用 Gemini AI 摘要"
        } else {
            "已切換為免金鑰本地智慧摘要"
        }
    }

    fun loginWithManualCallback(rawBaseUrl: String, callbackOrToken: String) {
        viewModelScope.launch {
            val cleanBase = runCatching { MoodleTokenParser.normalizeBaseUrl(rawBaseUrl) }
                .getOrElse { err ->
                    _statusBanner.value = err.message ?: "Moodle 網址格式不正確"
                    return@launch
                }

            val parsed = MoodleTokenParser.parseCallbackUrl(callbackOrToken)
                ?: run {
                    _statusBanner.value = "無法解析 Token 或回傳網址"
                    return@launch
                }

            _isSyncing.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val info = app.apiClient.getSiteInfo(cleanBase, parsed.token)
                    app.tokenStore.saveMoodleToken(parsed.token)
                    val settings = app.cacheRepository.updateSettings {
                        it.copy(
                            baseUrl = info.siteUrl.ifBlank { cleanBase },
                            siteName = info.siteName.ifBlank { it.siteName },
                            userId = info.userId,
                            userFullName = info.fullName
                        )
                    }
                    MoodleSyncWorker.schedulePeriodicSync(app, settings.syncIntervalHours)
                }
            }.onSuccess {
                _isLoggedIn.value = true
                _isSyncing.value = false
                syncNow()
            }.onFailure { err ->
                _isSyncing.value = false
                _statusBanner.value = "驗證失敗：${err.message}"
            }
        }
    }

    suspend fun exportIcsFile(): File = withContext(Dispatchers.IO) {
        val data = app.cacheRepository.getDashboardData()
        val settings = app.cacheRepository.getSettings()
        val enabledIds = data.courses.filter { it.isEnabled }.map { it.id }.toSet()
        val filtered = data.assignments.filter { enabledIds.isEmpty() || it.courseId in enabledIds }
        app.calendarSyncManager.writeIcsFileToCache(filtered, settings.siteName)
    }

    fun logout() {
        app.tokenStore.clearMoodleToken()
        app.cacheRepository.clearSessionData()
        _isLoggedIn.value = false
        _statusBanner.value = "已登出並清除本機資料"
        MoodleWidgetProvider.notifyWidgetsDataChanged(app)
    }
}
