package dev.rubiidev.shellinstaller

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.rubiidev.shellinstaller.databinding.ActivityInstallLogBinding
import dev.rubiidev.shellinstaller.install.InstallManager
import dev.rubiidev.shellinstaller.install.Prefs
import dev.rubiidev.shellinstaller.util.LogFormatter
import dev.rubiidev.shellinstaller.util.LogReveal
import kotlinx.coroutines.launch

class InstallLogActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LOG = "extra_log"
    }

    private lateinit var binding: ActivityInstallLogBinding
    private lateinit var logReveal: LogReveal

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstallLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        logReveal = LogReveal(
            onUpdate = { binding.tvFullLog.text = it },
            onScroll = { binding.root.post { binding.root.fullScroll(View.FOCUS_DOWN) } }
        )
        applyWindowInsets()
        val initialLog = intent.getStringExtra(EXTRA_LOG) ?: InstallManager.logSnapshot()
        setLog(initialLog)
        binding.btnCloseLog.setOnClickListener { finish() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    InstallManager.logFlow.collect { log ->
                        if (InstallManager.state.value is InstallManager.State.Installing) {
                            setLog(log)
                        }
                    }
                }
                InstallManager.state.collect { state ->
                    val log = when (state) {
                        is InstallManager.State.Installing -> InstallManager.logFlow.value
                        is InstallManager.State.Success -> state.log
                        is InstallManager.State.Failed -> state.log
                        else -> null
                    }
                    if (log != null) setLog(log)
                }
            }
        }
    }

    override fun onDestroy() {
        if (::logReveal.isInitialized) logReveal.cancel()
        super.onDestroy()
    }

    private fun applyWindowInsets() {
        val root = binding.root
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = baseLeft + bars.left,
                top = baseTop + bars.top,
                right = baseRight + bars.right,
                bottom = baseBottom + bars.bottom
            )
            insets
        }
    }

    private fun setLog(log: String) {
        val fx = Prefs.terminalEffects(this)
        val installing = InstallManager.state.value is InstallManager.State.Installing
        // Animate while installing, and keep typing the tail into the final state.
        // A fresh view of a finished log shows instantly instead of replaying.
        val animating = fx && (installing || logReveal.isContinuationOf(log))
        if (animating) {
            logReveal.setLog(log)
        } else {
            logReveal.cancel()
            binding.tvFullLog.text = if (fx) LogFormatter.format(log) else log
            binding.root.post { binding.root.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
