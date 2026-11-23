package dev.rubiidev.shellinstaller.install

import android.content.Context
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Read-only pre-install security/metadata report for the selected APK set. */
object ApkReportDialog {

    fun show(context: Context, info: ApkInfo) {
        val r = info.report
        val text = buildString {
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
            if (r.warnings.isEmpty()) appendLine("  none") else r.warnings.forEach { appendLine("  - $it") }
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
            .setPositiveButton("Close", null)
            .show()
    }
}
