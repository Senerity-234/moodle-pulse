package com.moodlepulse.app.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.moodlepulse.app.MoodlePulseApp
import com.moodlepulse.app.model.SsoLoginMethod
import com.moodlepulse.app.network.MoodleTokenParser
import com.moodlepulse.app.sync.MoodleSyncWorker
import com.moodlepulse.app.ui.MainActivity
import com.moodlepulse.app.ui.theme.MoodlePulseTheme
import kotlinx.coroutines.launch
import java.util.UUID

class SsoLoginActivity : ComponentActivity() {

    private var baseUrl: String = ""
    private var launchUrl: String? by mutableStateOf(null)
    private var ssoMethod: SsoLoginMethod by mutableStateOf(SsoLoginMethod.IN_APP_WEBVIEW)
    private var statusMessage: String by mutableStateOf("正在偵測學校 Moodle SSO 設定…")
    private var errorMessage: String? by mutableStateOf(null)
    private var isVerifying: Boolean by mutableStateOf(false)
    private var loadProgress: Int by mutableIntStateOf(0)
    private var webViewRef: WebView? = null
    private var handledToken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (handleIncomingDeepLink(intent)) {
            renderUi()
            return
        }

        val rawUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty()
        val methodName = intent.getStringExtra(EXTRA_SSO_METHOD)
        ssoMethod = runCatching {
            SsoLoginMethod.valueOf(methodName ?: SsoLoginMethod.IN_APP_WEBVIEW.name)
        }.getOrDefault(SsoLoginMethod.IN_APP_WEBVIEW)

        if (rawUrl.isBlank()) {
            finish()
            return
        }

        baseUrl = runCatching { MoodleTokenParser.normalizeBaseUrl(rawUrl) }.getOrDefault(rawUrl)
        val app = applicationContext as MoodlePulseApp
        app.cacheRepository.updateSettings {
            it.copy(baseUrl = baseUrl, preferredSsoMethod = ssoMethod)
        }

        renderUi()
        prepareSsoLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingDeepLink(intent)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun renderUi() {
        setContent {
            MoodlePulseTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("學校 SSO 登入") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                            },
                            actions = {
                                val currentLaunch = launchUrl
                                if (currentLaunch != null && ssoMethod == SsoLoginMethod.IN_APP_WEBVIEW && !isVerifying) {
                                    TextButton(onClick = { openInCustomTab(currentLaunch) }) {
                                        Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                                        Text("改用瀏覽器", modifier = Modifier.padding(start = 4.dp))
                                    }
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when {
                            isVerifying || launchUrl == null && errorMessage == null -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(statusMessage, style = MaterialTheme.typography.bodyLarge)
                                }
                            }

                            errorMessage != null -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = errorMessage.orEmpty(),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Button(onClick = {
                                            errorMessage = null
                                            handledToken = false
                                            prepareSsoLaunch()
                                        }) {
                                            Text("重試")
                                        }
                                        val currentLaunch = launchUrl
                                        if (currentLaunch != null) {
                                            OutlinedButton(onClick = { openInCustomTab(currentLaunch) }) {
                                                Text("改用外部瀏覽器")
                                            }
                                        }
                                    }
                                }
                            }

                            ssoMethod == SsoLoginMethod.EXTERNAL_BROWSER_CUSTOM_TAB -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "已在系統瀏覽器開啟學校 SSO 登入頁面。\n登入成功後會自動跳回本 App。",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.height(20.dp))
                                    Button(onClick = { launchUrl?.let { openInCustomTab(it) } }) {
                                        Text("重新開啟瀏覽器登入")
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(onClick = {
                                        ssoMethod = SsoLoginMethod.IN_APP_WEBVIEW
                                    }) {
                                        Text("改用內建視窗登入")
                                    }
                                }
                            }

                            else -> {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    if (loadProgress in 1..99) {
                                        LinearProgressIndicator(
                                            progress = { loadProgress / 100f },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    SsoWebView(
                                        url = launchUrl.orEmpty(),
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @androidx.compose.runtime.Composable
    private fun SsoWebView(url: String, modifier: Modifier = Modifier) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    // Strip "; wv" so OAuth/OIDC identity providers don't block embedded login
                    settings.userAgentString = settings.userAgentString
                        .replace("; wv", "")
                        .replace("wv)", ")")

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loadProgress = newProgress
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val target = request?.url?.toString().orEmpty()
                            if (MoodleTokenParser.isTokenCallbackUrl(target)) {
                                processCallbackUrl(target)
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            pageUrl: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            val target = pageUrl.orEmpty()
                            if (MoodleTokenParser.isTokenCallbackUrl(target)) {
                                view?.stopLoading()
                                processCallbackUrl(target)
                            }
                        }
                    }

                    loadUrl(url)
                }
            },
            onRelease = { view ->
                view.stopLoading()
                view.destroy()
                if (webViewRef == view) webViewRef = null
            }
        )
    }

    private fun prepareSsoLaunch() {
        val app = applicationContext as MoodlePulseApp
        lifecycleScope.launch {
            statusMessage = "正在連線學校 Moodle 取得 SSO 入口…"
            val config = app.apiClient.getPublicConfig(baseUrl)
            baseUrl = config.wwwroot
            app.cacheRepository.updateSettings {
                it.copy(
                    baseUrl = config.wwwroot,
                    siteName = config.siteName
                )
            }
            val passport = UUID.randomUUID().toString().replace("-", "").take(16)
            val generatedLaunchUrl = app.apiClient.buildSsoLaunchUrl(
                config = config,
                passport = passport,
                urlScheme = "moodlepulse"
            )
            launchUrl = generatedLaunchUrl

            if (ssoMethod == SsoLoginMethod.EXTERNAL_BROWSER_CUSTOM_TAB) {
                openInCustomTab(generatedLaunchUrl)
            }
        }
    }

    private fun openInCustomTab(url: String) {
        runCatching {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(this, Uri.parse(url))
        }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun handleIncomingDeepLink(intent: Intent?): Boolean {
        val dataUrl = intent?.dataString ?: return false
        if (!MoodleTokenParser.isTokenCallbackUrl(dataUrl)) return false
        processCallbackUrl(dataUrl)
        return true
    }

    private fun processCallbackUrl(callbackUrl: String) {
        if (handledToken) return
        val parsed = MoodleTokenParser.parseCallbackUrl(callbackUrl)
        if (parsed == null) {
            errorMessage = "無法解析 Moodle 回傳的登入憑證，請再試一次。"
            return
        }

        handledToken = true
        isVerifying = true
        statusMessage = "登入成功！正在驗證帳號並準備同步課程…"

        val app = applicationContext as MoodlePulseApp
        val resolvedBaseUrl = baseUrl.ifBlank { app.cacheRepository.getSettings().baseUrl }
        if (resolvedBaseUrl.isBlank()) {
            isVerifying = false
            handledToken = false
            errorMessage = "遺失學校 Moodle 網址，請返回重試。"
            return
        }

        lifecycleScope.launch {
            runCatching {
                val siteInfo = app.apiClient.getSiteInfo(resolvedBaseUrl, parsed.token)
                app.tokenStore.saveMoodleToken(parsed.token)
                val settings = app.cacheRepository.updateSettings {
                    it.copy(
                        baseUrl = siteInfo.siteUrl.ifBlank { resolvedBaseUrl },
                        siteName = siteInfo.siteName.ifBlank { it.siteName },
                        userId = siteInfo.userId,
                        userFullName = siteInfo.fullName
                    )
                }
                MoodleSyncWorker.schedulePeriodicSync(app, settings.syncIntervalHours)
                MoodleSyncWorker.enqueueImmediateSync(app)
            }.onSuccess {
                setResult(RESULT_OK)
                val mainIntent = Intent(this@SsoLoginActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_TRIGGER_SYNC, true)
                }
                startActivity(mainIntent)
                finish()
            }.onFailure { err ->
                handledToken = false
                isVerifying = false
                errorMessage = "憑證驗證失敗：${err.message ?: "請重新登入"}"
            }
        }
    }

    override fun onDestroy() {
        webViewRef?.destroy()
        webViewRef = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BASE_URL = "extra_base_url"
        const val EXTRA_SSO_METHOD = "extra_sso_method"

        fun createIntent(
            context: Context,
            baseUrl: String,
            ssoMethod: SsoLoginMethod
        ): Intent {
            return Intent(context, SsoLoginActivity::class.java).apply {
                putExtra(EXTRA_BASE_URL, baseUrl)
                putExtra(EXTRA_SSO_METHOD, ssoMethod.name)
            }
        }
    }
}
