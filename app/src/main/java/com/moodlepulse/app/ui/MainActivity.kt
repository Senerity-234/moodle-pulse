package com.moodlepulse.app.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.moodlepulse.app.BuildConfig
import com.moodlepulse.app.MoodlePulseApp
import com.moodlepulse.app.auth.SsoLoginActivity
import com.moodlepulse.app.model.AnnouncementItem
import com.moodlepulse.app.model.AssignmentItem
import com.moodlepulse.app.model.CourseItem
import com.moodlepulse.app.model.DeadlineModuleType
import com.moodlepulse.app.model.SsoLoginMethod
import com.moodlepulse.app.network.MoodleTokenParser
import com.moodlepulse.app.ui.theme.MoodlePulseTheme
import com.moodlepulse.app.update.GitHubReleaseInfo
import com.moodlepulse.app.widget.MoodleWidgetProvider
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.getBooleanExtra(EXTRA_TRIGGER_SYNC, false)) {
            viewModel.refreshSessionState()
            viewModel.syncNow()
        }

        setContent {
            MoodlePulseTheme {
                val state by viewModel.uiState.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                val app = application as MoodlePulseApp
                var pendingInstallFile by remember { mutableStateOf<File?>(null) }

                val installPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    val file = pendingInstallFile
                    if (file != null && file.exists() && app.updateManager.canInstallPackages()) {
                        pendingInstallFile = null
                        startActivity(app.updateManager.createInstallApkIntent(file))
                    } else if (file != null) {
                        pendingInstallFile = null
                    }
                }

                LaunchedEffect(state.statusBanner) {
                    val msg = state.statusBanner
                    if (!msg.isNullOrBlank()) {
                        snackbarHostState.showSnackbar(msg)
                        viewModel.clearBanner()
                    }
                }

                LaunchedEffect(state.updateState.pendingInstallTrigger) {
                    if (state.updateState.pendingInstallTrigger) {
                        viewModel.consumeInstallTrigger()
                        val apkFile = state.updateState.downloadedApkFile
                        if (apkFile != null && apkFile.exists()) {
                            if (app.updateManager.canInstallPackages()) {
                                startActivity(app.updateManager.createInstallApkIntent(apkFile))
                            } else {
                                pendingInstallFile = apkFile
                                installPermissionLauncher.launch(app.updateManager.createInstallPermissionIntent())
                            }
                        }
                    }
                }

                val availableRelease = state.updateState.availableUpdate
                if (state.updateState.showUpdateDialog && availableRelease != null) {
                    AppUpdateDialog(
                        release = availableRelease,
                        isDownloading = state.updateState.isDownloading,
                        progressPercent = state.updateState.downloadProgressPercent,
                        hasDownloadedApk = state.updateState.downloadedApkFile?.exists() == true,
                        onDownloadAndInstall = viewModel::downloadAndInstallUpdate,
                        onOpenReleasePage = {
                            openUrlInBrowser(this@MainActivity, availableRelease.htmlUrl)
                        },
                        onSkipVersion = { viewModel.dismissUpdateDialog(skipThisVersion = true) },
                        onDismiss = { viewModel.dismissUpdateDialog(skipThisVersion = false) }
                    )
                }

                if (!state.isLoggedIn) {
                    OnboardingLoginScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onLaunchSso = { url, method ->
                            runCatching {
                                MoodleTokenParser.normalizeBaseUrl(url)
                            }.onSuccess { normalized ->
                                startActivity(SsoLoginActivity.createIntent(this, normalized, method))
                            }
                        },
                        onManualTokenSubmit = { url, tokenOrCallback ->
                            viewModel.loginWithManualCallback(url, tokenOrCallback)
                        }
                    )
                } else {
                    MainSignedInScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSessionState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.refreshSessionState()
        if (intent.getBooleanExtra(EXTRA_TRIGGER_SYNC, false)) {
            viewModel.syncNow()
        }
    }

    companion object {
        const val EXTRA_TRIGGER_SYNC = "extra_trigger_sync"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingLoginScreen(
    state: MainUiState,
    snackbarHostState: SnackbarHostState,
    onLaunchSso: (String, SsoLoginMethod) -> Unit,
    onManualTokenSubmit: (String, String) -> Unit
) {
    var moodleUrl by rememberSaveable { mutableStateOf(state.settings.baseUrl) }
    var urlError by rememberSaveable { mutableStateOf<String?>(null) }
    var ssoMethod by rememberSaveable { mutableStateOf(state.settings.preferredSsoMethod) }
    var showManualFallback by rememberSaveable { mutableStateOf(false) }
    var manualCallbackText by rememberSaveable { mutableStateOf("") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text("Moodle 課程小幫手") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "這學期作業與通知，桌面一眼掌握",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "透過學校 SSO 跳轉登入一次後，即可在背景自動彙整作業期限、提取課程公告重點，並同步到手機桌面小工具與日曆。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            OutlinedTextField(
                value = moodleUrl,
                onValueChange = {
                    moodleUrl = it
                    urlError = null
                },
                label = { Text("學校 Moodle 網址") },
                placeholder = { Text("例如 https://moodle.ntu.edu.tw") },
                isError = urlError != null,
                supportingText = urlError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "SSO 登入方式",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ssoMethod == SsoLoginMethod.IN_APP_WEBVIEW,
                    onClick = { ssoMethod = SsoLoginMethod.IN_APP_WEBVIEW },
                    label = { Text("App 內建登入視窗 (推薦)") }
                )
                FilterChip(
                    selected = ssoMethod == SsoLoginMethod.EXTERNAL_BROWSER_CUSTOM_TAB,
                    onClick = { ssoMethod = SsoLoginMethod.EXTERNAL_BROWSER_CUSTOM_TAB },
                    label = { Text("外部瀏覽器跳轉") }
                )
            }

            Button(
                onClick = {
                    val normalized = runCatching { MoodleTokenParser.normalizeBaseUrl(moodleUrl) }
                    if (normalized.isFailure) {
                        urlError = normalized.exceptionOrNull()?.message ?: "請輸入有效的網址"
                    } else {
                        onLaunchSso(normalized.getOrThrow(), ssoMethod)
                    }
                },
                enabled = !state.isSyncing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (state.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("前往學校 SSO 登入")
            }

            TextButton(
                onClick = { showManualFallback = !showManualFallback },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(if (showManualFallback) "收起進階登入" else "進階：手動貼上回傳連結 / Token")
            }

            AnimatedVisibility(visible = showManualFallback) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = manualCallbackText,
                        onValueChange = { manualCallbackText = it },
                        label = { Text("moodlemobile://token=... 或 32 碼 Token") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { onManualTokenSubmit(moodleUrl, manualCallbackText) },
                        enabled = moodleUrl.isNotBlank() && manualCallbackText.isNotBlank() && !state.isSyncing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("驗證並登入")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainSignedInScreen(
    state: MainUiState,
    snackbarHostState: SnackbarHostState,
    viewModel: MainViewModel
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.settings.siteName.ifBlank { "Moodle 課程小幫手" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (state.settings.userFullName.isNotBlank()) {
                            Text(
                                text = state.settings.userFullName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.syncNow() },
                        enabled = !state.isSyncing
                    ) {
                        if (state.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "立即同步")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("總覽") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.School, contentDescription = null) },
                    label = { Text("課程") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("設定") }
                )
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> DashboardTab(
                state = state,
                modifier = Modifier.padding(innerPadding),
                onFilterChange = viewModel::setFilter,
                onSyncNow = viewModel::syncNow
            )
            1 -> CoursesTab(
                courses = state.dashboard.courses,
                modifier = Modifier.padding(innerPadding),
                onToggleCourse = viewModel::toggleCourseEnabled,
                onAutoDetectSemester = viewModel::resetCoursesToAutoCurrentSemester
            )
            else -> SettingsTab(
                state = state,
                modifier = Modifier.padding(innerPadding),
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun DashboardTab(
    state: MainUiState,
    modifier: Modifier = Modifier,
    onFilterChange: (DashboardFilter) -> Unit,
    onSyncNow: () -> Unit
) {
    val context = LocalContext.current
    val enabledCourseIds = remember(state.dashboard.courses) {
        state.dashboard.courses.filter { it.isEnabled }.map { it.id }.toSet()
    }
    val nowSec = System.currentTimeMillis() / 1000L

    val assignments = remember(state.dashboard.assignments, enabledCourseIds) {
        state.dashboard.assignments
            .filter { (enabledCourseIds.isEmpty() || it.courseId in enabledCourseIds) && it.dueAtSec >= nowSec - 86400L }
            .sortedWith(compareBy({ it.submitted }, { it.dueAtSec }))
    }

    val announcements = remember(state.dashboard.announcements, enabledCourseIds) {
        state.dashboard.announcements
            .filter { it.courseId == 0L || enabledCourseIds.isEmpty() || it.courseId in enabledCourseIds }
            .sortedByDescending { it.postedAtSec }
    }

    val pendingCount = assignments.count { !it.submitted && it.dueAtSec >= nowSec }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SummaryHeaderCard(
                pendingCount = pendingCount,
                announcementCount = announcements.size,
                lastSyncEpochSec = state.dashboard.lastSyncEpochSec,
                lastError = state.dashboard.lastSyncError,
                nextDeadline = assignments.firstOrNull { !it.submitted && it.dueAtSec >= nowSec },
                onSyncNow = onSyncNow
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.selectedFilter == DashboardFilter.ALL,
                    onClick = { onFilterChange(DashboardFilter.ALL) },
                    label = { Text("全部") }
                )
                FilterChip(
                    selected = state.selectedFilter == DashboardFilter.PENDING_ASSIGNMENTS,
                    onClick = { onFilterChange(DashboardFilter.PENDING_ASSIGNMENTS) },
                    label = { Text("作業期限 (${assignments.size})") }
                )
                FilterChip(
                    selected = state.selectedFilter == DashboardFilter.ANNOUNCEMENTS,
                    onClick = { onFilterChange(DashboardFilter.ANNOUNCEMENTS) },
                    label = { Text("通知摘要 (${announcements.size})") }
                )
            }
        }

        if (state.selectedFilter != DashboardFilter.ANNOUNCEMENTS) {
            if (assignments.isNotEmpty()) {
                item {
                    SectionTitle("近期作業與測驗期限")
                }
                items(assignments, key = { it.key }) { item ->
                    AssignmentCard(
                        item = item,
                        nowSec = nowSec,
                        onOpenUrl = { url -> openUrlInBrowser(context, url) }
                    )
                }
            }
        }

        if (state.selectedFilter != DashboardFilter.PENDING_ASSIGNMENTS) {
            if (announcements.isNotEmpty()) {
                item {
                    SectionTitle("課程公告與通知摘要")
                }
                items(announcements, key = { it.key }) { item ->
                    AnnouncementCard(
                        item = item,
                        onOpenUrl = { url -> openUrlInBrowser(context, url) }
                    )
                }
            }
        }

        if (assignments.isEmpty() && announcements.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("目前沒有可顯示的作業或通知", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "可點擊右上角重新整理，或到「課程」分頁確認已勾選這學期課程。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryHeaderCard(
    pendingCount: Int,
    announcementCount: Int,
    lastSyncEpochSec: Long,
    lastError: String?,
    nextDeadline: AssignmentItem?,
    onSyncNow: () -> Unit
) {
    val formatter = remember {
        DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault())
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "待繳作業 $pendingCount 項 · 通知 $announcementCount 則",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (lastSyncEpochSec > 0L) {
                    Text(
                        text = "${formatter.format(Instant.ofEpochSecond(lastSyncEpochSec))} 更新",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            if (nextDeadline != null) {
                Text(
                    text = "最近期限：[${nextDeadline.courseName}] ${nextDeadline.title}（${formatter.format(Instant.ofEpochSecond(nextDeadline.dueAtSec))}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            if (!lastError.isNullOrBlank()) {
                Text(
                    text = "上次同步提示：$lastError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onSyncNow, contentPadding = PaddingValues(0.dp)) {
                    Text("立即重試同步")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun AssignmentCard(
    item: AssignmentItem,
    nowSec: Long,
    onOpenUrl: (String) -> Unit
) {
    val formatter = remember {
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault())
    }
    val remainingSec = item.dueAtSec - nowSec
    val accentColor = when {
        item.submitted -> Color(0xFF16A34A)
        remainingSec <= 0L -> Color(0xFFDC2626)
        remainingSec <= 86400L -> Color(0xFFEA580C)
        remainingSec <= 3L * 86400L -> Color(0xFFD97706)
        else -> Color(0xFF2563EB)
    }

    val badgeText = when {
        item.submitted -> "已繳交"
        remainingSec <= 0L -> "已逾期"
        remainingSec < 3600L -> "即將截止"
        remainingSec < 86400L -> "剩 ${remainingSec / 3600L} 小時"
        else -> "剩 ${remainingSec / 86400L} 天"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(accentColor)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.courseName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = accentColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = accentColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.submitted) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF16A34A),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    } else if (item.moduleType == DeadlineModuleType.QUIZ) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "截止：${formatter.format(Instant.ofEpochSecond(item.dueAtSec))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (item.descriptionSummary.isNotBlank()) {
                    Text(
                        text = item.descriptionSummary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (item.url.isNotBlank()) {
                IconButton(onClick = { onOpenUrl(item.url) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "在瀏覽器開啟")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnouncementCard(
    item: AnnouncementItem,
    onOpenUrl: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember {
        DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault())
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.courseName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.postedAtSec > 0L) {
                    Text(
                        text = dateFormat.format(Instant.ofEpochSecond(item.postedAtSec)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            if (item.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.tags.forEach { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Text(
                text = "重點摘要：${item.summary}",
                style = MaterialTheme.typography.bodyMedium
            )

            AnimatedVisibility(visible = expanded && item.fullText.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    if (item.author.isNotBlank()) {
                        Text(
                            text = "發布者：${item.author}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = item.fullText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(if (expanded) "收起全文" else "展開完整通知")
                }

                if (item.url.isNotBlank()) {
                    TextButton(
                        onClick = { onOpenUrl(item.url) },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("前往 Moodle")
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoursesTab(
    courses: List<CourseItem>,
    modifier: Modifier = Modifier,
    onToggleCourse: (Long, Boolean) -> Unit,
    onAutoDetectSemester: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "這學期課程篩選",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "勾選的課程才會同步作業與公告到桌面小工具與手機日曆。你可以把舊學期課程取消勾選。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onAutoDetectSemester) {
                        Text("重設為自動偵測的本學期課程")
                    }
                }
            }
        }

        items(courses, key = { it.id }) { course ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = course.fullName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = buildString {
                                append(course.shortName)
                                if (course.isCurrentSemester) append(" · 偵測為本學期")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = course.isEnabled,
                        onCheckedChange = { checked -> onToggleCourse(course.id, checked) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsTab(
    state: MainUiState,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var geminiKeyInput by remember { mutableStateOf("") }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshCalendarPermissions()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Widget card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Widgets, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("手機桌面小工具", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "把「Moodle 作業與通知摘要」小工具加到手機桌面，不開 App 也能看期限倒數與通知重點。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val appWidgetManager = AppWidgetManager.getInstance(context)
                        val provider = ComponentName(context, MoodleWidgetProvider::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            appWidgetManager.isRequestPinAppWidgetSupported
                        ) {
                            appWidgetManager.requestPinAppWidget(provider, null, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("一鍵釘選桌面小工具")
                }
            }
        }

        // Calendar sync card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("日曆自動寫入設定", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }

                if (!state.hasCalendarPermission) {
                    Text(
                        text = "需要授予「日曆讀寫權限」，才能自動將作業期限寫入你的手機日曆或 Google 日曆帳號。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(
                        onClick = {
                            calendarPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_CALENDAR,
                                    Manifest.permission.WRITE_CALENDAR
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("授予日曆權限")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("同步資料時自動寫入日曆", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.settings.autoCalendarSyncEnabled,
                            onCheckedChange = viewModel::updateAutoCalendarSync
                        )
                    }

                    if (state.writableCalendars.isNotEmpty()) {
                        Text(
                            text = "寫入目標日曆（選 Google 帳號即可雲端同步）：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        state.writableCalendars.forEach { cal ->
                            val isSelected = cal.id == state.settings.targetCalendarId ||
                                (state.settings.targetCalendarId <= 0L && cal.isPrimary)
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.selectTargetCalendar(cal) },
                                label = {
                                    Text(
                                        text = "${cal.displayName} (${cal.accountName})",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = viewModel::syncCalendarNow,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("立即寫入日曆")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val file = viewModel.exportIcsFile()
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/calendar"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "匯出作業行事曆 (.ics)"))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("匯出 .ics")
                        }
                    }
                }
            }
        }

        // Background sync & AI summarizer card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("背景更新與摘要引擎", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = "背景自動同步頻率",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2L, 4L, 6L, 12L).forEach { hours ->
                        FilterChip(
                            selected = state.settings.syncIntervalHours == hours,
                            onClick = { viewModel.updateSyncIntervalHours(hours) },
                            label = { Text("每 ${hours} 小時") }
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Gemini AI 智慧摘要（選填）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (state.hasGeminiKeySaved) "已儲存 API Key，可強化長篇公告摘要" else "未設定時使用免費離線重點抽取",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.settings.useGeminiSummary,
                        onCheckedChange = { checked ->
                            viewModel.saveGeminiSettings(geminiKeyInput.ifBlank { null }, checked)
                        }
                    )
                }

                OutlinedTextField(
                    value = geminiKeyInput,
                    onValueChange = { geminiKeyInput = it },
                    label = { Text("更新 Gemini API Key（選填）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.saveGeminiSettings(geminiKeyInput, true)
                            geminiKeyInput = ""
                        },
                        enabled = geminiKeyInput.isNotBlank()
                    ) {
                        Text("儲存 Key 並啟用")
                    }
                    if (state.hasGeminiKeySaved) {
                        TextButton(onClick = { viewModel.saveGeminiSettings("", false) }) {
                            Text("清除 Key")
                        }
                    }
                }
            }
        }

        // Account card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Moodle 連線帳號", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = "網站：${state.settings.baseUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                SsoLoginActivity.createIntent(
                                    context,
                                    state.settings.baseUrl,
                                    state.settings.preferredSsoMethod
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("重新 SSO 登入")
                    }
                    AssistChip(
                        onClick = viewModel::logout,
                        label = { Text("登出清除", color = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }

        // GitHub self-update card
        GitHubUpdateCard(
            state = state,
            onSaveRepo = viewModel::updateGitHubRepo,
            onToggleAutoCheck = viewModel::updateAutoCheckAppUpdate,
            onSaveToken = viewModel::saveGitHubToken,
            onCheckNow = { viewModel.checkForAppUpdate(manual = true) },
            onDownloadAndInstall = viewModel::downloadAndInstallUpdate,
            onOpenReleaseUrl = { url -> openUrlInBrowser(context, url) }
        )
    }
}

@Composable
private fun GitHubUpdateCard(
    state: MainUiState,
    onSaveRepo: (String) -> Unit,
    onToggleAutoCheck: (Boolean) -> Unit,
    onSaveToken: (String) -> Unit,
    onCheckNow: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onOpenReleaseUrl: (String) -> Unit
) {
    var repoInput by remember(state.settings.githubUpdateRepo) {
        mutableStateOf(state.settings.githubUpdateRepo)
    }
    var showTokenField by rememberSaveable { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    val updateState = state.updateState

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("GitHub 自我更新", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = "目前版本：v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("開啟 App 時自動檢查更新", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.settings.autoCheckAppUpdate,
                    onCheckedChange = onToggleAutoCheck
                )
            }

            OutlinedTextField(
                value = repoInput,
                onValueChange = { repoInput = it },
                label = { Text("GitHub Repo（owner/repo）") },
                placeholder = { Text("例如 Senerity-234/moodle-pulse") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSaveRepo(repoInput) },
                    enabled = repoInput.isNotBlank() && repoInput.trim() != state.settings.githubUpdateRepo,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("儲存來源")
                }
                Button(
                    onClick = onCheckNow,
                    enabled = !updateState.isChecking && !updateState.isDownloading,
                    modifier = Modifier.weight(1f)
                ) {
                    if (updateState.isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("立即檢查更新")
                }
            }

            if (!updateState.statusMessage.isNullOrBlank()) {
                Text(
                    text = updateState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val release = updateState.availableUpdate
            if (release != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "可更新至 ${release.tagName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (release.releaseNotes.isNotBlank()) {
                            Text(
                                text = release.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        if (updateState.isDownloading) {
                            LinearProgressIndicator(
                                progress = { updateState.downloadProgressPercent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "下載中 ${updateState.downloadProgressPercent}%",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        val hasApk = !release.apkDownloadUrl.isNullOrBlank() || !release.apkAssetApiUrl.isNullOrBlank()
                        if (hasApk) {
                            Button(
                                onClick = onDownloadAndInstall,
                                enabled = !updateState.isDownloading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (updateState.downloadedApkFile?.exists() == true) {
                                        "立即安裝 ${release.tagName} APK"
                                    } else {
                                        "下載並覆蓋安裝 ${release.tagName}"
                                    }
                                )
                            }
                        } else if (release.htmlUrl.isNotBlank()) {
                            OutlinedButton(
                                onClick = { onOpenReleaseUrl(release.htmlUrl) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("前往 GitHub Release 下載")
                            }
                        }
                    }
                }
            }

            TextButton(
                onClick = { showTokenField = !showTokenField },
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    if (showTokenField) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    if (state.updateState.hasGitHubTokenSaved) {
                        "私人 Repo Token（已儲存）"
                    } else {
                        "進階：私人 Repo Token（公開 Repo 免填）"
                    }
                )
            }

            AnimatedVisibility(visible = showTokenField) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "若你的 GitHub Repo 設為 Private，需填入具備 Releases 讀取權限的 Personal Access Token（僅加密儲存在本機）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("GitHub Personal Access Token（選填）") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                onSaveToken(tokenInput)
                                tokenInput = ""
                            },
                            enabled = tokenInput.isNotBlank()
                        ) {
                            Text("儲存 Token")
                        }
                        if (updateState.hasGitHubTokenSaved) {
                            TextButton(onClick = { onSaveToken("") }) {
                                Text("清除 Token")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppUpdateDialog(
    release: GitHubReleaseInfo,
    isDownloading: Boolean,
    progressPercent: Int,
    hasDownloadedApk: Boolean,
    onDownloadAndInstall: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onSkipVersion: () -> Unit,
    onDismiss: () -> Unit
) {
    val hasApk = !release.apkDownloadUrl.isNullOrBlank() || !release.apkAssetApiUrl.isNullOrBlank()
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text("發現新版本 ${release.tagName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "目前版本：v${BuildConfig.VERSION_NAME} → 最新版本：${release.tagName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (release.releaseNotes.isNotBlank()) {
                    Text(
                        text = release.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isDownloading) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "正在下載更新檔... $progressPercent%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            if (hasApk) {
                Button(
                    onClick = onDownloadAndInstall,
                    enabled = !isDownloading
                ) {
                    Text(
                        when {
                            isDownloading -> "下載中..."
                            hasDownloadedApk -> "立即安裝"
                            else -> "下載並更新"
                        }
                    )
                }
            } else if (release.htmlUrl.isNotBlank()) {
                Button(onClick = onOpenReleasePage) {
                    Text("前往 Release")
                }
            }
        },
        dismissButton = {
            Row {
                if (!isDownloading) {
                    TextButton(onClick = onSkipVersion) {
                        Text("略過此版")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("稍後")
                    }
                }
            }
        }
    )
}

private fun openUrlInBrowser(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
