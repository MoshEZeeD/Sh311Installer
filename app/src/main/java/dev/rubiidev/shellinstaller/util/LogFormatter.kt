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
    private val GLINT = Color.parseColor("#F2F8FF")

    /**
     * @param glintTail when > 0, the last N characters get a bright silver-white glint
     * (specular shimmer) on top of their normal color — used for the typing frontier.
     */
    fun format(log: String, glintTail: Int = 0): CharSequence {
        val out = SpannableStringBuilder()
        log.lines().forEach { line ->
            val start = out.length
            out.append(line).append('\n')
            lineColor(line)?.let { color ->
                out.setSpan(ForegroundColorSpan(color), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (glintTail > 0 && out.isNotEmpty()) {
            val start = (out.length - glintTail).coerceAtLeast(0)
            out.setSpan(ForegroundColorSpan(GLINT), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun lineColor(line: String): Int? = when {
        line.contains("failure", true) || line.contains("failed", true) || line.contains("error", true) -> RED
        line.contains("success", true) || line.contains("installed", true) -> GREEN
        line.startsWith("$") || line.contains("pm install") || line.contains("pm ") -> CYAN
        line.contains("session") || line.contains("Session") -> MAGENTA
        line.startsWith("Backend:") || line.startsWith("Source:") || line.startsWith("Neuter") -> YELLOW
        line.startsWith("Bytes written") || line.startsWith("Size:") || line.startsWith("Files:") -> GRAY
        else -> null
    }
}
