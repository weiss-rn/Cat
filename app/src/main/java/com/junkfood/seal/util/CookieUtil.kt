package com.junkfood.seal.util

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.CheckResult
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.ui.page.settings.network.Cookie
import com.junkfood.seal.util.FileUtil.getCookiesFile
import com.junkfood.seal.util.PreferenceUtil.COOKIE_HEADER
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * All cookie plumbing for Cat, extracted from [DownloadUtil] so that the download engine
 * and the cookie subsystem can evolve independently.
 *
 * Responsibilities:
 *  - Parsing manually-pasted cookie text ([parseCookieContent]: Netscape / JSON / header formats).
 *  - Collecting cookies for every saved [com.junkfood.seal.database.objects.CookieProfile]
 *    ([getCookieListFromDatabase]) — from pasted content when present, otherwise from the
 *    in-app WebView's [CookieManager].
 *  - Serialising cookies to the Netscape `cookies.txt` format understood by yt-dlp
 *    ([toCookiesFileContent], [getCookiesContentFromDatabase]).
 *  - Rebuilding the on-disk cookie file before downloads ([refreshCookiesFile]) and wiring it
 *    into a [YoutubeDLRequest] ([enableCookies]).
 */
object CookieUtil {

    private const val TAG = "CookieUtil"

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // Manual cookie content parsing
    //
    // When a CookieProfile has non-empty `content`, the user pasted cookies
    // manually (Netscape / JSON / name=value format). These are parsed here and
    // used directly instead of reading from CookieManager.
    // -------------------------------------------------------------------------

    /** JSON shape produced by "Cookie-Editor" and "EditThisCookie" browser extensions. */
    @Serializable
    private data class CookieJson(
        val name: String = "",
        val value: String = "",
        val domain: String = "",
        val path: String = "/",
        val secure: Boolean = false,
        @SerialName("httpOnly") val httpOnly: Boolean = false,
        // Both extension names in the wild:
        val expirationDate: Double = 0.0,
        val expires: Double = 0.0,
        val session: Boolean = true,
    )

    /**
     * Parses manually-pasted cookie text into a [List<Cookie>].
     * Three input formats are supported:
     *
     *  1. **Netscape / cookies.txt** — tab-separated 7-field lines.
     *  2. **JSON** — array of cookie objects exported by "Cookie-Editor" /
     *     "EditThisCookie" browser extensions.
     *  3. **Header / name=value** — the semicolon-delimited string you see
     *     when you copy a `Cookie:` request header from a browser.
     *
     * @param profileUrl The URL of the [com.junkfood.seal.database.objects.CookieProfile] —
     *   used to derive the domain for format 3, where no domain is available in the text.
     * @param content    The raw text the user pasted.
     */
    fun parseCookieContent(profileUrl: String, content: String): List<Cookie> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return emptyList()

        return runCatching {
            when {
                // JSON: starts with [ (array) or { (single object)
                trimmed.startsWith('[') || trimmed.startsWith('{') ->
                    parseJsonCookies(profileUrl, trimmed)

                // Netscape: has at least one line that contains a tab character
                // (comment lines start with # and are fine to skip)
                trimmed.lines().any { it.isNotBlank() && !it.startsWith('#') && '\t' in it } ->
                    parseNetscapeCookies(trimmed)

                // Fallback: treat as Cookie header value  "name=val; name=val"
                else -> parseHeaderCookies(profileUrl, trimmed)
            }
        }.getOrElse { e ->
            Log.w(TAG, "parseCookieContent failed, attempting header fallback: ${e.message}")
            runCatching { parseHeaderCookies(profileUrl, trimmed) }.getOrDefault(emptyList())
        }
    }

    private fun parseJsonCookies(profileUrl: String, json: String): List<Cookie> {
        // Wrap bare object in an array so the decoder always receives a list.
        val normalised = if (json.trimStart().startsWith('{')) "[$json]" else json
        val items = jsonFormat.decodeFromString<List<CookieJson>>(normalised)
        val now = System.currentTimeMillis() / 1000L
        val fallbackDomain = profileUrl.toFallbackDomain()
        return items.mapNotNull { c ->
            if (c.name.isEmpty()) return@mapNotNull null
            // Pick whichever expiry field is populated; 0 means session cookie
            val expiry = when {
                c.expirationDate > 0 -> c.expirationDate.toLong()
                c.expires > 0        -> c.expires.toLong()
                else                 -> 0L
            }
            if (expiry > 0L && expiry < now) return@mapNotNull null // expired
            val domain = c.domain.let { d ->
                when {
                    d.isBlank()         -> fallbackDomain
                    d.startsWith('.')   -> d
                    else                -> ".$d"
                }
            }
            Cookie(
                domain            = domain,
                name              = c.name,
                value             = c.value,
                includeSubdomains = true,
                path              = c.path.ifEmpty { "/" },
                secure            = c.secure,
                expiry            = expiry,
                isHttpOnly        = c.httpOnly,
            )
        }
    }

    private fun parseNetscapeCookies(text: String): List<Cookie> {
        val now = System.currentTimeMillis() / 1000L
        val cookies = mutableListOf<Cookie>()
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val isHttpOnly = trimmed.startsWith("#HttpOnly_")
            val cookieLine = if (isHttpOnly) trimmed.removePrefix("#HttpOnly_") else trimmed
            if (cookieLine.startsWith('#')) return@forEach
            val parts = cookieLine.split('\t')
            if (parts.size < 7) return@forEach
            val rawDomain          = parts[0]
            val includeSubdomains  = parts[1].uppercase() == "TRUE"
            val path               = parts[2]
            val secure             = parts[3].uppercase() == "TRUE"
            val expiry             = parts[4].toLongOrNull() ?: 0L
            val name               = parts[5]
            // Value may itself contain tabs — re-join the remainder
            val value              = parts.drop(6).joinToString("\t")
            if (name.isEmpty()) return@forEach
            if (expiry > 0L && expiry < now) return@forEach // skip expired

            // IMPORTANT: yt-dlp's cookie loader (YoutubeDLCookieJar, a subclass of Python's
            // http.cookiejar.MozillaCookieJar) enforces `assert domain_specified == initial_dot`
            // for every single line in the file — i.e. the "include subdomains" flag (this
            // field) and whether `domain` starts with a dot MUST agree. A host-only cookie
            // (includeSubdomains = FALSE) legitimately has no leading dot in a real cookies.txt
            // export. The previous code unconditionally force-added a leading dot to every
            // domain regardless of this flag, which broke that invariant for any pasted file
            // containing host-only cookies. Because yt-dlp parses the WHOLE file in one pass,
            // that single inconsistent line raised a LoadError and rejected the entire cookies
            // file with "does not look like a Netscape format cookies file" — silently breaking
            // cookie-based auth for every profile, not just the pasted one.
            // Fix: derive the dot presence FROM includeSubdomains so the two always agree,
            // instead of assuming the source domain string's dot state is correct.
            val domain = if (includeSubdomains) {
                if (rawDomain.startsWith('.')) rawDomain else ".$rawDomain"
            } else {
                rawDomain.removePrefix(".")
            }

            cookies.add(
                Cookie(
                    domain            = domain,
                    name              = name,
                    value             = value,
                    includeSubdomains = includeSubdomains,
                    path              = path,
                    secure            = secure,
                    expiry            = expiry,
                    isHttpOnly        = isHttpOnly,
                )
            )
        }
        return cookies
    }

    private fun parseHeaderCookies(profileUrl: String, header: String): List<Cookie> {
        val domain = profileUrl.toBaseDomain()
        val cookies = mutableListOf<Cookie>()
        // Strip a leading "Cookie: " HTTP header prefix if the user copied the whole line
        val cookiePart = header.removePrefix("Cookie:").removePrefix("cookie:").trim()
        cookiePart.split(';').forEach { pair ->
            val eqIdx = pair.indexOf('=')
            if (eqIdx < 0) return@forEach
            val name  = pair.substring(0, eqIdx).trim()
            val value = pair.substring(eqIdx + 1).trim()
            if (name.isEmpty()) return@forEach
            cookies.add(
                Cookie(
                    domain            = domain,
                    name              = name,
                    value             = value,
                    includeSubdomains = true,
                    path              = "/",
                    secure            = false,
                    expiry            = 0L,
                    isHttpOnly        = false,
                )
            )
        }
        return cookies
    }

    // -------------------------------------------------------------------------
    // Domain helpers shared by the parsers and the CookieManager collector
    // -------------------------------------------------------------------------

    /** Ensures the URL has a scheme so it can be handed to [Uri.parse] / [CookieManager]. */
    private fun String.withHttpsScheme(): String =
        if (startsWith("http://") || startsWith("https://")) this else "https://$this"

    /**
     * Normalises a profile URL to HTTPS. [CookieManager.getCookie] with an http:// URL
     * silently omits all Secure-flagged cookies. Facebook session cookies (c_user, xs),
     * Instagram session cookies (sessionid, csrftoken), and virtually every other
     * social-media auth cookie carry the Secure flag, so an http:// query returns
     * an empty or incomplete cookie string — causing silent auth failures.
     */
    private fun String.toHttpsUrlNormalised(): String = when {
        startsWith("https://") -> this
        startsWith("http://")  -> replaceFirst("http://", "https://")
        else                   -> "https://$this"
    }

    /** Registered (base) domain of a URL in Netscape form, e.g. ".facebook.com". */
    private fun String.toBaseDomain(): String {
        val host = Uri.parse(withHttpsScheme()).host ?: ""
        return "." + if (host.startsWith("www.")) host.removePrefix("www.") else host
    }

    /** Like [toBaseDomain], but falls back to the empty string when the URL has no host. */
    private fun String.toFallbackDomain(): String {
        val host = Uri.parse(withHttpsScheme()).host?.removePrefix("www.")
        return if (host.isNullOrBlank()) "" else ".$host"
    }

    // -------------------------------------------------------------------------
    // Collecting cookies for all saved profiles
    // -------------------------------------------------------------------------

    /**
     * Reads cookies for every saved [com.junkfood.seal.database.objects.CookieProfile] URL using
     * the documented [android.webkit.CookieManager] public API. Cookies pasted manually into a
     * profile's `content` field take precedence (see [parseCookieContent]).
     *
     * ## Why this replaced the old SQLite approach
     * The old implementation opened the Chromium WebView cookie database file directly with
     * [android.database.sqlite.SQLiteDatabase]. That was fragile in three ways:
     *  1. The WebView process may hold a write lock on the file → SQLITE_BUSY / SQLITE_LOCKED
     *     errors returned no cookies at all.
     *  2. The internal DB path (`app_webview/Default/Cookies` vs `app_webview/Cookies`) varies
     *     across Android / WebView versions and required brittle path detection.
     *  3. The Chromium cookie table schema is an implementation detail that can change without
     *     notice.
     *
     * [CookieManager.getCookie] goes through the same process that owns the cookie store, so
     * there is no locking conflict, no internal paths, and no schema dependency.
     *
     * ## Limitations
     * [CookieManager.getCookie] returns only `name=value` pairs — expiry, Secure, HttpOnly, and
     * Path attributes are not accessible via this API. yt-dlp needs only name and value to
     * authenticate requests, so this is not a practical limitation.
     */
    @CheckResult
    fun getCookieListFromDatabase(): Result<List<Cookie>> = runCatching {
        val manager = CookieManager.getInstance()
        // Flush in-memory cookies to the WebView's persistent store before reading.
        manager.flush()

        // Fetch all saved profile URLs from Room first — we need to know whether any
        // profile uses manual cookies before deciding if hasCookies() matters.
        val profiles = runBlocking(Dispatchers.IO) {
            DatabaseUtil.getCookieProfileList()
        }

        if (profiles.isEmpty()) {
            throw Exception(
                "No cookie profiles configured. " +
                "Please go to Settings → Network → Cookies and add the site you want to download from."
            )
        }

        // Only require WebView cookies when NO profile has manually-pasted content.
        // If at least one profile has manual content, skip this check — those cookies
        // are read from the profile's content field, not from CookieManager.
        if (profiles.none { it.content.isNotEmpty() } && !manager.hasCookies()) {
            throw Exception(
                "No cookies found in the browser. " +
                "Please open Settings → Network → Cookies, tap a profile, " +
                "then tap 'Generate cookies' and log in to the website."
            )
        }

        val seen = HashSet<String>() // deduplication key: "domain|name"
        val cookies = mutableListOf<Cookie>()

        for (profile in profiles) {

            // ------------------------------------------------------------------
            // Manual cookie path: the user pasted cookies directly into the
            // profile. Parse them and skip the CookieManager lookup entirely.
            // ------------------------------------------------------------------
            if (profile.content.isNotEmpty()) {
                val manual = parseCookieContent(profile.url, profile.content)
                if (manual.isNotEmpty()) {
                    manual.forEach { c ->
                        val key = "${c.domain}|${c.name}"
                        if (seen.add(key)) cookies.add(c)
                    }
                    Log.d(TAG, "Profile '${profile.url}': using ${manual.size} manual cookie(s)")
                    continue // skip CookieManager for this profile
                }
                Log.w(TAG, "Profile '${profile.url}': content is set but parsing returned 0 cookies")
            }

            // ------------------------------------------------------------------
            // Automatic path: read cookies from the in-app WebView's CookieManager.
            // ------------------------------------------------------------------
            val rawUrl = profile.url.toHttpsUrlNormalised()
            val host = Uri.parse(rawUrl).host ?: continue

            // Query both the plain and www-prefixed variants because some sites set their
            // session cookies on "facebook.com" while others use "www.facebook.com".
            val urlVariants = buildList {
                add(rawUrl)
                if (host.startsWith("www.")) {
                    add(rawUrl.replaceFirst("://www.", "://"))
                } else {
                    add(rawUrl.replaceFirst("://", "://www."))
                }
            }

            // Use the registered (base) domain rather than the exact queried hostname.
            // Example: querying "https://www.facebook.com" gives host "www.facebook.com".
            // Facebook sets cookies on ".facebook.com", so writing ".www.facebook.com" in
            // the Netscape file means yt-dlp would NOT send those cookies to subdomains like
            // graph.facebook.com, video.facebook.com, etc., breaking authenticated downloads.
            // Stripping the leading "www." gives ".facebook.com" which covers all subdomains.
            val baseDomain = rawUrl.toBaseDomain()

            for (url in urlVariants) {
                // getCookie returns "name1=val1; name2=val2; ..." or null if nothing is set.
                val raw = manager.getCookie(url) ?: continue
                raw.split(";").forEach rawCookie@{ pair ->
                    val eqIdx = pair.indexOf('=')
                    if (eqIdx < 0) return@rawCookie
                    val name  = pair.substring(0, eqIdx).trim()
                    // Do NOT trim value — some cookies intentionally have leading/trailing
                    // spaces in their value, and we must preserve those exactly.
                    val value = pair.substring(eqIdx + 1)
                    if (name.isEmpty()) return@rawCookie
                    val dedupKey = "$baseDomain|$name"
                    if (seen.add(dedupKey)) {
                        cookies.add(
                            Cookie(
                                domain = baseDomain,
                                name   = name,
                                value  = value,
                                includeSubdomains = true,
                                path   = "/",
                                // CookieManager does not expose Secure, HttpOnly, or expiry —
                                // yt-dlp does not require these for authentication.
                                secure    = false,
                                expiry    = 0L,
                                isHttpOnly = false,
                            )
                        )
                    }
                }
            }
        }

        if (cookies.isEmpty()) {
            throw Exception(
                "No cookies could be retrieved for the saved profiles. " +
                "Please open the cookie browser and log in to each site again."
            )
        }

        Log.d(TAG, "getCookieListFromDatabase: extracted ${cookies.size} cookies for ${cookies.distinctBy { it.domain }.size} domain(s)")
        cookies
    }

    // -------------------------------------------------------------------------
    // Netscape serialisation & on-disk cookie file
    // -------------------------------------------------------------------------

    fun List<Cookie>.toCookiesFileContent(): String =
        this.fold(StringBuilder(COOKIE_HEADER)) { acc, cookie ->
                acc.append(cookie.toNetscapeCookieString()).append("\n")
            }
            .toString()

    /** Convenience wrapper: reads cookies and serialises them to Netscape file format. */
    fun getCookiesContentFromDatabase(): Result<String> =
        getCookieListFromDatabase().mapCatching { it.toCookiesFileContent() }

    /**
     * Rebuilds the on-disk Netscape cookie file from the current in-memory WebView cookie store.
     * Called automatically before every download when cookies are enabled.
     */
    fun refreshCookiesFile() {
        context.getCookiesFile().let { cookiesFile ->
            getCookieListFromDatabase()
                .mapCatching { it.toCookiesFileContent() }
                // Use mapCatching (not onSuccess) so an IOException from writeText
                // (e.g. disk full) is captured inside the Result and handled by the
                // onFailure below, rather than propagating as an uncaught exception
                // and leaving a partial/corrupt cookies.txt on disk.
                .mapCatching { content -> FileUtil.writeContentToFile(content, cookiesFile) }
                .onFailure { err ->
                    Log.w(TAG, "Failed to refresh cookies file: ${err.message}")
                    if (cookiesFile.exists()) cookiesFile.delete()
                }
        }
    }

    // -------------------------------------------------------------------------
    // YoutubeDLRequest integration
    // -------------------------------------------------------------------------

    fun YoutubeDLRequest.enableCookies(userAgentString: String): YoutubeDLRequest {
        refreshCookiesFile()
        return this.addOption("--cookies", context.getCookiesFile().absolutePath).apply {
            if (userAgentString.isNotEmpty()) {
                addOption("--add-header", "User-Agent:$userAgentString")
            }
        }
    }
}
