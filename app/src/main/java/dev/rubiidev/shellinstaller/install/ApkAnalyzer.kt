package dev.rubiidev.shellinstaller.install

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipFile

/** Result of the pre-install APK inspection shown in the report dialog. */
data class ApkReport(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val permissions: List<String>,
    val dangerousPermissions: List<String>,
    val features: List<String>,
    val abis: List<String>,
    val signerCount: Int,
    val signerSummary: String,
    val signatureStatus: SignatureStatus,
    val adFindings: List<AdFinding>,
    val warnings: List<String>,
)

enum class SignatureStatus { NEW, MATCH, MISMATCH, UNKNOWN }

data class AdFinding(
    val sdk: String,
    val category: String,
    val evidence: String,
)

/**
 * Static APK inspection used before install. It uses PackageManager archive parsing for
 * manifest metadata and a lightweight byte scan for known ad/tracking SDK markers.
 */
object ApkAnalyzer {

    fun analyze(context: Context, baseApk: File, allApks: List<File>, isBundle: Boolean): ApkReport {
        val pm = context.packageManager
        val flags = packageInfoFlags()
        val baseInfo = runCatching { pm.getPackageArchiveInfo(baseApk.absolutePath, flags) }.getOrNull()
        val packageName = baseInfo?.packageName ?: baseApk.nameWithoutExtension()

        val permissions = LinkedHashSet<String>()
        val features = LinkedHashSet<String>()
        val abis = LinkedHashSet<String>()
        var minSdk = baseInfo?.applicationInfo?.minSdkVersion ?: Build.VERSION_CODES.M
        var targetSdk = baseInfo?.applicationInfo?.targetSdkVersion ?: Build.VERSION_CODES.M

        allApks.forEach { apk ->
            val info = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, flags) }.getOrNull()
            info?.requestedPermissions?.let { permissions.addAll(it) }
            info?.reqFeatures?.let { list -> features.addAll(list.map(::featureLabel)) }
            info?.applicationInfo?.let { app ->
                if (app.minSdkVersion > 0) minSdk = minOf(minSdk, app.minSdkVersion)
                if (app.targetSdkVersion > 0) targetSdk = maxOf(targetSdk, app.targetSdkVersion)
            }
        }
        abis.addAll(detectAbis(allApks))

        val dangerous = permissions.filter { isDangerous(pm, it) }.sorted()
        val signatureStatus = signatureStatus(pm, packageName, baseInfo)
        val signers = signingCertSha256(baseInfo)
        val signerSummary = signerSummary(baseInfo)
        // Ad/tracker scanning is deliberately NOT done here: it decompresses dex bytes and is
        // too slow for the prepare path. ApkReportDialog runs it lazily on demand instead.
        val adFindings = emptyList<AdFinding>()
        val warnings = buildWarnings(
            minSdk = minSdk,
            targetSdk = targetSdk,
            signatureStatus = signatureStatus,
            signerCount = signers.size,
            dangerousCount = dangerous.size,
            adCount = 0,
            splitCount = allApks.size,
            isBundle = isBundle,
            packageName = packageName,
        )

        return ApkReport(
            packageName = packageName,
            versionName = baseInfo?.versionName,
            versionCode = versionCodeOf(baseInfo),
            minSdk = minSdk,
            targetSdk = targetSdk,
            permissions = permissions.sorted(),
            dangerousPermissions = dangerous,
            features = features.sorted(),
            abis = abis.sorted(),
            signerCount = signers.size,
            signerSummary = signerSummary,
            signatureStatus = signatureStatus,
            adFindings = adFindings,
            warnings = warnings,
        )
    }

    private fun packageInfoFlags(): Int {
        var flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
        flags = flags or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return flags
    }

    private fun versionCodeOf(info: PackageInfo?): Long = when {
        info == null -> 0L
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
        else -> @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun featureLabel(feature: FeatureInfo): String =
        feature.name ?: "glEs-${feature.version}"

    private fun isDangerous(pm: PackageManager, permission: String): Boolean =
        runCatching {
            val info = pm.getPermissionInfo(permission, 0)
            info.protectionLevel and PermissionInfo.PROTECTION_DANGEROUS != 0
        }.getOrDefault(false)

    private fun signatureStatus(pm: PackageManager, packageName: String, apkInfo: PackageInfo?): SignatureStatus {
        val installed = runCatching {
            pm.getPackageInfo(packageName, packageInfoFlags())
        }.getOrNull() ?: return SignatureStatus.NEW
        val installedSigners = signingCertSha256(installed)
        if (installedSigners.isEmpty()) return SignatureStatus.UNKNOWN
        val apkSigners = signingCertSha256(apkInfo)
        if (apkSigners.isEmpty()) return SignatureStatus.UNKNOWN
        return if (installedSigners == apkSigners) SignatureStatus.MATCH else SignatureStatus.MISMATCH
    }

    private fun signingCertSha256(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures?.map { sha256(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    private fun signerSummary(info: PackageInfo?): String {
        if (info == null) return "unavailable"
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val first = signatures?.firstOrNull() ?: return "unavailable"
        return runCatching {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(first.toByteArray().inputStream()) as X509Certificate
            val subject = cert.subjectX500Principal.name
            val sha = sha256(cert.encoded)
            "${subject.take(80)} | SHA-256 ${sha.take(16)}… | until ${cert.notAfter}"
        }.getOrDefault("unavailable")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun detectAbis(apks: List<File>): Set<String> {
        val found = LinkedHashSet<String>()
        apks.forEach { apk ->
            runCatching {
                ZipFile(apk).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        if (name.startsWith("lib/")) {
                            val abi = name.removePrefix("lib/").substringBefore('/')
                            if (abi.isNotBlank()) found.add(abi)
                        }
                    }
                }
            }
        }
        return found
    }

    private fun buildWarnings(
        packageName: String,
        minSdk: Int,
        targetSdk: Int,
        signatureStatus: SignatureStatus,
        signerCount: Int,
        dangerousCount: Int,
        adCount: Int,
        splitCount: Int,
        isBundle: Boolean,
    ): List<String> {
        val warnings = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < minSdk) {
            warnings += "Requires Android SDK $minSdk; device is ${Build.VERSION.SDK_INT}."
        }
        if (targetSdk in 1 until Build.VERSION_CODES.M) {
            warnings += "Targets a very old SDK ($targetSdk); compatibility may be limited."
        }
        when (signatureStatus) {
            SignatureStatus.MISMATCH ->
                warnings += "Signature does not match the installed app; install will fail unless the old app is removed."
            SignatureStatus.UNKNOWN ->
                warnings += "Could not fully compare signing certificates."
            else -> Unit
        }
        if (signerCount > 1) warnings += "APK reports multiple signers."
        if (dangerousCount > 10) warnings += "Requests $dangerousCount dangerous permissions."
        if (adCount > 0) warnings += "$adCount ad/tracking SDK indicator(s) detected."
        if (isBundle && splitCount > 1) warnings += "Bundle contains $splitCount APK split(s)."
        if (packageName.isBlank()) warnings += "Package name could not be determined."
        return warnings
    }
}

private fun File.nameWithoutExtension(): String = name.substringBeforeLast('.')
