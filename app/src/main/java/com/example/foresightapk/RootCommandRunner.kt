package com.example.foresightapk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class RootCommandRunner(private val context: Context) {
    suspend fun checkRootAvailability(): Pair<RootAvailability, RootCommandResult> {
        val result = runSafeCommand(SafeRootCommand.Id)
        return inferAvailabilityFromIdResult(result) to result
    }

    suspend fun runSafeCommand(command: SafeRootCommand): RootCommandResult {
        return withContext(Dispatchers.IO) {
            val result = executeThroughSu(command)
            logResult(result)
            result
        }
    }

    private fun executeThroughSu(command: SafeRootCommand): RootCommandResult {
        val timestamp = System.currentTimeMillis()
        val process = try {
            ProcessBuilder("su", "-c", command.shellCommand).start()
        } catch (error: IOException) {
            return RootCommandResult(
                timestampMillis = timestamp,
                commandId = command.id,
                commandLabel = command.label,
                command = command.shellCommand,
                stdout = "",
                stderr = "Could not start su: ${error.message ?: error::class.java.simpleName}",
                exitCode = EXIT_PROCESS_START_FAILED,
                timedOut = false
            )
        }

        var stdout = ""
        var stderr = ""
        val stdoutThread = Thread {
            stdout = process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrThread = Thread {
            stderr = process.errorStream.bufferedReader().use { it.readText() }
        }

        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
        }

        stdoutThread.join(STREAM_JOIN_MS)
        stderrThread.join(STREAM_JOIN_MS)

        return RootCommandResult(
            timestampMillis = timestamp,
            commandId = command.id,
            commandLabel = command.label,
            command = command.shellCommand,
            stdout = stdout,
            stderr = stderr,
            exitCode = if (finished) process.exitValue() else EXIT_TIMEOUT,
            timedOut = !finished
        )
    }

    private fun logResult(result: RootCommandResult) {
        val payload = JSONObject()
            .put("timestamp", result.timestampMillis)
            .put("command_id", result.commandId)
            .put("command_label", result.commandLabel)
            .put("command", result.command)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .put("exit_code", result.exitCode)
            .put("timed_out", result.timedOut)

        context.openFileOutput(LOG_FILE_NAME, Context.MODE_APPEND).use { stream ->
            stream.write((payload.toString() + "\n").toByteArray())
        }
        ForeSightLog.info(
            "Root command result: id=${result.commandId}, exit=${result.exitCode}, " +
                "timedOut=${result.timedOut}"
        )
    }

    sealed class SafeRootCommand(
        val id: String,
        val label: String,
        val shellCommand: String
    ) {
        object Id : SafeRootCommand(
            id = "id",
            label = "id",
            shellCommand = "id"
        )

        object PackageList : SafeRootCommand(
            id = "pm_list_packages",
            label = "pm list packages",
            shellCommand = "pm list packages"
        )

        class FreezePackage(packageName: String) : SafeRootCommand(
            id = "freeze_package",
            label = "pm disable-user",
            shellCommand = "pm disable-user --user 0 $packageName"
        ) {
            init {
                require(SAFE_PACKAGE_PATTERN.matches(packageName)) {
                    "Invalid package name."
                }
            }
        }

        class UnfreezePackage(packageName: String) : SafeRootCommand(
            id = "unfreeze_package",
            label = "pm enable",
            shellCommand = "pm enable $packageName"
        ) {
            init {
                require(SAFE_PACKAGE_PATTERN.matches(packageName)) {
                    "Invalid package name."
                }
            }
        }

        class ForceStopPackage(packageName: String) : SafeRootCommand(
            id = "force_stop_package",
            label = "am force-stop",
            shellCommand = "am force-stop $packageName"
        ) {
            init {
                require(SAFE_PACKAGE_PATTERN.matches(packageName)) {
                    "Invalid package name."
                }
            }
        }
    }

    companion object {
        const val LOG_FILE_NAME = "root_command_events.jsonl"
        val SAFE_PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private const val COMMAND_TIMEOUT_MS = 5_000L
        private const val STREAM_JOIN_MS = 500L
        private const val EXIT_PROCESS_START_FAILED = -127
        private const val EXIT_TIMEOUT = -124

        fun inferAvailabilityFromIdResult(result: RootCommandResult): RootAvailability {
            val combinedOutput = "${result.stdout}\n${result.stderr}".lowercase()
            return when {
                combinedOutput.contains("uid=0") -> RootAvailability.Available
                result.exitCode == EXIT_PROCESS_START_FAILED ||
                    combinedOutput.contains("no such file") ||
                    combinedOutput.contains("not found") -> RootAvailability.Unavailable
                else -> RootAvailability.Denied
            }
        }
    }
}
