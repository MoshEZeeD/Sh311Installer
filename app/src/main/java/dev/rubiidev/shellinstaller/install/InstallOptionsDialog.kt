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

/**
 * Simplified pre-install options: an install-source preset picker (store spoofing) plus the
 * essential pm flags. Rarely used experimental flags keep their saved values but are not
 * shown here.
 */
object InstallOptionsDialog {

    private data class SourcePreset(
        val label: String,
        val installerPackage: String?,
        val installerHint: String,
        val packageSource: Prefs.PackageSource,
        val installReason: Prefs.InstallReason,
        val originatingUri: String,
    )

    private val PRESETS = listOf(
        SourcePreset("None (default)", null, "this app (default)", Prefs.PackageSource.UNSPECIFIED, Prefs.InstallReason.UNSPECIFIED, ""),
        SourcePreset("Google Play", "com.android.vending", "com.android.vending", Prefs.PackageSource.STORE, Prefs.InstallReason.USER, "https://play.google.com/store/apps/details?id="),
        SourcePreset("Galaxy Store", "com.sec.android.app.samsungapps", "com.sec.android.app.samsungapps", Prefs.PackageSource.STORE, Prefs.InstallReason.USER, "https://galaxystore.samsung.com/detail/"),
        SourcePreset("AppGallery", "com.huawei.appmarket", "com.huawei.appmarket", Prefs.PackageSource.STORE, Prefs.InstallReason.USER, "https://appgallery.huawei.com/#/app/"),
        SourcePreset("Amazon Appstore", "com.amazon.venezia", "com.amazon.venezia", Prefs.PackageSource.STORE, Prefs.InstallReason.USER, "https://www.amazon.com/gp/product/"),
        SourcePreset("Local file (sideload)", null, "this app (default)", Prefs.PackageSource.LOCAL_FILE, Prefs.InstallReason.USER, ""),
    )

    private val CUSTOM = PRESETS.size

    fun show(context: Context, initial: Prefs.Options, onSave: (Prefs.Options) -> Unit) {
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, 18)
            setPadding(pad, dp(context, 8), pad, pad)
        }
        scroll.addView(root)

        root.addView(TextView(context).apply {
            text = "Choose where the install should appear to come from, then tweak the essentials."
            textSize = 12f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })

        // ---- Install source ----
        section(root, context, "Install source")

        val presetLabels = PRESETS.map { it.label }.toTypedArray() + "Custom / manual"
        val initialPreset = PRESETS.indexOfFirst {
            it.installerPackage == initial.installerPackage && it.packageSource == initial.packageSource
        }.let { if (it >= 0) it else CUSTOM }

        var installerPackage: String? = initial.installerPackage
        var packageSource = initial.packageSource
        var installReason = initial.installReason
        var originatingUri: String? = initial.originatingUri
        var referrerUri: String? = initial.referrerUri

        val installerHint = TextView(context).apply {
            textSize = 12f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        fun updateHint(presetIndex: Int) {
            installerHint.text = if (presetIndex == CUSTOM) {
                "Installer package: ${installerPackage ?: "this app (default)"}"
            } else {
                "Installer package: ${PRESETS[presetIndex].installerHint}"
            }
        }
        updateHint(initialPreset)

        // Custom fields, revealed only for the manual preset.
        val customBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initialPreset == CUSTOM) View.VISIBLE else View.GONE
        }

        val sourceLabels = arrayOf("Unspecified", "Other", "Store", "Local file", "Downloaded file")
        val reasonLabels = arrayOf("Unspecified", "Unknown", "Admin policy", "Device restore", "Device setup", "User request")
        val sourceValues = Prefs.PackageSource.entries.toTypedArray()
        val reasonValues = Prefs.InstallReason.entries.toTypedArray()

        val customInstaller = edit(customBox, context, "Installer package name (blank = this app)", initial.installerPackage ?: "")
        var customSourceIndex = sourceValues.indexOf(initial.packageSource).coerceAtLeast(0)
        var customReasonIndex = reasonValues.indexOf(initial.installReason).coerceAtLeast(0)
        dropdown(customBox, context, "Package source", sourceLabels, customSourceIndex) {
            customSourceIndex = it
            packageSource = sourceValues.getOrElse(it) { Prefs.PackageSource.UNSPECIFIED }
        }
        dropdown(customBox, context, "Install reason", reasonLabels, customReasonIndex) {
            customReasonIndex = it
            installReason = reasonValues.getOrElse(it) { Prefs.InstallReason.UNSPECIFIED }
        }
        val customOrigin = edit(customBox, context, "Originating URI (optional)", initial.originatingUri ?: "")
        val customReferrer = edit(customBox, context, "Referrer URI (optional)", initial.referrerUri ?: "")

        var presetIndex = initialPreset

        fun applyPreset(index: Int) {
            presetIndex = index
            if (index == CUSTOM) {
                customBox.visibility = View.VISIBLE
                installerPackage = customInstaller.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                packageSource = sourceValues.getOrElse(customSourceIndex) { Prefs.PackageSource.UNSPECIFIED }
                installReason = reasonValues.getOrElse(customReasonIndex) { Prefs.InstallReason.UNSPECIFIED }
                originatingUri = customOrigin.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                referrerUri = customReferrer.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            } else {
                customBox.visibility = View.GONE
                val preset = PRESETS[index]
                installerPackage = preset.installerPackage
                packageSource = preset.packageSource
                installReason = preset.installReason
                originatingUri = preset.originatingUri.takeIf { it.isNotBlank() }
                referrerUri = null
            }
            updateHint(index)
        }

        val presetView = dropdown(root, context, "Source preset", presetLabels, initialPreset) { applyPreset(it) }
        root.addView(installerHint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 2) })
        root.addView(customBox)

        // ---- Essential flags ----
        section(root, context, "Install flags")
        var allowTestOnly = initial.allowTestOnly
        var allowDowngrade = initial.allowDowngrade
        var grantAll = initial.grantAll
        var bypassLowTargetSdk = initial.bypassLowTargetSdk
        var dontKillApp = initial.dontKillApp
        var restrictPermissions = initial.restrictPermissions
        var updateOwnership = initial.updateOwnership
        var neuterAds = initial.neuterAds

        val flagSwitches = listOf(
            switchRow(root, context, "Bypass low-target-SDK block", bypassLowTargetSdk) { bypassLowTargetSdk = it },
            switchRow(root, context, "Allow downgrade (-d)", allowDowngrade) { allowDowngrade = it },
            switchRow(root, context, "Grant all runtime permissions (-g)", grantAll) { grantAll = it },
            switchRow(root, context, "Do not kill running app (--dont-kill)", dontKillApp) { dontKillApp = it },
            switchRow(root, context, "Restrict permissions (--restrict-permissions)", restrictPermissions) { restrictPermissions = it },
            switchRow(root, context, "Request update ownership (--update-ownership)", updateOwnership) { updateOwnership = it },
            switchRow(root, context, "Allow test-only packages (-t)", allowTestOnly) { allowTestOnly = it },
            switchRow(root, context, "Ad/tracker neuter after install", neuterAds) { neuterAds = it },
        )

        root.addView(TextView(context).apply {
            text = "Reset to defaults"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
            setPadding(0, dp(context, 14), 0, 0)
            setOnClickListener {
                flagSwitches.forEach { it.isChecked = false }
                customInstaller.setText("")
                customOrigin.setText("")
                customReferrer.setText("")
                presetView.setText(presetLabels[0], false)
                applyPreset(0)
            }
        })

        MaterialAlertDialogBuilder(context)
            .setTitle("Install options")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                if (presetIndex == CUSTOM) {
                    installerPackage = customInstaller.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    packageSource = sourceValues.getOrElse(customSourceIndex) { Prefs.PackageSource.UNSPECIFIED }
                    installReason = reasonValues.getOrElse(customReasonIndex) { Prefs.InstallReason.UNSPECIFIED }
                    originatingUri = customOrigin.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    referrerUri = customReferrer.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
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
                        installerPackage = installerPackage,
                        packageSource = packageSource,
                        installReason = installReason,
                        originatingUri = originatingUri,
                        referrerUri = referrerUri,
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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

    private fun switchRow(root: LinearLayout, context: Context, label: String, checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch {
        val sw = MaterialSwitch(context).apply {
            text = label
            textSize = 14f
            this.isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        root.addView(sw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 2)
        })
        return sw
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
