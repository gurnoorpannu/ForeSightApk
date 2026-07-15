package com.example.foresightapk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
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
import androidx.compose.material3.Switch
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

private const val ROOT_MANUAL_APP_LIMIT = 80

class MainActivity : ComponentActivity() {
    private lateinit var usageEventReader: UsageEventReader
    private lateinit var predictionLogger: PredictionLogger
    private lateinit var appInventoryReader: AppInventoryReader
    private lateinit var mappingStore: AppMappingStore
    private lateinit var policyStore: AppPolicyStore
    private lateinit var shadowStore: ShadowEvaluationStore
    private lateinit var rootCommandRunner: RootCommandRunner
    private lateinit var rootFreezeController: RootFreezeController
    private lateinit var rootDecisionExecutor: RootDecisionExecutor
    private var uiState by mutableStateOf(PredictionUiState())
    private var overridePackageText by mutableStateOf("")
    private var overrideVocabLabelText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ForeSightLog.info("MainActivity created")
        usageEventReader = UsageEventReader(this)
        predictionLogger = PredictionLogger(this)
        mappingStore = AppMappingStore(this)
        policyStore = AppPolicyStore(this)
        shadowStore = ShadowEvaluationStore(this, usageEventReader)
        rootCommandRunner = RootCommandRunner(this)
        rootFreezeController = RootFreezeController(this, rootCommandRunner, policyStore)
        rootDecisionExecutor = RootDecisionExecutor(policyStore, rootFreezeController)
        appInventoryReader = AppInventoryReader(this, mappingStore)
        refreshPolicyState()

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
                    onAddProtectedPackage = ::addProtectedPackage,
                    onRemoveProtectedPackage = ::removeProtectedPackage,
                    onPolicyChange = ::updateDecisionPolicy,
                    onRequestActiveRootMode = ::requestActiveRootMode,
                    onConfirmActiveRootMode = ::confirmActiveRootMode,
                    onDisableActiveRootMode = ::disableActiveRootMode,
                    onDismissActiveRootConfirmation = ::dismissActiveRootConfirmation,
                    onResetPolicyDefaults = ::resetDecisionPolicyDefaults,
                    onClearDryRunLogs = ::clearDryRunLogs,
                    onExportShadowLogs = ::exportShadowLogs,
                    onCheckRoot = ::checkRoot,
                    onRunRootId = ::runRootId,
                    onRunRootPackageList = ::runRootPackageList,
                    onFreezePackage = ::freezeRootPackage,
                    onUnfreezePackage = ::unfreezeRootPackage,
                    onUnfreezeAllForeSightFrozen = ::unfreezeAllForeSightFrozen,
                    onStartBackgroundService = ::startBackgroundService,
                    onStopBackgroundService = ::stopBackgroundService,
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
        if (::policyStore.isInitialized) {
            refreshPolicyState()
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

    private fun refreshPolicyState() {
        if (!::policyStore.isInitialized) return
        val (shadowMetrics, shadowEvaluations) = readShadowDashboard()
        uiState = uiState.copy(
            ownPackageName = packageName,
            isBackgroundServiceRunning = ForeSightBackgroundService.isRunning,
            protectedAllowlist = policyStore.getProtectedAllowlist(),
            dryRunFrozenPackages = policyStore.getDryRunFrozenPackages(),
            rootFrozenPackages = policyStore.getRootFrozenPackages(),
            decisionPolicy = policyStore.getDecisionPolicy(),
            dryRunActionHistory = policyStore.getRecentActionLogs(),
            shadowMetrics = shadowMetrics,
            shadowEvaluations = shadowEvaluations
        )
    }

    private fun readShadowDashboard(): Pair<ShadowMetrics, List<ShadowEvaluation>> {
        if (!::shadowStore.isInitialized) {
            return uiState.shadowMetrics to uiState.shadowEvaluations
        }
        return runCatching {
            shadowStore.getMetrics() to shadowStore.getRecentEvaluations()
        }.getOrElse { error ->
            ForeSightLog.warn("Could not read shadow evaluation dashboard", error)
            uiState.shadowMetrics to uiState.shadowEvaluations
        }
    }

    private fun recordShadowCycle(
        prediction: PredictionResult,
        decisionPlan: DecisionPlan
    ) {
        if (!::shadowStore.isInitialized) return
        runCatching {
            shadowStore.evaluatePendingCycles()
            shadowStore.recordCycle(prediction, decisionPlan)
        }.onFailure { error ->
            ForeSightLog.warn("Shadow evaluation logging failed", error)
        }
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
                    val decisionPlan = if (uiState.predictions.isNotEmpty()) {
                        buildDecisionPlan(
                            predictions = uiState.predictions,
                            installedApps = installedApps,
                            recentApps = uiState.recentApps
                        )
                    } else {
                        uiState.decisionPlan
                    }
                    uiState.copy(
                        isInventoryLoading = false,
                        installedApps = installedApps,
                        decisionPlan = decisionPlan,
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

    private fun addProtectedPackage(packageName: String) {
        val cleanPackageName = packageName.trim()
        if (cleanPackageName.isEmpty()) {
            uiState = uiState.copy(errorMessage = "Select a package before adding it to the allowlist.")
            return
        }

        policyStore.addProtectedPackage(cleanPackageName)
        updatePolicyStateAfterManualChange("Protected allowlist added: $cleanPackageName.")
    }

    private fun removeProtectedPackage(packageName: String) {
        val cleanPackageName = packageName.trim()
        if (cleanPackageName.isEmpty()) {
            uiState = uiState.copy(errorMessage = "Select a package before removing it from the allowlist.")
            return
        }

        policyStore.removeProtectedPackage(cleanPackageName)
        updatePolicyStateAfterManualChange("Protected allowlist removed: $cleanPackageName.")
    }

    private fun updatePolicyStateAfterManualChange(message: String) {
        val updatedAllowlist = policyStore.getProtectedAllowlist()
        val updatedFrozen = policyStore.getDryRunFrozenPackages()
        val decisionPlan = if (uiState.predictions.isNotEmpty()) {
            buildDecisionPlan(
                predictions = uiState.predictions,
                installedApps = uiState.installedApps,
                recentApps = uiState.recentApps
            )
        } else {
            uiState.decisionPlan
        }
        uiState = uiState.copy(
            protectedAllowlist = updatedAllowlist,
            dryRunFrozenPackages = updatedFrozen,
            decisionPolicy = policyStore.getDecisionPolicy(),
            dryRunActionHistory = policyStore.getRecentActionLogs(),
            decisionPlan = decisionPlan,
            errorMessage = null,
            diagnostics = uiState.diagnostics + message
        )
    }

    private fun updateDecisionPolicy(policy: DecisionPolicy) {
        policyStore.saveDecisionPolicy(policy)
        val decisionPlan = if (uiState.predictions.isNotEmpty()) {
            buildDecisionPlan(
                predictions = uiState.predictions,
                installedApps = uiState.installedApps,
                recentApps = uiState.recentApps
            )
        } else {
            uiState.decisionPlan
        }
        uiState = uiState.copy(
            decisionPolicy = policyStore.getDecisionPolicy(),
            decisionPlan = decisionPlan,
            errorMessage = null,
            diagnostics = uiState.diagnostics + "Updated dry-run policy settings."
        )
    }

    private fun requestActiveRootMode() {
        uiState = uiState.copy(
            activeRootConfirmationVisible = true,
            errorMessage = null,
            diagnostics = uiState.diagnostics + "Active Root Mode confirmation requested."
        )
    }

    private fun confirmActiveRootMode() {
        if (uiState.rootAvailability != RootAvailability.Available) {
            uiState = uiState.copy(
                errorMessage = "Check root successfully before enabling Active Root Mode.",
                diagnostics = uiState.diagnostics + "Active Root Mode blocked until root is available."
            )
            return
        }
        val updated = policyStore.getDecisionPolicy().copy(
            activeRootModeEnabled = true,
            dryRunEnabled = true
        )
        policyStore.saveDecisionPolicy(updated)
        uiState = uiState.copy(
            decisionPolicy = policyStore.getDecisionPolicy(),
            activeRootConfirmationVisible = false,
            errorMessage = null,
            diagnostics = uiState.diagnostics + "Active Root Mode enabled."
        )
    }

    private fun disableActiveRootMode() {
        val updated = policyStore.getDecisionPolicy().copy(activeRootModeEnabled = false)
        policyStore.saveDecisionPolicy(updated)
        uiState = uiState.copy(
            decisionPolicy = policyStore.getDecisionPolicy(),
            activeRootConfirmationVisible = false,
            errorMessage = null,
            diagnostics = uiState.diagnostics + "Active Root Mode disabled."
        )
    }

    private fun dismissActiveRootConfirmation() {
        uiState = uiState.copy(activeRootConfirmationVisible = false)
    }

    private fun resetDecisionPolicyDefaults() {
        policyStore.resetDecisionPolicyDefaults()
        updateDecisionPolicy(policyStore.getDecisionPolicy())
    }

    private fun clearDryRunLogs() {
        policyStore.clearActionLogs()
        predictionLogger.clearDryRunLogs()
        uiState = uiState.copy(
            dryRunActionHistory = emptyList(),
            errorMessage = null,
            diagnostics = uiState.diagnostics + "Cleared dry-run logs."
        )
    }

    private fun exportShadowLogs() {
        if (!::shadowStore.isInitialized) return
        val uris = runCatching {
            shadowStore.exportLogsToCache()
        }.getOrElse { error ->
            handleFailure(
                stage = "Shadow log export",
                throwable = error,
                diagnostics = uiState.diagnostics
            )
            return
        }

        if (uris.isEmpty()) {
            uiState = uiState.copy(
                errorMessage = "No shadow logs to export yet.",
                diagnostics = uiState.diagnostics + "Shadow log export skipped because no files exist yet."
            )
            return
        }

        val exportIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/json"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(exportIntent, "Export ForeSight shadow logs"))
    }

    private fun checkRoot() {
        if (!::rootCommandRunner.isInitialized || uiState.isRootCommandRunning) return
        uiState = uiState.copy(
            isRootCommandRunning = true,
            errorMessage = null,
            diagnostics = uiState.diagnostics + "Checking root with safe id probe."
        )
        lifecycleScope.launch {
            val result = runCatching {
                rootCommandRunner.checkRootAvailability()
            }
            uiState = result.fold(
                onSuccess = { (availability, commandResult) ->
                    uiState.copy(
                        isRootCommandRunning = false,
                        rootAvailability = availability,
                        lastRootCommandResult = commandResult,
                        diagnostics = uiState.diagnostics + "Root status: ${availability.displayName}."
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    ForeSightLog.error("Root capability check failed", error)
                    uiState.copy(
                        isRootCommandRunning = false,
                        rootAvailability = RootAvailability.Denied,
                        errorMessage = "Root capability check failed: ${error.message ?: error::class.java.simpleName}",
                        diagnostics = uiState.diagnostics + "Root capability check failed."
                    )
                }
            )
        }
    }

    private fun runRootId() {
        runSafeRootCommand(RootCommandRunner.SafeRootCommand.Id)
    }

    private fun runRootPackageList() {
        runSafeRootCommand(RootCommandRunner.SafeRootCommand.PackageList)
    }

    private fun freezeRootPackage(packageName: String) {
        runRootMutation(
            label = "Freeze package",
            packageName = packageName
        ) {
            rootFreezeController.freezePackage(packageName)
        }
    }

    private fun unfreezeRootPackage(packageName: String) {
        runRootMutation(
            label = "Unfreeze package",
            packageName = packageName
        ) {
            rootFreezeController.unfreezePackage(packageName)
        }
    }

    private fun unfreezeAllForeSightFrozen() {
        if (!::rootFreezeController.isInitialized || uiState.isRootCommandRunning) return
        val frozenCount = policyStore.getRootFrozenPackages().size
        if (frozenCount == 0) {
            uiState = uiState.copy(
                errorMessage = null,
                diagnostics = uiState.diagnostics + "No ForeSight-frozen apps to unfreeze."
            )
            return
        }

        uiState = uiState.copy(
            isRootCommandRunning = true,
            errorMessage = null,
            diagnostics = uiState.diagnostics + "Emergency unfreeze started for $frozenCount ForeSight-frozen apps."
        )
        lifecycleScope.launch {
            val result = runCatching {
                rootFreezeController.unfreezeAllFrozenByForeSight()
            }
            uiState = result.fold(
                onSuccess = { results ->
                    val failedCount = results.count { it.exitCode != 0 || it.timedOut }
                    uiState.copy(
                        isRootCommandRunning = false,
                        rootAvailability = if (failedCount == 0) RootAvailability.Available else uiState.rootAvailability,
                        lastRootCommandResult = results.lastOrNull() ?: uiState.lastRootCommandResult,
                        rootFrozenPackages = policyStore.getRootFrozenPackages(),
                        errorMessage = if (failedCount == 0) {
                            null
                        } else {
                            "Emergency unfreeze completed with $failedCount failures."
                        },
                        diagnostics = uiState.diagnostics +
                            "Emergency unfreeze complete: ${results.size - failedCount}/${results.size} succeeded."
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    ForeSightLog.error("Emergency unfreeze failed", error)
                    uiState.copy(
                        isRootCommandRunning = false,
                        errorMessage = "Emergency unfreeze failed: ${error.message ?: error::class.java.simpleName}",
                        diagnostics = uiState.diagnostics + "Emergency unfreeze failed."
                    )
                }
            )
            refreshAppInventory(showAllInstalledApps = true)
        }
    }

    private fun runSafeRootCommand(command: RootCommandRunner.SafeRootCommand) {
        if (!::rootCommandRunner.isInitialized || uiState.isRootCommandRunning) return
        uiState = uiState.copy(
            isRootCommandRunning = true,
            errorMessage = null,
            diagnostics = uiState.diagnostics + "Running safe root command: ${command.label}."
        )
        lifecycleScope.launch {
            val result = runCatching {
                rootCommandRunner.runSafeCommand(command)
            }
            uiState = result.fold(
                onSuccess = { commandResult ->
                    val availability = if (command == RootCommandRunner.SafeRootCommand.Id) {
                        RootCommandRunner.inferAvailabilityFromIdResult(commandResult)
                    } else {
                        uiState.rootAvailability
                    }
                    uiState.copy(
                        isRootCommandRunning = false,
                        rootAvailability = availability,
                        lastRootCommandResult = commandResult,
                        diagnostics = uiState.diagnostics + "Safe root command finished: ${command.label}."
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    ForeSightLog.error("Safe root command failed", error)
                    uiState.copy(
                        isRootCommandRunning = false,
                        rootAvailability = RootAvailability.Denied,
                        errorMessage = "Safe root command failed: ${error.message ?: error::class.java.simpleName}",
                        diagnostics = uiState.diagnostics + "Safe root command failed: ${command.label}."
                    )
                }
            )
        }
    }

    private fun runRootMutation(
        label: String,
        packageName: String,
        command: suspend () -> RootCommandResult
    ) {
        if (!::rootFreezeController.isInitialized || uiState.isRootCommandRunning) return
        uiState = uiState.copy(
            isRootCommandRunning = true,
            errorMessage = null,
            diagnostics = uiState.diagnostics + "$label requested for $packageName."
        )
        lifecycleScope.launch {
            val result = runCatching { command() }
            uiState = result.fold(
                onSuccess = { commandResult ->
                    val succeeded = commandResult.exitCode == 0 && !commandResult.timedOut
                    uiState.copy(
                        isRootCommandRunning = false,
                        rootAvailability = if (succeeded) RootAvailability.Available else uiState.rootAvailability,
                        lastRootCommandResult = commandResult,
                        rootFrozenPackages = policyStore.getRootFrozenPackages(),
                        errorMessage = if (succeeded) {
                            null
                        } else {
                            "$label failed for $packageName: ${rootFailureSummary(commandResult)}"
                        },
                        diagnostics = uiState.diagnostics +
                            "$label ${if (succeeded) "succeeded" else "failed"} for $packageName."
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    ForeSightLog.warn("$label blocked for package=$packageName", error)
                    uiState.copy(
                        isRootCommandRunning = false,
                        errorMessage = "$label blocked for $packageName: ${error.message ?: error::class.java.simpleName}",
                        diagnostics = uiState.diagnostics + "$label blocked for $packageName."
                    )
                }
            )
            refreshAppInventory(showAllInstalledApps = true)
        }
    }

    private fun startBackgroundService() {
        val intent = Intent(this, ForeSightBackgroundService::class.java)
            .setAction(ForeSightBackgroundService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        uiState = uiState.copy(
            isBackgroundServiceRunning = true,
            diagnostics = uiState.diagnostics + "Started background prediction service."
        )
    }

    private fun stopBackgroundService() {
        val intent = Intent(this, ForeSightBackgroundService::class.java)
            .setAction(ForeSightBackgroundService.ACTION_STOP)
        startService(intent)
        uiState = uiState.copy(
            isBackgroundServiceRunning = false,
            diagnostics = uiState.diagnostics + "Stopped background prediction service."
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
                    val decisionPlan = buildDecisionPlan(
                        predictions = prediction.predictions,
                        installedApps = uiState.installedApps,
                        recentApps = prediction.recentApps
                    )
                    val policy = policyStore.getDecisionPolicy()
                    if (policy.activeRootModeEnabled) {
                        rootDecisionExecutor.rollbackRecentlyOpenedFrozenApps(prediction.recentApps, policy)
                    } else {
                        policyStore.applyDecisionPlan(decisionPlan)
                    }
                    val actionLogs = policyStore.recordActionLogs(
                        decisionPlan,
                        mode = if (policy.activeRootModeEnabled) ActionMode.ROOT_ACTIVE else ActionMode.DRY_RUN
                    )
                    val updatedFrozenPackages = policyStore.getDryRunFrozenPackages()
                    runCatching {
                        predictionLogger.logDecisionPlan(decisionPlan)
                        predictionLogger.logDryRunActions(actionLogs)
                    }.onFailure { logError ->
                        ForeSightLog.warn("Decision dry run succeeded but local logging failed", logError)
                        predictionLogger.logError(
                            stage = "Decision dry-run logging",
                            throwable = logError,
                            diagnostics = decisionPlan.diagnostics
                        )
                    }
                    if (policy.activeRootModeEnabled) {
                        rootDecisionExecutor.executeActivePlan(decisionPlan, policy)
                    }
                    recordShadowCycle(prediction, decisionPlan)
                    val (shadowMetrics, shadowEvaluations) = readShadowDashboard()
                    uiState.copy(
                        usageAccessEnabled = true,
                        isLoading = false,
                        recentApps = prediction.recentApps,
                        inputAppIds = prediction.inputAppIds,
                        predictions = prediction.predictions,
                        decisionPlan = decisionPlan,
                        protectedAllowlist = policyStore.getProtectedAllowlist(),
                        dryRunFrozenPackages = updatedFrozenPackages,
                        decisionPolicy = policyStore.getDecisionPolicy(),
                        dryRunActionHistory = policyStore.getRecentActionLogs(),
                        rootFrozenPackages = policyStore.getRootFrozenPackages(),
                        shadowMetrics = shadowMetrics,
                        shadowEvaluations = shadowEvaluations,
                        latencyMs = prediction.latencyMs,
                        lastUpdatedText = timestampText(),
                        diagnostics = prediction.diagnostics + decisionPlan.diagnostics,
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

    private fun buildDecisionPlan(
        predictions: List<PredictedApp>,
        installedApps: List<InstalledAppInfo>,
        recentApps: List<AppLaunch>
    ): DecisionPlan {
        val policy = policyStore.getDecisionPolicy()
        return AppDecisionEngine.buildPlan(
            predictions = predictions,
            installedApps = installedApps,
            recentApps = recentApps,
            protectedAllowlist = policyStore.getProtectedAllowlist(),
            dryRunFrozenPackages = if (policy.activeRootModeEnabled) {
                policyStore.getRootFrozenPackages()
            } else {
                policyStore.getDryRunFrozenPackages()
            },
            ownPackageName = packageName,
            policy = policy
        )
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
    onAddProtectedPackage: (String) -> Unit,
    onRemoveProtectedPackage: (String) -> Unit,
    onPolicyChange: (DecisionPolicy) -> Unit,
    onRequestActiveRootMode: () -> Unit,
    onConfirmActiveRootMode: () -> Unit,
    onDisableActiveRootMode: () -> Unit,
    onDismissActiveRootConfirmation: () -> Unit,
    onResetPolicyDefaults: () -> Unit,
    onClearDryRunLogs: () -> Unit,
    onExportShadowLogs: () -> Unit,
    onCheckRoot: () -> Unit,
    onRunRootId: () -> Unit,
    onRunRootPackageList: () -> Unit,
    onFreezePackage: (String) -> Unit,
    onUnfreezePackage: (String) -> Unit,
    onUnfreezeAllForeSightFrozen: () -> Unit,
    onStartBackgroundService: () -> Unit,
    onStopBackgroundService: () -> Unit,
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
                            onAddProtectedPackage = onAddProtectedPackage,
                            onRemoveProtectedPackage = onRemoveProtectedPackage,
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
                        DecisionsCard(state)
                    }
                    item {
                        ShadowEvaluationCard(
                            state = state,
                            onExportShadowLogs = onExportShadowLogs
                        )
                    }
                }

                4 -> {
                    item {
                        RootSandboxCard(
                            state = state,
                            onCheckRoot = onCheckRoot,
                            onRunRootId = onRunRootId,
                            onRunRootPackageList = onRunRootPackageList
                        )
                    }
                    item {
                        ManualRootFreezeCard(
                            state = state,
                            onLoadAllInstalledApps = { onInventoryScopeChange(true) },
                            onFreezePackage = onFreezePackage,
                            onUnfreezePackage = onUnfreezePackage,
                            onUnfreezeAllForeSightFrozen = onUnfreezeAllForeSightFrozen
                        )
                    }
                    item {
                        PolicySettingsCard(
                            serviceRunning = state.isBackgroundServiceRunning,
                            state = state,
                            policy = state.decisionPolicy,
                            onPolicyChange = onPolicyChange,
                            onRequestActiveRootMode = onRequestActiveRootMode,
                            onConfirmActiveRootMode = onConfirmActiveRootMode,
                            onDisableActiveRootMode = onDisableActiveRootMode,
                            onDismissActiveRootConfirmation = onDismissActiveRootConfirmation,
                            onResetPolicyDefaults = onResetPolicyDefaults,
                            onClearDryRunLogs = onClearDryRunLogs,
                            onStartBackgroundService = onStartBackgroundService,
                            onStopBackgroundService = onStopBackgroundService
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
        3 -> "Decisions"
        4 -> "Settings"
        else -> "Prediction"
    }
}

private fun formatTimestamp(timestampMillis: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))
}

private fun formatPercent(value: Float): String {
    return "%.1f%%".format(value * 100f)
}

@Composable
private fun DecisionsCard(state: PredictionUiState) {
    SectionCard(title = "Decision Dry Run") {
        val plan = state.decisionPlan
        if (plan == null) {
            Text("No decision plan yet. Run prediction first.")
            return@SectionCard
        }

        val protectCount = plan.decisions.count { it.action == DecisionAction.PROTECT }
        val unfreezeCount = plan.decisions.count { it.action == DecisionAction.WOULD_UNFREEZE }
        val freezeCount = plan.decisions.count { it.action == DecisionAction.WOULD_FREEZE }
        val ignoredCount = plan.decisions.count { it.action == DecisionAction.IGNORE }

        Text("Mode: dry run only")
        Text("Protected: $protectCount")
        Text("Would unfreeze: $unfreezeCount")
        Text("Would freeze: $freezeCount")
        Text("Ignored: $ignoredCount")
        Text("Manual allowlist: ${state.protectedAllowlist.size}")
        Text("Dry-run frozen: ${state.dryRunFrozenPackages.size}")
        Text("Last decision cycle: ${formatTimestamp(plan.timestampMillis)}")
        Text("Decision log: ${state.decisionLogFileName}")
        Text("Action log: ${state.actionLogFileName}")

        plan.diagnostics.forEach { diagnostic ->
            Text("- $diagnostic")
        }

        if (plan.decisions.isEmpty()) {
            Text("No app actions selected by policy.")
        } else {
            DecisionGroup(
                title = "Protected",
                decisions = plan.decisions.filter { it.action == DecisionAction.PROTECT }
            )
            DecisionGroup(
                title = "Would Unfreeze",
                decisions = plan.decisions.filter { it.action == DecisionAction.WOULD_UNFREEZE }
            )
            DecisionGroup(
                title = "Would Freeze",
                decisions = plan.decisions.filter { it.action == DecisionAction.WOULD_FREEZE }
            )
            DecisionGroup(
                title = "Ignored",
                decisions = plan.decisions.filter { it.action == DecisionAction.IGNORE },
                displayLimit = 20
            )
        }

        Text(
            text = "Recent Dry-Run Actions (${state.dryRunActionHistory.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (state.dryRunActionHistory.isEmpty()) {
            Text("No dry-run action history yet.")
        } else {
            state.dryRunActionHistory.take(20).forEachIndexed { index, log ->
                DryRunActionLogRow(log)
                if (index != state.dryRunActionHistory.take(20).lastIndex) HorizontalDivider()
            }
            if (state.dryRunActionHistory.size > 20) {
                Text("Showing 20 of ${state.dryRunActionHistory.size}.")
            }
        }
    }
}

@Composable
private fun ShadowEvaluationCard(
    state: PredictionUiState,
    onExportShadowLogs: () -> Unit
) {
    val metrics = state.shadowMetrics
    SectionCard(title = "Shadow Evaluation") {
        Text("Evaluated cycles: ${metrics.evaluatedCycles}")
        Text("Pending cycles: ${metrics.pendingCycles}")
        Text("Top-1 accuracy: ${formatPercent(metrics.top1Accuracy)}")
        Text("Top-3 accuracy: ${formatPercent(metrics.top3Accuracy)}")
        Text("Top-5 accuracy: ${formatPercent(metrics.top5Accuracy)}")
        Text("Average inference latency: ${"%.1f".format(metrics.averageInferenceLatencyMs)}ms")
        Text("Unknown app rate: ${formatPercent(metrics.unknownAppRate)}")
        Text("Dry-run freeze count: ${metrics.dryRunFreezeCount}")
        Text("Would-have-frozen actual next app: ${metrics.wouldHaveFrozenActualNextAppCount}")
        Text("Cycle log: ${state.shadowCycleLogFileName}")
        Text("Evaluation log: ${state.shadowEvaluationLogFileName}")

        if (metrics.recentUnsafeEvaluations.isNotEmpty()) {
            Text(
                text = "Unsafe decisions",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            metrics.recentUnsafeEvaluations.forEachIndexed { index, evaluation ->
                ShadowEvaluationRow(evaluation)
                if (index != metrics.recentUnsafeEvaluations.lastIndex) HorizontalDivider()
            }
        }

        Text(
            text = "Recent Evaluations (${state.shadowEvaluations.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (state.shadowEvaluations.isEmpty()) {
            Text("No evaluated shadow cycles yet. Leave ForeSight running, then open another app.")
        } else {
            state.shadowEvaluations.take(10).forEachIndexed { index, evaluation ->
                ShadowEvaluationRow(evaluation)
                if (index != state.shadowEvaluations.take(10).lastIndex) HorizontalDivider()
            }
        }

        Button(onClick = onExportShadowLogs) {
            Text("Export Logs")
        }
    }
}

@Composable
private fun ShadowEvaluationRow(evaluation: ShadowEvaluation) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = if (evaluation.unsafeWouldFreezeActualNextApp) {
                "Unsafe: ${evaluation.actualNextApp.appLabel}"
            } else {
                "Opened: ${evaluation.actualNextApp.appLabel}"
            },
            fontWeight = FontWeight.Medium,
            color = if (evaluation.unsafeWouldFreezeActualNextApp) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        Text(
            text = evaluation.actualNextApp.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Predicted ${formatTimestamp(evaluation.predictionTimestampMillis)} · " +
                "opened ${formatTimestamp(evaluation.actualNextApp.timestampMillis)} · " +
                "latency ${evaluation.predictionToOpenLatencyMs}ms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Hits: top1=${evaluation.top1Hit}, top3=${evaluation.top3Hit}, top5=${evaluation.top5Hit}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Inference ${evaluation.inferenceLatencyMs}ms · " +
                "unknown recent ${evaluation.unknownRecentAppCount}/${evaluation.recentAppCount} · " +
                "would-freeze ${evaluation.dryRunFreezeCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RootSandboxCard(
    state: PredictionUiState,
    onCheckRoot: () -> Unit,
    onRunRootId: () -> Unit,
    onRunRootPackageList: () -> Unit
) {
    SectionCard(title = "Root Sandbox") {
        Text(
            text = state.rootAvailability.displayName,
            fontWeight = FontWeight.SemiBold,
            color = when (state.rootAvailability) {
                RootAvailability.Available -> MaterialTheme.colorScheme.primary
                RootAvailability.Denied -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        Text("Command log: ${state.rootCommandLogFileName}")
        Text(
            text = "Only fixed allowlisted probes and validated package actions can run. Arbitrary commands are blocked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCheckRoot,
                enabled = !state.isRootCommandRunning
            ) {
                Text(if (state.isRootCommandRunning) "Running" else "Check Root")
            }
            TextButton(
                onClick = onRunRootId,
                enabled = !state.isRootCommandRunning
            ) {
                Text("Run id")
            }
        }
        TextButton(
            onClick = onRunRootPackageList,
            enabled = !state.isRootCommandRunning
        ) {
            Text("Run pm list packages")
        }

        state.lastRootCommandResult?.let { result ->
            RootCommandResultView(result)
        }
    }
}

@Composable
private fun RootCommandResultView(result: RootCommandResult) {
    Text(
        text = "Last command: ${result.commandLabel}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Text("Command: ${result.command}")
    Text("Exit code: ${result.exitCode}")
    Text("Timed out: ${result.timedOut}")
    Text("Time: ${formatTimestamp(result.timestampMillis)}")

    val stdoutPreview = rootOutputPreview(result.stdout, result.commandId)
    val stderrPreview = rootOutputPreview(result.stderr, result.commandId)
    if (stdoutPreview.isNotBlank()) {
        Text(
            text = "stdout",
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stdoutPreview,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (stderrPreview.isNotBlank()) {
        Text(
            text = "stderr",
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = stderrPreview,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

private fun rootOutputPreview(output: String, commandId: String): String {
    val lines = output.lineSequence()
        .filter { it.isNotBlank() }
        .toList()
    if (lines.isEmpty()) return ""

    val limit = if (commandId == RootCommandRunner.SafeRootCommand.PackageList.id) 10 else 20
    val visible = lines.take(limit)
    return if (lines.size > visible.size) {
        visible.joinToString(separator = "\n") + "\n... showing ${visible.size} of ${lines.size} lines"
    } else {
        visible.joinToString(separator = "\n")
    }
}

private fun rootFailureSummary(result: RootCommandResult): String {
    return result.stderr
        .takeIf { it.isNotBlank() }
        ?: result.stdout.takeIf { it.isNotBlank() }
        ?: "exit=${result.exitCode}, timedOut=${result.timedOut}"
}

@Composable
private fun ManualRootFreezeCard(
    state: PredictionUiState,
    onLoadAllInstalledApps: () -> Unit,
    onFreezePackage: (String) -> Unit,
    onUnfreezePackage: (String) -> Unit,
    onUnfreezeAllForeSightFrozen: () -> Unit
) {
    SectionCard(title = "Manual Root Freeze") {
        Text("ForeSight frozen apps: ${state.rootFrozenPackages.size}")
        Text(
            text = "Manual actions use root immediately. Automation still does not freeze or unfreeze apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onLoadAllInstalledApps,
                enabled = !state.isInventoryLoading
            ) {
                Text(if (state.isInventoryLoading) "Loading Apps" else "Load All Apps")
            }
            TextButton(
                onClick = onUnfreezeAllForeSightFrozen,
                enabled = !state.isRootCommandRunning && state.rootFrozenPackages.isNotEmpty()
            ) {
                Text("Unfreeze All")
            }
        }

        val apps = state.installedApps
            .sortedWith(
                compareByDescending<InstalledAppInfo> { it.packageName in state.rootFrozenPackages }
                    .thenBy { it.isEnabled }
                    .thenBy { it.isSystemApp }
                    .thenBy { it.appLabel.lowercase(Locale.getDefault()) }
            )
            .take(ROOT_MANUAL_APP_LIMIT)

        if (state.installedApps.isEmpty()) {
            Text("Load all apps to choose manual root freeze targets.")
        } else {
            apps.forEachIndexed { index, app ->
                ManualRootAppRow(
                    app = app,
                    state = state,
                    onFreezePackage = onFreezePackage,
                    onUnfreezePackage = onUnfreezePackage
                )
                if (index != apps.lastIndex) HorizontalDivider()
            }
            if (state.installedApps.size > apps.size) {
                Text("Showing ${apps.size} of ${state.installedApps.size} apps.")
            }
        }
    }
}

@Composable
private fun ManualRootAppRow(
    app: InstalledAppInfo,
    state: PredictionUiState,
    onFreezePackage: (String) -> Unit,
    onUnfreezePackage: (String) -> Unit
) {
    val freezeBlockedReason = app.manualFreezeBlockedReason(
        ownPackageName = state.ownPackageName,
        allowlist = state.protectedAllowlist
    )
    val isForeSightFrozen = app.packageName in state.rootFrozenPackages
    Column(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(app.appLabel, fontWeight = FontWeight.Medium)
        Text(
            text = app.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = app.rootStatusText(isForeSightFrozen),
            style = MaterialTheme.typography.bodySmall,
            color = if (isForeSightFrozen) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        freezeBlockedReason?.let { reason ->
            Text(
                text = "Freeze blocked: $reason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onFreezePackage(app.packageName) },
                enabled = !state.isRootCommandRunning && app.isEnabled && freezeBlockedReason == null
            ) {
                Text("Freeze")
            }
            TextButton(
                onClick = { onUnfreezePackage(app.packageName) },
                enabled = !state.isRootCommandRunning && (!app.isEnabled || isForeSightFrozen)
            ) {
                Text("Unfreeze")
            }
        }
    }
}

private fun InstalledAppInfo.rootStatusText(isForeSightFrozen: Boolean): String {
    return when {
        isForeSightFrozen -> "Frozen by ForeSight"
        !isEnabled -> "Disabled outside ForeSight"
        else -> "Enabled"
    }
}

private fun InstalledAppInfo.manualFreezeBlockedReason(
    ownPackageName: String,
    allowlist: Set<String>
): String? {
    val packageNameLower = packageName.lowercase(Locale.getDefault())
    val labelLower = appLabel.lowercase(Locale.getDefault())
    return when {
        packageName == ownPackageName -> "ForeSight itself"
        packageName in allowlist -> "manual allowlist"
        packageNameLower.contains("launcher") || labelLower.contains("launcher") -> "launcher"
        packageNameLower.contains("inputmethod") ||
            labelLower.contains("keyboard") ||
            labelLower.contains("gboard") -> "keyboard/input method"
        packageNameLower.contains("systemui") || labelLower == "system ui" -> "system UI"
        packageNameLower.contains("dialer") || packageNameLower.contains(".phone") || labelLower == "phone" -> {
            "phone/dialer"
        }
        packageNameLower.contains("settings") || labelLower == "settings" -> "settings"
        packageNameLower.contains("clock") || packageNameLower.contains("alarm") || labelLower == "clock" -> {
            "clock/alarm"
        }
        packageNameLower.contains("accessibility") ||
            packageNameLower.contains("talkback") ||
            labelLower.contains("accessibility") ||
            labelLower.contains("voice access") ||
            labelLower.contains("switch access") -> "accessibility"
        isSystemApp -> "system app"
        else -> null
    }
}

@Composable
private fun PolicySettingsCard(
    serviceRunning: Boolean,
    state: PredictionUiState,
    policy: DecisionPolicy,
    onPolicyChange: (DecisionPolicy) -> Unit,
    onRequestActiveRootMode: () -> Unit,
    onConfirmActiveRootMode: () -> Unit,
    onDisableActiveRootMode: () -> Unit,
    onDismissActiveRootConfirmation: () -> Unit,
    onResetPolicyDefaults: () -> Unit,
    onClearDryRunLogs: () -> Unit,
    onStartBackgroundService: () -> Unit,
    onStopBackgroundService: () -> Unit
) {
    SectionCard(title = "Dry-Run Policy") {
        Text(
            text = if (serviceRunning) "Background service: running" else "Background service: stopped",
            fontWeight = FontWeight.Medium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStartBackgroundService,
                enabled = !serviceRunning
            ) {
                Text("Start Service")
            }
            TextButton(
                onClick = onStopBackgroundService,
                enabled = serviceRunning
            ) {
                Text("Stop Service")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Dry-run enabled", fontWeight = FontWeight.Medium)
                Text(
                    text = "Dry Run remains the default. Active Root Mode requires separate confirmation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = policy.dryRunEnabled,
                onCheckedChange = { enabled ->
                    onPolicyChange(policy.copy(dryRunEnabled = enabled))
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Active Root Mode", fontWeight = FontWeight.Medium)
                Text(
                    text = if (policy.activeRootModeEnabled) {
                        "Automatic root freeze/unfreeze is enabled."
                    } else {
                        "Automatic root freeze/unfreeze is disabled."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (policy.activeRootModeEnabled) {
                TextButton(onClick = onDisableActiveRootMode) {
                    Text("Disable")
                }
            } else {
                Button(onClick = onRequestActiveRootMode) {
                    Text("Enable")
                }
            }
        }
        if (state.activeRootConfirmationVisible) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Enable Active Root Mode?",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "ForeSight will automatically run root freeze and unfreeze commands from prediction cycles.",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConfirmActiveRootMode) {
                            Text("Confirm")
                        }
                        TextButton(onClick = onDismissActiveRootConfirmation) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Pause when battery low", fontWeight = FontWeight.Medium)
                Text(
                    text = "The background loop skips cycles while unplugged and low.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = policy.pauseWhenBatteryLow,
                onCheckedChange = { enabled ->
                    onPolicyChange(policy.copy(pauseWhenBatteryLow = enabled))
                }
            )
        }

        PolicyFloatStepper(
            title = "Freeze threshold",
            value = policy.freezeThreshold,
            onValueChange = { value -> onPolicyChange(policy.copy(freezeThreshold = value)) }
        )
        PolicyFloatStepper(
            title = "Protect threshold",
            value = policy.protectThreshold,
            onValueChange = { value -> onPolicyChange(policy.copy(protectThreshold = value)) }
        )
        PolicyIntStepper(
            title = "Recent-app protection window",
            value = policy.recentAppProtectionWindow,
            min = 0,
            max = 50,
            onValueChange = { value -> onPolicyChange(policy.copy(recentAppProtectionWindow = value)) }
        )
        PolicyIntStepper(
            title = "Max apps to freeze per cycle",
            value = policy.maxAppsToFreezePerCycle,
            min = 0,
            max = 25,
            onValueChange = { value -> onPolicyChange(policy.copy(maxAppsToFreezePerCycle = value)) }
        )
        PolicyIntStepper(
            title = "Prediction interval seconds",
            value = policy.predictionIntervalSeconds,
            min = 30,
            max = 3600,
            step = 30,
            onValueChange = { value -> onPolicyChange(policy.copy(predictionIntervalSeconds = value)) }
        )
        PolicyIntStepper(
            title = "Root action cooldown seconds",
            value = policy.rootActionCooldownSeconds,
            min = 30,
            max = 3600,
            step = 30,
            onValueChange = { value -> onPolicyChange(policy.copy(rootActionCooldownSeconds = value)) }
        )
        PolicyIntStepper(
            title = "Rollback window seconds",
            value = policy.rollbackWindowSeconds,
            min = 30,
            max = 3600,
            step = 30,
            onValueChange = { value -> onPolicyChange(policy.copy(rollbackWindowSeconds = value)) }
        )

        Text("Default max would-freeze apps: ${DecisionPolicy.DEFAULT_MAX_APPS_TO_FREEZE_PER_CYCLE}")
        Text("Default protected recent apps: ${DecisionPolicy.DEFAULT_RECENT_APP_PROTECTION_WINDOW}")
        Text("Default prediction interval: ${DecisionPolicy.DEFAULT_PREDICTION_INTERVAL_SECONDS}s")
        Text("Default root cooldown: ${DecisionPolicy.DEFAULT_ROOT_ACTION_COOLDOWN_SECONDS}s")
        Text("Protected top predictions: ${DecisionPolicy.PROTECTED_TOP_PREDICTIONS}")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onResetPolicyDefaults) {
                Text("Reset Policy Defaults")
            }
            TextButton(onClick = onClearDryRunLogs) {
                Text("Clear Logs")
            }
        }
    }
}

@Composable
private fun PolicyFloatStepper(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                text = "%.2f".format(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onValueChange((value - 0.01f).coerceIn(0f, 1f)) }) {
                Text("-")
            }
            TextButton(onClick = { onValueChange((value + 0.01f).coerceIn(0f, 1f)) }) {
                Text("+")
            }
        }
    }
}

@Composable
private fun PolicyIntStepper(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onValueChange((value - step).coerceIn(min, max)) }) {
                Text("-")
            }
            TextButton(onClick = { onValueChange((value + step).coerceIn(min, max)) }) {
                Text("+")
            }
        }
    }
}

@Composable
private fun DryRunActionLogRow(log: DryRunActionLog) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = "${log.action.displayName}: ${log.appLabel}",
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "${formatTimestamp(log.timestampMillis)} · ${log.mode.displayName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = log.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Reason: ${log.reason.displayName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        log.predictionConfidence?.let { confidence ->
            Text(
                text = "Confidence: ${"%.3f".format(confidence)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DecisionGroup(
    title: String,
    decisions: List<AppDecision>,
    displayLimit: Int = Int.MAX_VALUE
) {
    Text(
        text = "$title (${decisions.size})",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    if (decisions.isEmpty()) {
        Text("None")
        return
    }

    val visibleDecisions = decisions.take(displayLimit)
    visibleDecisions.forEachIndexed { index, decision ->
        DecisionRow(decision)
        if (index != visibleDecisions.lastIndex) HorizontalDivider()
    }
    if (decisions.size > visibleDecisions.size) {
        Text("Showing ${visibleDecisions.size} of ${decisions.size}.")
    }
}

@Composable
private fun DecisionRow(decision: AppDecision) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = "${decision.action.displayName}: ${decision.appLabel}",
            fontWeight = FontWeight.Medium
        )
        Text(
            text = decision.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        decision.modelAppId?.let { appId ->
            Text(
                text = "Model: ${decision.modelLabel ?: "App"} ($appId)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        decision.confidence?.let { confidence ->
            Text(
                text = "Confidence: ${"%.3f".format(confidence)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "Reason: ${decision.reason.displayName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = decision.reasonDetail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    onAddProtectedPackage: (String) -> Unit,
    onRemoveProtectedPackage: (String) -> Unit,
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
                    isAllowlisted = app.packageName in state.protectedAllowlist,
                    vocabLabelText = overrideVocabLabelText,
                    onVocabLabelChange = onOverrideVocabLabelChange,
                    onSave = { onSaveMappingOverride(app.packageName, overrideVocabLabelText) },
                    onClear = { onClearMappingOverride(app.packageName) },
                    onAddProtected = { onAddProtectedPackage(app.packageName) },
                    onRemoveProtected = { onRemoveProtectedPackage(app.packageName) },
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
            Text(
                text = if (state.isBackgroundServiceRunning) {
                    "Background loop: running"
                } else {
                    "Background loop: stopped"
                }
            )
            Text(
                text = if (state.decisionPolicy.activeRootModeEnabled) {
                    "Execution mode: Active Root"
                } else {
                    "Execution mode: Dry Run"
                },
                color = if (state.decisionPolicy.activeRootModeEnabled) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text("Prediction interval: ${state.decisionPolicy.predictionIntervalSeconds}s")
            state.lastUpdatedText?.let { updated ->
                Text("Last refresh: $updated")
            }
            Text("Prediction log: ${state.logFileName}")
            Text("Decision log: ${state.decisionLogFileName}")
            Text("Action log: ${state.actionLogFileName}")
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
    isAllowlisted: Boolean,
    vocabLabelText: String,
    onVocabLabelChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onAddProtected: () -> Unit,
    onRemoveProtected: () -> Unit,
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
                    text = app.inventoryTypeText(isAllowlisted),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isAllowlisted) {
                    TextButton(onClick = onRemoveProtected) {
                        Text("Remove Protect")
                    }
                } else {
                    Button(onClick = onAddProtected) {
                        Text("Protect")
                    }
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

private fun InstalledAppInfo.inventoryTypeText(isAllowlisted: Boolean): String {
    val appType = if (isSystemApp) "system app" else "user app"
    val launchableType = if (isLaunchable) "launchable" else "not launchable"
    val allowlistType = if (isAllowlisted) ", allowlisted" else ""
    return "$appType, $launchableType$allowlistType"
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
