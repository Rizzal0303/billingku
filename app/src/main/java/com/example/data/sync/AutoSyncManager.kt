package com.example.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AutoSyncManager(
    private val networkMonitor: NetworkMonitor,
    private val mysqlSyncManager: MysqlSyncManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private var periodicJob: Job? = null

    init {
        // Automatically trigger sync as soon as internet connection is restored
        networkMonitor.setOnNetworkAvailableListener {
            scope.launch {
                if (mysqlSyncManager.isAutoSyncEnabled() && mysqlSyncManager.getApiUrl().isNotBlank()) {
                    mysqlSyncManager.performFullAutoSync()
                }
            }
        }
    }

    fun startPeriodicSync() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            // Initial sync on app launch / start if connected
            if (networkMonitor.isOnline.value && mysqlSyncManager.isAutoSyncEnabled() && mysqlSyncManager.getApiUrl().isNotBlank()) {
                mysqlSyncManager.performFullAutoSync()
            }

            while (isActive) {
                delay(60_000L) // Poll every 60 seconds to ensure multi-device consistency
                if (networkMonitor.isOnline.value && mysqlSyncManager.isAutoSyncEnabled() && mysqlSyncManager.getApiUrl().isNotBlank()) {
                    mysqlSyncManager.performFullAutoSync()
                }
            }
        }
    }

    fun stopPeriodicSync() {
        periodicJob?.cancel()
        periodicJob = null
    }

    fun triggerManualSync(onComplete: (Result<String>) -> Unit = {}) {
        scope.launch {
            val result = mysqlSyncManager.performFullAutoSync()
            onComplete(result)
        }
    }
}
