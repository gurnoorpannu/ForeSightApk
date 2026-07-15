package com.example.foresightapk

class RootDecisionExecutor(
    private val policyStore: AppPolicyStore,
    private val rootFreezeController: RootFreezeController
) {
    suspend fun rollbackRecentlyOpenedFrozenApps(
        recentApps: List<AppLaunch>,
        policy: DecisionPolicy
    ): List<RootCommandResult> {
        val rollbackWindowMs = policy.rollbackWindowSeconds * 1000L
        val recordsByPackage = policyStore.getRootFrozenRecords().associateBy { it.packageName }
        val rollbackTargets = recentApps
            .asSequence()
            .mapNotNull { launch ->
                val record = recordsByPackage[launch.packageName] ?: return@mapNotNull null
                if (
                    record.frozenAtMillis > 0L &&
                    launch.timestampMillis > record.frozenAtMillis &&
                    launch.timestampMillis - record.frozenAtMillis <= rollbackWindowMs
                ) {
                    launch to record
                } else {
                    null
                }
            }
            .distinctBy { (_, record) -> record.packageName }
            .toList()

        if (rollbackTargets.isEmpty()) return emptyList()

        return rollbackTargets.map { (launch, record) ->
            ForeSightLog.warn(
                "Rollback unfreezing recently opened frozen package=${record.packageName}"
            )
            policyStore.recordBadFreezeDecision(
                packageName = record.packageName,
                openedAtMillis = launch.timestampMillis,
                frozenAtMillis = record.frozenAtMillis
            )
            rootFreezeController.unfreezePackage(record.packageName)
        }
    }

    suspend fun executeActivePlan(
        plan: DecisionPlan,
        policy: DecisionPolicy
    ): List<RootCommandResult> {
        if (!policy.activeRootModeEnabled) return emptyList()

        val now = System.currentTimeMillis()
        val cooldownMs = policy.rootActionCooldownSeconds * 1000L
        val lastActionAt = policyStore.getLastRootActionTimestamp()
        if (lastActionAt > 0L && now - lastActionAt < cooldownMs) {
            ForeSightLog.info(
                "Active root action skipped for cooldown: remainingMs=${cooldownMs - (now - lastActionAt)}"
            )
            return emptyList()
        }

        val decision = plan.decisions.firstOrNull { it.action == DecisionAction.WOULD_UNFREEZE }
            ?: plan.decisions.firstOrNull { it.action == DecisionAction.WOULD_FREEZE }
            ?: return emptyList()

        val result = when (decision.action) {
            DecisionAction.WOULD_UNFREEZE -> rootFreezeController.unfreezePackage(decision.packageName)
            DecisionAction.WOULD_FREEZE -> rootFreezeController.freezePackage(decision.packageName)
            DecisionAction.PROTECT,
            DecisionAction.IGNORE -> return emptyList()
        }

        if (result.exitCode == 0 && !result.timedOut) {
            policyStore.markRootActionTimestamp(result.timestampMillis)
        }
        return listOf(result)
    }
}
