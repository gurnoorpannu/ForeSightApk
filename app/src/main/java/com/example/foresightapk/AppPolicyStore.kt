package com.example.foresightapk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AppPolicyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getProtectedAllowlist(): Set<String> {
        return preferences.getStringSet(KEY_ALLOWLIST, emptySet()).orEmpty()
    }

    fun addProtectedPackage(packageName: String) {
        val updated = getProtectedAllowlist() + packageName
        preferences.edit().putStringSet(KEY_ALLOWLIST, updated).apply()
        ForeSightLog.info("Added protected allowlist package=$packageName")
    }

    fun removeProtectedPackage(packageName: String) {
        val updated = getProtectedAllowlist() - packageName
        preferences.edit().putStringSet(KEY_ALLOWLIST, updated).apply()
        ForeSightLog.info("Removed protected allowlist package=$packageName")
    }

    fun getDryRunFrozenPackages(): Set<String> {
        return preferences.getStringSet(KEY_DRY_RUN_FROZEN, emptySet()).orEmpty()
    }

    fun getDecisionPolicy(): DecisionPolicy {
        return DecisionPolicy(
            freezeThreshold = preferences.getFloat(
                KEY_FREEZE_THRESHOLD,
                DecisionPolicy.DEFAULT_FREEZE_THRESHOLD
            ),
            protectThreshold = preferences.getFloat(
                KEY_PROTECT_THRESHOLD,
                DecisionPolicy.DEFAULT_PROTECT_THRESHOLD
            ),
            recentAppProtectionWindow = preferences.getInt(
                KEY_RECENT_APP_WINDOW,
                DecisionPolicy.DEFAULT_RECENT_APP_PROTECTION_WINDOW
            ),
            maxAppsToFreezePerCycle = preferences.getInt(
                KEY_MAX_FREEZE_PER_CYCLE,
                DecisionPolicy.DEFAULT_MAX_APPS_TO_FREEZE_PER_CYCLE
            ),
            dryRunEnabled = preferences.getBoolean(
                KEY_DRY_RUN_ENABLED,
                DecisionPolicy.DEFAULT_DRY_RUN_ENABLED
            )
        ).sanitized()
    }

    fun saveDecisionPolicy(policy: DecisionPolicy) {
        val cleanPolicy = policy.sanitized()
        preferences.edit()
            .putFloat(KEY_FREEZE_THRESHOLD, cleanPolicy.freezeThreshold)
            .putFloat(KEY_PROTECT_THRESHOLD, cleanPolicy.protectThreshold)
            .putInt(KEY_RECENT_APP_WINDOW, cleanPolicy.recentAppProtectionWindow)
            .putInt(KEY_MAX_FREEZE_PER_CYCLE, cleanPolicy.maxAppsToFreezePerCycle)
            .putBoolean(KEY_DRY_RUN_ENABLED, cleanPolicy.dryRunEnabled)
            .apply()
        ForeSightLog.info("Saved decision policy: $cleanPolicy")
    }

    fun resetDecisionPolicyDefaults() {
        saveDecisionPolicy(DecisionPolicy())
        ForeSightLog.info("Reset decision policy defaults")
    }

    fun applyDecisionPlan(plan: DecisionPlan) {
        val current = getDryRunFrozenPackages().toMutableSet()
        plan.decisions.forEach { decision ->
            when (decision.action) {
                DecisionAction.WOULD_FREEZE -> current.add(decision.packageName)
                DecisionAction.WOULD_UNFREEZE -> current.remove(decision.packageName)
                DecisionAction.PROTECT,
                DecisionAction.IGNORE -> Unit
            }
        }
        preferences.edit().putStringSet(KEY_DRY_RUN_FROZEN, current).apply()
        ForeSightLog.info("Updated dry-run frozen package state: count=${current.size}")
    }

    fun recordActionLogs(plan: DecisionPlan): List<DryRunActionLog> {
        val logs = plan.decisions.map { decision ->
            DryRunActionLog(
                timestampMillis = plan.timestampMillis,
                packageName = decision.packageName,
                appLabel = decision.appLabel,
                action = decision.action,
                reason = decision.reason,
                predictionConfidence = decision.confidence
            )
        }
        val updated = (logs + getRecentActionLogs()).take(MAX_ACTION_HISTORY)
        preferences.edit()
            .putString(KEY_ACTION_HISTORY, JSONArray(updated.map { it.toJson() }).toString())
            .apply()
        ForeSightLog.info("Recorded dry-run action logs: new=${logs.size}, retained=${updated.size}")
        return logs
    }

    fun getRecentActionLogs(): List<DryRunActionLog> {
        val raw = preferences.getString(KEY_ACTION_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(array.getJSONObject(index).toDryRunActionLog())
                }
            }
        }.getOrElse { error ->
            ForeSightLog.warn("Could not parse dry-run action history", error)
            emptyList()
        }
    }

    fun clearActionLogs() {
        preferences.edit().remove(KEY_ACTION_HISTORY).apply()
        ForeSightLog.info("Cleared dry-run action history")
    }

    private fun DryRunActionLog.toJson(): JSONObject {
        return JSONObject()
            .put("timestamp", timestampMillis)
            .put("package_name", packageName)
            .put("app_label", appLabel)
            .put("action", action.displayName)
            .put("reason", reason.displayName)
            .put("reason_key", reason.name)
            .put("prediction_confidence", predictionConfidence?.toDouble())
            .put("mode", mode.displayName)
    }

    private fun JSONObject.toDryRunActionLog(): DryRunActionLog {
        val action = DecisionAction.valueOf(getString("action"))
        val reason = optString("reason_key")
            .takeIf { it.isNotBlank() }
            ?.let { key -> runCatching { DecisionReason.valueOf(key) }.getOrNull() }
            ?: DecisionReason.LowProbabilitySafeCandidate
        return DryRunActionLog(
            timestampMillis = getLong("timestamp"),
            packageName = getString("package_name"),
            appLabel = getString("app_label"),
            action = action,
            reason = reason,
            predictionConfidence = if (isNull("prediction_confidence")) {
                null
            } else {
                getDouble("prediction_confidence").toFloat()
            },
            mode = ActionMode.DRY_RUN
        )
    }

    companion object {
        private const val PREFS_NAME = "app_policy_state"
        private const val KEY_ALLOWLIST = "protected_allowlist"
        private const val KEY_DRY_RUN_FROZEN = "dry_run_frozen_packages"
        private const val KEY_FREEZE_THRESHOLD = "freeze_threshold"
        private const val KEY_PROTECT_THRESHOLD = "protect_threshold"
        private const val KEY_RECENT_APP_WINDOW = "recent_app_protection_window"
        private const val KEY_MAX_FREEZE_PER_CYCLE = "max_apps_to_freeze_per_cycle"
        private const val KEY_DRY_RUN_ENABLED = "dry_run_enabled"
        private const val KEY_ACTION_HISTORY = "dry_run_action_history"
        private const val MAX_ACTION_HISTORY = 100
    }
}
