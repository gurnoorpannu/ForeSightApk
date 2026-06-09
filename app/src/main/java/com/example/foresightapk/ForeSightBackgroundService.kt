package com.example.foresightapk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ForeSightBackgroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var usageEventReader: UsageEventReader
    private lateinit var predictionLogger: PredictionLogger
    private lateinit var appInventoryReader: AppInventoryReader
    private lateinit var policyStore: AppPolicyStore
    private lateinit var shadowStore: ShadowEvaluationStore
    private var loopJob: Job? = null
    private var lastPredictionText: String = "not run yet"

    override fun onCreate() {
        super.onCreate()
        ForeSightLog.info("ForeSight background service created")
        isRunning = true
        usageEventReader = UsageEventReader(this)
        predictionLogger = PredictionLogger(this)
        shadowStore = ShadowEvaluationStore(this, usageEventReader)
        val mappingStore = AppMappingStore(this)
        policyStore = AppPolicyStore(this)
        appInventoryReader = AppInventoryReader(this, mappingStore)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ForeSightLog.info("Stopping ForeSight background service")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification())
        startLoopIfNeeded()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ForeSightLog.info("ForeSight background service destroyed")
        isRunning = false
        loopJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun startLoopIfNeeded() {
        if (loopJob?.isActive == true) {
            ForeSightLog.debug("Background prediction loop already running")
            return
        }

        loopJob = serviceScope.launch {
            while (isActive) {
                val policy = policyStore.getDecisionPolicy()
                if (policy.pauseWhenBatteryLow && isBatteryLow()) {
                    ForeSightLog.warn("Background prediction paused because battery is low")
                    lastPredictionText = "paused: low battery"
                    updateNotification()
                } else {
                    runCatching {
                        runPredictionCycle()
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        ForeSightLog.error("Background prediction cycle failed", error)
                        predictionLogger.logError(
                            stage = "Background prediction cycle",
                            throwable = error,
                            diagnostics = listOf("Foreground service dry-run loop.")
                        )
                    }
                }

                delay(policyStore.getDecisionPolicy().predictionIntervalSeconds * 1000L)
            }
        }
        ForeSightLog.info("Started background prediction loop")
    }

    private suspend fun runPredictionCycle() {
        if (!UsageAccess.isEnabled(this)) {
            ForeSightLog.warn("Background prediction skipped; Usage Access disabled")
            lastPredictionText = "usage access disabled"
            updateNotification()
            return
        }

        val recentApps = withContext(Dispatchers.Default) {
            usageEventReader.readRecentLaunches()
        }
        val prediction = runIsolatedInference(recentApps)
        predictionLogger.log(prediction)

        val installedApps = withContext(Dispatchers.Default) {
            appInventoryReader.readInstalledApps(includeNonLaunchable = false)
        }
        val policy = policyStore.getDecisionPolicy()
        val decisionPlan = AppDecisionEngine.buildPlan(
            predictions = prediction.predictions,
            installedApps = installedApps,
            recentApps = prediction.recentApps,
            protectedAllowlist = policyStore.getProtectedAllowlist(),
            dryRunFrozenPackages = policyStore.getDryRunFrozenPackages(),
            ownPackageName = packageName,
            policy = policy
        )
        policyStore.applyDecisionPlan(decisionPlan)
        val actionLogs = policyStore.recordActionLogs(decisionPlan)
        predictionLogger.logDecisionPlan(decisionPlan)
        predictionLogger.logDryRunActions(actionLogs)
        runCatching {
            shadowStore.evaluatePendingCycles()
            shadowStore.recordCycle(prediction, decisionPlan)
        }.onFailure { error ->
            ForeSightLog.warn("Background shadow evaluation logging failed", error)
        }

        lastPredictionText = timestampText(decisionPlan.timestampMillis)
        updateNotification()
        ForeSightLog.info(
            "Background dry-run cycle complete: predictions=${prediction.predictions.size}, " +
                "decisions=${decisionPlan.decisions.size}"
        )
    }

    private suspend fun runIsolatedInference(recentApps: List<AppLaunch>): PredictionResult {
        return withTimeout(INFERENCE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                lateinit var connection: ServiceConnection
                var unbound = false

                fun unbindOnce() {
                    if (!unbound) {
                        unbound = true
                        runCatching { unbindService(connection) }
                    }
                }

                val replyMessenger = Messenger(
                    Handler(Looper.getMainLooper()) { message ->
                        when (message.what) {
                            InferenceContract.MSG_RESULT -> {
                                unbindOnce()
                                continuation.resume(InferenceContract.resultFromBundle(message.data))
                                true
                            }
                            InferenceContract.MSG_ERROR -> {
                                unbindOnce()
                                continuation.resumeWithException(
                                    IllegalStateException(
                                        InferenceContract.errorFromBundle(message.data)
                                    )
                                )
                                true
                            }
                            else -> false
                        }
                    }
                )

                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        val request = Message.obtain(null, InferenceContract.MSG_PREDICT).apply {
                            replyTo = replyMessenger
                            data = InferenceContract.launchesToBundle(recentApps)
                        }

                        runCatching {
                            Messenger(service).send(request)
                        }.onFailure { error ->
                            unbindOnce()
                            continuation.resumeWithException(error)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        ForeSightLog.error("Background inference process disconnected")
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException(
                                    "Inference process disconnected during background prediction."
                                )
                            )
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    unbindOnce()
                }

                val bound = bindService(
                    Intent(this@ForeSightBackgroundService, InferenceService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )

                if (!bound) {
                    unbindOnce()
                    continuation.resumeWithException(
                        IllegalStateException("Could not bind inference process.")
                    )
                }
            }
        }
    }

    private fun isBatteryLow(): Boolean {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPercent = if (level >= 0 && scale > 0) {
            level * 100 / scale
        } else {
            100
        }
        return !charging && batteryPercent <= LOW_BATTERY_PERCENT
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ForeSightBackgroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("ForeSight running")
            .setContentText("Last prediction: $lastPredictionText · Mode: Dry Run")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ForeSight background prediction",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows ForeSight dry-run prediction loop status."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun timestampText(timestampMillis: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))
    }

    companion object {
        const val ACTION_START = "com.example.foresightapk.action.START_BACKGROUND"
        const val ACTION_STOP = "com.example.foresightapk.action.STOP_BACKGROUND"
        private const val CHANNEL_ID = "foresight_background"
        private const val NOTIFICATION_ID = 4107
        private const val INFERENCE_TIMEOUT_MS = 8_000L
        private const val LOW_BATTERY_PERCENT = 15

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
