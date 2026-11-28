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
    private val onScroll: () -> Unit = {},
    private val onDone: () -> Unit = {}
) {

    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

    private var source = ""
    private var resolved = 0
    private var hurry = false

    /** True while a log is loaded, even if the reveal already caught up. */
    val hasContent: Boolean get() = source.isNotEmpty()

    /** True when [text] is the same log with more content appended. */
    fun isContinuationOf(text: String): Boolean = source.isNotEmpty() && text.startsWith(source)

    /**
     * [hurry] speeds the reveal up so the tail catches up with an already-finished install
     * instead of lagging behind the Done button. Still reveals per character, just faster.
     */
    fun setLog(text: String, hurry: Boolean = false) {
        this.hurry = hurry
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
                    onDone()
                    return
                }
                resolved += if (hurry) {
                    (10 + remaining / 80).coerceAtMost(remaining)
                } else {
                    (3 + remaining / 400).coerceAtMost(remaining)
                }
                push()
                // Pause briefly after each completed line so lines appear one at a time
                // instead of streaming continuously.
                val delay = if (resolved > 0 && source[resolved - 1] == '\n') {
                    if (hurry) HURRY_LINE_PAUSE_MS else LINE_PAUSE_MS
                } else {
                    if (hurry) HURRY_TICK_MS else TICK_MS
                }
                handler.postDelayed(this, delay)
            }
        }
        runnable = r
        handler.post(r)
    }

    private var pushes = 0

    private fun push(glint: Boolean = true) {
        val shown = source.substring(0, resolved)
        // No glint while hurrying — the bright band strobes too fast at 8ms ticks.
        onUpdate(LogFormatter.format(shown, if (glint && !hurry) GLINT_TAIL else 0))
        // Scrolling every tick makes the view jump once per frame — throttle it and
        // always scroll on the final (glint-free) push so the end state is visible.
        if (!glint || ++pushes % SCROLL_EVERY == 0) onScroll()
    }

    companion object {
        private const val TICK_MS = 32L
        private const val LINE_PAUSE_MS = 160L
        private const val HURRY_TICK_MS = 8L
        private const val HURRY_LINE_PAUSE_MS = 40L
        private const val GLINT_TAIL = 16
        private const val SCROLL_EVERY = 4
    }
}
