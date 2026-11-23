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

    private data class Sdk(val name: String, val category: String, val patterns: List<ByteArray>)

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
    private val MAX_PATTERN = SDKS.flatMap { it.patterns }.maxOf { it.size }
    private val CHUNK_SIZE = 256 * 1024

    fun scan(apk: File): List<AdFinding> {
        if (!apk.isFile) return emptyList()
        val evidence = LinkedHashMap<String, LinkedHashSet<String>>()
        runCatching {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (!name.endsWith(".dex") && name != "resources.arsc" && name != "AndroidManifest.xml") continue
                    zip.getInputStream(entry).use { input ->
                        scanStream(input, name, evidence)
                    }
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

    private fun scanStream(input: InputStream, entryName: String, evidence: LinkedHashMap<String, LinkedHashSet<String>>) {
        val buffer = ByteArray(CHUNK_SIZE)
        var tail = ByteArray(0)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            val chunk = if (tail.isEmpty()) buffer.copyOf(read) else tail + buffer.copyOf(read)
            for (sdk in SDKS) {
                if (evidence.containsKey(sdk.name)) continue
                val hit = sdk.patterns.any { contains(chunk, it) }
                if (hit) {
                    evidence.getOrPut(sdk.name) { linkedSetOf() }.add(entryName)
                }
            }
            val keep = min(MAX_PATTERN - 1, chunk.size)
            tail = if (keep <= 0) ByteArray(0) else chunk.copyOfRange(chunk.size - keep, chunk.size)
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        var index = indexOfByte(haystack, needle[0], 0)
        val last = haystack.size - needle.size
        while (index >= 0 && index <= last) {
            var i = 1
            while (i < needle.size && haystack[index + i] == needle[i]) i++
            if (i == needle.size) return true
            index = indexOfByte(haystack, needle[0], index + 1)
        }
        return false
    }

    private fun indexOfByte(haystack: ByteArray, value: Byte, from: Int): Int {
        var i = from
        while (i < haystack.size) {
            if (haystack[i] == value) return i
            i++
        }
        return -1
    }

    private fun sdk(name: String, category: String, vararg patterns: String): Sdk =
        Sdk(name, category, patterns.map { it.toByteArray(Charsets.UTF_8) })
}
