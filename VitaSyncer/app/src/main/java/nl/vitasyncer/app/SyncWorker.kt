package nl.vitasyncer.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.ZoneId

/**
 * WorkManager worker die periodiek Virtuagym body metrics ophaalt
 * en naar Health Connect schrijft.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "vitasyncer_periodic_sync"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Sync gestart")

        val store = CredentialStore(applicationContext)
        val hcManager = HealthConnectManager(applicationContext)

        if (store.username.isBlank() || store.password.isBlank()) {
            val msg = "⚠️ Inloggegevens niet ingesteld."
            store.lastSyncStatus = msg
            Log.w(TAG, msg)
            return Result.failure()
        }

        if (!hcManager.isAvailable()) {
            val msg = "❌ Health Connect niet beschikbaar op dit toestel."
            store.lastSyncStatus = msg
            return Result.failure()
        }

        if (!hcManager.hasPermissions()) {
            val msg = "⚠️ Health Connect toestemmingen niet verleend. Open de app om toe te staan."
            store.lastSyncStatus = msg
            return Result.failure()
        }

        // Haal metingen op vanaf 30 dagen geleden (of laatste sync)
        val lastSyncEpoch = store.lastSyncTimestamp
        val fromDate = if (lastSyncEpoch > 0) {
            LocalDate.ofEpochDay(lastSyncEpoch / 86400).minusDays(1)
        } else {
            LocalDate.now().minusDays(30)
        }

        val api = VirtuagymApi(store.username, store.password, store.apiKey.ifBlank { null })
        val result = api.getBodyMetrics(fromDate)

        return when (result) {
            is ApiResult.Error -> {
                val msg = "❌ API fout: ${result.message}"
                store.lastSyncStatus = msg
                Log.e(TAG, msg)
                Result.retry()
            }

            is ApiResult.Success -> {
                val newEntries = result.entries.filter { it.epochSeconds > lastSyncEpoch }

                if (newEntries.isEmpty()) {
                    val msg = "✅ Geen nieuwe metingen gevonden."
                    store.lastSyncStatus = msg
                    Log.d(TAG, msg)
                    return Result.success()
                }

                // Groepeer metingen per datum (elke weging = meerdere entries)
                val grouped = newEntries.groupBy { it.dateStr }
                val statusLines = mutableListOf<String>()
                var latestEpoch = lastSyncEpoch

                for ((_, group) in grouped.entries.sortedBy { it.key }) {
                    val writeResult = hcManager.writeMetricGroup(group, store)
                    statusLines += writeResult
                    val maxEpoch = group.maxOf { it.epochSeconds }
                    if (maxEpoch > latestEpoch) latestEpoch = maxEpoch
                }

                store.lastSyncTimestamp = latestEpoch
                store.lastSyncStatus = statusLines.joinToString("\n---\n")
                Log.d(TAG, "Sync klaar: ${statusLines.size} groep(en) geschreven")
                Result.success()
            }
        }
    }
}
