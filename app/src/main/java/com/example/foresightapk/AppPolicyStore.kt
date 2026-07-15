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

    fun getRootFrozenPackages(): Set<String> {
        return getRootFrozenRecords().map { it.packageName }.toSet()
    }

    fun getRootFrozenRecords(): List<RootFrozenRecord> {
        val raw = preferences.getString(KEY_ROOT_FROZEN_RECORDS, null)
        if (raw != null) {
            return runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(
                            RootFrozenRecord(
                                packageName = item.getString("package_name"),
                                frozenAtMillis = item.getLong("frozen_at")
                            )
                        )
                    }
                }
            }.getOrElse { error ->
                ForeSightLog.warn("Could not parse root frozen records", error)
                emptyList()
            }
        }

        return preferences.getStringSet(KEY_ROOT_FROZEN, emptySet()).orEmpty()
            .map { packageName ->
                RootFrozenRecord(packageName = packageName, frozenAtMillis = 0L)
            }
    }

    fun markRootFrozen(packageName: String, timestampMillis: Long = System.currentTimeMillis()) {
        val updated = (getRootFrozenRecords().filterNot { it.packageName == packageName } +
            RootFrozenRecord(packageName, timestampMillis))
            .sortedBy { it.packageName }
        saveRootFrozenRecords(updated)
        ForeSightLog.info("Marked root frozen package=$packageName")
    }

    fun markRootUnfrozen(packageName: String) {
        saveRootFrozenRecords(getRootFrozenRecords().filterNot { it.packageName == packageName })
        ForeSightLog.info("Marked root unfrozen package=$packageName")
    }

    fun clearRootFrozenPackages() {
        preferences.edit()
            .remove(KEY_ROOT_FROZEN)
            .remove(KEY_ROOT_FROZEN_RECORDS)
            .apply()
        ForeSightLog.info("Cleared ForeSight root frozen package state")
    }

    fun getLastRootActionTimestamp(): Long {
        return preferences.getLong(KEY_LAST_ROOT_ACTION_TIMESTAMP, 0L)
    }

    fun markRootActionTimestamp(timestampMillis: Long = System.currentTimeMillis()) {
        preferences.edit().putLong(KEY_LAST_ROOT_ACTION_TIMESTAMP, timestampMillis).apply()
    }

    fun recordBadFreezeDecision(packageName: String, openedAtMillis: Long, frozenAtMillis: Long) {
        val payload = JSONObject()
            .put("package_name", packageName)
            .put("opened_at", openedAtMillis)
            .put("frozen_at", frozenAtMillis)
            .put("recorded_at", System.currentTimeMillis())
        val updated = (listOf(payload) + getBadFreezeDecisions()).take(MAX_BAD_FREEZE_HISTORY)
        preferences.edit()
            .putString(KEY_BAD_FREEZE_HISTORY, JSONArray(updated).toString())
            .apply()
        ForeSightLog.warn("Recorded bad freeze decision for package=$packageName")
    }

    fun getBadFreezeDecisionCount(): Int {
        return getBadFreezeDecisions().size
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
            ),
            activeRootModeEnabled = preferences.getBoolean(
                KEY_ACTIVE_ROOT_MODE_ENABLED,
                DecisionPolicy.DEFAULT_ACTIVE_ROOT_MODE_ENABLED
            ),
            predictionIntervalSeconds = preferences.getInt(
                KEY_PREDICTION_INTERVAL_SECONDS,
                DecisionPolicy.DEFAULT_PREDICTION_INTERVAL_SECONDS
            ),
            pauseWhenBatteryLow = preferences.getBoolean(
                KEY_PAUSE_WHEN_BATTERY_LOW,
                DecisionPolicy.DEFAULT_PAUSE_WHEN_BATTERY_LOW
            ),
            rootActionCooldownSeconds = preferences.getInt(
                KEY_ROOT_ACTION_COOLDOWN_SECONDS,
                DecisionPolicy.DEFAULT_ROOT_ACTION_COOLDOWN_SECONDS
            ),
            rollbackWindowSeconds = preferences.getInt(
                KEY_ROLLBACK_WINDOW_SECONDS,
                DecisionPolicy.DEFAULT_ROLLBACK_WINDOW_SECONDS
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
            .putBoolean(KEY_ACTIVE_ROOT_MODE_ENABLED, cleanPolicy.activeRootModeEnabled)
            .putInt(KEY_PREDICTION_INTERVAL_SECONDS, cleanPolicy.predictionIntervalSeconds)
            .putBoolean(KEY_PAUSE_WHEN_BATTERY_LOW, cleanPolicy.pauseWhenBatteryLow)
            .putInt(KEY_ROOT_ACTION_COOLDOWN_SECONDS, cleanPolicy.rootActionCooldownSeconds)
            .putInt(KEY_ROLLBACK_WINDOW_SECONDS, cleanPolicy.rollbackWindowSeconds)
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

    fun recordActionLogs(
        plan: DecisionPlan,
        mode: ActionMode = ActionMode.DRY_RUN
    ): List<DryRunActionLog> {
        val logs = plan.decisions.map { decision ->
            DryRunActionLog(
                timestampMillis = plan.timestampMillis,
                packageName = decision.packageName,
                appLabel = decision.appLabel,
                action = decision.action,
                reason = decision.reason,
                predictionConfidence = decision.confidence,
                mode = mode
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
            mode = runCatching { ActionMode.valueOf(getString("mode")) }.getOrDefault(ActionMode.DRY_RUN)
        )
    }

    private fun saveRootFrozenRecords(records: List<RootFrozenRecord>) {
        preferences.edit()
            .putString(
                KEY_ROOT_FROZEN_RECORDS,
                JSONArray(
                    records.map { record ->
                        JSONObject()
                            .put("package_name", record.packageName)
                            .put("frozen_at", record.frozenAtMillis)
                    }
                ).toString()
            )
            .putStringSet(KEY_ROOT_FROZEN, records.map { it.packageName }.toSet())
            .apply()
    }

    private fun getBadFreezeDecisions(): List<JSONObject> {
        val raw = preferences.getString(KEY_BAD_FREEZE_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(array.getJSONObject(index))
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS_NAME = "app_policy_state"
        private const val KEY_ALLOWLIST = "protected_allowlist"
        private const val KEY_DRY_RUN_FROZEN = "dry_run_frozen_packages"
        private const val KEY_ROOT_FROZEN = "root_frozen_packages"
        private const val KEY_ROOT_FROZEN_RECORDS = "root_frozen_records"
        private const val KEY_LAST_ROOT_ACTION_TIMESTAMP = "last_root_action_timestamp"
        private const val KEY_BAD_FREEZE_HISTORY = "bad_freeze_history"
        private const val KEY_FREEZE_THRESHOLD = "freeze_threshold"
        private const val KEY_PROTECT_THRESHOLD = "protect_threshold"
        private const val KEY_RECENT_APP_WINDOW = "recent_app_protection_window"
        private const val KEY_MAX_FREEZE_PER_CYCLE = "max_apps_to_freeze_per_cycle"
        private const val KEY_DRY_RUN_ENABLED = "dry_run_enabled"
        private const val KEY_ACTIVE_ROOT_MODE_ENABLED = "active_root_mode_enabled"
        private const val KEY_PREDICTION_INTERVAL_SECONDS = "prediction_interval_seconds"
        private const val KEY_PAUSE_WHEN_BATTERY_LOW = "pause_when_battery_low"
        private const val KEY_ROOT_ACTION_COOLDOWN_SECONDS = "root_action_cooldown_seconds"
        private const val KEY_ROLLBACK_WINDOW_SECONDS = "rollback_window_seconds"
        private const val KEY_ACTION_HISTORY = "dry_run_action_history"
        private const val MAX_ACTION_HISTORY = 100
        private const val MAX_BAD_FREEZE_HISTORY = 100
    }
}
