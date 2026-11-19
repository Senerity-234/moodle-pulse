package com.moodlepulse.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moodlepulse.app.BuildConfig
import com.moodlepulse.app.MoodlePulseApp
import com.moodlepulse.app.model.AppSettings
import com.moodlepulse.app.model.CachedDashboardData
import com.moodlepulse.app.model.DeviceCalendarInfo
import com.moodlepulse.app.model.SsoLoginMethod
import com.moodlepulse.app.network.MoodleTokenParser
import com.moodlepulse.app.sync.MoodleSyncWorker
import com.moodlepulse.app.sync.SyncOutcome
import com.moodlepulse.app.update.GitHubReleaseInfo
import com.moodlepulse.app.update.GitHubReleaseParser
import com.moodlepulse.app.update.UpdateCheckResult
import com.moodlepulse.app.widget.MoodleWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class DashboardFilter {
    ALL,
    PENDING_ASSIGNMENTS,
    ANNOUNCEMENTS
}

data class AppUpdateUiState(
    val hasGitHubTokenSaved: Boolean = false,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgressPercent: Int = 0,
    val availableUpdate: GitHubReleaseInfo? = null,
    val showUpdateDialog: Boolean = false,
    val downloadedApkFile: File? = null,
    val pendingInstallTrigger: Boolean = false,
    val statusMessage: String? = null
)

data class MainUiState(
    val isLoggedIn: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val dashboard: CachedDashboardData = CachedDashboardData(),
    val isSyncing: Boolean = false,
    val statusBanner: String? = null,
    val hasCalendarPermission: Boolean = false,
    val writableCalendars: List<DeviceCalendarInfo> = emptyList(),
    val hasGeminiKeySaved: Boolean = false,
    val selectedFilter: DashboardFilter = DashboardFilter.ALL,
    val updateState: AppUpdateUiState = AppUpdateUiState()
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
    private val _updateState = MutableStateFlow(
        AppUpdateUiState(hasGitHubTokenSaved = !app.tokenStore.getGitHubToken().isNullOrBlank())
    )

    val uiState: StateFlow<MainUiState> = combine(
        app.cacheRepository.settingsFlow,
        app.cacheRepository.dashboardFlow,
        _isSyncing,
        _statusBanner,
        _selectedFilter,
        _calendars,
        _hasCalendarPermission,
        _isLoggedIn,
        _hasGeminiKey,
        _updateState
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
        val updateState = values[9] as AppUpdateUiState

        MainUiState(
            isLoggedIn = loggedIn,
            settings = settings,
            dashboard = dashboard,
            isSyncing = syncing,
            statusBanner = banner,
            hasCalendarPermission = calPerm,
            writableCalendars = calendars,
            hasGeminiKeySaved = hasGemini,
            selectedFilter = filter,
            updateState = updateState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState(
            isLoggedIn = !app.tokenStore.getMoodleToken().isNullOrBlank(),
            settings = app.cacheRepository.getSettings(),
            dashboard = app.cacheRepository.getDashboardData(),
            hasCalendarPermission = app.calendarSyncManager.hasCalendarPermissions(),
            hasGeminiKeySaved = !app.tokenStore.getGeminiApiKey().isNullOrBlank(),
            updateState = AppUpdateUiState(
                hasGitHubTokenSaved = !app.tokenStore.getGitHubToken().isNullOrBlank()
            )
        )
    )

    init {
        refreshCalendarPermissions()
        val initialSettings = app.cacheRepository.getSettings()
        if (initialSettings.autoCheckAppUpdate && initialSettings.githubUpdateRepo.isNotBlank()) {
            checkForAppUpdate(manual = false)
        }
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

    fun updateGitHubRepo(rawRepo: String) {
        val slug = GitHubReleaseParser.normalizeRepoSlug(rawRepo)
        if (slug == null) {
            _statusBanner.value = "GitHub Repo 格式不正確，請輸入 owner/repo"
            return
        }
        app.cacheRepository.updateSettings {
            it.copy(githubUpdateRepo = slug, dismissedUpdateTag = "")
        }
        _updateState.update {
            it.copy(availableUpdate = null, downloadedApkFile = null, statusMessage = "已儲存更新來源：$slug")
        }
        _statusBanner.value = "已更新 GitHub 檢查來源：$slug"
    }

    fun updateAutoCheckAppUpdate(enabled: Boolean) {
        app.cacheRepository.updateSettings { it.copy(autoCheckAppUpdate = enabled) }
    }

    fun saveGitHubToken(tokenInput: String) {
        app.tokenStore.saveGitHubToken(tokenInput)
        val hasToken = !app.tokenStore.getGitHubToken().isNullOrBlank()
        _updateState.update {
            it.copy(
                hasGitHubTokenSaved = hasToken,
                statusMessage = if (hasToken) "已儲存私人 Repo Token" else "已清除 GitHub Token"
            )
        }
        _statusBanner.value = if (hasToken) "已安全儲存 GitHub Token" else "已清除 GitHub Token"
    }

    fun checkForAppUpdate(manual: Boolean = true) {
        if (_updateState.value.isChecking || _updateState.value.isDownloading) return
        val settings = app.cacheRepository.getSettings()
        val repo = settings.githubUpdateRepo
        if (repo.isBlank()) {
            if (manual) _statusBanner.value = "請先設定 GitHub Repo（例如 Senerity-234/moodle-pulse）"
            return
        }

        viewModelScope.launch {
            _updateState.update {
                it.copy(
                    isChecking = true,
                    statusMessage = if (manual) "正在檢查 GitHub 最新版本..." else it.statusMessage
                )
            }

            val result = app.updateManager.checkForUpdate(
                repoInput = repo,
                currentVersionName = BuildConfig.VERSION_NAME,
                githubToken = app.tokenStore.getGitHubToken()
            )

            when (result) {
                is UpdateCheckResult.UpdateAvailable -> {
                    val shouldShowDialog = manual || result.release.tagName != settings.dismissedUpdateTag
                    _updateState.update {
                        it.copy(
                            isChecking = false,
                            availableUpdate = result.release,
                            showUpdateDialog = shouldShowDialog,
                            downloadedApkFile = null,
                            statusMessage = "發現新版本 ${result.release.tagName}"
                        )
                    }
                    if (manual && result.release.apkDownloadUrl.isNullOrBlank() && result.release.apkAssetApiUrl.isNullOrBlank()) {
                        _statusBanner.value = "偵測到新版本 ${result.release.tagName}，但該 Release 未附加 APK 檔案"
                    }
                }
                is UpdateCheckResult.UpToDate -> {
                    _updateState.update {
                        it.copy(
                            isChecking = false,
                            availableUpdate = null,
                            showUpdateDialog = false,
                            statusMessage = if (manual) "目前 v${result.currentVersion} 已是最新版本" else it.statusMessage
                        )
                    }
                    if (manual) {
                        _statusBanner.value = "目前 v${result.currentVersion} 已是最新版本"
                    }
                }
                is UpdateCheckResult.Error -> {
                    _updateState.update {
                        it.copy(
                            isChecking = false,
                            statusMessage = if (manual) result.message else it.statusMessage
                        )
                    }
                    if (manual) {
                        _statusBanner.value = result.message
                    }
                }
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val release = _updateState.value.availableUpdate ?: return
        if (_updateState.value.isDownloading) return

        val cached = _updateState.value.downloadedApkFile
        if (cached != null && cached.exists() && cached.length() > 0L) {
            _updateState.update { it.copy(pendingInstallTrigger = true) }
            return
        }

        viewModelScope.launch {
            _updateState.update {
                it.copy(
                    isDownloading = true,
                    downloadProgressPercent = 0,
                    statusMessage = "正在下載 ${release.tagName}..."
                )
            }

            runCatching {
                app.updateManager.downloadApk(
                    release = release,
                    githubToken = app.tokenStore.getGitHubToken(),
                    onProgress = { pct ->
                        _updateState.update { state -> state.copy(downloadProgressPercent = pct) }
                    }
                )
            }.onSuccess { apkFile ->
                _updateState.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgressPercent = 100,
                        downloadedApkFile = apkFile,
                        pendingInstallTrigger = true,
                        statusMessage = "下載完成，準備安裝 ${release.tagName}"
                    )
                }
            }.onFailure { err ->
                val msg = err.message ?: "下載失敗"
                _updateState.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgressPercent = 0,
                        statusMessage = msg
                    )
                }
                _statusBanner.value = "更新下載失敗：$msg"
            }
        }
    }

    fun consumeInstallTrigger() {
        _updateState.update { it.copy(pendingInstallTrigger = false) }
    }

    fun dismissUpdateDialog(skipThisVersion: Boolean) {
        val tag = _updateState.value.availableUpdate?.tagName
        if (skipThisVersion && !tag.isNullOrBlank()) {
            app.cacheRepository.updateSettings { it.copy(dismissedUpdateTag = tag) }
            _statusBanner.value = "已略過版本 $tag（仍可在設定手動檢查）"
        }
        _updateState.update { it.copy(showUpdateDialog = false) }
    }
}
