package nl.vitasyncer.app

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Eén meting van de weegschaal (bijv. vetpercentage, gewicht, etc.)
 */
data class BodyMetricEntry(
    val definitionId: Int,
    val value: Double,
    val epochSeconds: Long,  // Unix timestamp
    val dateStr: String      // "YYYY-MM-DD"
)

sealed class ApiResult {
    data class Success(
        val entries: List<BodyMetricEntry>,
        val rawJson: String
    ) : ApiResult()

    data class Error(val message: String) : ApiResult()
}

class VirtuagymApi(
    private val username: String,
    private val password: String,
    private val apiKey: String?
) {
    companion object {
        private const val TAG = "VirtuagymApi"
        private const val BASE_URL = "https://api.virtuagym.com/api/v0"
    }

    private val authHeader: String
        get() {
            val credentials = "$username:$password"
            return "Basic " + Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }

    /**
     * Haalt body metrics op uit Virtuagym.
     * [fromDate] filtert op metingen vanaf deze datum (optioneel).
     */
    fun getBodyMetrics(fromDate: LocalDate? = null): ApiResult {
        return try {
            val params = mutableListOf<String>()
            if (!apiKey.isNullOrBlank()) {
                params.add("api_key=${apiKey}")
            }
            if (fromDate != null) {
                params.add("start=${fromDate.format(DateTimeFormatter.ISO_LOCAL_DATE)}")
            }
            // Maximaal 100 resultaten ophalen (meest recent eerst)
            params.add("limit=100")
            params.add("order=desc")

            val urlStr = "$BASE_URL/bodymetric" +
                if (params.isNotEmpty()) "?" + params.joinToString("&") else ""

            Log.d(TAG, "GET $urlStr")

            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", authHeader)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val responseBody = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()

            Log.d(TAG, "Respons HTTP $responseCode: $responseBody")

            if (responseCode !in 200..299) {
                return ApiResult.Error("HTTP fout $responseCode:\n$responseBody")
            }

            val entries = parseResponse(responseBody)
            ApiResult.Success(entries, responseBody)

        } catch (e: Exception) {
            Log.e(TAG, "Verbindingsfout", e)
            ApiResult.Error("Verbindingsfout: ${e.message}")
        }
    }

    /**
     * Verwerkt de JSON respons van de Virtuagym API.
     *
     * Verwacht formaat:
     * {
     *   "result": [
     *     {
     *       "bodymetric_definition_id": 1,
     *       "value": "75.5",
     *       "created": "2024-04-14T10:30:00+02:00"
     *     },
     *     ...
     *   ]
     * }
     *
     * Let op: als de API een ander formaat gebruikt, is dat zichtbaar
     * via de "Verken API" knop in de app.
     */
    private fun parseResponse(json: String): List<BodyMetricEntry> {
        val entries = mutableListOf<BodyMetricEntry>()
        return try {
            val root = JSONObject(json)

            // Probeer zowel "result" als directe array
            val resultArray = root.optJSONArray("result")
                ?: root.optJSONArray("bodymetrics")
                ?: return entries

            for (i in 0 until resultArray.length()) {
                val obj = resultArray.optJSONObject(i) ?: continue
                val defId = obj.optInt("bodymetric_definition_id", -1)
                if (defId < 0) continue

                // "value" kan string of number zijn
                val value = try {
                    obj.getDouble("value")
                } catch (e: Exception) {
                    obj.optString("value", "0").toDoubleOrNull() ?: continue
                }

                // Timestamp uit "created" veld
                val created = obj.optString("created", obj.optString("date", ""))
                val epochSeconds = parseCreatedToEpoch(created)
                val dateStr = created.take(10).ifBlank {
                    LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                }

                entries.add(BodyMetricEntry(defId, value, epochSeconds, dateStr))
            }
            entries
        } catch (e: Exception) {
            Log.e(TAG, "Parse fout", e)
            entries
        }
    }

    /**
     * Converteert een datum/tijd string naar Unix epoch (seconden).
     * Ondersteunt ISO 8601 formaten zoals "2024-04-14T10:30:00+02:00"
     */
    private fun parseCreatedToEpoch(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis() / 1000

        return try {
            // ISO 8601 met timezone offset
            Instant.parse(
                if (dateStr.contains("T")) dateStr.replace(" ", "T")
                else "${dateStr}T00:00:00Z"
            ).epochSecond
        } catch (e1: Exception) {
            try {
                // Probeer zonder timezone
                val local = LocalDate.parse(dateStr.take(10))
                local.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
            } catch (e2: Exception) {
                System.currentTimeMillis() / 1000
            }
        }
    }
}
