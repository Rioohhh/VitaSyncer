package nl.vitasyncer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var store: CredentialStore
    private lateinit var hcManager: HealthConnectManager
    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = CredentialStore(this)
        hcManager = HealthConnectManager(this)

        requestPermissions = registerForActivityResult(
            HealthConnectManager.createPermissionContract()
        ) { /* recompose zorgt voor UI update */ }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VitaSyncerScreen(
                        store = store,
                        hcManager = hcManager,
                        onRequestPermissions = {
                            requestPermissions.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                        },
                        onSyncNow = { oneShotSync() },
                        onToggleAutoSync = { enabled -> setAutoSync(enabled) }
                    )
                }
            }
        }
    }

    private fun oneShotSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
        WorkManager.getInstance(this).enqueue(request)
    }

    private fun setAutoSync(enabled: Boolean) {
        store.autoSyncEnabled = enabled
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                SyncWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } else {
            WorkManager.getInstance(this).cancelUniqueWork(SyncWorker.WORK_NAME)
        }
    }
}

@Composable
fun VitaSyncerScreen(
    store: CredentialStore,
    hcManager: HealthConnectManager,
    onRequestPermissions: () -> Unit,
    onSyncNow: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit
) {
    var username by remember { mutableStateOf(store.username) }
    var password by remember { mutableStateOf(store.password) }
    var apiKey by remember { mutableStateOf(store.apiKey) }
    var showPassword by remember { mutableStateOf(false) }
    var autoSync by remember { mutableStateOf(store.autoSyncEnabled) }
    var syncStatus by remember { mutableStateOf(store.lastSyncStatus) }
    var hcPermissionsGranted by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var rawApiJson by remember { mutableStateOf("") }
    var showApiExplorer by remember { mutableStateOf(false) }
    var showIdSettings by remember { mutableStateOf(false) }

    LaunchedEffect(username) { store.username = username }
    LaunchedEffect(password) { store.password = password }
    LaunchedEffect(apiKey) { store.apiKey = apiKey }

    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        hcPermissionsGranted = hcManager.isAvailable() && hcManager.hasPermissions()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("VitaSyncer", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Synchroniseert Virtuagym lichaamsmetingen naar Health Connect.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
        Text("Virtuagym inloggegevens", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("E-mailadres") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Wachtwoord") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "Verberg" else "Toon")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key (optioneel)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider()
        Text("Health Connect", style = MaterialTheme.typography.titleMedium)

        if (!hcManager.isAvailable()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    "Health Connect is niet beschikbaar op dit toestel.",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        } else if (!hcPermissionsGranted) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "⚠️ Toestemmingen nodig voor Health Connect.",
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Button(onClick = {
                        onRequestPermissions()
                        coroutineScope.launch {
                            kotlinx.coroutines.delay(1500)
                            hcPermissionsGranted = hcManager.hasPermissions()
                        }
                    }) {
                        Text("Toestemmingen verlenen")
                    }
                }
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text(
                    "✅ Health Connect toestemmingen verleend.",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        HorizontalDivider()
        Text("Synchronisatie", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Auto-sync elke 6 uur", modifier = Modifier.weight(1f))
            Switch(
                checked = autoSync,
                onCheckedChange = { checked ->
                    autoSync = checked
                    onToggleAutoSync(checked)
                }
            )
        }

        Button(
            onClick = {
                isBusy = true
                syncStatus = "⏳ Bezig met synchroniseren..."
                onSyncNow()
                coroutineScope.launch {
                    repeat(15) {
                        kotlinx.coroutines.delay(2000)
                        val newStatus = store.lastSyncStatus
                        syncStatus = newStatus
                        if (!newStatus.contains("Bezig")) {
                            isBusy = false
                            return@launch
                        }
                    }
                    isBusy = false
                }
            },
            enabled = !isBusy && hcPermissionsGranted && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Nu synchroniseren")
        }

        if (syncStatus.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    syncStatus,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        HorizontalDivider()
        Text("Diagnose & instellingen", style = MaterialTheme.typography.titleMedium)
        Text(
            "Gebruik de API verkenner om de definition IDs uit de Virtuagym respons te lezen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedButton(
            onClick = {
                coroutineScope.launch {
                    rawApiJson = "⏳ Bezig..."
                    showApiExplorer = true
                    val result = withContext(Dispatchers.IO) {
                        VirtuagymApi(username, password, apiKey.ifBlank { null })
                            .getBodyMetrics(fromDate = java.time.LocalDate.now().minusDays(7))
                    }
                    rawApiJson = when (result) {
                        is ApiResult.Success -> result.rawJson
                        is ApiResult.Error -> "FOUT: ${result.message}"
                    }
                }
            },
            enabled = username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🔍 Verken Virtuagym API (laatste 7 dagen)")
        }

        if (showApiExplorer && rawApiJson.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Raw API respons:", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        rawApiJson,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { showIdSettings = !showIdSettings },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("⚙️ Definition ID instellingen")
        }

        if (showIdSettings) {
            DefinitionIdSettings(store = store)
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun DefinitionIdSettings(store: CredentialStore) {
    var idWeight by remember { mutableStateOf(store.idWeight.toString()) }
    var idFat by remember { mutableStateOf(store.idBodyFat.toString()) }
    var idMuscle by remember { mutableStateOf(store.idMuscleMass.toString()) }
    var idWater by remember { mutableStateOf(store.idWater.toString()) }
    var idBone by remember { mutableStateOf(store.idBoneMass.toString()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Definition IDs — zie de ruwe API respons voor de juiste waarden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IdField("Gewicht ID", idWeight) {
                idWeight = it; it.toIntOrNull()?.let { n -> store.idWeight = n }
            }
            IdField("Vetpercentage ID", idFat) {
                idFat = it; it.toIntOrNull()?.let { n -> store.idBodyFat = n }
            }
            IdField("Spiermassa ID", idMuscle) {
                idMuscle = it; it.toIntOrNull()?.let { n -> store.idMuscleMass = n }
            }
            IdField("Waterpercentage ID", idWater) {
                idWater = it; it.toIntOrNull()?.let { n -> store.idWater = n }
            }
            IdField("Botmassa ID", idBone) {
                idBone = it; it.toIntOrNull()?.let { n -> store.idBoneMass = n }
            }
        }
    }
}

@Composable
fun IdField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}
