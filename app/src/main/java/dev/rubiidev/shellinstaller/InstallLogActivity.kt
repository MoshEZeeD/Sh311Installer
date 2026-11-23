package dev.rubiidev.shellinstaller

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.rubiidev.shellinstaller.databinding.ActivityInstallLogBinding
import dev.rubiidev.shellinstaller.install.InstallManager
import dev.rubiidev.shellinstaller.install.Prefs
import dev.rubiidev.shellinstaller.util.LogFormatter
import kotlinx.coroutines.launch

class InstallLogActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LOG = "extra_log"
    }

    private lateinit var binding: ActivityInstallLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstallLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val initialLog = intent.getStringExtra(EXTRA_LOG) ?: InstallManager.logSnapshot()
        setLog(initialLog)
        binding.btnCloseLog.setOnClickListener { finish() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                InstallManager.state.collect { state ->
                    val log = when (state) {
                        is InstallManager.State.Installing -> InstallManager.logFlow.value
                        is InstallManager.State.Success -> state.log
                        is InstallManager.State.Failed -> state.log
                        else -> null
                    }
                    if (log != null && log.isNotBlank()) setLog(log)
                }
            }
        }
    }

    private fun setLog(log: String) {
        binding.tvFullLog.text = if (Prefs.terminalEffects(this)) LogFormatter.format(log) else log
        binding.root.post { binding.root.fullScroll(View.FOCUS_DOWN) }
    }
}
