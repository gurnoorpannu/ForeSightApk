package com.example.foresightapk

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

class UsageEventReader(private val context: Context) {
    private val usageStatsManager =
        context.getSystemService(UsageStatsManager::class.java)
    private val packageManager = context.packageManager

    fun readRecentLaunches(limit: Int = 10): List<AppLaunch> {
        val now = System.currentTimeMillis()
        val lookbackWindows = listOf(
            24L * 60L * 60L * 1000L,
            7L * 24L * 60L * 60L * 1000L,
            30L * 24L * 60L * 60L * 1000L
        )

        var latest = emptyList<AppLaunch>()
        for (lookback in lookbackWindows) {
            val startMillis = now - lookback
            ForeSightLog.debug("Querying usage events: lookbackMs=$lookback, limit=$limit")
            val launches = queryLaunches(startMillis, now)
            ForeSightLog.debug("Usage event query returned ${launches.size} deduped launches")
            latest = launches
            if (launches.size >= limit) break
        }

        val result = latest.takeLast(limit)
        ForeSightLog.info("Loaded ${result.size} recent app launches")
        return result
    }

    fun readFirstLaunchAfter(
        timestampMillis: Long,
        endMillis: Long = System.currentTimeMillis()
    ): AppLaunch? {
        if (endMillis <= timestampMillis) return null
        return queryLaunches(timestampMillis + 1, endMillis).firstOrNull()
    }

    private fun queryLaunches(startMillis: Long, endMillis: Long): List<AppLaunch> {
        val events = usageStatsManager.queryEvents(startMillis, endMillis)
        val event = UsageEvents.Event()
        val launches = mutableListOf<AppLaunch>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (!event.isForegroundLaunch()) continue
            val packageName = event.packageName ?: continue
            if (packageName == context.packageName) continue

            launches += AppLaunch(
                packageName = packageName,
                appLabel = labelForPackage(packageName),
                timestampMillis = event.timeStamp
            )
        }

        return launches
            .sortedBy { it.timestampMillis }
            .fold(mutableListOf()) { deduped, launch ->
                if (deduped.lastOrNull()?.packageName != launch.packageName) {
                    deduped += launch
                }
                deduped
            }
    }

    private fun UsageEvents.Event.isForegroundLaunch(): Boolean {
        return eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                eventType == UsageEvents.Event.ACTIVITY_RESUMED)
    }

    private fun labelForPackage(packageName: String): String {
        val info = applicationInfo(packageName) ?: return packageName
        return runCatching {
            packageManager.getApplicationLabel(info).toString()
        }.getOrElse { error ->
            ForeSightLog.warn("Could not resolve label for package=$packageName", error)
            packageName
        }
    }

    private fun applicationInfo(packageName: String): ApplicationInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}

object UsageAccess {
    fun isEnabled(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }

        val enabled = mode == AppOpsManager.MODE_ALLOWED
        ForeSightLog.debug("Usage access mode=$mode, enabled=$enabled")
        return enabled
    }
}
