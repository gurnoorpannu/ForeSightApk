package com.example.foresightapk

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

class InferenceService : Service() {
    private lateinit var predictor: ForeSightPredictor

    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                InferenceContract.MSG_PREDICT -> {
                    runPrediction(message)
                    true
                }
                else -> false
            }
        }
    )

    override fun onCreate() {
        super.onCreate()
        ForeSightLog.info("InferenceService created in process=${android.os.Process.myPid()}")
        predictor = ForeSightPredictor(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        ForeSightLog.debug("InferenceService bound")
        return messenger.binder
    }

    override fun onDestroy() {
        ForeSightLog.debug("InferenceService destroyed")
        if (::predictor.isInitialized) {
            predictor.close()
        }
        super.onDestroy()
    }

    private fun runPrediction(message: Message) {
        val replyTo = message.replyTo ?: return
        val launches = InferenceContract.launchesFromBundle(message.data)

        Thread {
            val response = runCatching {
                ForeSightLog.info("Remote inference started with launches=${launches.size}")
                predictor.predict(launches)
            }.fold(
                onSuccess = { result ->
                    Message.obtain(null, InferenceContract.MSG_RESULT).apply {
                        data = InferenceContract.resultToBundle(result)
                    }
                },
                onFailure = { error ->
                    ForeSightLog.error("Remote inference failed", error)
                    Message.obtain(null, InferenceContract.MSG_ERROR).apply {
                        data = InferenceContract.errorToBundle(
                            error.message ?: error::class.java.simpleName
                        )
                    }
                }
            )

            runCatching {
                replyTo.send(response)
            }.onFailure { error ->
                ForeSightLog.error("Could not send inference response", error)
            }
        }.apply {
            name = "ForeSightInference"
            start()
        }
    }
}
