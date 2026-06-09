package com.example.foresightapk

import android.content.Context

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

    companion object {
        private const val PREFS_NAME = "app_policy_state"
        private const val KEY_ALLOWLIST = "protected_allowlist"
        private const val KEY_DRY_RUN_FROZEN = "dry_run_frozen_packages"
    }
}
