package com.example.foresightapk

import android.os.Bundle

object InferenceContract {
    const val MSG_PREDICT = 1
    const val MSG_RESULT = 2
    const val MSG_ERROR = 3

    private const val KEY_PACKAGES = "packages"
    private const val KEY_LABELS = "labels"
    private const val KEY_TIMESTAMPS = "timestamps"
    private const val KEY_INPUT_IDS = "input_ids"
    private const val KEY_INPUT_LABELS = "input_labels"
    private const val KEY_PREDICTION_LABELS = "prediction_labels"
    private const val KEY_PREDICTION_IDS = "prediction_ids"
    private const val KEY_CONFIDENCES = "confidences"
    private const val KEY_LATENCY = "latency"
    private const val KEY_DIAGNOSTICS = "diagnostics"
    private const val KEY_ERROR = "error"

    fun launchesToBundle(launches: List<AppLaunch>): Bundle {
        return Bundle().apply {
            putStringArray(KEY_PACKAGES, launches.map { it.packageName }.toTypedArray())
            putStringArray(KEY_LABELS, launches.map { it.appLabel }.toTypedArray())
            putLongArray(KEY_TIMESTAMPS, launches.map { it.timestampMillis }.toLongArray())
        }
    }

    fun launchesFromBundle(bundle: Bundle): List<AppLaunch> {
        val packages = bundle.getStringArray(KEY_PACKAGES).orEmpty()
        val labels = bundle.getStringArray(KEY_LABELS).orEmpty()
        val timestamps = bundle.getLongArray(KEY_TIMESTAMPS) ?: LongArray(0)
        val size = minOf(packages.size, labels.size, timestamps.size)

        return List(size) { index ->
            AppLaunch(
                packageName = packages[index],
                appLabel = labels[index],
                timestampMillis = timestamps[index]
            )
        }
    }

    fun resultToBundle(result: PredictionResult): Bundle {
        return launchesToBundle(result.recentApps).apply {
            putLongArray(KEY_INPUT_IDS, result.inputAppIds.toLongArray())
            putStringArray(KEY_INPUT_LABELS, result.inputAppLabels.toTypedArray())
            putStringArray(KEY_PREDICTION_LABELS, result.predictions.map { it.label }.toTypedArray())
            putIntArray(KEY_PREDICTION_IDS, result.predictions.map { it.appId }.toIntArray())
            putFloatArray(KEY_CONFIDENCES, result.predictions.map { it.confidence }.toFloatArray())
            putLong(KEY_LATENCY, result.latencyMs)
            putStringArray(KEY_DIAGNOSTICS, result.diagnostics.toTypedArray())
        }
    }

    fun resultFromBundle(bundle: Bundle): PredictionResult {
        val predictionLabels = bundle.getStringArray(KEY_PREDICTION_LABELS).orEmpty()
        val predictionIds = bundle.getIntArray(KEY_PREDICTION_IDS) ?: IntArray(0)
        val confidences = bundle.getFloatArray(KEY_CONFIDENCES) ?: FloatArray(0)
        val predictionCount = minOf(predictionLabels.size, predictionIds.size, confidences.size)

        return PredictionResult(
            recentApps = launchesFromBundle(bundle),
            inputAppIds = (bundle.getLongArray(KEY_INPUT_IDS) ?: LongArray(0)).toList(),
            inputAppLabels = bundle.getStringArray(KEY_INPUT_LABELS).orEmpty().toList(),
            predictions = List(predictionCount) { index ->
                PredictedApp(
                    label = predictionLabels[index],
                    appId = predictionIds[index],
                    confidence = confidences[index]
                )
            },
            latencyMs = bundle.getLong(KEY_LATENCY),
            diagnostics = bundle.getStringArray(KEY_DIAGNOSTICS).orEmpty().toList()
        )
    }

    fun errorToBundle(message: String): Bundle {
        return Bundle().apply {
            putString(KEY_ERROR, message)
        }
    }

    fun errorFromBundle(bundle: Bundle): String {
        return bundle.getString(KEY_ERROR) ?: "Inference failed."
    }
}
