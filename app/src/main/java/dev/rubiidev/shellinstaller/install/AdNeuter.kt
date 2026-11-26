package dev.rubiidev.shellinstaller.install

import dev.rubiidev.shellinstaller.backend.InstallBackend

/**
 * Optional post-install "neuter" step. It does not rewrite the APK; it revokes a curated set of
 * dangerous permissions that ad/tracking SDKs commonly abuse. The user can re-grant them later.
 */
object AdNeuter {

    private val TARGET_PERMISSIONS = setOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.GET_ACCOUNTS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.BODY_SENSORS",
        "android.permission.BODY_SENSORS_BACKGROUND",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.RECORD_AUDIO",
    )

    fun neuter(
        context: android.content.Context,
        backend: InstallBackend,
        packageName: String,
        report: ApkReport,
    ): List<String> {
        if (packageName.isBlank()) return listOf("Neuter skipped: package name unknown.")
        val targets = report.dangerousPermissions.filter { it in TARGET_PERMISSIONS }
        if (targets.isEmpty()) return listOf("Neuter: no high-risk ad/tracking permissions found.")

        val lines = mutableListOf<String>()
        lines += "Neuter targets ${targets.size} permission(s):"
        for (permission in targets) {
            val result = runCatching {
                backend.exec(listOf("pm", "revoke", "--user", "current", packageName, permission))
            }
            val short = permission.substringAfterLast('.')
            if (result.isFailure) {
                lines += "  revoke $permission -> error: ${result.exceptionOrNull()?.message}"
                continue
            }
            val exec = result.getOrThrow()
            if (exec.looksSuccessful) {
                lines += "  revoked $short"
            } else {
                lines += "  revoke $short failed: ${exec.output.trim().ifBlank { "unknown" }}"
            }
        }

        // App-op restrictions that limit what ad/tracking SDKs can do even with permissions
        // intact: advertising ID access, usage-stats access and unrestricted background runs.
        for (op in APP_OPS) {
            val result = runCatching {
                backend.exec(listOf("appops", "set", packageName, op, "ignore"))
            }
            if (result.isSuccess && result.getOrThrow().looksSuccessful) {
                lines += "  restricted app-op $op"
            }
        }
        return lines
    }

    private val APP_OPS = listOf(
        "AD_ID",
        "GET_USAGE_STATS",
        "RUN_ANY_IN_BACKGROUND",
    )
}
