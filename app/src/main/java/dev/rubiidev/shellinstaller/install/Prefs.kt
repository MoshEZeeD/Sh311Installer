package dev.rubiidev.shellinstaller.install

import android.content.Context
import org.json.JSONObject

/** Persistent installer preferences stored in the "settings" SharedPreferences file. */
object Prefs {
    private const val NAME = "settings"

    // Legacy keys are kept so older installs migrate cleanly into the JSON option set.
    private const val KEY_DOWNGRADE = "allow_downgrade"
    private const val KEY_GRANT = "grant_runtime_permissions"
    private const val KEY_BYPASS = "bypass_low_target_sdk"
    private const val KEY_BACKEND = "backend_preference"
    private const val KEY_ALLOW_TEST = "allow_test_only"
    private const val KEY_ALL_USERS = "all_users"
    private const val KEY_DONT_KILL = "dont_kill_app"
    private const val KEY_UPDATE_OWNERSHIP = "update_ownership"
    private const val KEY_OPTIONS_JSON = "install_options_json"

    private const val KEY_TERMINAL_FX = "terminal_fx"
    private const val KEY_VT_KEY = "virustotal_api_key"

    enum class BackendPreference { AUTO, SHIZUKU, ADB, ROOT }

    enum class TargetUser { CURRENT, ALL, OWNER, CUSTOM }

    enum class InstallLocation(val code: Int) {
        UNSPECIFIED(-1),
        AUTO(0),
        INTERNAL(1),
        EXTERNAL(2),
    }

    enum class InstallReason(val code: Int) {
        UNSPECIFIED(-1),
        UNKNOWN(0),
        POLICY(1),
        DEVICE_RESTORE(2),
        DEVICE_SETUP(3),
        USER(4),
    }

    enum class PackageSource(val code: Int) {
        UNSPECIFIED(-1),
        OTHER(1),
        STORE(2),
        LOCAL_FILE(3),
        DOWNLOADED_FILE(4),
    }

    enum class InstallMode { FULL, INSTANT, PRELOAD }

    enum class StagedMode { DEFAULT, STAGED, NON_STAGED, FORCE_NON_STAGED }

    enum class RollbackPolicy(val code: Int) {
        RESTORE(0),
        WIPE(1),
        RETAIN(2),
    }

    /**
     * Everything the user can configure before an install. Fields are deliberately granular so
     * the command builder can map them onto real `pm install-create` arguments per API level.
     */
    data class Options(
        val allowDowngrade: Boolean = false,
        val grantAll: Boolean = false,
        val bypassLowTargetSdk: Boolean = false,
        val allowTestOnly: Boolean = false,
        val dontKillApp: Boolean = false,
        val restrictPermissions: Boolean = false,
        val updateOwnership: Boolean = false,
        val neuterAds: Boolean = false,

        val targetUser: TargetUser = TargetUser.CURRENT,
        val customUserId: Int? = null,
        val installLocation: InstallLocation = InstallLocation.UNSPECIFIED,
        val forceUuid: String? = null,
        val expectedPackage: String? = null,

        val installerPackage: String? = null,
        val originatingUri: String? = null,
        val referrerUri: String? = null,
        val installReason: InstallReason = InstallReason.UNSPECIFIED,
        val packageSource: PackageSource = PackageSource.UNSPECIFIED,

        val installMode: InstallMode = InstallMode.FULL,
        val stagedMode: StagedMode = StagedMode.DEFAULT,
        val stagedReadyTimeoutMs: Int? = null,
        val enableRollback: Boolean = false,
        val rollbackPolicy: RollbackPolicy = RollbackPolicy.RESTORE,
        val apex: Boolean = false,
        val abiOverride: String? = null,
        val skipVerification: Boolean = false,
        val forceQueryable: Boolean = false,
        val ignoreDexoptProfile: Boolean = false,
        val dexoptCompilerFilter: String? = null,
        val disableAutoInstallDependencies: Boolean = false,
        val partialInheritPackage: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("allowDowngrade", allowDowngrade)
            put("grantAll", grantAll)
            put("bypassLowTargetSdk", bypassLowTargetSdk)
            put("allowTestOnly", allowTestOnly)
            put("dontKillApp", dontKillApp)
            put("restrictPermissions", restrictPermissions)
            put("updateOwnership", updateOwnership)
            put("neuterAds", neuterAds)
            put("targetUser", targetUser.name)
            putNullable("customUserId", customUserId)
            put("installLocation", installLocation.name)
            putNullable("forceUuid", forceUuid)
            putNullable("expectedPackage", expectedPackage)
            putNullable("installerPackage", installerPackage)
            putNullable("originatingUri", originatingUri)
            putNullable("referrerUri", referrerUri)
            put("installReason", installReason.name)
            put("packageSource", packageSource.name)
            put("installMode", installMode.name)
            put("stagedMode", stagedMode.name)
            putNullable("stagedReadyTimeoutMs", stagedReadyTimeoutMs)
            put("enableRollback", enableRollback)
            put("rollbackPolicy", rollbackPolicy.name)
            put("apex", apex)
            putNullable("abiOverride", abiOverride)
            put("skipVerification", skipVerification)
            put("forceQueryable", forceQueryable)
            put("ignoreDexoptProfile", ignoreDexoptProfile)
            putNullable("dexoptCompilerFilter", dexoptCompilerFilter)
            put("disableAutoInstallDependencies", disableAutoInstallDependencies)
            putNullable("partialInheritPackage", partialInheritPackage)
        }

        companion object {
            fun fromJson(json: JSONObject): Options = Options(
                allowDowngrade = json.optBoolean("allowDowngrade"),
                grantAll = json.optBoolean("grantAll"),
                bypassLowTargetSdk = json.optBoolean("bypassLowTargetSdk", true),
                allowTestOnly = json.optBoolean("allowTestOnly"),
                dontKillApp = json.optBoolean("dontKillApp"),
                restrictPermissions = json.optBoolean("restrictPermissions"),
                updateOwnership = json.optBoolean("updateOwnership"),
                neuterAds = json.optBoolean("neuterAds"),
                targetUser = enumOr(json, "targetUser", TargetUser.CURRENT),
                customUserId = json.nullableInt("customUserId"),
                installLocation = enumOr(json, "installLocation", InstallLocation.UNSPECIFIED),
                forceUuid = json.nullableString("forceUuid"),
                expectedPackage = json.nullableString("expectedPackage"),
                installerPackage = json.nullableString("installerPackage"),
                originatingUri = json.nullableString("originatingUri"),
                referrerUri = json.nullableString("referrerUri"),
                installReason = enumOr(json, "installReason", InstallReason.UNSPECIFIED),
                packageSource = enumOr(json, "packageSource", PackageSource.UNSPECIFIED),
                installMode = enumOr(json, "installMode", InstallMode.FULL),
                stagedMode = enumOr(json, "stagedMode", StagedMode.DEFAULT),
                stagedReadyTimeoutMs = json.nullableInt("stagedReadyTimeoutMs"),
                enableRollback = json.optBoolean("enableRollback"),
                rollbackPolicy = enumOr(json, "rollbackPolicy", RollbackPolicy.RESTORE),
                apex = json.optBoolean("apex"),
                abiOverride = json.nullableString("abiOverride"),
                skipVerification = json.optBoolean("skipVerification"),
                forceQueryable = json.optBoolean("forceQueryable"),
                ignoreDexoptProfile = json.optBoolean("ignoreDexoptProfile"),
                dexoptCompilerFilter = json.nullableString("dexoptCompilerFilter"),
                disableAutoInstallDependencies = json.optBoolean("disableAutoInstallDependencies"),
                partialInheritPackage = json.nullableString("partialInheritPackage"),
            )

            private inline fun <reified T : Enum<T>> enumOr(json: JSONObject, key: String, default: T): T =
                runCatching { enumValueOf<T>(json.optString(key, default.name)) }.getOrDefault(default)

            private fun JSONObject.putNullable(key: String, value: String?) {
                if (value.isNullOrBlank()) put(key, JSONObject.NULL) else put(key, value)
            }

            private fun JSONObject.putNullable(key: String, value: Int?) {
                if (value == null) put(key, JSONObject.NULL) else put(key, value)
            }

            private fun JSONObject.nullableString(key: String): String? =
                if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

            private fun JSONObject.nullableInt(key: String): Int? =
                if (isNull(key)) null else optInt(key).takeIf { it >= 0 }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun backendPreference(context: Context): BackendPreference {
        val value = prefs(context).getString(KEY_BACKEND, BackendPreference.AUTO.name)
        return runCatching { BackendPreference.valueOf(value ?: "") }.getOrDefault(BackendPreference.AUTO)
    }

    fun setBackendPreference(context: Context, preference: BackendPreference) {
        prefs(context).edit().putString(KEY_BACKEND, preference.name).apply()
    }

    fun options(context: Context): Options {
        val json = prefs(context).getString(KEY_OPTIONS_JSON, null)
        if (!json.isNullOrBlank()) {
            return runCatching { Options.fromJson(JSONObject(json)) }
                .getOrElse { legacyOptions(context) }
        }
        return legacyOptions(context)
    }

    private fun legacyOptions(context: Context): Options {
        val p = prefs(context)
        val allUsers = p.getBoolean(KEY_ALL_USERS, false)
        return Options(
            allowDowngrade = p.getBoolean(KEY_DOWNGRADE, false),
            grantAll = p.getBoolean(KEY_GRANT, false),
            bypassLowTargetSdk = p.getBoolean(KEY_BYPASS, false),
            allowTestOnly = p.getBoolean(KEY_ALLOW_TEST, false),
            dontKillApp = p.getBoolean(KEY_DONT_KILL, false),
            updateOwnership = p.getBoolean(KEY_UPDATE_OWNERSHIP, false),
            targetUser = if (allUsers) TargetUser.ALL else TargetUser.CURRENT,
        )
    }

    fun saveOptions(context: Context, options: Options) {
        prefs(context).edit()
            .putString(KEY_OPTIONS_JSON, options.toJson().toString())
            // Keep legacy keys in sync so older code paths keep working.
            .putBoolean(KEY_DOWNGRADE, options.allowDowngrade)
            .putBoolean(KEY_GRANT, options.grantAll)
            .putBoolean(KEY_BYPASS, options.bypassLowTargetSdk)
            .putBoolean(KEY_ALLOW_TEST, options.allowTestOnly)
            .putBoolean(KEY_DONT_KILL, options.dontKillApp)
            .putBoolean(KEY_UPDATE_OWNERSHIP, options.updateOwnership)
            .putBoolean(KEY_ALL_USERS, options.targetUser == TargetUser.ALL)
            .apply()
    }

    fun update(context: Context, transform: (Options) -> Options) {
        saveOptions(context, transform(options(context)))
    }

    fun setDowngrade(context: Context, value: Boolean) =
        update(context) { it.copy(allowDowngrade = value) }

    fun setGrant(context: Context, value: Boolean) =
        update(context) { it.copy(grantAll = value) }

    fun setBypass(context: Context, value: Boolean) =
        update(context) { it.copy(bypassLowTargetSdk = value) }

    fun terminalEffects(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TERMINAL_FX, true)

    fun setTerminalEffects(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TERMINAL_FX, enabled).apply()
    }

    fun virusTotalApiKey(context: Context): String =
        prefs(context).getString(KEY_VT_KEY, "").orEmpty()

    fun setVirusTotalApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_VT_KEY, key.trim()).apply()
    }
}
