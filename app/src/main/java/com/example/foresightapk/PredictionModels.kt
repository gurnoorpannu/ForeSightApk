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
    AlreadyDryRunFrozen("Already marked frozen in dry-run state")
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
    val errorLogFileName: String = PredictionLogger.ERROR_FILE_NAME
)
