package nl.vitasyncer.app

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import java.time.Instant
import java.time.ZoneId

class HealthConnectManager(private val context: Context) {

    companion object {
        private const val TAG = "HealthConnect"

        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getWritePermission(WeightRecord::class),
            HealthPermission.getWritePermission(BodyFatRecord::class),
            HealthPermission.getWritePermission(LeanBodyMassRecord::class),
            HealthPermission.getWritePermission(BoneMassRecord::class),
        )
    }

    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    fun isAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun hasPermissions(): Boolean {
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            REQUIRED_PERMISSIONS.all { it in granted }
        } catch (e: Exception) {
            Log.e(TAG, "Kon toestemmingen niet ophalen", e)
            false
        }
    }

    /**
     * Schrijft een groep metingen (van één weegmoment) naar Health Connect.
     * Geeft een leesbaar resultaatbericht terug.
     *
     * @param entries    Alle metingen van dit weegmoment
     * @param store      CredentialStore om ID-mapping op te halen
     */
    suspend fun writeMetricGroup(
        entries: List<BodyMetricEntry>,
        store: CredentialStore
    ): String {
        if (entries.isEmpty()) return "Geen data."

        val records = mutableListOf<Record>()
        val logLines = mutableListOf<String>()
        val instant = Instant.ofEpochSecond(entries.first().epochSeconds)
        val zone = ZoneId.systemDefault()

        // Gewicht
        val weightEntry = entries.find { it.definitionId == store.idWeight }
        val weightKg = weightEntry?.value
        weightEntry?.let {
            records += WeightRecord(
                weight = Mass.kilograms(it.value),
                time = instant,
                zoneOffset = zone.rules.getOffset(instant)
            )
            logLines += "⚖️ Gewicht: ${"%.1f".format(it.value)} kg"
        }

        // Vetpercentage
        val fatEntry = entries.find { it.definitionId == store.idBodyFat }
        fatEntry?.let {
            // Health Connect verwacht een waarde van 0..100
            records += BodyFatRecord(
                percentage = Percentage(it.value),
                time = instant,
                zoneOffset = zone.rules.getOffset(instant)
            )
            logLines += "🫀 Vetpercentage: ${"%.1f".format(it.value)}%"
        }

        // Vetvrije massa (lean body mass) = gewicht × (1 - vet%)
        // Wordt gebruikt als proxy voor spiermassa in Health Connect
        if (weightKg != null && fatEntry != null) {
            val leanKg = weightKg * (1.0 - fatEntry.value / 100.0)
            records += LeanBodyMassRecord(
                mass = Mass.kilograms(leanKg),
                time = instant,
                zoneOffset = zone.rules.getOffset(instant)
            )
            logLines += "💪 Vetvrije massa: ${"%.1f".format(leanKg)} kg"
        }

        // Botmassa (indien aanwezig)
        val boneEntry = entries.find { it.definitionId == store.idBoneMass }
        boneEntry?.let {
            records += BoneMassRecord(
                mass = Mass.kilograms(it.value),
                time = instant,
                zoneOffset = zone.rules.getOffset(instant)
            )
            logLines += "🦴 Botmassa: ${"%.2f".format(it.value)} kg"
        }

        return try {
            if (records.isEmpty()) {
                "⚠️ Geen bekende meetwaarden gevonden.\nControleer de definition ID instellingen."
            } else {
                client.insertRecords(records)
                "✅ Opgeslagen op ${entries.first().dateStr}:\n" + logLines.joinToString("\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Schrijffout Health Connect", e)
            "❌ Schrijffout: ${e.message}"
        }
    }
}
