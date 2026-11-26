package dev.rubiidev.shellinstaller.install

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.min

/**
 * Lightweight detector for well-known ad, analytics, attribution and crash SDK markers inside
 * APK bytecode/resources. It is intentionally heuristic: a hit means "indicator found", not a
 * guarantee that the SDK is active.
 */
object AdTrackerScanner {

    private data class Sdk(val name: String, val category: String, val patterns: List<String>)

    private val SDKS = listOf(
        sdk("Google AdMob / GMA", "Ads", "com.google.android.gms.ads", "com.google.ads", "ads.admob"),
        sdk("AppLovin", "Ads", "com.applovin", "applovin.com"),
        sdk("Unity Ads", "Ads", "com.unity3d.ads", "com.unity3d.services", "unity3d.com/ads"),
        sdk("Facebook Audience Network", "Ads", "com.facebook.ads", "com.facebook.AudienceNetwork", "audience-network"),
        sdk("IronSource / LevelPlay", "Ads", "com.ironsource", "ironsrc.com", "supersonicads"),
        sdk("Vungle / Liftoff", "Ads", "com.vungle", "vungle.com"),
        sdk("MoPub", "Ads", "com.mopub", "mopub.com"),
        sdk("Chartboost", "Ads", "com.chartboost", "chartboost.com"),
        sdk("Pangle / ByteDance Ads", "Ads", "com.bytedance.applog", "com.pangle", "pangleglobal"),
        sdk("Mintegral", "Ads", "com.mbridge.msdk", "mintegral.com", "mtgglobals.com"),
        sdk("AdColony", "Ads", "com.adcolony", "adcolony.com"),
        sdk("Tapjoy", "Ads", "com.tapjoy", "tapjoy.com"),
        sdk("InMobi", "Ads", "com.inmobi", "inmobi.com"),
        sdk("Start.io", "Ads", "com.startapp", "startapp.com"),
        sdk("Appodeal", "Ads", "com.appodeal", "appodeal.com"),
        sdk("Yandex Mobile Ads", "Ads", "com.yandex.mobile.ads", "yandex.ru/ads"),
        sdk("Xiaomi Ads", "Ads", "com.xiaomi.ad", "miui.ad"),
        sdk("Samsung Ads", "Ads", "com.samsung.android.sdk.ads", "samsungads"),
        sdk("Huawei Ads", "Ads", "com.huawei.ads", "huaweiads"),
        sdk("AppNext", "Ads", "com.appnext", "appnext.com"),
        sdk("MyTarget", "Ads", "com.my.target", "mytarget"),
        sdk("Firebase Analytics", "Analytics", "com.google.firebase.analytics", "com.google.android.gms.measurement", "firebase_analytics"),
        sdk("Google Analytics for Firebase", "Analytics", "com.google.android.gms.analytics", "google-analytics.com"),
        sdk("Crashlytics", "Crash", "com.google.firebase.crashlytics", "io.fabric", "crashlytics.com"),
        sdk("AppsFlyer", "Attribution", "com.appsflyer", "appsflyer.com"),
        sdk("Adjust", "Attribution", "com.adjust.sdk", "adjust.com", "adjust.io"),
        sdk("Branch", "Attribution", "io.branch.referral", "branch.io"),
        sdk("Kochava", "Attribution", "com.kochava", "kochava.com"),
        sdk("Singular", "Attribution", "com.singular.sdk", "singular.net"),
        sdk("Tenjin", "Attribution", "com.tenjin", "tenjin.io"),
        sdk("Braze", "Analytics", "com.braze", "braze.com"),
        sdk("Amplitude", "Analytics", "com.amplitude", "amplitude.com"),
        sdk("Mixpanel", "Analytics", "com.mixpanel", "mixpanel.com"),
        sdk("Segment", "Analytics", "com.segment.analytics", "segment.io", "segment.com"),
        sdk("Datadog", "Analytics", "com.datadoghq", "datadoghq.com"),
        sdk("New Relic", "Analytics", "com.newrelic", "newrelic.com"),
        sdk("Sentry", "Crash", "io.sentry", "sentry.io"),
        sdk("Bugsnag", "Crash", "com.bugsnag", "bugsnag.com"),
        sdk("UXCam", "Analytics", "com.uxcam", "uxcam.com"),
        sdk("Microsoft Clarity", "Analytics", "com.microsoft.clarity", "clarity.ms"),
        sdk("Yandex Metrica", "Analytics", "com.yandex.metrica", "metrica.yandex"),
        sdk("Umeng", "Analytics", "com.umeng", "umeng.com"),
        sdk("JPush", "Analytics", "cn.jpush", "jiguang.cn", "jiguang.com"),
        sdk("AppMetrica", "Analytics", "io.appmetrica", "appmetrica.yandex"),
        sdk("OneSignal", "Analytics", "com.onesignal", "onesignal.com"),
        sdk("Airbridge", "Attribution", "co.ab180.airbridge", "airbridge.io"),
        sdk("RangersAppLog / Volcano", "Analytics", "com.volcengine", "rangersapplog", "data.rangers"),
    )

    private val SDK_BY_NAME = SDKS.associateBy { it.name }
    private val MAX_PATTERN = SDKS.flatMap { it.patterns }.maxOf { it.length }
    private val CHUNK_SIZE = 512 * 1024

    // Total bytes of dex/resources we are willing to decompress per APK. Normal apps sit far
    // below this; huge bundles stop early instead of stalling preparation for minutes.
    private const val MAX_SCAN_BYTES = 256L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    fun scan(apk: File): List<AdFinding> {
        if (!apk.isFile) return emptyList()
        val evidence = LinkedHashMap<String, LinkedHashSet<String>>()
        var budget = MAX_SCAN_BYTES
        runCatching {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements() && budget > 0) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (!name.endsWith(".dex") && name != "resources.arsc" && name != "AndroidManifest.xml") continue
                    val limit = minOf(MAX_ENTRY_BYTES, budget)
                    val consumed = zip.getInputStream(entry).use { input ->
                        scanStream(input, name, evidence, limit)
                    }
                    budget -= consumed
                }
            }
        }
        return evidence.map { (sdk, entries) ->
            AdFinding(
                sdk = sdk,
                category = SDK_BY_NAME[sdk]?.category ?: "Tracker",
                evidence = entries.joinToString(", "),
            )
        }
    }

    private fun scanStream(
        input: InputStream,
        entryName: String,
        evidence: LinkedHashMap<String, LinkedHashSet<String>>,
        limit: Long,
    ): Long {
        val buffer = ByteArray(CHUNK_SIZE)
        var consumed = 0L
        // ISO-8859-1 maps bytes 1:1 to chars, so ASCII patterns still match while
        // String.contains uses a much faster search than a manual byte loop.
        var tail = ""
        while (consumed < limit) {
            val want = min(buffer.size.toLong(), limit - consumed).toInt()
            val read = input.read(buffer, 0, want)
            if (read <= 0) break
            consumed += read
            val chunk = tail + String(buffer, 0, read, Charsets.ISO_8859_1)
            for (sdk in SDKS) {
                if (evidence.containsKey(sdk.name)) continue
                if (sdk.patterns.any { chunk.contains(it) }) {
                    evidence.getOrPut(sdk.name) { linkedSetOf() }.add(entryName)
                }
            }
            val keep = min(MAX_PATTERN - 1, chunk.length)
            tail = chunk.substring(chunk.length - keep)
        }
        return consumed
    }

    private fun sdk(name: String, category: String, vararg patterns: String): Sdk =
        Sdk(name, category, patterns.toList())
}
