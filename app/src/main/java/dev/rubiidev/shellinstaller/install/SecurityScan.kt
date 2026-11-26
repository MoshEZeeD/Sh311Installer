package dev.rubiidev.shellinstaller.install

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Offline APK security heuristics. Produces a verdict plus human-readable findings from data
 * already collected by [ApkAnalyzer] — no extra APK parsing, so it stays fast even on huge files.
 */
object SecurityScan {

    enum class Verdict { SAFE, SUSPICIOUS, POTENTIALLY_HARMFUL }

    data class Result(val verdict: Verdict, val findings: List<String>) {
        val headline: String
            get() = when (verdict) {
                Verdict.SAFE -> "SAFE — no obvious red flags"
                Verdict.SUSPICIOUS -> "SUSPICIOUS — review before installing"
                Verdict.POTENTIALLY_HARMFUL -> "POTENTIALLY HARMFUL — strong warning signs"
            }
    }

    // Dangerous permissions that are commonly abused by spyware, stalkerware and aggressive
    // ad/tracking SDKs. Presence alone is not proof of malice, but several together raise risk.
    private val HIGH_RISK_PERMISSIONS = setOf(
        "android.permission.SEND_SMS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.GET_ACCOUNTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS",
        "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.BODY_SENSORS",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
    )

    // Normal (install-time) permissions that legitimate apps rarely need.
    private val SENSITIVE_NORMAL_PERMISSIONS = listOf(
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.REQUEST_DELETE_PACKAGES",
    )

    fun analyze(report: ApkReport): Result {
        val findings = mutableListOf<String>()
        var score = 0

        if (report.signatureStatus == SignatureStatus.MISMATCH) {
            score += 4
            findings += "Signature mismatch — APK is signed with a different key than expected."
        }
        if (report.signatureStatus == SignatureStatus.UNKNOWN) {
            score += 1
            findings += "Signature could not be verified."
        }

        val highRisk = report.dangerousPermissions.filter { it in HIGH_RISK_PERMISSIONS }
        if (highRisk.isNotEmpty()) {
            score += minOf(highRisk.size, 4)
            findings += "Requests ${highRisk.size} high-risk permission(s): " +
                highRisk.joinToString(", ") { it.substringAfterLast('.') }
        }

        val sensitive = report.permissions.filter { it in SENSITIVE_NORMAL_PERMISSIONS }
        if (sensitive.isNotEmpty()) {
            score += sensitive.size.coerceAtMost(3)
            findings += "Requests sensitive system-level permission(s): " +
                sensitive.joinToString(", ") { it.substringAfterLast('.') }
        }

        if (report.targetSdk in 1..22) {
            score += 2
            findings += "Targets SDK ${report.targetSdk} — far below current Android; often used to bypass modern restrictions."
        } else if (report.targetSdk in 23..28) {
            score += 1
            findings += "Targets SDK ${report.targetSdk} — older than recommended."
        }

        if (report.adFindings.size >= 5) {
            score += 1
            findings += "Heavy ad/tracking footprint: ${report.adFindings.size} SDK indicators detected."
        }

        val verdict = when {
            score >= 5 -> Verdict.POTENTIALLY_HARMFUL
            score >= 2 -> Verdict.SUSPICIOUS
            else -> Verdict.SAFE
        }
        return Result(verdict, findings)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * VirusTotal v3 hash lookup. Only the SHA-256 is sent — the APK itself is never uploaded.
     * Requires a free API key from virustotal.com. Blocking — call off the main thread.
     */
    fun virusTotalLookup(apiKey: String, sha256: String): String {
        val conn = URL("https://www.virustotal.com/api/v3/files/$sha256")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("x-apikey", apiKey)
            val code = conn.responseCode
            val body = (if (code == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return "VirusTotal: this file has not been seen before — no detections on record."
            }
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == 403) {
                throw IOException("VirusTotal rejected the API key (HTTP $code).")
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("VirusTotal request failed (HTTP $code).")
            }
            val stats = JSONObject(body)
                .getJSONObject("data")
                .getJSONObject("attributes")
                .getJSONObject("last_analysis_stats")
            val malicious = stats.optInt("malicious")
            val suspicious = stats.optInt("suspicious")
            val harmless = stats.optInt("harmless")
            val undetected = stats.optInt("undetected")
            val total = malicious + suspicious + harmless + undetected
            val date = JSONObject(body).getJSONObject("data")
                .getJSONObject("attributes").optString("last_analysis_date", "")
            return buildString {
                appendLine("VirusTotal: $malicious/$total engines flag this file as malicious" +
                    if (suspicious > 0) ", $suspicious suspicious" else "")
                appendLine("harmless: $harmless · undetected: $undetected" +
                    if (date.isNotBlank()) " · last analysis: ${date.take(10)}" else "")
                if (malicious + suspicious == 0) {
                    append("Result: clean on VirusTotal.")
                } else {
                    append("Result: flagged by at least one engine — review carefully.")
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
