package com.example.foresightapk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter

class PredictionLogger(private val context: Context) {
    fun log(result: PredictionResult) {
        val payload = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("input_app_sequence", JSONArray(result.inputAppIds))
            .put("input_app_labels", JSONArray(result.inputAppLabels))
            .put(
                "top_k_predictions",
                JSONArray(
                    result.predictions.map { prediction ->
                        JSONObject()
                            .put("app_id", prediction.appId)
                            .put("label", prediction.label)
                            .put("confidence", prediction.confidence.toDouble())
                    }
                )
            )
            .put("inference_latency_ms", result.latencyMs)
            .put("diagnostics", JSONArray(result.diagnostics))

        appendJsonLine(PREDICTION_FILE_NAME, payload)
        ForeSightLog.info(
            "Logged prediction: latency=${result.latencyMs}ms, " +
                "recentApps=${result.recentApps.size}, topK=${result.predictions.size}"
        )
    }

    fun logDecisionPlan(plan: DecisionPlan) {
        val payload = JSONObject()
            .put("timestamp", plan.timestampMillis)
            .put("predictions_seen", plan.predictionsSeen)
            .put("installed_apps_seen", plan.installedAppsSeen)
            .put("recent_apps_seen", plan.recentAppsSeen)
            .put(
                "decisions",
                JSONArray(
                    plan.decisions.map { decision ->
                        JSONObject()
                            .put("action", decision.action.displayName)
                            .put("package_name", decision.packageName)
                            .put("app_label", decision.appLabel)
                            .put("model_app_id", decision.modelAppId)
                            .put("model_label", decision.modelLabel)
                            .put("confidence", decision.confidence?.toDouble())
                            .put("reason", decision.reason.displayName)
                            .put("reason_detail", decision.reasonDetail)
                    }
                )
            )
            .put("diagnostics", JSONArray(plan.diagnostics))

        appendJsonLine(DECISION_FILE_NAME, payload)
        ForeSightLog.info(
            "Logged decision dry run: decisions=${plan.decisions.size}, " +
                "installedApps=${plan.installedAppsSeen}"
        )
    }

    fun logDryRunActions(logs: List<DryRunActionLog>) {
        logs.forEach { log ->
            val payload = JSONObject()
                .put("timestamp", log.timestampMillis)
                .put("package_name", log.packageName)
                .put("app_label", log.appLabel)
                .put("action", log.action.displayName)
                .put("reason", log.reason.displayName)
                .put("prediction_confidence", log.predictionConfidence?.toDouble())
                .put("mode", log.mode.displayName)

            appendJsonLine(ACTION_FILE_NAME, payload)
        }
        ForeSightLog.info("Logged dry-run actions: count=${logs.size}")
    }

    fun clearDryRunLogs() {
        context.deleteFile(ACTION_FILE_NAME)
        context.deleteFile(DECISION_FILE_NAME)
        ForeSightLog.info("Cleared dry-run log files")
    }

    fun logError(stage: String, throwable: Throwable, diagnostics: List<String> = emptyList()) {
        val payload = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("stage", stage)
            .put("message", throwable.message ?: throwable::class.java.simpleName)
            .put("type", throwable::class.java.name)
            .put("stacktrace", throwable.stackTraceString())
            .put("diagnostics", JSONArray(diagnostics))

        appendJsonLine(ERROR_FILE_NAME, payload)
        ForeSightLog.error("Logged error at stage=$stage", throwable)
    }

    private fun appendJsonLine(fileName: String, payload: JSONObject) {
        context.openFileOutput(fileName, Context.MODE_APPEND).use { stream ->
            stream.write((payload.toString() + "\n").toByteArray())
        }
    }

    private fun Throwable.stackTraceString(): String {
        val writer = StringWriter()
        printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    companion object {
        const val PREDICTION_FILE_NAME = "prediction_events.jsonl"
        const val DECISION_FILE_NAME = "decision_events.jsonl"
        const val ACTION_FILE_NAME = "dry_run_action_events.jsonl"
        const val ERROR_FILE_NAME = "foresight_errors.jsonl"
    }
}
