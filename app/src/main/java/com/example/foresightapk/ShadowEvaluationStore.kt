package com.example.foresightapk

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ShadowEvaluationStore(
    private val context: Context,
    private val usageEventReader: UsageEventReader
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun evaluatePendingCycles(): List<ShadowEvaluation> {
        val pending = getPendingCycles()
        if (pending.isEmpty()) return emptyList()

        val stillPending = mutableListOf<ShadowPredictionCycle>()
        val newlyEvaluated = mutableListOf<ShadowEvaluation>()
        pending.forEach { cycle ->
            val actualNextApp = usageEventReader.readFirstLaunchAfter(cycle.timestampMillis)
            if (actualNextApp == null) {
                stillPending += cycle
            } else {
                newlyEvaluated += cycle.evaluate(actualNextApp)
            }
        }

        savePendingCycles(stillPending)
        newlyEvaluated.forEach { evaluation ->
            appendJsonLine(EVALUATION_FILE_NAME, evaluation.toJson())
        }
        if (newlyEvaluated.isNotEmpty()) {
            ForeSightLog.info("Shadow evaluated cycles: count=${newlyEvaluated.size}")
        }
        return newlyEvaluated
    }

    fun recordCycle(
        prediction: PredictionResult,
        decisionPlan: DecisionPlan
    ): ShadowPredictionCycle {
        val cycle = buildCycle(prediction, decisionPlan)
        appendJsonLine(CYCLE_FILE_NAME, cycle.toJson())
        val pending = (getPendingCycles() + cycle).takeLast(MAX_PENDING_CYCLES)
        savePendingCycles(pending)
        ForeSightLog.info(
            "Shadow prediction cycle recorded: predictions=${cycle.predictions.size}, " +
                "recent=${cycle.recentApps.size}, dryRunFreeze=${cycle.dryRunFreezeCount}"
        )
        return cycle
    }

    fun getMetrics(): ShadowMetrics {
        val evaluations = readEvaluations()
        val cycles = readCycles()
        val evaluatedCount = evaluations.size
        val totalRecentApps = cycles.sumOf { it.recentApps.size }.coerceAtLeast(1)
        val totalUnknownRecentApps = cycles.sumOf { it.unknownRecentAppCount }
        val averageLatency = if (cycles.isEmpty()) {
            0f
        } else {
            cycles.map { it.inferenceLatencyMs }.average().toFloat()
        }

        return ShadowMetrics(
            evaluatedCycles = evaluatedCount,
            pendingCycles = getPendingCycles().size,
            top1Accuracy = evaluations.accuracyOf { it.top1Hit },
            top3Accuracy = evaluations.accuracyOf { it.top3Hit },
            top5Accuracy = evaluations.accuracyOf { it.top5Hit },
            averageInferenceLatencyMs = averageLatency,
            unknownAppRate = totalUnknownRecentApps.toFloat() / totalRecentApps.toFloat(),
            dryRunFreezeCount = cycles.sumOf { it.dryRunFreezeCount },
            wouldHaveFrozenActualNextAppCount = evaluations.count { it.unsafeWouldFreezeActualNextApp },
            recentUnsafeEvaluations = evaluations
                .filter { it.unsafeWouldFreezeActualNextApp }
                .sortedByDescending { it.actualNextApp.timestampMillis }
                .take(10)
        )
    }

    fun getRecentEvaluations(limit: Int = 20): List<ShadowEvaluation> {
        return readEvaluations()
            .sortedByDescending { it.actualNextApp.timestampMillis }
            .take(limit)
    }

    fun exportLogsToCache(): List<Uri> {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        return listOf(CYCLE_FILE_NAME, EVALUATION_FILE_NAME)
            .mapNotNull { fileName ->
                val source = File(context.filesDir, fileName)
                if (!source.exists()) return@mapNotNull null
                val target = File(exportDir, fileName)
                source.copyTo(target, overwrite = true)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    target
                )
            }
    }

    private fun buildCycle(
        prediction: PredictionResult,
        decisionPlan: DecisionPlan
    ): ShadowPredictionCycle {
        val mappedPackages = decisionPlan.decisions
            .filter { it.modelAppId != null }
            .map { it.packageName }
            .toSet()
        val predictions = prediction.predictions.map { predictedApp ->
            val decisionMatch = decisionPlan.decisions.firstOrNull { decision ->
                decision.modelAppId == predictedApp.appId || decision.modelLabel == predictedApp.label
            }
            ShadowPredictionEntry(
                label = predictedApp.label,
                appId = predictedApp.appId,
                confidence = predictedApp.confidence,
                packageName = decisionMatch?.packageName
            )
        }

        return ShadowPredictionCycle(
            timestampMillis = decisionPlan.timestampMillis,
            recentApps = prediction.recentApps,
            predictions = predictions,
            inferenceLatencyMs = prediction.latencyMs,
            unknownRecentAppCount = prediction.recentApps.count { it.packageName !in mappedPackages },
            dryRunFreezeCount = decisionPlan.decisions.count { it.action == DecisionAction.WOULD_FREEZE },
            wouldFreezePackages = decisionPlan.decisions
                .filter { it.action == DecisionAction.WOULD_FREEZE }
                .map { it.packageName }
                .toSet()
        )
    }

    private fun ShadowPredictionCycle.evaluate(actualNextApp: AppLaunch): ShadowEvaluation {
        fun hitAt(k: Int): Boolean {
            return predictions.take(k).any { prediction ->
                prediction.packageName == actualNextApp.packageName
            }
        }

        return ShadowEvaluation(
            predictionTimestampMillis = timestampMillis,
            actualNextApp = actualNextApp,
            top1Hit = hitAt(1),
            top3Hit = hitAt(3),
            top5Hit = hitAt(5),
            predictionToOpenLatencyMs = actualNextApp.timestampMillis - timestampMillis,
            inferenceLatencyMs = inferenceLatencyMs,
            unknownRecentAppCount = unknownRecentAppCount,
            recentAppCount = recentApps.size,
            dryRunFreezeCount = dryRunFreezeCount,
            unsafeWouldFreezeActualNextApp = actualNextApp.packageName in wouldFreezePackages
        )
    }

    private fun getPendingCycles(): List<ShadowPredictionCycle> {
        val raw = preferences.getString(KEY_PENDING_CYCLES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(array.getJSONObject(index).toShadowPredictionCycle())
                }
            }
        }.getOrElse { error ->
            ForeSightLog.warn("Could not parse shadow pending cycles", error)
            emptyList()
        }
    }

    private fun savePendingCycles(cycles: List<ShadowPredictionCycle>) {
        preferences.edit()
            .putString(KEY_PENDING_CYCLES, JSONArray(cycles.map { it.toJson() }).toString())
            .apply()
    }

    private fun readCycles(): List<ShadowPredictionCycle> {
        return readJsonLines(CYCLE_FILE_NAME).mapNotNull { json ->
            runCatching { json.toShadowPredictionCycle() }.getOrNull()
        }
    }

    private fun readEvaluations(): List<ShadowEvaluation> {
        return readJsonLines(EVALUATION_FILE_NAME).mapNotNull { json ->
            runCatching { json.toShadowEvaluation() }.getOrNull()
        }
    }

    private fun readJsonLines(fileName: String): List<JSONObject> {
        val file = File(context.filesDir, fileName)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
    }

    private fun appendJsonLine(fileName: String, payload: JSONObject) {
        context.openFileOutput(fileName, Context.MODE_APPEND).use { stream ->
            stream.write((payload.toString() + "\n").toByteArray())
        }
    }

    private fun List<ShadowEvaluation>.accuracyOf(selector: (ShadowEvaluation) -> Boolean): Float {
        if (isEmpty()) return 0f
        return count(selector).toFloat() / size.toFloat()
    }

    private fun ShadowPredictionCycle.toJson(): JSONObject {
        return JSONObject()
            .put("timestamp", timestampMillis)
            .put("recent_apps", JSONArray(recentApps.map { it.toJson() }))
            .put("top_predictions", JSONArray(predictions.map { it.toJson() }))
            .put("inference_latency_ms", inferenceLatencyMs)
            .put("unknown_recent_app_count", unknownRecentAppCount)
            .put("dry_run_freeze_count", dryRunFreezeCount)
            .put("would_freeze_packages", JSONArray(wouldFreezePackages.toList()))
    }

    private fun JSONObject.toShadowPredictionCycle(): ShadowPredictionCycle {
        val recent = getJSONArray("recent_apps")
        val predictionsArray = getJSONArray("top_predictions")
        val wouldFreezeArray = getJSONArray("would_freeze_packages")
        return ShadowPredictionCycle(
            timestampMillis = getLong("timestamp"),
            recentApps = buildList {
                for (index in 0 until recent.length()) {
                    add(recent.getJSONObject(index).toAppLaunch())
                }
            },
            predictions = buildList {
                for (index in 0 until predictionsArray.length()) {
                    add(predictionsArray.getJSONObject(index).toShadowPredictionEntry())
                }
            },
            inferenceLatencyMs = getLong("inference_latency_ms"),
            unknownRecentAppCount = getInt("unknown_recent_app_count"),
            dryRunFreezeCount = getInt("dry_run_freeze_count"),
            wouldFreezePackages = buildSet {
                for (index in 0 until wouldFreezeArray.length()) {
                    add(wouldFreezeArray.getString(index))
                }
            }
        )
    }

    private fun ShadowEvaluation.toJson(): JSONObject {
        return JSONObject()
            .put("prediction_timestamp", predictionTimestampMillis)
            .put("actual_next_app", actualNextApp.toJson())
            .put("top1_hit", top1Hit)
            .put("top3_hit", top3Hit)
            .put("top5_hit", top5Hit)
            .put("prediction_to_open_latency_ms", predictionToOpenLatencyMs)
            .put("inference_latency_ms", inferenceLatencyMs)
            .put("unknown_recent_app_count", unknownRecentAppCount)
            .put("recent_app_count", recentAppCount)
            .put("dry_run_freeze_count", dryRunFreezeCount)
            .put("unsafe_would_freeze_actual_next_app", unsafeWouldFreezeActualNextApp)
    }

    private fun JSONObject.toShadowEvaluation(): ShadowEvaluation {
        return ShadowEvaluation(
            predictionTimestampMillis = getLong("prediction_timestamp"),
            actualNextApp = getJSONObject("actual_next_app").toAppLaunch(),
            top1Hit = getBoolean("top1_hit"),
            top3Hit = getBoolean("top3_hit"),
            top5Hit = getBoolean("top5_hit"),
            predictionToOpenLatencyMs = getLong("prediction_to_open_latency_ms"),
            inferenceLatencyMs = getLong("inference_latency_ms"),
            unknownRecentAppCount = getInt("unknown_recent_app_count"),
            recentAppCount = getInt("recent_app_count"),
            dryRunFreezeCount = getInt("dry_run_freeze_count"),
            unsafeWouldFreezeActualNextApp = getBoolean("unsafe_would_freeze_actual_next_app")
        )
    }

    private fun AppLaunch.toJson(): JSONObject {
        return JSONObject()
            .put("package_name", packageName)
            .put("app_label", appLabel)
            .put("timestamp", timestampMillis)
    }

    private fun JSONObject.toAppLaunch(): AppLaunch {
        return AppLaunch(
            packageName = getString("package_name"),
            appLabel = getString("app_label"),
            timestampMillis = getLong("timestamp")
        )
    }

    private fun ShadowPredictionEntry.toJson(): JSONObject {
        return JSONObject()
            .put("label", label)
            .put("app_id", appId)
            .put("confidence", confidence.toDouble())
            .put("package_name", packageName)
    }

    private fun JSONObject.toShadowPredictionEntry(): ShadowPredictionEntry {
        return ShadowPredictionEntry(
            label = getString("label"),
            appId = getInt("app_id"),
            confidence = getDouble("confidence").toFloat(),
            packageName = if (isNull("package_name")) null else getString("package_name")
        )
    }

    companion object {
        const val CYCLE_FILE_NAME = "shadow_prediction_cycles.jsonl"
        const val EVALUATION_FILE_NAME = "shadow_evaluation_events.jsonl"
        private const val PREFS_NAME = "shadow_evaluation_state"
        private const val KEY_PENDING_CYCLES = "pending_cycles"
        private const val MAX_PENDING_CYCLES = 50
    }
}
