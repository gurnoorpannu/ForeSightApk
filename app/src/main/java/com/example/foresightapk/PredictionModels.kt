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

data class PredictionUiState(
    val usageAccessEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val recentApps: List<AppLaunch> = emptyList(),
    val inputAppIds: List<Long> = emptyList(),
    val predictions: List<PredictedApp> = emptyList(),
    val latencyMs: Long? = null,
    val errorMessage: String? = null,
    val diagnostics: List<String> = emptyList(),
    val lastUpdatedText: String? = null,
    val logFileName: String = PredictionLogger.PREDICTION_FILE_NAME,
    val errorLogFileName: String = PredictionLogger.ERROR_FILE_NAME
)
