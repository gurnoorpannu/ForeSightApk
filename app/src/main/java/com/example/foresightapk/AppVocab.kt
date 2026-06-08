package com.example.foresightapk

import android.content.Context
import org.json.JSONObject
import java.util.Locale

object AppVocab {
    const val VOCAB_FILE = "app_vocab.json"

    fun load(context: Context): Map<String, Int> {
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

    fun normalizeLabel(label: String): String {
        return label
            .lowercase(Locale.US)
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "")
    }
}
