package dev.rubiidev.shellinstaller.install

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read-only pre-install security/metadata report for the selected APK set. */
object ApkReportDialog {

    fun show(context: Context, info: ApkInfo) {
        // The dex scan is the expensive part of the report — run it only when the user opens
        // the report, off the main thread, instead of during file preparation.
        Toast.makeText(context, "Scanning for ad/tracker SDKs…", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            val findings = withContext(Dispatchers.IO) {
                info.allApks
                    .flatMap { AdTrackerScanner.scan(it) }
                    .distinctBy { it.sdk to it.evidence }
            }
            val scanned = info.copy(
                report = info.report.copy(adFindings = findings)
            )
            showDialog(context, scanned)
        }
    }

    private fun showDialog(context: Context, info: ApkInfo) {
        val r = info.report
        val verdict = SecurityScan.analyze(r)
        val text = buildString {
            appendLine("SECURITY VERDICT: ${verdict.headline}")
            verdict.findings.forEach { appendLine("  - $it") }
            if (verdict.findings.isEmpty()) appendLine("  no findings")
            appendLine()
            appendLine("Package: ${r.packageName.ifBlank { info.packageName ?: "unknown" }}")
            appendLine("Label: ${info.label}")
            appendLine("Version: ${r.versionName ?: "?"} (${r.versionCode})")
            appendLine("File: ${info.fileName}")
            appendLine("Splits: ${info.splitCount} · Bundle: ${info.isBundle} · Size: ${info.totalSize} bytes")
            appendLine("SDK: min ${r.minSdk} / target ${r.targetSdk} · Device SDK ${android.os.Build.VERSION.SDK_INT}")
            appendLine()
            appendLine("ABI: ${r.abis.joinToString(", ").ifBlank { "unknown" }}")
            appendLine("Features: ${r.features.size}")
            r.features.take(12).forEach { appendLine("  - $it") }
            if (r.features.size > 12) appendLine("  … +${r.features.size - 12} more")
            appendLine()
            appendLine("Permissions: ${r.permissions.size} total, ${r.dangerousPermissions.size} dangerous")
            r.dangerousPermissions.take(20).forEach { appendLine("  ! ${it.substringAfterLast('.')}") }
            if (r.dangerousPermissions.size > 20) appendLine("  … +${r.dangerousPermissions.size - 20} more")
            appendLine()
            appendLine("Signature: ${r.signatureStatus} · signers ${r.signerCount}")
            appendLine(r.signerSummary)
            appendLine()
            appendLine("Ad/tracking indicators: ${r.adFindings.size}")
            r.adFindings.forEach { appendLine("  [${it.category}] ${it.sdk} <- ${it.evidence}") }
            if (r.adFindings.isEmpty()) appendLine("  none detected")
            appendLine()
            appendLine("Warnings:")
            val warnings = r.warnings +
                if (r.adFindings.isNotEmpty()) listOf("${r.adFindings.size} ad/tracking SDK indicator(s) detected.") else emptyList()
            if (warnings.isEmpty()) appendLine("  none") else warnings.forEach { appendLine("  - $it") }
        }

        val scroll = ScrollView(context)
        val tv = TextView(context).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        MaterialAlertDialogBuilder(context)
            .setTitle("APK report")
            .setView(scroll)
            .setNeutralButton("VirusTotal") { _, _ -> promptApiKeyThenScan(context, info) }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun promptApiKeyThenScan(context: Context, info: ApkInfo) {
        if (Prefs.virusTotalApiKey(context).isNotBlank()) {
            runVirusTotalScan(context, info)
            return
        }
        val layout = TextInputLayout(context).apply {
            hint = "VirusTotal API key (free at virustotal.com)"
        }
        val input = EditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.virusTotalApiKey(context))
        }
        layout.addView(input)
        val pad = (24 * context.resources.displayMetrics.density).toInt()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(layout)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("VirusTotal scan")
            .setView(box)
            .setPositiveButton("Scan") { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                if (key.isBlank()) {
                    Toast.makeText(context, "API key required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Prefs.setVirusTotalApiKey(context, key)
                runVirusTotalScan(context, info)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runVirusTotalScan(context: Context, info: ApkInfo) {
        val key = Prefs.virusTotalApiKey(context)
        if (key.isBlank()) return
        Toast.makeText(context, "Querying VirusTotal…", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val hash = SecurityScan.sha256(info.baseApk)
                    SecurityScan.virusTotalLookup(key, hash)
                }.getOrElse { "VirusTotal scan failed: ${it.message}" }
            }
            showTextDialog(context, "VirusTotal result", result)
        }
    }

    private fun showTextDialog(context: Context, title: String, text: String) {
        val scroll = ScrollView(context)
        val tv = TextView(context).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }
}
