package com.moodlepulse.app.data

import android.content.Context
import android.util.AtomicFile
import com.moodlepulse.app.model.AppSettings
import com.moodlepulse.app.model.CachedDashboardData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets

class LocalCacheRepository(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val lock = Any()
    private val settingsFile = File(context.filesDir, "app_settings.json")
    private val dashboardFile = File(context.filesDir, "dashboard_cache.json")

    private val _settingsFlow = MutableStateFlow(readSettingsInternal())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    private val _dashboardFlow = MutableStateFlow(readDashboardInternal())
    val dashboardFlow: StateFlow<CachedDashboardData> = _dashboardFlow.asStateFlow()

    fun getSettings(): AppSettings = _settingsFlow.value

    fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings {
        synchronized(lock) {
            val updated = transform(_settingsFlow.value)
            writeAtomic(settingsFile, json.encodeToString(updated))
            _settingsFlow.value = updated
            return updated
        }
    }

    fun getDashboardData(): CachedDashboardData = _dashboardFlow.value

    fun updateDashboardData(transform: (CachedDashboardData) -> CachedDashboardData): CachedDashboardData {
        synchronized(lock) {
            val updated = transform(_dashboardFlow.value)
            writeAtomic(dashboardFile, json.encodeToString(updated))
            _dashboardFlow.value = updated
            return updated
        }
    }

    fun clearSessionData() {
        synchronized(lock) {
            val current = _settingsFlow.value
            val resetSettings = AppSettings(
                githubUpdateRepo = current.githubUpdateRepo,
                autoCheckAppUpdate = current.autoCheckAppUpdate
            )
            val resetData = CachedDashboardData()
            writeAtomic(settingsFile, json.encodeToString(resetSettings))
            writeAtomic(dashboardFile, json.encodeToString(resetData))
            _settingsFlow.value = resetSettings
            _dashboardFlow.value = resetData
        }
    }

    private fun readSettingsInternal(): AppSettings {
        return readAtomic(settingsFile)?.let { raw ->
            runCatching { json.decodeFromString<AppSettings>(raw) }.getOrNull()
        } ?: AppSettings()
    }

    private fun readDashboardInternal(): CachedDashboardData {
        return readAtomic(dashboardFile)?.let { raw ->
            runCatching { json.decodeFromString<CachedDashboardData>(raw) }.getOrNull()
        } ?: CachedDashboardData()
    }

    private fun readAtomic(file: File): String? {
        if (!file.exists()) return null
        return runCatching {
            val atomicFile = AtomicFile(file)
            String(atomicFile.readFully(), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun writeAtomic(file: File, content: String) {
        val atomicFile = AtomicFile(file)
        val out = atomicFile.startWrite()
        try {
            out.write(content.toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(out)
        } catch (t: Throwable) {
            atomicFile.failWrite(out)
            throw t
        }
    }
}
