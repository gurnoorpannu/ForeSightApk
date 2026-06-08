package com.example.foresightapk

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

class AppInventoryReader(
    private val context: Context,
    private val mappingStore: AppMappingStore
) {
    private val appVocab: Map<String, Int> by lazy { AppVocab.load(context) }
    private val appMapper: AppMapper by lazy { AppMapper(appVocab) }

    fun readInstalledApps(includeNonLaunchable: Boolean): List<InstalledAppInfo> {
        val packageManager = context.packageManager
        val launchablePackages = readLaunchablePackages(packageManager)

        val appSources = if (includeNonLaunchable) {
            packageManager.getInstalledApplications(0).map { applicationInfo ->
                AppInventorySource(
                    packageName = applicationInfo.packageName,
                    appLabel = applicationInfo.loadLabel(packageManager)?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: applicationInfo.packageName,
                    applicationInfo = applicationInfo,
                    isLaunchable = launchablePackages.contains(applicationInfo.packageName)
                )
            }
        } else {
            readLaunchableSources(packageManager, launchablePackages)
        }

        val installedApps = appSources
            .distinctBy { it.packageName }
            .map { source ->
                source.toInstalledAppInfo()
            }
            .sortedBy { it.appLabel.lowercase() }

        val mappedCount = installedApps.count { it.mappedModelAppId != null }
        val manualCount = installedApps.count { it.mappingSource == MappingSource.ManualOverride }
        ForeSightLog.info(
            "Loaded app inventory: scope=${if (includeNonLaunchable) "all installed" else "launchable"}, " +
                "total=${installedApps.size}, mapped=$mappedCount, " +
                "unmapped=${installedApps.size - mappedCount}, manualOverrides=$manualCount"
        )
        return installedApps
    }

    private fun readLaunchableSources(
        packageManager: PackageManager,
        launchablePackages: Set<String>
    ): List<AppInventorySource> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        return packageManager.queryIntentActivities(launcherIntent, 0).map { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName
            AppInventorySource(
                packageName = packageName,
                appLabel = resolveInfo.loadLabel(packageManager)?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: packageName,
                applicationInfo = resolveInfo.activityInfo.applicationInfo,
                isLaunchable = launchablePackages.contains(packageName)
            )
        }
    }

    private fun readLaunchablePackages(packageManager: PackageManager): Set<String> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    private fun AppInventorySource.toInstalledAppInfo(): InstalledAppInfo {
        val overrideLabel = mappingStore.getOverrideLabel(packageName)
        val mapping = appMapper.map(
            packageName = packageName,
            appLabel = appLabel,
            overrideLabel = overrideLabel,
            fallbackToUnknown = false
        )

        ForeSightLog.debug(
            "Inventory mapping: package=$packageName, label=$appLabel, launchable=$isLaunchable, " +
                "modelId=${mapping.modelAppId}, source=${mapping.source.displayName}, " +
                "confidence=${mapping.confidence}"
        )

        return InstalledAppInfo(
            packageName = packageName,
            appLabel = appLabel,
            isSystemApp = applicationInfo.isSystemApp(),
            isLaunchable = isLaunchable,
            mappedModelAppId = mapping.modelAppId,
            mappedModelLabel = mapping.modelLabel,
            mappingSource = mapping.source,
            mappingConfidence = mapping.confidence
        )
    }

    private fun ApplicationInfo.isSystemApp(): Boolean {
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return flags and systemFlags != 0
    }

    private data class AppInventorySource(
        val packageName: String,
        val appLabel: String,
        val applicationInfo: ApplicationInfo,
        val isLaunchable: Boolean
    )
}
