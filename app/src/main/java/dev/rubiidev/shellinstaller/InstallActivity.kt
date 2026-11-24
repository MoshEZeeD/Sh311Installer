package dev.rubiidev.shellinstaller

import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.rubiidev.shellinstaller.backend.Backends
import dev.rubiidev.shellinstaller.databinding.ActivityInstallBinding
import dev.rubiidev.shellinstaller.install.ApkInfo
import dev.rubiidev.shellinstaller.install.ApkReportDialog
import dev.rubiidev.shellinstaller.install.InstallManager
import dev.rubiidev.shellinstaller.install.InstallOptionsDialog
import dev.rubiidev.shellinstaller.install.Prefs
import dev.rubiidev.shellinstaller.util.LogFormatter
import dev.rubiidev.shellinstaller.util.LogReveal
import dev.rubiidev.shellinstaller.util.formatSize
import dev.rubiidev.shellinstaller.util.versionCodeCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * System-installer-style popup, anchored to the bottom of the screen like Samsung's One UI
 * package installer. The activity uses a floating dialog theme and renders the install session
 * as a bottom sheet (Cancel / Install -> progress -> Done / Open) that slides up from the bottom.
 */
class InstallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "extra_uris"
        private const val MAX_SHEET_WIDTH_DP = 600
    }

    private lateinit var binding: ActivityInstallBinding

    private var detailsExpanded = false
    private var detailsAutoExpanded = false
    private var boundInfo: ApkInfo? = null
    private var installedInfo: PackageInfo? = null
    private lateinit var logReveal: LogReveal

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        logReveal = LogReveal(
            onUpdate = { binding.tvLog.text = it },
            onScroll = { binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) } }
        )
        setFinishOnTouchOutside(false)
        configureWindow()
        applyWindowInsets()
        setupButtons()
        setupLogCredit()
        observeState()
        observeLog()
        handleIntent(intent)

        onBackPressedDispatcher.addCallback(this) { handleBack() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun configureWindow() {
        val window = window ?: return
        // Draw behind the navigation bar so the sheet sits flush with the bottom edge,
        // then pad the content back out by the navigation-bar inset (see applyWindowInsets).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Match the nav bar to the sheet surface so the bottom edge reads as one continuous
        // panel (whether or not the window draws behind the bar on a given device).
        window.navigationBarColor =
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        applyGlassBackground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val metrics = resources.displayMetrics
        val maxWidth = (MAX_SHEET_WIDTH_DP * metrics.density).toInt()
        val width = min(metrics.widthPixels, maxWidth)
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
    }

    private fun applyGlassBackground() {
        val root = binding.root
        val surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface)
        val onSurface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface)
        val radius = 26f * resources.displayMetrics.density
        val glass = GradientDrawable()
        glass.cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        glass.setColor(ColorUtils.setAlphaComponent(surface, 216))
        glass.setStroke(1, ColorUtils.setAlphaComponent(onSurface, 36))
        root.background = glass
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
                top = baseTop,
                right = baseRight + bars.right,
                bottom = baseBottom + bars.bottom
            )
            insets
        }
    }

    private fun setupButtons() {
        binding.btnDetails.setOnClickListener {
            detailsExpanded = !detailsExpanded
            render(InstallManager.state.value)
        }
        binding.btnFullLog.setOnClickListener { openFullLog() }
        binding.btnApkReport.setOnClickListener {
            infoOf(InstallManager.state.value)?.let { ApkReportDialog.show(this, it) }
        }
        binding.btnUninstall.setOnClickListener { confirmUninstall() }
    }

    private fun setupLogCredit() {
        val full = getString(R.string.log_credit)
        val name = "MoshEZeeD"
        val start = full.lastIndexOf(name)
        if (start < 0) {
            binding.tvLogCredit.text = full
            return
        }
        val credit = SpannableString(full)
        val primary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        credit.setSpan(ForegroundColorSpan(primary), start, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        credit.setSpan(StyleSpan(Typeface.BOLD), start, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.tvLogCredit.text = credit
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                InstallManager.state.collect { render(it) }
            }
        }
    }

    private fun observeLog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                InstallManager.logFlow.collect { log ->
                    val state = InstallManager.state.value
                    if (state is InstallManager.State.Installing && detailsExpanded && Prefs.terminalEffects(this@InstallActivity)) {
                        setLogText(log, animate = true)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (::logReveal.isInitialized) logReveal.cancel()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent) {
        val uris = collectUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }
        boundInfo = null
        installedInfo = null
        detailsExpanded = false
        detailsAutoExpanded = false
        cancelReveal()
        setupInstallOptions()
        InstallManager.prepare(this, uris)
    }

    @Suppress("DEPRECATION")
    private fun collectUris(intent: Intent): List<Uri> {
        val list = ArrayList<Uri>()
        intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS)?.let { list.addAll(it) }
        intent.data?.let { list.add(it) }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) list.add(clip.getItemAt(i).uri)
        }
        if (intent.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.add(it) }
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.addAll(it) }
        }
        return list.distinct()
    }

    private fun render(state: InstallManager.State) {
        val info = infoOf(state)
        if (info != null && (info !== boundInfo || state !is InstallManager.State.Installing)) {
            bindAppInfo(info)
        } else if (info == null) {
            boundInfo = null
            installedInfo = null
            binding.tvAppLabel.setText(R.string.app_name)
            binding.tvPackageName.visibility = View.GONE
            binding.tvVersion.visibility = View.GONE
            binding.ivIcon.setImageResource(R.mipmap.ic_launcher)
        }

        val showInfo = info != null
        binding.tvPackageName.visibility = if (showInfo) View.VISIBLE else View.GONE
        binding.tvVersion.visibility = if (showInfo) View.VISIBLE else View.GONE
        binding.tvInstalled.visibility =
            if (showInfo && state is InstallManager.State.Ready) View.VISIBLE else View.GONE
        val showMeta = showInfo && when (state) {
            is InstallManager.State.Ready,
            is InstallManager.State.Installing,
            is InstallManager.State.Success,
            is InstallManager.State.Failed -> true
            else -> false
        }
        binding.tvMeta.visibility = if (showMeta) View.VISIBLE else View.GONE
        binding.tvBackendLine.visibility = binding.tvMeta.visibility

        when (state) {
            is InstallManager.State.Preparing -> {
                binding.tvQuestion.visibility = View.GONE
                binding.tvError.visibility = View.GONE
                showProgress(indeterminate = true, status = state.message, progress = 0f)
                setButtons(negative = R.string.cancel to { InstallManager.reset(); finish() }, positive = null)
            }
            is InstallManager.State.Ready -> {
                binding.tvQuestion.visibility = View.VISIBLE
                binding.tvQuestion.text = questionFor(info!!)
                binding.tvError.visibility = View.GONE
                hideProgress()
                setButtons(
                    negative = R.string.cancel to { InstallManager.reset(); finish() },
                    positive = R.string.install to { InstallManager.install(this) }
                )
            }
            is InstallManager.State.Installing -> {
                binding.tvQuestion.visibility = View.VISIBLE
                binding.tvQuestion.setText(R.string.installing_app)
                binding.tvError.visibility = View.GONE
                showProgress(indeterminate = false, status = state.status, progress = state.progress)
                setButtons(negative = R.string.cancel to { InstallManager.cancel() }, positive = null)
            }
            is InstallManager.State.Success -> {
                binding.tvQuestion.visibility = View.VISIBLE
                binding.tvQuestion.setText(R.string.install_success_popup)
                binding.tvError.visibility = View.GONE
                showResult(true, getString(R.string.install_success))
                setButtons(
                    negative = R.string.done to { InstallManager.reset(); finish() },
                    positive = R.string.open_app to { openInstalledApp() }
                )
            }
            is InstallManager.State.Failed -> {
                binding.tvQuestion.visibility = View.VISIBLE
                binding.tvQuestion.setText(R.string.install_failed_popup)
                binding.tvError.visibility = View.VISIBLE
                binding.tvError.text = state.message
                showResult(false, getString(R.string.install_failed))
                setButtons(
                    negative = R.string.close to { InstallManager.reset(); finish() },
                    positive = if (state.info != null) R.string.retry to { InstallManager.install(this) } else null
                )
            }
            InstallManager.State.Idle -> {
                binding.tvQuestion.visibility = View.GONE
                binding.tvError.visibility = View.GONE
                hideProgress()
                setButtons(negative = null, positive = null)
            }
        }

        renderDetails(state)
        renderUninstall(state, info)
        renderInstallOptions(state)
    }

    private fun bindAppInfo(info: ApkInfo) {
        boundInfo = info
        binding.tvAppLabel.text = info.label
        binding.tvPackageName.text = info.packageName ?: "—"
        binding.tvVersion.text = "${info.versionName ?: "?"} (${info.versionCode})"
        if (info.icon != null) {
            binding.ivIcon.setImageDrawable(info.icon)
        } else {
            binding.ivIcon.setImageResource(R.mipmap.ic_launcher)
        }

        installedInfo = info.packageName?.let { pkg ->
            try {
                packageManager.getPackageInfo(pkg, 0)
            } catch (e: Exception) {
                null
            }
        }
        binding.tvInstalled.text = when {
            installedInfo == null -> getString(R.string.not_installed)
            installedInfo!!.versionCodeCompat() < info.versionCode ->
                getString(R.string.update_available, installedInfo!!.versionName ?: "?", info.versionName ?: "?")
            installedInfo!!.versionCodeCompat() > info.versionCode ->
                getString(R.string.downgrade_warning, installedInfo!!.versionName ?: "?", info.versionName ?: "?")
            else -> getString(R.string.same_version, info.versionName ?: "?")
        }

        val splitText = if (info.splitCount > 1) {
            getString(R.string.splits_count, info.splitCount)
        } else {
            getString(R.string.single_apk)
        }
        binding.tvMeta.text = listOf(
            formatSize(info.totalSize),
            splitText,
            getString(R.string.sdk_range, info.minSdk, info.targetSdk)
        ).joinToString(" · ")

        val backend = Backends.readyBackendOrNull(this)
        binding.tvBackendLine.text = getString(
            R.string.backend_line,
            backend?.displayName ?: getString(R.string.no_backend_short)
        )
    }

    private fun questionFor(info: ApkInfo): String {
        val installed = installedInfo
        return when {
            installed == null -> getString(R.string.install_question)
            installed.versionCodeCompat() < info.versionCode -> getString(R.string.update_question)
            installed.versionCodeCompat() > info.versionCode -> getString(R.string.downgrade_question)
            else -> getString(R.string.same_version_question)
        }
    }

    private fun showProgress(indeterminate: Boolean, status: String, progress: Float) {
        binding.progressSection.visibility = View.VISIBLE
        binding.progress.isIndeterminate = indeterminate
        if (!indeterminate) {
            binding.progress.progress = (progress * 1000).toInt()
        }
        binding.tvInstallStatus.text = status
    }

    private fun showResult(success: Boolean, status: String) {
        binding.progressSection.visibility = View.VISIBLE
        binding.progress.isIndeterminate = false
        binding.progress.progress = if (success) 1000 else 0
        binding.tvInstallStatus.text = status
    }

    private fun hideProgress() {
        binding.progressSection.visibility = View.GONE
    }

    private fun setButtons(negative: Pair<Int, () -> Unit>?, positive: Pair<Int, () -> Unit>?) {
        bindButton(binding.btnNegative, negative)
        bindButton(binding.btnPositive, positive)
    }

    private fun bindButton(button: MaterialButton, spec: Pair<Int, () -> Unit>?) {
        if (spec == null) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
        } else {
            button.visibility = View.VISIBLE
            button.setText(spec.first)
            button.setOnClickListener { spec.second.invoke() }
        }
    }

    private fun renderDetails(state: InstallManager.State) {
        val logText = when (state) {
            is InstallManager.State.Installing -> InstallManager.logFlow.value
            is InstallManager.State.Success -> state.log
            is InstallManager.State.Failed -> state.log
            else -> ""
        }
        val hasLog = logText.isNotBlank()
        val isTerminal = state is InstallManager.State.Installing ||
            state is InstallManager.State.Success ||
            state is InstallManager.State.Failed
        if (hasLog && isTerminal && !detailsAutoExpanded) {
            detailsExpanded = true
            detailsAutoExpanded = true
        }
        binding.btnFullLog.visibility = if (hasLog) View.VISIBLE else View.GONE
        binding.btnDetails.visibility = if (hasLog) View.VISIBLE else View.GONE
        binding.btnDetails.setText(if (detailsExpanded) R.string.hide_details else R.string.details)
        binding.btnDetails.setIconResource(if (detailsExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        binding.detailsSection.visibility = if (hasLog && detailsExpanded) View.VISIBLE else View.GONE
        if (hasLog && detailsExpanded) {
            setLogText(logText, animate = state is InstallManager.State.Installing)
        }
    }

    private fun setLogText(logText: String, animate: Boolean) {
        val fx = Prefs.terminalEffects(this)
        if (!fx) {
            logReveal.cancel()
            binding.tvLog.text = logText
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
            return
        }
        // Keep typing while installing, and keep typing the tail into Success/Failed
        // instead of snapping the full log in at once. Only show instantly when this is
        // a fresh view of an already-finished log.
        if (animate || logReveal.isContinuationOf(logText)) {
            logReveal.setLog(logText)
        } else {
            logReveal.cancel()
            binding.tvLog.text = LogFormatter.format(logText)
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun cancelReveal() {
        logReveal.cancel()
    }

    private fun renderUninstall(state: InstallManager.State, info: ApkInfo?) {
        val canUninstall = info?.packageName != null && installedInfo != null &&
            (state is InstallManager.State.Ready || state is InstallManager.State.Failed)
        binding.btnUninstall.visibility = if (canUninstall) View.VISIBLE else View.GONE
    }

    private fun renderInstallOptions(state: InstallManager.State) {
        binding.installOptionsSection.visibility = if (state is InstallManager.State.Ready) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.btnApkReport.visibility = if (infoOf(state) != null) View.VISIBLE else View.GONE
    }

    private fun setupInstallOptions() {
        val options = Prefs.options(this)
        binding.tvInstallOptions.setText(summarizeInstallOptions(options))
        binding.installOptionsSection.setOnClickListener {
            InstallOptionsDialog.show(this, Prefs.options(this)) { saved ->
                Prefs.saveOptions(this, saved)
                binding.tvInstallOptions.setText(summarizeInstallOptions(saved))
            }
        }
        binding.tvInstallOptions.setOnClickListener {
            binding.installOptionsSection.performClick()
        }
    }

    private fun summarizeInstallOptions(o: Prefs.Options): String {
        var count = 0
        count += listOf(
            o.allowDowngrade, o.grantAll, o.allowTestOnly, o.dontKillApp, o.restrictPermissions,
            o.updateOwnership, o.neuterAds, o.skipVerification, o.forceQueryable,
            o.ignoreDexoptProfile, o.disableAutoInstallDependencies, o.enableRollback, o.apex,
        ).count { it }
        count += listOf(
            o.targetUser != Prefs.TargetUser.CURRENT,
            o.installLocation != Prefs.InstallLocation.UNSPECIFIED,
            o.installReason != Prefs.InstallReason.UNSPECIFIED,
            o.packageSource != Prefs.PackageSource.UNSPECIFIED,
            o.installMode != Prefs.InstallMode.FULL,
            o.stagedMode != Prefs.StagedMode.DEFAULT,
            o.installerPackage != null,
            o.originatingUri != null,
            o.referrerUri != null,
            o.forceUuid != null,
            o.expectedPackage != null,
            o.abiOverride != null,
            o.dexoptCompilerFilter != null,
            o.partialInheritPackage != null,
        ).count { it }
        return if (count == 0) getString(R.string.no_extra_options) else getString(R.string.extra_options_count, count)
    }

    private fun openFullLog() {
        val state = InstallManager.state.value
        val logText = when (state) {
            is InstallManager.State.Installing -> InstallManager.logFlow.value
            is InstallManager.State.Success -> state.log
            is InstallManager.State.Failed -> state.log
            else -> return
        }
        if (logText.isBlank()) return
        startActivity(Intent(this, InstallLogActivity::class.java).putExtra(InstallLogActivity.EXTRA_LOG, logText))
    }

    private fun infoOf(state: InstallManager.State): ApkInfo? = when (state) {
        is InstallManager.State.Ready -> state.info
        is InstallManager.State.Installing -> state.info
        is InstallManager.State.Success -> state.info
        is InstallManager.State.Failed -> state.info
        else -> null
    }

    private fun handleBack() {
        when (InstallManager.state.value) {
            is InstallManager.State.Installing -> InstallManager.cancel()
            else -> {
                InstallManager.reset()
                finish()
            }
        }
    }

    private fun openInstalledApp() {
        val packageName = (InstallManager.state.value as? InstallManager.State.Success)?.packageName
            ?: return
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            InstallManager.reset()
            finish()
        } else {
            toast(getString(R.string.not_installed))
        }
    }

    private fun confirmUninstall() {
        val info = infoOf(InstallManager.state.value) ?: return
        val packageName = info.packageName ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.uninstall_confirm_title, info.label))
            .setMessage(R.string.uninstall_confirm_msg)
            .setPositiveButton(R.string.uninstall) { _, _ -> doUninstall(packageName) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doUninstall(packageName: String) {
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val backend = Backends.resolve(this@InstallActivity)
                    val result = backend.exec(listOf("pm", "uninstall", "--user", "current", packageName))
                    if (result.looksSuccessful) getString(R.string.uninstalled) else result.output.trim()
                } catch (t: Throwable) {
                    t.message ?: "Uninstall failed"
                }
            }
            toast(message)
            boundInfo = null
            installedInfo = null
            render(InstallManager.state.value)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
