package dev.rubiidev.shellinstaller.util

import android.os.Handler
import android.os.Looper

/**
 * Fast typewriter reveal for terminal logs: shows [source] progressively from the first
 * character onward. Shared by the install popup details and the full-screen log activity.
 * Re-feeding the same text is a no-op, so re-opening a finished log never replays.
 */
class LogReveal(
    private val onUpdate: (CharSequence) -> Unit,
    private val onScroll: () -> Unit = {}
) {

    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

    private var source = ""
    private var resolved = 0

    /** True while a log is loaded, even if the reveal already caught up. */
    val hasContent: Boolean get() = source.isNotEmpty()

    /** True when [text] is the same log with more content appended. */
    fun isContinuationOf(text: String): Boolean = source.isNotEmpty() && text.startsWith(source)

    fun setLog(text: String) {
        if (text == source) return
        if (text.length < resolved) resolved = 0
        source = text
        start()
    }

    fun cancel() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        source = ""
        resolved = 0
    }

    private fun start() {
        runnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val remaining = source.length - resolved
                if (remaining <= 0) {
                    resolved = source.length
                    push(glint = false)
                    return
                }
                resolved += (3 + remaining / 400).coerceAtMost(remaining)
                push()
                // Pause briefly after each completed line so lines appear one at a time
                // instead of streaming continuously.
                val delay = if (resolved > 0 && source[resolved - 1] == '\n') LINE_PAUSE_MS else TICK_MS
                handler.postDelayed(this, delay)
            }
        }
        runnable = r
        handler.post(r)
    }

    private fun push(glint: Boolean = true) {
        val shown = source.substring(0, resolved)
        onUpdate(LogFormatter.format(shown, if (glint) GLINT_TAIL else 0))
        onScroll()
    }

    companion object {
        private const val TICK_MS = 32L
        private const val LINE_PAUSE_MS = 160L
        private const val GLINT_TAIL = 16
    }
}
