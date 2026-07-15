package com.example.foresightapk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

class RootFreezeController(
    private val context: Context,
    private val rootCommandRunner: RootCommandRunner,
    private val policyStore: AppPolicyStore
) {
    suspend fun freezePackage(packageName: String): RootCommandResult {
        val app = validatePackageExists(packageName)
        validateFreezeTarget(app)
        val result = rootCommandRunner.runSafeCommand(
            RootCommandRunner.SafeRootCommand.FreezePackage(packageName)
        )
        if (result.exitCode == 0 && !result.timedOut) {
            policyStore.markRootFrozen(packageName)
        }
        return result
    }

    suspend fun unfreezePackage(packageName: String): RootCommandResult {
        validatePackageExists(packageName)
        validateRestorativeTarget(packageName)
        val result = rootCommandRunner.runSafeCommand(
            RootCommandRunner.SafeRootCommand.UnfreezePackage(packageName)
        )
        if (result.exitCode == 0 && !result.timedOut) {
            policyStore.markRootUnfrozen(packageName)
        }
        return result
    }

    suspend fun forceStopPackage(packageName: String): RootCommandResult {
        val app = validatePackageExists(packageName)
        validateFreezeTarget(app)
        return rootCommandRunner.runSafeCommand(
            RootCommandRunner.SafeRootCommand.ForceStopPackage(packageName)
        )
    }

    suspend fun unfreezeAllFrozenByForeSight(): List<RootCommandResult> {
        val frozenPackages = policyStore.getRootFrozenPackages().toList().sorted()
        val results = frozenPackages.map { packageName ->
            unfreezePackage(packageName)
        }
        if (results.all { it.exitCode == 0 && !it.timedOut }) {
            policyStore.clearRootFrozenPackages()
        }
        return results
    }

    private fun validatePackageExists(packageName: String): AppInfoSnapshot {
        require(RootCommandRunner.SAFE_PACKAGE_PATTERN.matches(packageName)) {
            "Invalid package name."
        }

        val applicationInfo = try {
            context.packageManager.getApplicationInfoCompat(packageName)
        } catch (error: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("Package is not installed: $packageName")
        }

        return AppInfoSnapshot(
            packageName = packageName,
            label = applicationInfo.loadLabel(context.packageManager)?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: packageName,
            isSystemApp = applicationInfo.isSystemApp()
        )
    }

    private fun validateFreezeTarget(app: AppInfoSnapshot) {
        validateRestorativeTarget(app.packageName)
        if (app.packageName in policyStore.getProtectedAllowlist()) {
            throw IllegalArgumentException("Package is protected by the manual allowlist.")
        }
        protectedReason(app)?.let { reason ->
            throw IllegalArgumentException("Package is protected: $reason.")
        }
    }

    private fun validateRestorativeTarget(packageName: String) {
        if (packageName == context.packageName) {
            throw IllegalArgumentException("ForeSight cannot freeze or unfreeze itself.")
        }
    }

    private fun protectedReason(app: AppInfoSnapshot): String? {
        val packageName = app.packageName.lowercase()
        val label = app.label.lowercase()
        return when {
            packageName.contains("launcher") || label.contains("launcher") -> "launcher"
            packageName.contains("inputmethod") || label.contains("keyboard") || label.contains("gboard") -> {
                "keyboard/input method"
            }
            packageName.contains("systemui") || label == "system ui" -> "system UI"
            packageName.contains("dialer") || packageName.contains(".phone") || label == "phone" -> "phone/dialer"
            packageName.contains("settings") || label == "settings" -> "settings"
            packageName.contains("clock") || packageName.contains("alarm") || label == "clock" -> "clock/alarm"
            packageName.contains("accessibility") ||
                packageName.contains("talkback") ||
                label.contains("accessibility") ||
                label.contains("voice access") ||
                label.contains("switch access") -> "accessibility"
            app.isSystemApp -> "system app"
            else -> null
        }
    }

    private fun PackageManager.getApplicationInfoCompat(packageName: String): ApplicationInfo {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getApplicationInfo(packageName, flags)
        }
    }

    private fun ApplicationInfo.isSystemApp(): Boolean {
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return flags and systemFlags != 0
    }

    private data class AppInfoSnapshot(
        val packageName: String,
        val label: String,
        val isSystemApp: Boolean
    )
}
