package com.example.foresightapk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.foresightapk.ui.theme.ForeSightApkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : ComponentActivity() {
    private lateinit var usageEventReader: UsageEventReader
    private lateinit var predictionLogger: PredictionLogger
    private lateinit var appInventoryReader: AppInventoryReader
    private lateinit var mappingStore: AppMappingStore
    private var uiState by mutableStateOf(PredictionUiState())
    private var overridePackageText by mutableStateOf("")
    private var overrideVocabLabelText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ForeSightLog.info("MainActivity created")
        usageEventReader = UsageEventReader(this)
        predictionLogger = PredictionLogger(this)
        mappingStore = AppMappingStore(this)
        appInventoryReader = AppInventoryReader(this, mappingStore)

        enableEdgeToEdge()
        setContent {
            ForeSightApkTheme {
                ForeSightScreen(
                    state = uiState,
                    overridePackageText = overridePackageText,
                    overrideVocabLabelText = overrideVocabLabelText,
                    onRefresh = ::runPrediction,
                    onOpenUsageSettings = ::openUsageSettings,
                    onRefreshInventory = ::refreshAppInventory,
                    onInventoryScopeChange = ::setInventoryScope,
                    onOverridePackageChange = { overridePackageText = it },
                    onOverrideVocabLabelChange = { overrideVocabLabelText = it },
                    onSaveMappingOverride = ::saveMappingOverride,
                    onClearMappingOverride = ::clearMappingOverride,
                    onSelectMappingPackage = { packageName, vocabLabel ->
                        overridePackageText = packageName
                        overrideVocabLabelText = vocabLabel.orEmpty()
                    }
                )
            }
        }
        refreshAppInventory()
    }

    override fun onResume() {
        super.onResume()
        ForeSightLog.debug("MainActivity resumed; checking Usage Access")
        if (::usageEventReader.isInitialized) {
            refreshUsageAccessState()
        }
        if (
            ::appInventoryReader.isInitialized &&
            uiState.installedApps.isEmpty() &&
            !uiState.isInventoryLoading
        ) {
            refreshAppInventory()
        }
    }

    override fun onDestroy() {
        ForeSightLog.debug("MainActivity destroyed")
        super.onDestroy()
    }

    private fun openUsageSettings() {
        ForeSightLog.info("Opening Android Usage Access settings")
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun refreshUsageAccessState() {
        val usageAccessEnabled = runCatching {
            UsageAccess.isEnabled(this)
        }.getOrElse { error ->
            handleFailure(
                stage = "Usage Access check",
                throwable = error,
                diagnostics = listOf("Could not read AppOps usage access state.")
            )
            return
        }

        uiState = uiState.copy(
            usageAccessEnabled = usageAccessEnabled,
            isLoading = false,
            lastUpdatedText = timestampText(),
            errorMessage = if (usageAccessEnabled) {
                null
            } else {
                "Enable Usage Access for ForeSight to read recent app launches."
            },
            diagnostics = if (usageAccessEnabled) {
                listOf("Usage Access enabled. Tap Refresh to run prediction.")
            } else {
                listOf("Usage Access disabled.")
            }
        )
    }

    private fun setInventoryScope(showAllInstalledApps: Boolean) {
        if (uiState.showAllInstalledApps == showAllInstalledApps && uiState.installedApps.isNotEmpty()) {
            return
        }
        uiState = uiState.copy(
            showAllInstalledApps = showAllInstalledApps,
            installedApps = emptyList()
        )
        refreshAppInventory(showAllInstalledApps)
    }

    private fun refreshAppInventory(
        showAllInstalledApps: Boolean = uiState.showAllInstalledApps
    ) {
        if (uiState.isInventoryLoading) return
        val scopeName = if (showAllInstalledApps) "all installed" else "launchable"
        ForeSightLog.info("Refreshing app inventory scope=$scopeName")
        uiState = uiState.copy(
            isInventoryLoading = true,
            showAllInstalledApps = showAllInstalledApps
        )
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    appInventoryReader.readInstalledApps(
                        includeNonLaunchable = showAllInstalledApps
                    )
                }
            }

            uiState = result.fold(
                onSuccess = { installedApps ->
                    val mappedCount = installedApps.count { it.mappedModelAppId != null }
                    val launchableCount = installedApps.count { it.isLaunchable }
                    uiState.copy(
                        isInventoryLoading = false,
                        installedApps = installedApps,
                        diagnostics = uiState.diagnostics + listOf(
                            "App inventory loaded: ${installedApps.size} apps ($scopeName scope).",
                            "Inventory launchable apps: $launchableCount.",
                            "Inventory mapped: $mappedCount, unmapped: ${installedApps.size - mappedCount}."
                        )
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    handleFailure(
                        stage = "App inventory refresh",
                        throwable = error,
                        diagnostics = uiState.diagnostics
                    )
                    uiState
                }
            )
        }
    }

    private fun saveMappingOverride(packageName: String, vocabLabel: String) {
        val cleanPackageName = packageName.trim()
        val cleanVocabLabel = vocabLabel.trim()
        if (cleanPackageName.isEmpty() || cleanVocabLabel.isEmpty()) {
            uiState = uiState.copy(
                errorMessage = "Manual mapping needs both a package name and a vocab label."
            )
            return
        }

        val vocab = runCatching { AppVocab.load(this) }.getOrElse { error ->
            handleFailure(
                stage = "Manual mapping save",
                throwable = error,
                diagnostics = uiState.diagnostics
            )
            return
        }

        if (!vocab.containsKey(cleanVocabLabel)) {
            ForeSightLog.warn("Manual mapping rejected; vocab label not found: $cleanVocabLabel")
            uiState = uiState.copy(
                errorMessage = "Vocab label '$cleanVocabLabel' was not found in ${AppVocab.VOCAB_FILE}."
            )
            return
        }

        mappingStore.saveOverride(cleanPackageName, cleanVocabLabel)
        uiState = uiState.copy(
            errorMessage = null,
            diagnostics = uiState.diagnostics + "Saved manual mapping: $cleanPackageName -> $cleanVocabLabel."
        )
        refreshAppInventory()
    }

    private fun clearMappingOverride(packageName: String) {
        val cleanPackageName = packageName.trim()
        if (cleanPackageName.isEmpty()) {
            uiState = uiState.copy(
                errorMessage = "Enter a package name before clearing a manual mapping."
            )
            return
        }

        mappingStore.clearOverride(cleanPackageName)
        uiState = uiState.copy(
            errorMessage = null,
            diagnostics = uiState.diagnostics + "Cleared manual mapping for $cleanPackageName."
        )
        refreshAppInventory()
    }

    private fun runPrediction() {
        ForeSightLog.info("Refresh requested")
        val usageAccessEnabled = runCatching {
            UsageAccess.isEnabled(this)
        }.getOrElse { error ->
            handleFailure(
                stage = "Usage Access check",
                throwable = error,
                diagnostics = listOf("Could not read AppOps usage access state.")
            )
            return
        }

        uiState = uiState.copy(
            usageAccessEnabled = usageAccessEnabled,
            isLoading = true,
            errorMessage = null,
            diagnostics = listOf("Refresh started at ${timestampText()}")
        )

        if (!usageAccessEnabled) {
            ForeSightLog.warn("Usage Access is disabled")
            uiState = uiState.copy(
                isLoading = false,
                recentApps = emptyList(),
                inputAppIds = emptyList(),
                predictions = emptyList(),
                latencyMs = null,
                lastUpdatedText = timestampText(),
                errorMessage = "Enable Usage Access for ForeSight to read recent app launches.",
                diagnostics = listOf(
                    "Usage Access disabled.",
                    "Tap Usage Settings, enable ForeSightApk, then refresh."
                )
            )
            return
        }

        lifecycleScope.launch {
            val result = runCatching {
                val recentApps = withContext(Dispatchers.Default) {
                    ForeSightLog.debug("Reading recent launch events")
                    usageEventReader.readRecentLaunches()
                }
                uiState = uiState.copy(
                    recentApps = recentApps,
                    lastUpdatedText = timestampText(),
                    diagnostics = uiState.diagnostics + listOf(
                        "Recent app launches loaded: ${recentApps.size}",
                        "Starting isolated model inference."
                    )
                )
                ForeSightLog.debug("Requesting isolated inference process")
                val prediction = runIsolatedInference(recentApps)
                runCatching {
                    predictionLogger.log(prediction)
                }.onFailure { logError ->
                    ForeSightLog.warn("Prediction succeeded but local prediction logging failed", logError)
                    predictionLogger.logError(
                        stage = "Prediction event logging",
                        throwable = logError,
                        diagnostics = prediction.diagnostics
                    )
                }
                prediction
            }

            uiState = result.fold(
                onSuccess = { prediction ->
                    uiState.copy(
                        usageAccessEnabled = true,
                        isLoading = false,
                        recentApps = prediction.recentApps,
                        inputAppIds = prediction.inputAppIds,
                        predictions = prediction.predictions,
                        latencyMs = prediction.latencyMs,
                        lastUpdatedText = timestampText(),
                        diagnostics = prediction.diagnostics,
                        errorMessage = if (prediction.recentApps.isEmpty()) {
                            "No recent app launches found yet. Open a few apps, then refresh."
                        } else {
                            null
                        }
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    handleFailure(
                        stage = "Prediction refresh",
                        throwable = error,
                        diagnostics = uiState.diagnostics
                    )
                    uiState
                }
            )
        }
    }

    private suspend fun runIsolatedInference(recentApps: List<AppLaunch>): PredictionResult {
        return withTimeout(INFERENCE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                lateinit var connection: ServiceConnection
                var unbound = false

                fun unbindOnce() {
                    if (!unbound) {
                        unbound = true
                        runCatching { unbindService(connection) }
                    }
                }

                val replyMessenger = Messenger(
                    Handler(Looper.getMainLooper()) { message ->
                        when (message.what) {
                            InferenceContract.MSG_RESULT -> {
                                unbindOnce()
                                continuation.resume(InferenceContract.resultFromBundle(message.data))
                                true
                            }
                            InferenceContract.MSG_ERROR -> {
                                unbindOnce()
                                continuation.resumeWithException(
                                    IllegalStateException(
                                        InferenceContract.errorFromBundle(message.data)
                                    )
                                )
                                true
                            }
                            else -> false
                        }
                    }
                )

                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        ForeSightLog.debug("Connected to isolated inference process")
                        val request = Message.obtain(null, InferenceContract.MSG_PREDICT).apply {
                            replyTo = replyMessenger
                            data = InferenceContract.launchesToBundle(recentApps)
                        }

                        runCatching {
                            Messenger(service).send(request)
                        }.onFailure { error ->
                            unbindOnce()
                            continuation.resumeWithException(error)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        ForeSightLog.error("Isolated inference process disconnected")
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException(
                                    "Inference process crashed while running the TFLite model."
                                )
                            )
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    unbindOnce()
                }

                val bound = bindService(
                    Intent(this@MainActivity, InferenceService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )

                if (!bound) {
                    unbindOnce()
                    continuation.resumeWithException(
                        IllegalStateException("Could not bind inference process.")
                    )
                }
            }
        }
    }

    private fun handleFailure(stage: String, throwable: Throwable, diagnostics: List<String>) {
        ForeSightLog.error("$stage failed: ${throwable.message}", throwable)
        if (::predictionLogger.isInitialized) {
            runCatching {
                predictionLogger.logError(stage, throwable, diagnostics)
            }.onFailure { logFailure ->
                ForeSightLog.error("Failed to write error log", logFailure)
            }
        }

        uiState = uiState.copy(
            isLoading = false,
            isInventoryLoading = false,
            latencyMs = null,
            lastUpdatedText = timestampText(),
            errorMessage = "$stage failed: ${throwable.message ?: throwable::class.java.simpleName}",
            diagnostics = diagnostics + listOf(
                "Failed stage: $stage",
                "Error type: ${throwable::class.java.simpleName}",
                "Error message: ${throwable.message ?: "No message"}"
            )
        )
    }

    private fun timestampText(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    companion object {
        private const val INFERENCE_TIMEOUT_MS = 8_000L
    }
}

@Composable
private fun ForeSightScreen(
    state: PredictionUiState,
    overridePackageText: String,
    overrideVocabLabelText: String,
    onRefresh: () -> Unit,
    onOpenUsageSettings: () -> Unit,
    onRefreshInventory: () -> Unit,
    onInventoryScopeChange: (Boolean) -> Unit,
    onOverridePackageChange: (String) -> Unit,
    onOverrideVocabLabelChange: (String) -> Unit,
    onSaveMappingOverride: (String, String) -> Unit,
    onClearMappingOverride: (String) -> Unit,
    onSelectMappingPackage: (String, String?) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val bottomIcons = remember {
        listOf(
            Icons.Outlined.FavoriteBorder,
            Icons.Outlined.Place,
            Icons.Outlined.Settings,
            Icons.Outlined.NotificationsNone,
            Icons.Outlined.PersonOutline
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            ConcaveNotchBottomBar(
                items = bottomIcons,
                selectedIndex = selectedTab,
                onItemSelected = { selectedTab = it },
                bubbleColor = MaterialTheme.colorScheme.primary
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "ForeSight",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Next-app prediction MVP",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.errorMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = state.errorMessage,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            when (selectedTab) {
                0 -> {
                    item {
                        StatusCard(
                            state = state,
                            onRefresh = onRefresh,
                            onOpenUsageSettings = onOpenUsageSettings
                        )
                    }

                    item {
                        SectionCard(title = "Recent 10 Apps") {
                            if (state.recentApps.isEmpty()) {
                                Text("No app launch events loaded.")
                            } else {
                                state.recentApps.asReversed().forEachIndexed { index, launch ->
                                    AppLaunchRow(index + 1, launch)
                                    if (index != state.recentApps.lastIndex) HorizontalDivider()
                                }
                            }
                        }
                    }

                    item {
                        SectionCard(title = "Top 5 Predictions") {
                            if (state.predictions.isEmpty()) {
                                Text("No predictions yet.")
                            } else {
                                state.predictions.forEachIndexed { index, prediction ->
                                    PredictionRow(index + 1, prediction)
                                    if (index != state.predictions.lastIndex) HorizontalDivider()
                                }
                            }
                        }
                    }

                    item {
                        SectionCard(title = "Model Input") {
                            Text("App sequence IDs: ${state.inputAppIds.joinToString(prefix = "[", postfix = "]")}")
                            Text("Context shape: [1, 10, 3]")
                        }
                    }
                }

                1 -> {
                    item {
                        AppInventoryCard(
                            state = state,
                            overridePackageText = overridePackageText,
                            overrideVocabLabelText = overrideVocabLabelText,
                            onRefreshInventory = onRefreshInventory,
                            onInventoryScopeChange = onInventoryScopeChange,
                            onOverrideVocabLabelChange = onOverrideVocabLabelChange,
                            onSaveMappingOverride = onSaveMappingOverride,
                            onClearMappingOverride = onClearMappingOverride,
                            onSelectMappingPackage = onSelectMappingPackage
                        )
                    }
                }

                2 -> {
                    item {
                        MappingOverrideCard(
                            packageText = overridePackageText,
                            vocabLabelText = overrideVocabLabelText,
                            onPackageChange = onOverridePackageChange,
                            onVocabLabelChange = onOverrideVocabLabelChange,
                            onSave = { onSaveMappingOverride(overridePackageText, overrideVocabLabelText) },
                            onClear = { onClearMappingOverride(overridePackageText) }
                        )
                    }
                }

                3 -> {
                    item {
                        DiagnosticsCard(state)
                    }
                }

                4 -> {
                    item {
                        StatusCard(
                            state = state,
                            onRefresh = onRefresh,
                            onOpenUsageSettings = onOpenUsageSettings
                        )
                    }
                    item {
                        DiagnosticsCard(state)
                    }
                }

                else -> {
                    item {
                        StatusCard(
                            state = state,
                            onRefresh = onRefresh,
                            onOpenUsageSettings = onOpenUsageSettings
                        )
                    }
                }
            }

            item {
                Text(
                    text = activeTabLabel(selectedTab),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(state: PredictionUiState) {
    SectionCard(title = "Diagnostics") {
        if (state.diagnostics.isEmpty()) {
            Text("No diagnostics yet.")
        } else {
            state.diagnostics.forEach { diagnostic ->
                Text("- $diagnostic")
            }
        }
    }
}

private fun activeTabLabel(selectedTab: Int): String {
    return when (selectedTab) {
        0 -> "Prediction"
        1 -> "Inventory"
        2 -> "Manual Mapping"
        3 -> "Diagnostics"
        4 -> "Settings"
        else -> "Prediction"
    }
}

@Composable
private fun MappingOverrideCard(
    packageText: String,
    vocabLabelText: String,
    onPackageChange: (String) -> Unit,
    onVocabLabelChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    SectionCard(title = "Manual Mapping") {
        OutlinedTextField(
            value = packageText,
            onValueChange = onPackageChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Package name") }
        )
        OutlinedTextField(
            value = vocabLabelText,
            onValueChange = onVocabLabelChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Vocab label") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave) {
                Text("Save Mapping")
            }
            TextButton(onClick = onClear) {
                Text("Clear")
            }
        }
    }
}

@Composable
private fun AppInventoryCard(
    state: PredictionUiState,
    overridePackageText: String,
    overrideVocabLabelText: String,
    onRefreshInventory: () -> Unit,
    onInventoryScopeChange: (Boolean) -> Unit,
    onOverrideVocabLabelChange: (String) -> Unit,
    onSaveMappingOverride: (String, String) -> Unit,
    onClearMappingOverride: (String) -> Unit,
    onSelectMappingPackage: (String, String?) -> Unit
) {
    SectionCard(title = "App Inventory") {
        val mappedCount = state.installedApps.count { it.mappedModelAppId != null }
        val manualCount = state.installedApps.count { it.mappingSource == MappingSource.ManualOverride }
        val launchableCount = state.installedApps.count { it.isLaunchable }
        val systemCount = state.installedApps.count { it.isSystemApp }
        val displayApps = state.installedApps.sortedWith(
            compareBy<InstalledAppInfo> { it.mappedModelAppId != null }
                .thenBy { it.appLabel.lowercase(Locale.getDefault()) }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.showAllInstalledApps) {
                TextButton(onClick = { onInventoryScopeChange(false) }) {
                    Text("Launchable")
                }
                Button(onClick = { onInventoryScopeChange(true) }) {
                    Text("All Installed")
                }
            } else {
                Button(onClick = { onInventoryScopeChange(false) }) {
                    Text("Launchable")
                }
                TextButton(onClick = { onInventoryScopeChange(true) }) {
                    Text("All Installed")
                }
            }
        }

        Text("Scope: ${if (state.showAllInstalledApps) "all installed apps" else "launchable apps"}")
        Text("Total shown: ${state.installedApps.size}")
        Text("Launchable: $launchableCount")
        Text("System apps: $systemCount")
        Text("Mapped: $mappedCount")
        Text("Unmapped: ${state.installedApps.size - mappedCount}")
        Text("Manual overrides: $manualCount")
        Button(
            onClick = onRefreshInventory,
            enabled = !state.isInventoryLoading
        ) {
            Text(if (state.isInventoryLoading) "Loading Apps" else "Refresh Apps")
        }

        if (state.installedApps.isEmpty()) {
            Text(if (state.isInventoryLoading) "Loading app inventory." else "No apps loaded.")
        } else {
            displayApps.forEachIndexed { index, app ->
                InstalledAppRow(
                    app = app,
                    isSelected = app.packageName == overridePackageText,
                    vocabLabelText = overrideVocabLabelText,
                    onVocabLabelChange = onOverrideVocabLabelChange,
                    onSave = { onSaveMappingOverride(app.packageName, overrideVocabLabelText) },
                    onClear = { onClearMappingOverride(app.packageName) },
                    onSelectMappingPackage = onSelectMappingPackage
                )
                if (index != displayApps.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: PredictionUiState,
    onRefresh: () -> Unit,
    onOpenUsageSettings: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (state.usageAccessEnabled) "Usage Access: enabled" else "Usage Access: disabled",
                fontWeight = FontWeight.SemiBold
            )
            state.latencyMs?.let { latency ->
                Text("Inference latency: ${latency}ms")
            }
            state.lastUpdatedText?.let { updated ->
                Text("Last refresh: $updated")
            }
            Text("Prediction log: ${state.logFileName}")
            Text("Error log: ${state.errorLogFileName}")
            Text("Logcat tag: ${ForeSightLog.TAG}")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRefresh,
                    enabled = !state.isLoading
                ) {
                    Text(if (state.isLoading) "Refreshing" else "Refresh")
                }
                TextButton(onClick = onOpenUsageSettings) {
                    Text("Usage Settings")
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun AppLaunchRow(index: Int, launch: AppLaunch) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = "$index. ${launch.appLabel}",
            fontWeight = FontWeight.Medium
        )
        Text(
            text = launch.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InstalledAppRow(
    app: InstalledAppInfo,
    isSelected: Boolean,
    vocabLabelText: String,
    onVocabLabelChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onSelectMappingPackage: (String, String?) -> Unit
) {
    Column(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appLabel,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = app.mappingSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                text = app.inventoryTypeText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }
            TextButton(
                onClick = {
                    onSelectMappingPackage(app.packageName, app.mappedModelLabel)
                }
            ) {
                Text(if (isSelected) "Editing" else "Edit")
            }
        }

        if (isSelected) {
            OutlinedTextField(
                value = vocabLabelText,
                onValueChange = onVocabLabelChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Vocab label") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) {
                    Text("Save Mapping")
                }
                TextButton(onClick = onClear) {
                    Text("Clear")
                }
            }
        }
    }
}

private fun InstalledAppInfo.mappingSummary(): String {
    val source = mappingSource.displayName
    val confidenceText = "%.2f".format(mappingConfidence)
    return if (mappedModelAppId == null) {
        "Unmapped ($source, confidence $confidenceText)"
    } else {
        "ID $mappedModelAppId: $mappedModelLabel ($source, confidence $confidenceText)"
    }
}

private fun InstalledAppInfo.inventoryTypeText(): String {
    val appType = if (isSystemApp) "system app" else "user app"
    val launchableType = if (isLaunchable) "launchable" else "not launchable"
    return "$appType, $launchableType"
}

@Composable
private fun PredictionRow(index: Int, prediction: PredictedApp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$index. ${prediction.label}",
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "App ID ${prediction.appId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "%.3f".format(prediction.confidence),
            fontWeight = FontWeight.SemiBold
        )
    }
}
