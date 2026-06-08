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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    private var uiState by mutableStateOf(PredictionUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ForeSightLog.info("MainActivity created")
        usageEventReader = UsageEventReader(this)
        predictionLogger = PredictionLogger(this)

        enableEdgeToEdge()
        setContent {
            ForeSightApkTheme {
                ForeSightScreen(
                    state = uiState,
                    onRefresh = ::runPrediction,
                    onOpenUsageSettings = ::openUsageSettings
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ForeSightLog.debug("MainActivity resumed; checking Usage Access")
        if (::usageEventReader.isInitialized) {
            refreshUsageAccessState()
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
    onRefresh: () -> Unit,
    onOpenUsageSettings: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing
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

            item {
                StatusCard(
                    state = state,
                    onRefresh = onRefresh,
                    onOpenUsageSettings = onOpenUsageSettings
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

            item {
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
