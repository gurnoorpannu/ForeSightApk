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
    val recentApps: List<AppLaunch> = emptyList(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val inputAppIds: List<Long> = emptyList(),
    val predictions: List<PredictedApp> = emptyList(),
    val latencyMs: Long? = null,
    val errorMessage: String? = null,
    val diagnostics: List<String> = emptyList(),
    val lastUpdatedText: String? = null,
    val logFileName: String = PredictionLogger.PREDICTION_FILE_NAME,
    val errorLogFileName: String = PredictionLogger.ERROR_FILE_NAME
)
