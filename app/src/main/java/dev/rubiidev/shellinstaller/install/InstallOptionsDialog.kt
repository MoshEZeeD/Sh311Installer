package dev.rubiidev.shellinstaller.install

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Programmatic pre-install options editor covering pm flags, source metadata and experimental modes. */
object InstallOptionsDialog {

    fun show(context: Context, initial: Prefs.Options, onSave: (Prefs.Options) -> Unit) {
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, 18)
            setPadding(pad, dp(context, 8), pad, pad)
        }
        scroll.addView(root)

        root.addView(TextView(context).apply {
            text = "Applied to the pm install session. Flags unsupported by this device are skipped automatically."
            textSize = 12f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })

        // ---- Core flags ----
        section(root, context, "Core install flags")
        var allowTestOnly = initial.allowTestOnly
        var allowDowngrade = initial.allowDowngrade
        var grantAll = initial.grantAll
        var bypassLowTargetSdk = initial.bypassLowTargetSdk
        var dontKillApp = initial.dontKillApp
        var restrictPermissions = initial.restrictPermissions
        var updateOwnership = initial.updateOwnership
        var neuterAds = initial.neuterAds

        switchRow(root, context, "Allow test-only packages (-t)", allowTestOnly) { allowTestOnly = it }
        switchRow(root, context, "Allow downgrade (-d)", allowDowngrade) { allowDowngrade = it }
        switchRow(root, context, "Grant all runtime permissions (-g)", grantAll) { grantAll = it }
        switchRow(root, context, "Bypass low-target-SDK block", bypassLowTargetSdk) { bypassLowTargetSdk = it }
        switchRow(root, context, "Do not kill running app (--dont-kill)", dontKillApp) { dontKillApp = it }
        switchRow(root, context, "Restrict permissions (--restrict-permissions)", restrictPermissions) { restrictPermissions = it }
        switchRow(root, context, "Request update ownership (--update-ownership)", updateOwnership) { updateOwnership = it }
        switchRow(root, context, "Ad/tracker neuter after install (experimental)", neuterAds) { neuterAds = it }

        // ---- Install source / metadata ----
        section(root, context, "Install source & metadata")

        val installerLabels = arrayOf(
            "Shell Installer (default)", "Google Play", "Galaxy Store", "AppGallery", "Amazon Appstore", "Custom package…",
        )
        val installerPackages = arrayOf<String?>(null, "com.android.vending", "com.sec.android.app.samsungapps", "com.huawei.appmarket", "com.amazon.venezia", null)
        var installerIndex = when (initial.installerPackage) {
            null -> 0
            "com.android.vending" -> 1
            "com.sec.android.app.samsungapps" -> 2
            "com.huawei.appmarket" -> 3
            "com.amazon.venezia" -> 4
            else -> 5
        }

        val reasonLabels = arrayOf("Unspecified", "Unknown", "Admin policy", "Device restore", "Device setup", "User request")
        var installReasonIndex = initial.installReason.ordinal

        val sourceLabels = arrayOf("Unspecified", "Other", "Store", "Local file", "Downloaded file")
        var packageSourceIndex = when (initial.packageSource) {
            Prefs.PackageSource.UNSPECIFIED -> 0
            Prefs.PackageSource.OTHER -> 1
            Prefs.PackageSource.STORE -> 2
            Prefs.PackageSource.LOCAL_FILE -> 3
            Prefs.PackageSource.DOWNLOADED_FILE -> 4
        }

        lateinit var installerView: MaterialAutoCompleteTextView
        lateinit var reasonView: MaterialAutoCompleteTextView
        lateinit var sourceView: MaterialAutoCompleteTextView
        lateinit var originatingView: TextInputEditText
        lateinit var referrerView: TextInputEditText

        // Quick presets that fill installer package + package source + reason + store URI in one tap.
        val presetLabels = arrayOf(
            "Custom / manual",
            "Google Play",
            "Galaxy Store",
            "AppGallery",
            "Amazon Appstore",
            "Local file (sideload)",
        )
        val initialPreset = when {
            initial.installerPackage == "com.android.vending" && initial.packageSource == Prefs.PackageSource.STORE -> 1
            initial.installerPackage == "com.sec.android.app.samsungapps" && initial.packageSource == Prefs.PackageSource.STORE -> 2
            initial.installerPackage == "com.huawei.appmarket" && initial.packageSource == Prefs.PackageSource.STORE -> 3
            initial.installerPackage == "com.amazon.venezia" && initial.packageSource == Prefs.PackageSource.STORE -> 4
            initial.packageSource == Prefs.PackageSource.LOCAL_FILE -> 5
            else -> 0
        }
        dropdown(root, context, "Source preset", presetLabels, initialPreset) { preset ->
            if (preset == 0) return@dropdown
            val (inst, src, reason, uri) = when (preset) {
                1 -> Quad(1, 2, 5, "https://play.google.com/store/apps/details?id=")
                2 -> Quad(2, 2, 5, "https://galaxystore.samsung.com/detail/")
                3 -> Quad(3, 2, 5, "https://appgallery.huawei.com/#/app/")
                4 -> Quad(4, 2, 5, "https://www.amazon.com/gp/product/")
                else -> Quad(0, 3, 5, "")
            }
            installerIndex = inst
            packageSourceIndex = src
            installReasonIndex = reason
            installerView.setText(installerLabels[installerIndex], false)
            sourceView.setText(sourceLabels[packageSourceIndex], false)
            reasonView.setText(reasonLabels[installReasonIndex], false)
            originatingView.setText(uri)
            referrerView.setText("")
        }

        installerView = dropdown(root, context, "Installer package (-i)", installerLabels, installerIndex) { installerIndex = it }
        val customInstaller = edit(root, context, "Custom installer package name", initial.installerPackage ?: "")
        reasonView = dropdown(root, context, "Install reason (--install-reason)", reasonLabels, installReasonIndex) { installReasonIndex = it }
        sourceView = dropdown(root, context, "Package source (--package-source)", sourceLabels, packageSourceIndex) { packageSourceIndex = it }
        originatingView = edit(root, context, "Originating URI (--originating-uri)", initial.originatingUri ?: "")
        referrerView = edit(root, context, "Referrer URI (--referrer)", initial.referrerUri ?: "")

        // ---- User & location ----
        section(root, context, "User & location")
        val userLabels = arrayOf("Current user", "All users", "Owner", "Custom user id")
        var targetUserIndex = when (initial.targetUser) {
            Prefs.TargetUser.CURRENT -> 0
            Prefs.TargetUser.ALL -> 1
            Prefs.TargetUser.OWNER -> 2
            Prefs.TargetUser.CUSTOM -> 3
        }
        dropdown(root, context, "Target user (--user)", userLabels, targetUserIndex) { targetUserIndex = it }
        val customUserId = edit(root, context, "Custom user id", initial.customUserId?.toString() ?: "")

        val locationLabels = arrayOf("System default", "Auto", "Internal only", "Prefer external")
        var locationIndex = when (initial.installLocation) {
            Prefs.InstallLocation.UNSPECIFIED -> 0
            Prefs.InstallLocation.AUTO -> 1
            Prefs.InstallLocation.INTERNAL -> 2
            Prefs.InstallLocation.EXTERNAL -> 3
        }
        dropdown(root, context, "Install location (--install-location)", locationLabels, locationIndex) { locationIndex = it }
        val forceUuid = edit(root, context, "Force volume UUID (--force-uuid, e.g. internal)", initial.forceUuid ?: "")
        val expectedPackage = edit(root, context, "Expected package (--pkg)", initial.expectedPackage ?: "")

        // ---- Experimental ----
        section(root, context, "Experimental / advanced")
        val modeLabels = arrayOf("Full app", "Instant app", "Preload")
        var installModeIndex = when (initial.installMode) {
            Prefs.InstallMode.FULL -> 0
            Prefs.InstallMode.INSTANT -> 1
            Prefs.InstallMode.PRELOAD -> 2
        }
        dropdown(root, context, "Install mode", modeLabels, installModeIndex) { installModeIndex = it }

        val stagedLabels = arrayOf("Default", "Staged", "Non-staged", "Force non-staged")
        var stagedIndex = when (initial.stagedMode) {
            Prefs.StagedMode.DEFAULT -> 0
            Prefs.StagedMode.STAGED -> 1
            Prefs.StagedMode.NON_STAGED -> 2
            Prefs.StagedMode.FORCE_NON_STAGED -> 3
        }
        dropdown(root, context, "Staged mode", stagedLabels, stagedIndex) { stagedIndex = it }
        val stagedTimeout = edit(root, context, "Staged ready timeout ms", initial.stagedReadyTimeoutMs?.toString() ?: "")

        var enableRollback = initial.enableRollback
        var apex = initial.apex
        var skipVerification = initial.skipVerification
        var forceQueryable = initial.forceQueryable
        var ignoreDexoptProfile = initial.ignoreDexoptProfile
        var disableAutoDeps = initial.disableAutoInstallDependencies
        switchRow(root, context, "Enable rollback (--enable-rollback)", enableRollback) { enableRollback = it }
        val rollbackLabels = arrayOf("Restore data", "Wipe data", "Retain data")
        var rollbackIndex = initial.rollbackPolicy.ordinal
        dropdown(root, context, "Rollback data policy", rollbackLabels, rollbackIndex) { rollbackIndex = it }
        switchRow(root, context, "APEX package (--apex)", apex) { apex = it }
        switchRow(root, context, "Skip verification (--skip-verification)", skipVerification) { skipVerification = it }
        switchRow(root, context, "Force queryable (--force-queryable)", forceQueryable) { forceQueryable = it }
        switchRow(root, context, "Ignore dexopt profile (--ignore-dexopt-profile)", ignoreDexoptProfile) { ignoreDexoptProfile = it }
        switchRow(root, context, "Disable auto install dependencies", disableAutoDeps) { disableAutoDeps = it }

        val dexoptLabels = arrayOf("Default", "verify", "speed-profile", "speed", "everything", "skip")
        val dexoptValues = arrayOf<String?>(null, "verify", "speed-profile", "speed", "everything", "skip")
        var dexoptIndex = dexoptValues.indexOf(initial.dexoptCompilerFilter).coerceAtLeast(0)
        dropdown(root, context, "Dexopt compiler filter", dexoptLabels, dexoptIndex) { dexoptIndex = it }
        val abiOverride = edit(root, context, "ABI override (--abi, e.g. arm64-v8a)", initial.abiOverride ?: "")
        val partialInherit = edit(root, context, "Inherit package (-p)", initial.partialInheritPackage ?: "")

        MaterialAlertDialogBuilder(context)
            .setTitle("Install options")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val targetUser = when (targetUserIndex) {
                    1 -> Prefs.TargetUser.ALL
                    2 -> Prefs.TargetUser.OWNER
                    3 -> Prefs.TargetUser.CUSTOM
                    else -> Prefs.TargetUser.CURRENT
                }
                val installerPackage = if (installerIndex == 5) {
                    customInstaller.text?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: initial.installerPackage
                } else {
                    installerPackages.getOrNull(installerIndex)
                }
                onSave(
                    initial.copy(
                        allowDowngrade = allowDowngrade,
                        grantAll = grantAll,
                        bypassLowTargetSdk = bypassLowTargetSdk,
                        allowTestOnly = allowTestOnly,
                        dontKillApp = dontKillApp,
                        restrictPermissions = restrictPermissions,
                        updateOwnership = updateOwnership,
                        neuterAds = neuterAds,
                        targetUser = targetUser,
                        customUserId = customUserId.text?.toString()?.trim()?.toIntOrNull(),
                        installLocation = when (locationIndex) {
                            1 -> Prefs.InstallLocation.AUTO
                            2 -> Prefs.InstallLocation.INTERNAL
                            3 -> Prefs.InstallLocation.EXTERNAL
                            else -> Prefs.InstallLocation.UNSPECIFIED
                        },
                        forceUuid = forceUuid.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                        expectedPackage = expectedPackage.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                        installerPackage = installerPackage,
                        originatingUri = originatingView.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                        referrerUri = referrerView.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                        installReason = Prefs.InstallReason.entries.getOrNull(installReasonIndex) ?: Prefs.InstallReason.UNSPECIFIED,
                        packageSource = when (packageSourceIndex) {
                            1 -> Prefs.PackageSource.OTHER
                            2 -> Prefs.PackageSource.STORE
                            3 -> Prefs.PackageSource.LOCAL_FILE
                            4 -> Prefs.PackageSource.DOWNLOADED_FILE
                            else -> Prefs.PackageSource.UNSPECIFIED
                        },
                        installMode = when (installModeIndex) {
                            1 -> Prefs.InstallMode.INSTANT
                            2 -> Prefs.InstallMode.PRELOAD
                            else -> Prefs.InstallMode.FULL
                        },
                        stagedMode = when (stagedIndex) {
                            1 -> Prefs.StagedMode.STAGED
                            2 -> Prefs.StagedMode.NON_STAGED
                            3 -> Prefs.StagedMode.FORCE_NON_STAGED
                            else -> Prefs.StagedMode.DEFAULT
                        },
                        stagedReadyTimeoutMs = stagedTimeout.text?.toString()?.trim()?.toIntOrNull(),
                        enableRollback = enableRollback,
                        rollbackPolicy = Prefs.RollbackPolicy.entries.getOrNull(rollbackIndex) ?: Prefs.RollbackPolicy.RESTORE,
                        apex = apex,
                        abiOverride = abiOverride.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                        skipVerification = skipVerification,
                        forceQueryable = forceQueryable,
                        ignoreDexoptProfile = ignoreDexoptProfile,
                        dexoptCompilerFilter = dexoptValues.getOrNull(dexoptIndex),
                        disableAutoInstallDependencies = disableAutoDeps,
                        partialInheritPackage = partialInherit.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private data class Quad(val installer: Int, val source: Int, val reason: Int, val uri: String)

    private fun section(root: LinearLayout, context: Context, title: String) {
        root.addView(TextView(context).apply {
            text = title.uppercase()
            textSize = 12f
            letterSpacing = 0.08f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
            setPadding(0, dp(context, 16), 0, dp(context, 6))
        })
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)
            ).apply { bottomMargin = dp(context, 6) }
            setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant))
        })
    }

    private fun switchRow(root: LinearLayout, context: Context, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = MaterialSwitch(context).apply {
            text = label
            textSize = 14f
            this.isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        root.addView(sw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 2)
        })
    }

    private fun edit(root: LinearLayout, context: Context, hint: String, value: String): TextInputEditText {
        val layout = TextInputLayout(context).apply { this.hint = hint }
        val edit = TextInputEditText(layout.context).apply {
            setText(value)
            isSingleLine = true
            textSize = 14f
        }
        layout.addView(edit)
        root.addView(layout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 4)
        })
        return edit
    }

    private fun dropdown(
        root: LinearLayout,
        context: Context,
        label: String,
        items: Array<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ): MaterialAutoCompleteTextView {
        val layout = TextInputLayout(
            context, null,
            com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox_ExposedDropdownMenu
        ).apply {
            this.hint = label
            isExpandedHintEnabled = false
        }
        val view = MaterialAutoCompleteTextView(layout.context).apply {
            setText(items.getOrElse(selected) { items.first() }, false)
            inputType = InputType.TYPE_NULL
            setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, items.toList()))
            setOnItemClickListener { _, _, position, _ -> onSelect(position) }
            setOnClickListener { if (!isPopupShowing) showDropDown() }
        }
        layout.addView(view)
        root.addView(layout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 4)
        })
        return view
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
