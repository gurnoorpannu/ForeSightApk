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
        const val ERROR_FILE_NAME = "foresight_errors.jsonl"
    }
}
