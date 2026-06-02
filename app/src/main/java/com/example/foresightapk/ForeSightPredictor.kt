package com.example.foresightapk

import android.content.Context
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

class ForeSightPredictor(private val context: Context) : AutoCloseable {
    private val appVocab: Map<String, Int> by lazy { loadAppVocab() }
    private val idToLabel: Map<Int, String> by lazy { appVocab.entries.associate { it.value to it.key } }
    private val interpreterHolder = lazy {
        ForeSightLog.info("Loading TFLite model asset: $MODEL_FILE")
        Interpreter(
            loadModelBuffer(MODEL_FILE),
            Interpreter.Options().apply {
                setNumThreads(1)
                setUseXNNPACK(false)
            }
        ).also {
            ForeSightLog.info(
                "TFLite interpreter initialized on CPU with ${it.inputTensorCount} inputs; " +
                    "XNNPACK disabled"
            )
        }
    }
    private val interpreter: Interpreter by interpreterHolder

    fun predict(recentLaunches: List<AppLaunch>): PredictionResult {
        ForeSightLog.info("Starting prediction for recentLaunches=${recentLaunches.size}")
        val launches = recentLaunches.takeLast(SEQUENCE_LENGTH)
        val inputAppIds = buildAppInput(launches)
        val contextInput = buildContextInput(launches)
        val inputLabels = buildInputLabels(launches)
        val routing = resolveInputRouting()
        val outputClassCount = interpreter.getOutputTensor(0).shape().last()
        val outputBuffer = directBuffer(outputClassCount * FLOAT_BYTES)
        val diagnostics = buildList {
            add("Recent launches read: ${recentLaunches.size}")
            add("App input shape: [1, $SEQUENCE_LENGTH], dtype=int64")
            add("Context input shape: [1, $CONTEXT_FEATURES, $SEQUENCE_LENGTH], dtype=float32")
            add("Input routing: appIndex=${routing.appSequenceIndex}, contextIndex=${routing.contextSequenceIndex}")
            add("Output classes: $outputClassCount")
            add("Unknown/PAD ID count: ${inputAppIds.count { it == 0L }}")
            add("Mapped input IDs: ${inputAppIds.joinToString(prefix = "[", postfix = "]")}")
            add("TFLite delegate: none; CPU single-thread")
            add("Tensor transport: direct ByteBuffer")
        }

        ForeSightLog.debug(diagnostics.joinToString(separator = " | "))

        val inputs = arrayOfNulls<Any>(interpreter.inputTensorCount)
        inputs[routing.appSequenceIndex] = appInputBuffer(inputAppIds)
        inputs[routing.contextSequenceIndex] = contextInputBuffer(contextInput)

        if (inputs.any { it == null }) {
            error("Unsupported model input count: ${interpreter.inputTensorCount}")
        }

        ForeSightLog.debug("Calling TFLite interpreter.runForMultipleInputsOutputs")
        val latencyMs = measureTimeMillis {
            interpreter.runForMultipleInputsOutputs(inputs, mapOf(0 to outputBuffer))
        }
        ForeSightLog.info("TFLite inference completed in ${latencyMs}ms")

        val probabilities = normalizeScores(outputBuffer.readFloatArray(outputClassCount))
        val topPredictions = probabilities.indices
            .sortedByDescending { probabilities[it] }
            .take(5)
            .map { appId ->
                PredictedApp(
                    label = idToLabel[appId] ?: "App ID $appId",
                    appId = appId,
                    confidence = probabilities[appId]
                )
            }
        ForeSightLog.info(
            "Top predictions: " + topPredictions.joinToString { prediction ->
                "${prediction.label}=${"%.3f".format(prediction.confidence)}"
            }
        )

        return PredictionResult(
            recentApps = recentLaunches,
            inputAppIds = inputAppIds,
            inputAppLabels = inputLabels,
            predictions = topPredictions,
            latencyMs = latencyMs,
            diagnostics = diagnostics + "Inference latency: ${latencyMs}ms"
        )
    }

    private fun buildAppInput(launches: List<AppLaunch>): List<Long> {
        val padded = MutableList(SEQUENCE_LENGTH) { 0L }
        val mappedIds = launches.map { appIdForLaunch(it).toLong() }
        val offset = SEQUENCE_LENGTH - mappedIds.size
        mappedIds.forEachIndexed { index, appId ->
            padded[offset + index] = appId
        }
        return padded
    }

    private fun buildInputLabels(launches: List<AppLaunch>): List<String> {
        val labels = MutableList(SEQUENCE_LENGTH) { "PAD/unknown -> 0" }
        val offset = SEQUENCE_LENGTH - launches.size
        launches.forEachIndexed { index, launch ->
            labels[offset + index] = "${launch.appLabel} -> ${appIdForLaunch(launch)}"
        }
        return labels
    }

    private fun buildContextInput(launches: List<AppLaunch>): FloatArray {
        val input = FloatArray(CONTEXT_FEATURES * SEQUENCE_LENGTH)
        val offset = SEQUENCE_LENGTH - launches.size

        launches.forEachIndexed { index, launch ->
            val inputIndex = offset + index
            val calendar = Calendar.getInstance().apply {
                timeInMillis = launch.timestampMillis
            }
            val hour = calendar.get(Calendar.HOUR_OF_DAY) / 23.0f
            val day = (calendar.get(Calendar.DAY_OF_WEEK) - 1) / 6.0f
            val previous = launches.getOrNull(index - 1)
            val gap = if (previous == null) {
                0.0f
            } else {
                val seconds = (launch.timestampMillis - previous.timestampMillis) / 1000.0f
                min(1.0f, max(0.0f, seconds / 3600.0f))
            }

            input[featureOffset(0, inputIndex)] = hour
            input[featureOffset(1, inputIndex)] = day
            input[featureOffset(2, inputIndex)] = gap
        }

        return input
    }

    private fun featureOffset(featureIndex: Int, timeIndex: Int): Int {
        return featureIndex * SEQUENCE_LENGTH + timeIndex
    }

    private fun appInputBuffer(inputAppIds: List<Long>): ByteBuffer {
        return directBuffer(SEQUENCE_LENGTH * LONG_BYTES).apply {
            inputAppIds.forEach { appId -> putLong(appId) }
            rewind()
        }
    }

    private fun contextInputBuffer(contextInput: FloatArray): ByteBuffer {
        return directBuffer(CONTEXT_FEATURES * SEQUENCE_LENGTH * FLOAT_BYTES).apply {
            contextInput.forEach { value -> putFloat(value) }
            rewind()
        }
    }

    private fun directBuffer(byteCount: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
    }

    private fun ByteBuffer.readFloatArray(size: Int): FloatArray {
        rewind()
        return FloatArray(size) { getFloat() }
    }

    private fun appIdForLaunch(launch: AppLaunch): Int {
        val candidates = listOfNotNull(
            packageAliases[launch.packageName],
            launch.appLabel,
            launch.packageName
        )

        val mapped = candidates.firstNotNullOfOrNull { appVocab[it] }
        if (mapped == null) {
            ForeSightLog.warn(
                "No vocab match for package=${launch.packageName}, " +
                    "label=${launch.appLabel}; falling back to ID 0"
            )
        }
        return mapped ?: 0
    }

    private fun resolveInputRouting(): InputRouting {
        var appIndex: Int? = null
        var contextIndex: Int? = null

        repeat(interpreter.inputTensorCount) { index ->
            val tensor = interpreter.getInputTensor(index)
            val shape = tensor.shape()
            ForeSightLog.debug(
                "Input tensor[$index]: name=${tensor.name()}, " +
                    "shape=${shape.contentToString()}, dtype=${tensor.dataType()}"
            )
            when {
                tensor.dataType() == DataType.INT64 && shape.matches(1, SEQUENCE_LENGTH) -> {
                    appIndex = index
                }
                tensor.dataType() == DataType.FLOAT32 && shape.matches(1, CONTEXT_FEATURES, SEQUENCE_LENGTH) -> {
                    contextIndex = index
                }
                shape.size == 2 && shape.last() == SEQUENCE_LENGTH -> {
                    appIndex = index
                }
                shape.size == 3 && shape[1] == CONTEXT_FEATURES && shape[2] == SEQUENCE_LENGTH -> {
                    contextIndex = index
                }
            }
        }

        if (appIndex == null || contextIndex == null) {
            val tensorSummary = (0 until interpreter.inputTensorCount).joinToString { index ->
                val tensor = interpreter.getInputTensor(index)
                "[$index name=${tensor.name()} shape=${tensor.shape().contentToString()} dtype=${tensor.dataType()}]"
            }
            error("Could not route model inputs. Expected [1,10] and [1,3,10]. Actual: $tensorSummary")
        }

        return InputRouting(
            appSequenceIndex = appIndex,
            contextSequenceIndex = contextIndex
        )
    }

    private fun IntArray.matches(vararg expected: Int): Boolean {
        return size == expected.size && indices.all { this[it] == expected[it] }
    }

    private fun normalizeScores(scores: FloatArray): FloatArray {
        val sum = scores.sum()
        val alreadyProbabilities = scores.all { it >= 0.0f } && abs(sum - 1.0f) < 0.05f
        if (alreadyProbabilities) return scores

        val maxScore = scores.maxOrNull() ?: 0.0f
        val expScores = scores.map { exp((it - maxScore).toDouble()).toFloat() }
        val expSum = expScores.sum().takeIf { it > 0.0f } ?: 1.0f
        return FloatArray(scores.size) { index -> expScores[index] / expSum }
    }

    private fun loadAppVocab(): Map<String, Int> {
        val json = context.assets.open(VOCAB_FILE).bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val vocab = mutableMapOf<String, Int>()

        root.keys().forEach { key ->
            when (val value = root.get(key)) {
                is Number -> vocab[key] = value.toInt()
                is JSONObject -> value.keys().forEach { nestedKey ->
                    val nestedValue = value.opt(nestedKey)
                    if (nestedValue is Number) vocab[nestedKey] = nestedValue.toInt()
                }
            }
        }

        check(vocab.isNotEmpty()) { "$VOCAB_FILE did not contain an app-name to id mapping" }
        ForeSightLog.info("Loaded app vocab entries=${vocab.size} from $VOCAB_FILE")
        return vocab
    }

    private fun loadModelBuffer(fileName: String): ByteBuffer {
        val bytes = context.assets.open(fileName).use { it.readBytes() }
        ForeSightLog.info("Loaded model asset bytes=${bytes.size}")
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }

    override fun close() {
        if (interpreterHolder.isInitialized()) {
            interpreter.close()
        }
    }

    private data class InputRouting(
        val appSequenceIndex: Int,
        val contextSequenceIndex: Int
    )

    companion object {
        private const val MODEL_FILE = "foresight_fp32.tflite"
        private const val VOCAB_FILE = "app_vocab.json"
        private const val SEQUENCE_LENGTH = 10
        private const val CONTEXT_FEATURES = 3
        private const val FLOAT_BYTES = 4
        private const val LONG_BYTES = 8

        private val packageAliases = mapOf(
            "com.android.chrome" to "Google Chrome",
            "com.google.android.googlequicksearchbox" to "Google",
            "com.google.android.youtube" to "YouTube",
            "com.google.android.gm" to "Gmail",
            "com.google.android.apps.maps" to "Maps",
            "com.google.android.apps.photos" to "Google Photos",
            "com.google.android.apps.docs" to "Google Drive",
            "com.android.vending" to "Google Play Store",
            "com.google.android.apps.messaging" to "Messages",
            "com.android.mms" to "Messaging",
            "com.android.contacts" to "Contacts",
            "com.android.dialer" to "Phone",
            "com.android.settings" to "Settings",
            "com.whatsapp" to "WhatsApp Messenger",
            "com.instagram.android" to "Instagram",
            "com.facebook.katana" to "Facebook",
            "com.facebook.orca" to "Facebook Messenger",
            "org.telegram.messenger" to "Telegram",
            "com.twitter.android" to "Twitter",
            "com.snapchat.android" to "Snapchat",
            "com.discord" to "Discord",
            "com.spotify.music" to "Spotify Music",
            "com.netflix.mediaclient" to "Netflix",
            "com.reddit.frontpage" to "Reddit",
            "com.pinterest" to "Pinterest",
            "com.ebay.mobile" to "eBay",
            "com.amazon.mShop.android.shopping" to "Amazon Shopping",
            "com.paypal.android.p2pmobile" to "PayPal Mobile Cash",
            "com.brave.browser" to "Brave Browser"
        )
    }
}
