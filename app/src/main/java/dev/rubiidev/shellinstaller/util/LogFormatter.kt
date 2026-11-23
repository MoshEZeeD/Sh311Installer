package dev.rubiidev.shellinstaller.util

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/** Terminal-style coloring for installer logs. */
object LogFormatter {

    private val RED = Color.parseColor("#FF1744")
    private val GREEN = Color.parseColor("#00E676")
    private val CYAN = Color.parseColor("#18E0FF")
    private val YELLOW = Color.parseColor("#FFD600")
    private val MAGENTA = Color.parseColor("#FF4081")
    private val GRAY = Color.parseColor("#7C8A99")

    fun format(log: String): CharSequence {
        val out = SpannableStringBuilder()
        log.lines().forEach { line ->
            val start = out.length
            out.append(line).append('\n')
            val color = when {
                line.contains("failure", true) || line.contains("failed", true) || line.contains("error", true) -> RED
                line.contains("success", true) || line.contains("installed", true) -> GREEN
                line.startsWith("$") || line.contains("pm install") || line.contains("pm ") -> CYAN
                line.contains("session") || line.contains("Session") -> MAGENTA
                line.startsWith("Backend:") || line.startsWith("Source:") || line.startsWith("Neuter") -> YELLOW
                line.startsWith("Bytes written") || line.startsWith("Size:") || line.startsWith("Files:") -> GRAY
                else -> null
            }
            if (color != null) {
                out.setSpan(ForegroundColorSpan(color), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }
}
