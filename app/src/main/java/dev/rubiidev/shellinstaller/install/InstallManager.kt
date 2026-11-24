package dev.rubiidev.shellinstaller.install

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import dev.rubiidev.shellinstaller.R
import dev.rubiidev.shellinstaller.backend.Backends
import dev.rubiidev.shellinstaller.backend.InstallBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

/**
 * Drives a `pm install-create / install-write / install-commit` session through the selected
 * privileged backend. Runs in an application-scoped coroutine so it survives activity recreation.
 */
object InstallManager {

    sealed interface State {
        data object Idle : State
        data class Preparing(val message: String) : State
        data class Ready(val info: ApkInfo) : State
        data class Installing(val info: ApkInfo, val progress: Float, val status: String) : State
        data class Success(val info: ApkInfo, val packageName: String, val log: String) : State
        data class Failed(val info: ApkInfo?, val message: String, val log: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _logFlow = MutableStateFlow("")
    val logFlow: StateFlow<String> = _logFlow

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var current: ApkInfo? = null

    @Volatile
    private var cancelled = false
    private val log = StringBuilder()
    private var lastProgressUpdate = 0L

    fun prepare(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        cancelled = true // stop any in-flight install before swapping files
        scope.launch {
            try {
                current?.workDir?.deleteRecursively()
                current = null
                _state.value = State.Preparing(app.getString(R.string.copying_file))
                val info = ApkPreparer(app).prepare(uris)
                current = info
                _state.value = State.Ready(info)
            } catch (t: Throwable) {
                _state.value = State.Failed(null, friendly(t), "")
            }
        }
    }

    fun install(context: Context) {
        val info = current ?: return
        val app = context.applicationContext
        val options = Prefs.options(app)
        cancelled = false
        _logFlow.value = ""
        scope.launch { doInstall(app, info, options) }
    }

    fun cancel() {
        cancelled = true
    }

    fun reset() {
        current?.workDir?.deleteRecursively()
        current = null
        _state.value = State.Idle
    }

    fun logSnapshot(): String = log.toString()

    private suspend fun doInstall(context: Context, info: ApkInfo, options: Prefs.Options) {
        log.setLength(0)
        _logFlow.value = ""
        appendLog("Package: ${info.label} (${info.packageName ?: "unknown"})")
        appendLog("Version: ${info.versionName ?: "?"} (${info.versionCode})")
        appendLog("Files: ${info.splitCount} · Size: ${info.totalSize} bytes · Bundle: ${info.isBundle}")
        val backend = try {
            Backends.resolve(context)
        } catch (t: Throwable) {
            _state.value = State.Failed(info, friendly(t), log.toString())
            return
        }
        appendLog("Backend: ${backend.displayName}")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShellInstaller:install")
        wakeLock.acquire(10 * 60 * 1000L)
        try {
            val error = runSession(context, backend, info, options)
            if (error == null) {
                appendLog("Success")
                if (options.neuterAds) {
                    appendLog("Ad/tracker neuter enabled; applying permission lockdown…")
                    AdNeuter.neuter(context, backend, info.packageName.orEmpty(), info.report)
                        .forEach(::appendLog)
                }
                _state.value = State.Success(info, info.packageName.orEmpty(), log.toString())
                info.workDir.deleteRecursively()
                if (current === info) current = null
            } else {
                _state.value = State.Failed(info, error, log.toString())
            }
        } catch (t: Throwable) {
            val message = if (cancelled || t is CancellationException) {
                context.getString(R.string.cancelled)
            } else {
                friendly(t)
            }
            _state.value = State.Failed(info, message, log.toString())
        } finally {
            try {
                wakeLock.release()
            } catch (_: Throwable) {
            }
        }
    }

    /** Returns null on success, or a human-readable error message. */
    private suspend fun runSession(
        context: Context,
        backend: InstallBackend,
        info: ApkInfo,
        options: Prefs.Options
    ): String? {
        var sessionId: Int? = null
        var committed = false
        try {
            updateProgress(info, 0.02f, "Creating install session…")
            val createCmd = createCommand(context, info, options)
            val create = backend.exec(createCmd)
            appendLog("$ " + createCmd.joinToString(" "))
            appendLog("Backend: ${backend.displayName}")
            appendLog(create.output.trim())
            if (!create.looksSuccessful) return "install-create failed: ${create.output.trim()}"
            sessionId = parseSessionId(create.output)
                ?: return "Could not parse install session id: ${create.output.trim()}"

            val total = info.totalSize.coerceAtLeast(1L)
            var completedBytes = 0L
            for ((index, apk) in info.allApks.withIndex()) {
                checkCancelled()
                val status = "Writing ${apk.name} (${index + 1}/${info.splitCount})…"
                updateProgress(info, 0.05f + 0.9f * (completedBytes.toFloat() / total), status)
                appendLog("$ pm install-write -S ${apk.length()} $sessionId ${apk.name} -")
                appendLog("Source: ${apk.absolutePath}")
                appendLog("Size: ${apk.length()} bytes")
                var fileWritten = 0L
                val writeCmd = listOf(
                    "pm", "install-write", "-S", apk.length().toString(),
                    sessionId.toString(), apk.name, "-"
                )
                val result = backend.exec(writeCmd, stdin = apk) { n ->
                    fileWritten += n
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > 200) {
                        lastProgressUpdate = now
                        updateProgress(info, 0.05f + 0.9f * ((completedBytes + fileWritten).toFloat() / total), status)
                    }
                }
                completedBytes += apk.length()
                appendLog("Bytes written: ${apk.length()}")
                appendLog(result.output.trim().ifBlank { "No output" })
                if (!result.looksSuccessful) {
                    appendLog("install-write failed")
                    return humanError(parseFailure(result.output))
                }
            }

            checkCancelled()
            updateProgress(info, 0.97f, "Committing…")
            val commit = backend.exec(listOf("pm", "install-commit", sessionId.toString()))
            appendLog("$ pm install-commit $sessionId")
            appendLog(commit.output.trim().ifBlank { "No output" })
            if (!commit.looksSuccessful) {
                appendLog("install-commit failed")
                return humanError(parseFailure(commit.output))
            }
            committed = true
            updateProgress(info, 1f, "Done")
            return null
        } catch (t: Throwable) {
            if (cancelled || t is CancellationException) throw t
            return friendly(t)
        } finally {
            val id = sessionId
            if (id != null && !committed) {
                try {
                    backend.exec(listOf("pm", "install-abandon", id.toString()))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun createCommand(context: Context, info: ApkInfo, options: Prefs.Options): List<String> {
        val cmd = mutableListOf("pm", "install-create", "-r", "-S", info.totalSize.coerceAtLeast(1L).toString())
        val installer = options.installerPackage?.takeIf { it.isNotBlank() } ?: context.packageName
        cmd += listOf("-i", installer)

        when (options.targetUser) {
            Prefs.TargetUser.CURRENT -> cmd += listOf("--user", "current")
            Prefs.TargetUser.ALL -> cmd += listOf("--user", "all")
            Prefs.TargetUser.OWNER -> cmd += listOf("--user", "owner")
            Prefs.TargetUser.CUSTOM -> {
                val id = options.customUserId
                cmd += if (id != null && id >= 0) listOf("--user", id.toString()) else listOf("--user", "current")
            }
        }

        if (options.installLocation != Prefs.InstallLocation.UNSPECIFIED) {
            cmd += listOf("--install-location", options.installLocation.code.toString())
        }
        options.forceUuid?.takeIf { it.isNotBlank() }?.let { cmd += listOf("--force-uuid", it) }
        options.expectedPackage?.takeIf { it.isNotBlank() }?.let { cmd += listOf("--pkg", it) }
        options.originatingUri?.takeIf { it.isNotBlank() }?.let { cmd += listOf("--originating-uri", it) }
        options.referrerUri?.takeIf { it.isNotBlank() }?.let { cmd += listOf("--referrer", it) }
        if (options.installReason != Prefs.InstallReason.UNSPECIFIED) {
            cmd += listOf("--install-reason", options.installReason.code.toString())
        }
        if (Build.VERSION.SDK_INT >= 35 && options.packageSource != Prefs.PackageSource.UNSPECIFIED) {
            cmd += listOf("--package-source", options.packageSource.code.toString())
        }
        options.partialInheritPackage?.takeIf { it.isNotBlank() }?.let { cmd += listOf("-p", it) }

        when (options.installMode) {
            Prefs.InstallMode.INSTANT -> if (Build.VERSION.SDK_INT >= 24) cmd.add("--instant")
            Prefs.InstallMode.PRELOAD -> if (Build.VERSION.SDK_INT >= 26) cmd.add("--preload")
            Prefs.InstallMode.FULL -> Unit
        }

        when (options.stagedMode) {
            Prefs.StagedMode.STAGED -> if (Build.VERSION.SDK_INT >= 29) cmd.add("--staged")
            Prefs.StagedMode.NON_STAGED -> if (Build.VERSION.SDK_INT >= 29) cmd.add("--non-staged")
            Prefs.StagedMode.FORCE_NON_STAGED -> if (Build.VERSION.SDK_INT >= 29) cmd.add("--force-non-staged")
            Prefs.StagedMode.DEFAULT -> Unit
        }
        options.stagedReadyTimeoutMs?.let {
            if (Build.VERSION.SDK_INT >= 29) cmd += listOf("--staged-ready-timeout", it.toString())
        }
        if (options.enableRollback && Build.VERSION.SDK_INT >= 29) {
            cmd += listOf("--enable-rollback", options.rollbackPolicy.code.toString())
        }
        if (options.apex && Build.VERSION.SDK_INT >= 29) cmd.add("--apex")
        options.abiOverride?.takeIf { it.isNotBlank() }?.let { cmd += listOf("--abi", it) }
        if (options.skipVerification && Build.VERSION.SDK_INT >= 30) cmd.add("--skip-verification")
        if (options.forceQueryable && Build.VERSION.SDK_INT >= 30) cmd.add("--force-queryable")
        if (options.restrictPermissions && Build.VERSION.SDK_INT >= 29) cmd.add("--restrict-permissions")
        if (options.ignoreDexoptProfile && Build.VERSION.SDK_INT >= 35) cmd.add("--ignore-dexopt-profile")
        options.dexoptCompilerFilter?.takeIf { it.isNotBlank() }?.let {
            if (Build.VERSION.SDK_INT >= 35) cmd += listOf("--dexopt-compiler-filter", it)
        }
        if (options.disableAutoInstallDependencies && Build.VERSION.SDK_INT >= 36) {
            cmd.add("--disable-auto-install-dependencies")
        }
        if (options.updateOwnership && Build.VERSION.SDK_INT >= 34) cmd.add("--update-ownership")
        if (options.bypassLowTargetSdk && Build.VERSION.SDK_INT >= 34) {
            cmd.add("--bypass-low-target-sdk-block")
        }

        if (options.allowDowngrade) cmd.add("-d")
        if (options.grantAll) cmd.add("-g")
        if (options.allowTestOnly) cmd.add("-t")
        if (options.dontKillApp) cmd.add("--dont-kill")
        return cmd
    }

    private fun checkCancelled() {
        if (cancelled) throw CancellationException("Cancelled")
    }

    private fun updateProgress(info: ApkInfo, progress: Float, status: String) {
        _state.value = State.Installing(info, progress.coerceIn(0f, 1f), status)
    }

    private fun appendLog(line: String) {
        log.append(line).append('\n')
        _logFlow.value = log.toString()
    }

    private fun parseSessionId(output: String): Int? =
        Regex("""Success: created install session \[(\d+)\]""")
            .find(output)?.groupValues?.get(1)?.toIntOrNull()

    private fun parseFailure(output: String): String {
        val bracketed = Regex("""Failure \[(.+)\]""", RegexOption.DOT_MATCHES_ALL).find(output)
        if (bracketed != null) return bracketed.groupValues[1].trim()
        val token = Regex("""INSTALL_[A-Z_]+""").find(output)
        if (token != null) return token.value
        return output.trim().ifBlank { "Unknown error" }
    }

    private fun humanError(reason: String): String = when {
        reason.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ->
            "Signature mismatch with the installed version. Uninstall the existing app first (its data will be removed), then install this APK."
        reason.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
            "This APK is older than the installed version. Enable “Allow downgrade” and retry."
        reason.contains("INSTALL_FAILED_DEPRECATED_SDK_VERSION") ->
            "This app targets a very old Android version and is blocked by the system. Enable “Bypass low-target-SDK block” and retry."
        reason.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") ->
            "Not enough storage space to install this app."
        reason.contains("INSTALL_FAILED_NO_MATCHING_ABIS") ->
            "This APK does not match the device's CPU architecture (ABI)."
        reason.contains("INSTALL_FAILED_MISSING_SPLIT") ->
            "Required split APKs are missing. Install the full bundle (APKS / XAPK / APKM) instead of a single split."
        reason.contains("INSTALL_FAILED_USER_RESTRICTED") ->
            "A device policy (user restriction / MDM) is blocking installs. Shell Installer cannot override device-owner policies."
        reason.contains("INSTALL_PARSE_FAILED", true) || reason.contains("INSTALL_FAILED_INVALID_APK") ->
            "The APK is invalid or corrupted."
        reason.contains("INSTALL_FAILED_DUPLICATE_PERMISSION") ->
            "A permission defined by this APK conflicts with another installed app."
        else -> reason
    }

    private fun friendly(t: Throwable): String = t.message ?: t.javaClass.simpleName
}
