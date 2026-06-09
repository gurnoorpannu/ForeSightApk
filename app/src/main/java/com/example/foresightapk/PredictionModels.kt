package com.example.foresightapk

data class AppLaunch(
    val packageName: String,
    val appLabel: String,
    val timestampMillis: Long
)

data class PredictedApp(
    val label: String,
    val appId: Int,
    val confidence: Float
)

data class PredictionResult(
    val recentApps: List<AppLaunch>,
    val inputAppIds: List<Long>,
    val inputAppLabels: List<String>,
    val predictions: List<PredictedApp>,
    val latencyMs: Long,
    val diagnostics: List<String>
)

enum class DecisionAction(val displayName: String) {
    PROTECT("PROTECT"),
    WOULD_FREEZE("WOULD_FREEZE"),
    WOULD_UNFREEZE("WOULD_UNFREEZE"),
    IGNORE("IGNORE")
}

enum class DecisionReason(val displayName: String) {
    TopPrediction("Top prediction"),
    PredictedAndDryRunFrozen("Predicted soon and marked frozen in dry-run state"),
    RecentlyUsed("Recently used"),
    ProtectedSelf("ForeSight itself"),
    ProtectedLauncher("Launcher"),
    ProtectedKeyboard("Keyboard/input method"),
    ProtectedSystemUi("System UI"),
    ProtectedDialer("Phone/dialer"),
    ProtectedSettings("Settings"),
    ProtectedClock("Alarm/clock"),
    ProtectedAccessibility("Accessibility-related app"),
    ManualAllowlist("Manual allowlist"),
    LowProbabilitySafeCandidate("Low probability and safe candidate"),
    NotLaunchable("Not launchable"),
    NoModelMapping("No model mapping"),
    SystemApp("System app"),
    AlreadyDryRunFrozen("Already marked frozen in dry-run state"),
    AboveFreezeThreshold("Above freeze threshold"),
    MaxFreezeLimitReached("Max freeze limit reached"),
    DryRunDisabled("Dry-run disabled")
}

data class AppDecision(
    val action: DecisionAction,
    val packageName: String,
    val appLabel: String,
    val modelAppId: Int?,
    val modelLabel: String?,
    val confidence: Float?,
    val reason: DecisionReason,
    val reasonDetail: String
)

data class DecisionPlan(
    val timestampMillis: Long,
    val predictionsSeen: Int,
    val installedAppsSeen: Int,
    val recentAppsSeen: Int,
    val decisions: List<AppDecision>,
    val diagnostics: List<String>
)

enum class ActionMode(val displayName: String) {
    DRY_RUN("DRY_RUN")
}

data class DryRunActionLog(
    val timestampMillis: Long,
    val packageName: String,
    val appLabel: String,
    val action: DecisionAction,
    val reason: DecisionReason,
    val predictionConfidence: Float?,
    val mode: ActionMode = ActionMode.DRY_RUN
)

data class DecisionPolicy(
    val freezeThreshold: Float = DEFAULT_FREEZE_THRESHOLD,
    val protectThreshold: Float = DEFAULT_PROTECT_THRESHOLD,
    val recentAppProtectionWindow: Int = DEFAULT_RECENT_APP_PROTECTION_WINDOW,
    val maxAppsToFreezePerCycle: Int = DEFAULT_MAX_APPS_TO_FREEZE_PER_CYCLE,
    val dryRunEnabled: Boolean = DEFAULT_DRY_RUN_ENABLED
) {
    fun sanitized(): DecisionPolicy {
        return copy(
            freezeThreshold = freezeThreshold.coerceIn(0f, 1f),
            protectThreshold = protectThreshold.coerceIn(0f, 1f),
            recentAppProtectionWindow = recentAppProtectionWindow.coerceIn(0, 50),
            maxAppsToFreezePerCycle = maxAppsToFreezePerCycle.coerceIn(0, 25)
        )
    }

    companion object {
        const val DEFAULT_FREEZE_THRESHOLD = 0.02f
        const val DEFAULT_PROTECT_THRESHOLD = 0.03f
        const val DEFAULT_RECENT_APP_PROTECTION_WINDOW = 10
        const val DEFAULT_MAX_APPS_TO_FREEZE_PER_CYCLE = 3
        const val DEFAULT_DRY_RUN_ENABLED = true
        const val PROTECTED_TOP_PREDICTIONS = 5
    }
}

enum class MappingSource(val displayName: String) {
    ManualOverride("manual override"),
    PackageAlias("package alias"),
    ExactLabel("exact label"),
    NormalizedLabel("normalized label"),
    PackageName("package name"),
    Unknown("unknown")
}

data class AppMappingResult(
    val modelAppId: Int?,
    val modelLabel: String?,
    val source: MappingSource,
    val confidence: Float
)

data class InstalledAppInfo(
    val packageName: String,
    val appLabel: String,
    val isSystemApp: Boolean,
    val isLaunchable: Boolean,
    val mappedModelAppId: Int?,
    val mappedModelLabel: String?,
    val mappingSource: MappingSource,
    val mappingConfidence: Float
)

data class PredictionUiState(
    val usageAccessEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val isInventoryLoading: Boolean = false,
    val showAllInstalledApps: Boolean = false,
    val protectedAllowlist: Set<String> = emptySet(),
    val dryRunFrozenPackages: Set<String> = emptySet(),
    val decisionPolicy: DecisionPolicy = DecisionPolicy(),
    val dryRunActionHistory: List<DryRunActionLog> = emptyList(),
    val recentApps: List<AppLaunch> = emptyList(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val inputAppIds: List<Long> = emptyList(),
    val predictions: List<PredictedApp> = emptyList(),
    val decisionPlan: DecisionPlan? = null,
    val latencyMs: Long? = null,
    val errorMessage: String? = null,
    val diagnostics: List<String> = emptyList(),
    val lastUpdatedText: String? = null,
    val logFileName: String = PredictionLogger.PREDICTION_FILE_NAME,
    val decisionLogFileName: String = PredictionLogger.DECISION_FILE_NAME,
    val actionLogFileName: String = PredictionLogger.ACTION_FILE_NAME,
    val errorLogFileName: String = PredictionLogger.ERROR_FILE_NAME
)
