package com.moodlepulse.app

import android.app.Application
import com.moodlepulse.app.calendar.CalendarSyncManager
import com.moodlepulse.app.data.LocalCacheRepository
import com.moodlepulse.app.data.SecureTokenStore
import com.moodlepulse.app.network.MoodleApiClient
import com.moodlepulse.app.sync.MoodleSyncManager
import com.moodlepulse.app.sync.MoodleSyncWorker
import com.moodlepulse.app.update.GitHubUpdateManager

class MoodlePulseApp : Application() {

    lateinit var tokenStore: SecureTokenStore
        private set

    lateinit var cacheRepository: LocalCacheRepository
        private set

    lateinit var apiClient: MoodleApiClient
        private set

    lateinit var calendarSyncManager: CalendarSyncManager
        private set

    lateinit var syncManager: MoodleSyncManager
        private set

    lateinit var updateManager: GitHubUpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        tokenStore = SecureTokenStore(this)
        cacheRepository = LocalCacheRepository(this)
        apiClient = MoodleApiClient()
        calendarSyncManager = CalendarSyncManager(this)
        syncManager = MoodleSyncManager(
            context = this,
            apiClient = apiClient,
            cacheRepository = cacheRepository,
            tokenStore = tokenStore,
            calendarSyncManager = calendarSyncManager
        )
        updateManager = GitHubUpdateManager(this)

        val settings = cacheRepository.getSettings()
        if (!tokenStore.getMoodleToken().isNullOrBlank()) {
            MoodleSyncWorker.schedulePeriodicSync(this, settings.syncIntervalHours)
        }
    }
}
