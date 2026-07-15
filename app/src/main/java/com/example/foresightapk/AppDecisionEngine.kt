package com.example.foresightapk

object AppDecisionEngine {
    fun buildPlan(
        predictions: List<PredictedApp>,
        installedApps: List<InstalledAppInfo>,
        recentApps: List<AppLaunch>,
        protectedAllowlist: Set<String>,
        dryRunFrozenPackages: Set<String>,
        ownPackageName: String,
        policy: DecisionPolicy
    ): DecisionPlan {
        val cleanPolicy = policy.sanitized()
        val recentPackages = recentApps
            .takeLast(cleanPolicy.recentAppProtectionWindow)
            .map { it.packageName }
            .toSet()
        val predictedTargets = predictions
            .withIndex()
            .filter { indexedPrediction ->
                indexedPrediction.index < DecisionPolicy.PROTECTED_TOP_PREDICTIONS ||
                    indexedPrediction.value.confidence >= cleanPolicy.protectThreshold
            }
            .map { it.value }
            .mapNotNull { prediction ->
                installedApps.bestTargetFor(prediction)?.let { app -> prediction to app }
            }
            .distinctBy { (_, app) -> app.packageName }

        val predictedPackages = predictedTargets.map { (_, app) -> app.packageName }.toSet()
        val predictedConfidenceByPackage = predictedTargets.associate { (_, app) ->
            app.packageName to predictions.first { prediction ->
                app.mappedModelAppId == prediction.appId || app.mappedModelLabel == prediction.label
            }.confidence
        }

        val preliminaryDecisions = installedApps
            .asSequence()
            .sortedWith(
                compareByDescending<InstalledAppInfo> { it.packageName in predictedPackages }
                    .thenByDescending { it.isLaunchable }
                    .thenBy { it.isSystemApp }
                    .thenBy { it.appLabel.lowercase() }
            )
            .map { app ->
                decide(
                    app = app,
                    recentPackages = recentPackages,
                    predictedPackages = predictedPackages,
                    predictedConfidenceByPackage = predictedConfidenceByPackage,
                    protectedAllowlist = protectedAllowlist,
                    dryRunFrozenPackages = dryRunFrozenPackages,
                    ownPackageName = ownPackageName,
                    policy = cleanPolicy
                )
            }
            .toList()
        var remainingFreezeBudget = cleanPolicy.maxAppsToFreezePerCycle
        val decisions = preliminaryDecisions.map { decision ->
            if (decision.action != DecisionAction.WOULD_FREEZE) {
                decision
            } else if (remainingFreezeBudget > 0) {
                remainingFreezeBudget -= 1
                decision
            } else {
                decision.copy(
                    action = DecisionAction.IGNORE,
                    reason = DecisionReason.MaxFreezeLimitReached,
                    reasonDetail = "Max would-freeze limit reached for this dry-run cycle."
                )
            }
        }

        val diagnostics = listOf(
            "Policy mode: dry run only; no freeze or unfreeze command is executed.",
            "Dry-run enabled: ${cleanPolicy.dryRunEnabled}.",
            "Freeze threshold: ${"%.2f".format(cleanPolicy.freezeThreshold)}.",
            "Protect threshold: ${"%.2f".format(cleanPolicy.protectThreshold)}.",
            "Recent-app protection window: ${cleanPolicy.recentAppProtectionWindow}.",
            "Max apps to freeze per cycle: ${cleanPolicy.maxAppsToFreezePerCycle}.",
            "Protected top predictions: ${DecisionPolicy.PROTECTED_TOP_PREDICTIONS}.",
            "Protected allowlist packages: ${protectedAllowlist.size}.",
            "Previously frozen dry-run packages: ${dryRunFrozenPackages.size}.",
            "Predicted package targets: ${predictedPackages.size}."
        )
        val protectCount = decisions.count { it.action == DecisionAction.PROTECT }
        val wouldFreezeCount = decisions.count { it.action == DecisionAction.WOULD_FREEZE }
        val wouldUnfreezeCount = decisions.count { it.action == DecisionAction.WOULD_UNFREEZE }
        val ignoreCount = decisions.count { it.action == DecisionAction.IGNORE }

        ForeSightLog.info(
            "Decision dry run built: predictions=${predictions.size}, installed=${installedApps.size}, " +
                "recent=${recentApps.size}, decisions=${decisions.size}, protect=$protectCount, " +
                "wouldFreeze=$wouldFreezeCount, wouldUnfreeze=$wouldUnfreezeCount, ignore=$ignoreCount"
        )

        return DecisionPlan(
            timestampMillis = System.currentTimeMillis(),
            predictionsSeen = predictions.size,
            installedAppsSeen = installedApps.size,
            recentAppsSeen = recentApps.size,
            decisions = decisions,
            diagnostics = diagnostics
        )
    }

    private fun decide(
        app: InstalledAppInfo,
        recentPackages: Set<String>,
        predictedPackages: Set<String>,
        predictedConfidenceByPackage: Map<String, Float>,
        protectedAllowlist: Set<String>,
        dryRunFrozenPackages: Set<String>,
        ownPackageName: String,
        policy: DecisionPolicy
    ): AppDecision {
        val confidence = predictedConfidenceByPackage[app.packageName]
        val modelLabel = app.mappedModelLabel
        val modelAppId = app.mappedModelAppId

        if (!policy.dryRunEnabled && !policy.activeRootModeEnabled) {
            return app.decision(
                action = DecisionAction.IGNORE,
                confidence = confidence,
                reason = DecisionReason.DryRunDisabled,
                detail = "Dry-run and active root decisions are disabled in policy settings."
            )
        }

        if (app.packageName in predictedPackages && app.packageName in dryRunFrozenPackages) {
            return app.decision(
                action = DecisionAction.WOULD_UNFREEZE,
                confidence = confidence,
                reason = DecisionReason.PredictedAndDryRunFrozen,
                detail = "Predicted soon and currently marked frozen by ForeSight dry-run state."
            )
        }

        if (app.packageName in predictedPackages) {
            return app.decision(
                action = DecisionAction.PROTECT,
                confidence = confidence,
                reason = DecisionReason.TopPrediction,
                detail = "Top prediction with confidence ${"%.3f".format(confidence ?: 0f)}."
            )
        }

        safetyReason(
            app = app,
            recentPackages = recentPackages,
            protectedAllowlist = protectedAllowlist,
            ownPackageName = ownPackageName
        )?.let { reason ->
            return app.decision(
                action = DecisionAction.PROTECT,
                confidence = confidence,
                reason = reason,
                detail = reason.displayName
            )
        }

        if (!app.isLaunchable) {
            return app.decision(
                action = DecisionAction.IGNORE,
                confidence = confidence,
                reason = DecisionReason.NotLaunchable,
                detail = "Non-launchable package is not a user-facing freeze target in dry-run policy."
            )
        }

        if (modelAppId == null || modelLabel == null) {
            return app.decision(
                action = DecisionAction.IGNORE,
                confidence = confidence,
                reason = DecisionReason.NoModelMapping,
                detail = "No matching model app ID is available."
            )
        }

        if (app.packageName in dryRunFrozenPackages) {
            return app.decision(
                action = DecisionAction.IGNORE,
                confidence = confidence,
                reason = DecisionReason.AlreadyDryRunFrozen,
                detail = "Already marked frozen in dry-run state and not predicted soon."
            )
        }

        val effectiveConfidence = confidence ?: 0f
        if (effectiveConfidence > policy.freezeThreshold) {
            return app.decision(
                action = DecisionAction.IGNORE,
                confidence = confidence,
                reason = DecisionReason.AboveFreezeThreshold,
                detail = "Confidence ${"%.3f".format(effectiveConfidence)} is above freeze threshold " +
                    "${"%.3f".format(policy.freezeThreshold)}."
            )
        }

        return app.decision(
            action = DecisionAction.WOULD_FREEZE,
            confidence = confidence,
            reason = DecisionReason.LowProbabilitySafeCandidate,
            detail = "Mapped launchable app is not protected, not recent, and not a top prediction."
        )
    }

    private fun safetyReason(
        app: InstalledAppInfo,
        recentPackages: Set<String>,
        protectedAllowlist: Set<String>,
        ownPackageName: String
    ): DecisionReason? {
        val packageName = app.packageName.lowercase()
        val label = app.appLabel.lowercase()

        return when {
            app.packageName == ownPackageName -> DecisionReason.ProtectedSelf
            app.packageName in protectedAllowlist -> DecisionReason.ManualAllowlist
            app.packageName in recentPackages -> DecisionReason.RecentlyUsed
            packageName.contains("launcher") || label.contains("launcher") -> DecisionReason.ProtectedLauncher
            packageName.contains("inputmethod") || label.contains("keyboard") || label.contains("gboard") -> {
                DecisionReason.ProtectedKeyboard
            }
            packageName.contains("systemui") || label == "system ui" -> DecisionReason.ProtectedSystemUi
            packageName.contains("dialer") || packageName.contains(".phone") || label == "phone" -> {
                DecisionReason.ProtectedDialer
            }
            packageName.contains("settings") || label == "settings" -> DecisionReason.ProtectedSettings
            packageName.contains("clock") || packageName.contains("alarm") || label == "clock" -> {
                DecisionReason.ProtectedClock
            }
            packageName.contains("accessibility") ||
                packageName.contains("talkback") ||
                label.contains("accessibility") ||
                label.contains("voice access") ||
                label.contains("switch access") -> DecisionReason.ProtectedAccessibility
            app.isSystemApp -> DecisionReason.SystemApp
            else -> null
        }
    }

    private fun InstalledAppInfo.decision(
        action: DecisionAction,
        confidence: Float?,
        reason: DecisionReason,
        detail: String
    ): AppDecision {
        return AppDecision(
            action = action,
            packageName = packageName,
            appLabel = appLabel,
            modelAppId = mappedModelAppId,
            modelLabel = mappedModelLabel,
            confidence = confidence,
            reason = reason,
            reasonDetail = detail
        )
    }

    private fun List<InstalledAppInfo>.bestTargetFor(prediction: PredictedApp): InstalledAppInfo? {
        return filter { app ->
            app.mappedModelAppId == prediction.appId || app.mappedModelLabel == prediction.label
        }.sortedWith(
            compareByDescending<InstalledAppInfo> { it.isLaunchable }
                .thenBy { it.isSystemApp }
                .thenBy { it.appLabel }
        ).firstOrNull()
    }
}
