package eu.kanade.tachiyomi.animeextension.pt.animeplayer

import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import org.jsoup.nodes.Document

internal object KyozExtractor {

    private val BLOGGER_URL_REGEX = Regex("""https?:\\?/\\?/(?:www\.)?blogger\.com\\?/video\.g\?token=[^"'\s<>\\]+""")
    private val SAFE_RESPONSE_LOG_HEADER_NAMES = setOf(
        "cf-cache-status",
        "content-length",
        "vary",
        "x-cache",
        "cache-control",
        "server",
        "content-type",
    )

    class KyozFetchResult(
        val code: Int,
        val html: String?,
        val server: String?,
        val cfMitigated: String?,
        val contentType: String?,
        val bodyLength: Int,
        val bodyKind: String,
    )

    class VerifyFetchResult(
        val code: Int,
        val ticket: String?,
        val servers: List<VerifyServerDto>,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    fun absoluteUrl(baseUrl: String, pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl
        }
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl"
        return cleanBase + cleanPath
    }

    fun extractPlayerKeys(document: Document): List<String> = document
        .select(".player-placeholder[data-player-key]")
        .map { it.attr("data-player-key").trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    fun extractLegacyIframeUrl(document: Document): String? {
        val src = document.selectFirst("div.playex iframe")?.absUrl("src")?.takeIf { it.isNotBlank() }
            ?: return null
        val parsed = src.toHttpUrlOrNull() ?: return src
        return parsed.queryParameter("link")?.takeIf { it.isNotBlank() } ?: parsed.toString()
    }

    fun extractQualityLabel(document: Document): String = document
        .selectFirst("span.qualityx")
        ?.text()
        ?.substringAfterLast(" ")
        ?.takeIf { it.isNotBlank() }
        ?: "Default"

    fun isKyozUrl(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return host == "kyoz.net" || host.endsWith(".kyoz.net")
    }

    fun extractUserAgent(headers: Headers): String? = headers
        .firstOrNull { (name, _) -> name.equals("user-agent", ignoreCase = true) }
        ?.second
        ?.takeIf { it.isNotBlank() }

    fun iframeHeadersFor(
        episodeUrl: String,
        baseUrl: String,
        baseHeaders: Headers,
        fullReferer: Boolean = false,
    ): Headers {
        val referer = if (fullReferer) {
            episodeUrl
        } else {
            baseUrl.trimEnd('/').takeIf { it.isNotEmpty() }?.let { "$it/" } ?: episodeUrl
        }
        return baseHeaders.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            .set("Referer", referer)
            .set("Sec-Fetch-Dest", "iframe")
            .set("Sec-Fetch-Mode", "navigate")
            .set("Sec-Fetch-Site", "cross-site")
            .set("Upgrade-Insecure-Requests", "1")
            .build()
    }

    fun withUserAgent(baseHeaders: Headers, userAgent: String?): Headers {
        val trimmed = userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: return baseHeaders
        return baseHeaders.newBuilder()
            // repo-rules: allow hardcoded-user-agent - propagates runtime WebView UA so verify/load/kyoz share one session UA
            .set("User-Agent", trimmed)
            .build()
    }

    fun bloggerHeadersFor(kyozUrl: String, baseHeaders: Headers): Headers {
        val parsed = kyozUrl.toHttpUrlOrNull()
        val origin = if (parsed != null) "${parsed.scheme}://${parsed.host}/" else "https://img.kyoz.net/"
        return baseHeaders.newBuilder()
            .set("Referer", origin)
            .build()
    }

    fun apiHeadersFor(episodeUrl: String, baseUrl: String, baseHeaders: Headers): Headers {
        val origin = baseUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: baseUrl.trimEnd('/')
        return baseHeaders.newBuilder()
            .set("Accept", "*/*")
            .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            .set("Origin", origin)
            .set("Referer", episodeUrl)
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "same-origin")
            .build()
    }

    fun extractBloggerUrl(html: String): String? {
        val raw = BLOGGER_URL_REGEX.find(html)?.value ?: return null
        return raw.replace("\\/", "/").replace("&amp;", "&")
    }

    fun extractBloggerUrlFromReferer(referer: String?): String? {
        if (referer.isNullOrBlank()) return null
        extractBloggerUrl(referer)?.let { return it }
        val forigin = referer.toHttpUrlOrNull()?.queryParameter("forigin") ?: return null
        return extractBloggerUrl(forigin)
    }

    fun classifyHttpErrorBody(
        code: Int,
        body: String,
        server: String?,
        cfMitigated: String?,
    ): String {
        if (code in 200..299) return "ok"
        if (body.isBlank()) return "empty"
        val lower = body.lowercase()
        return when {
            cfMitigated.equals("challenge", ignoreCase = true) ||
                "just a moment" in lower ||
                "cf-chl-" in lower -> "cf_challenge"

            code == 410 || "player expirado" in lower -> "player_expired"

            code == 403 && "nginx" in lower -> "nginx_403"

            code == 403 && body.trimStart().startsWith("{") -> "json_403"

            code == 403 && (
                server.equals("cloudflare", ignoreCase = true) ||
                    "cloudflare" in lower ||
                    "attention required" in lower
                ) -> "cf_waf_403"

            "<html" in lower || "<!doctype" in lower -> "html_other"

            else -> "other"
        }
    }

    fun summarizeRequestHeadersForLog(headers: Headers): String = summarizeHeadersForLog(
        (0 until headers.size).asSequence().map { idx -> headers.name(idx) to headers.value(idx) },
    )

    private fun summarizeHeadersForLog(pairs: Sequence<Pair<String, String>>): String {
        var ua: String? = null
        var referer: String? = null
        var sfd: String? = null
        var sfm: String? = null
        var sfs: String? = null
        var hasXrw = false
        var hasCookie = false

        for ((rawName, rawValue) in pairs) {
            when (rawName.lowercase()) {
                "user-agent" -> ua = rawValue
                "referer" -> referer = rawValue
                "sec-fetch-dest" -> sfd = rawValue.trim()
                "sec-fetch-mode" -> sfm = rawValue.trim()
                "sec-fetch-site" -> sfs = rawValue.trim()
                "x-requested-with" -> if (rawValue.isNotBlank()) hasXrw = true
                "cookie" -> if (rawValue.isNotBlank()) hasCookie = true
            }
        }

        val uaSummary = classifyUserAgent(ua)
        val parsedRef = referer?.toHttpUrlOrNull()
        val refHost = parsedRef?.host?.lowercase() ?: "none"
        val refPath = when {
            parsedRef == null -> "none"
            parsedRef.encodedPath.length <= 1 -> "origin"
            else -> "full"
        }
        return "ua=$uaSummary refHost=$refHost refPath=$refPath sfd=${sfd ?: "none"} sfm=${sfm ?: "none"} sfs=${sfs ?: "none"} hasXrw=$hasXrw hasCookie=$hasCookie"
    }

    fun summarizeResponseHeadersForLog(headers: Headers): String = (0 until headers.size)
        .asSequence()
        .map { idx -> headers.name(idx).lowercase() to headers.value(idx) }
        .filter { (name, _) -> name in SAFE_RESPONSE_LOG_HEADER_NAMES }
        .map { (name, value) -> "$name=${AnimePlayerLog.sanitizeMessage(value)}" }
        .joinToString(" ")
        .ifEmpty { "none" }

    fun summarizeResponseHeaderMapForLog(headers: Map<String, String>?): String {
        if (headers.isNullOrEmpty()) return "none"
        return headers.entries
            .asSequence()
            .map { (name, value) -> name.lowercase() to value }
            .filter { (name, _) -> name in SAFE_RESPONSE_LOG_HEADER_NAMES }
            .map { (name, value) -> "$name=${AnimePlayerLog.sanitizeMessage(value)}" }
            .joinToString(" ")
            .ifEmpty { "none" }
    }

    fun summarizeWireHeadersForLog(wireHeaders: Headers?, expectedUserAgent: String?): String {
        if (wireHeaders == null) return "none"
        val wireUa = extractUserAgent(wireHeaders)
        val uaSummary = classifyUserAgent(wireUa)
        val uaMatch = when {
            expectedUserAgent.isNullOrBlank() || wireUa.isNullOrBlank() -> "unknown"
            else -> (wireUa == expectedUserAgent).toString()
        }
        var hasXrw = false
        val cookieValues = mutableListOf<String>()
        for (idx in 0 until wireHeaders.size) {
            val name = wireHeaders.name(idx)
            val value = wireHeaders.value(idx)
            when {
                name.equals("x-requested-with", ignoreCase = true) -> if (value.isNotBlank()) hasXrw = true
                name.equals("cookie", ignoreCase = true) -> if (value.isNotBlank()) cookieValues += value
            }
        }
        val cookieCount = cookieValues
            .joinToString(";")
            .split(';')
            .count { it.isNotBlank() }
        val hasCookie = cookieCount > 0
        return "ua=$uaSummary uaMatch=$uaMatch hasXrw=$hasXrw hasCookie=$hasCookie cookieCount=$cookieCount"
    }

    fun parseApiErrorSummary(body: String): String {
        if (body.isBlank()) return "errCode=none errMsg=none"
        val dto = runCatching { body.parseAs<ApiErrorDto>() }.getOrNull()
        val code = AnimePlayerLog.sanitizeMessage(dto?.code).ifEmpty { "none" }
        val msg = AnimePlayerLog.sanitizeMessage(dto?.message).ifEmpty { "none" }
        return "errCode=$code errMsg=$msg"
    }

    private fun classifyUserAgent(ua: String?): String {
        if (ua.isNullOrBlank()) return "missing"
        val lower = ua.lowercase()
        val family = when {
            "okhttp" in lower -> "okhttp"
            "dalvik" in lower -> "dalvik"
            "chrome" in lower -> "chrome"
            "firefox" in lower -> "firefox"
            else -> "other"
        }
        val hasWv = "; wv" in lower || "(wv)" in lower || " wv)" in lower
        return if (hasWv) "$family(wv)" else family
    }

    suspend fun resolveBloggerUrlFromKyoz(
        kyozUrl: String,
        episodeUrl: String,
        baseUrl: String,
        baseHeaders: Headers,
        fetchHtml: suspend (String, Headers) -> String?,
    ): String? {
        val reqHeaders = iframeHeadersFor(episodeUrl, baseUrl, baseHeaders)
        val html = fetchHtml(kyozUrl, reqHeaders) ?: return null
        val bloggerUrl = extractBloggerUrl(html)
        if (bloggerUrl == null) {
            AnimePlayerLog.w(
                AnimePlayerLog.Category.EXTRACTION_FAILURE,
                "blogger token not found in kyoz html len=${html.length} url=${AnimePlayerLog.sanitizeUrlForLog(kyozUrl)}",
            )
        }
        return bloggerUrl
    }

    suspend fun resolveBloggerUrlWithRefererFallback(
        kyozUrl: String,
        episodeUrl: String,
        baseUrl: String,
        baseHeaders: Headers,
        fetchDetailed: suspend (String, Headers) -> KyozFetchResult,
    ): String? {
        val originHeaders = iframeHeadersFor(episodeUrl, baseUrl, baseHeaders, fullReferer = false)
        val first = fetchDetailed(kyozUrl, originHeaders)
        val result = if (first.code == 403) {
            val fullHeaders = iframeHeadersFor(episodeUrl, baseUrl, baseHeaders, fullReferer = true)
            fetchDetailed(kyozUrl, fullHeaders)
        } else {
            first
        }
        val html = result.html?.takeIf { result.code in 200..299 && it.isNotBlank() } ?: return null
        val bloggerUrl = extractBloggerUrl(html)
        if (bloggerUrl == null) {
            AnimePlayerLog.w(
                AnimePlayerLog.Category.EXTRACTION_FAILURE,
                "blogger token not found in kyoz html len=${html.length} url=${AnimePlayerLog.sanitizeUrlForLog(kyozUrl)}",
            )
        }
        return bloggerUrl
    }

    suspend fun fetchKyozHtmlDetailed(
        kyozUrl: String,
        reqHeaders: Headers,
        client: OkHttpClient,
    ): KyozFetchResult = client.get(
        url = kyozUrl,
        headers = reqHeaders,
        cacheControl = CacheControl.FORCE_NETWORK,
        ensureSuccess = false,
    ).use { response ->
        val body = response.bodyString()
        val server = response.header("Server")
        val cfMitigated = response.header("cf-mitigated")
        val contentType = response.header("Content-Type")
        val bodyKind = classifyHttpErrorBody(response.code, body, server, cfMitigated)
        if (!response.isSuccessful) {
            val wireSummary = summarizeWireHeadersForLog(
                response.networkResponse?.request?.headers,
                extractUserAgent(reqHeaders),
            )
            AnimePlayerLog.w(
                AnimePlayerLog.Category.EXTRACTION_FAILURE,
                "kyoz HTTP ${response.code} kind=$bodyKind srv=$server cf=$cfMitigated ct=$contentType len=${body.length} " +
                    "req=[${summarizeRequestHeadersForLog(reqHeaders)}] wire=[$wireSummary] resHdrs=[${summarizeResponseHeadersForLog(response.headers)}] " +
                    "url=${AnimePlayerLog.sanitizeUrlForLog(kyozUrl)}",
            )
        } else if (body.isBlank()) {
            AnimePlayerLog.w(
                AnimePlayerLog.Category.EMPTY_RESPONSE,
                "kyoz empty body for ${AnimePlayerLog.sanitizeUrlForLog(kyozUrl)}",
            )
        }
        KyozFetchResult(
            code = response.code,
            html = body.takeIf { response.isSuccessful && it.isNotBlank() },
            server = server,
            cfMitigated = cfMitigated,
            contentType = contentType,
            bodyLength = body.length,
            bodyKind = bodyKind,
        )
    }

    suspend fun fetchKyozHtml(
        kyozUrl: String,
        reqHeaders: Headers,
        client: OkHttpClient,
    ): String? {
        val result = fetchKyozHtmlDetailed(kyozUrl, reqHeaders, client)
        return result.html?.takeIf { result.code in 200..299 && it.isNotBlank() }
    }

    suspend fun verifyAccess(
        baseUrl: String,
        episodeUrl: String,
        turnstileToken: String,
        playerKeys: List<String>,
        baseHeaders: Headers,
        postVerify: suspend (url: String, headers: Headers, body: RequestBody) -> VerifyFetchResult,
    ): VerifyFetchResult {
        val url = "${baseUrl.trimEnd('/')}/wp-json/ap-player/v2/verify"
        val headers = apiHeadersFor(episodeUrl, baseUrl, baseHeaders)
        val body = VerifyRequestDto(turnstileToken = turnstileToken, playerKeys = playerKeys).toJsonRequestBody()
        return postVerify(url, headers, body)
    }

    suspend fun fetchVerify(
        baseUrl: String,
        episodeUrl: String,
        turnstileToken: String,
        playerKeys: List<String>,
        baseHeaders: Headers,
        client: OkHttpClient,
    ): VerifyFetchResult = verifyAccess(baseUrl, episodeUrl, turnstileToken, playerKeys, baseHeaders) { url, headers, body ->
        client.post(
            url = url,
            headers = headers,
            body = body,
            ensureSuccess = false,
        ).use { response ->
            if (!response.isSuccessful) {
                val respBody = response.bodyString()
                val server = response.header("Server")
                val cfMitigated = response.header("cf-mitigated")
                val contentType = response.header("Content-Type")
                val bodyKind = classifyHttpErrorBody(response.code, respBody, server, cfMitigated)
                val apiErr = parseApiErrorSummary(respBody)
                val wireSummary = summarizeWireHeadersForLog(
                    response.networkResponse?.request?.headers,
                    extractUserAgent(headers),
                )
                val resHdrs = summarizeResponseHeadersForLog(response.headers)
                val errDto = runCatching { respBody.parseAs<ApiErrorDto>() }.getOrNull()
                AnimePlayerLog.w(
                    AnimePlayerLog.Category.AUTH_VERIFY_FAILURE,
                    "ap-player /verify HTTP ${response.code} kind=$bodyKind srv=$server cf=$cfMitigated ct=$contentType len=${respBody.length} " +
                        "$apiErr wire=[$wireSummary] resHdrs=[$resHdrs]",
                )
                return@use VerifyFetchResult(
                    code = response.code,
                    ticket = null,
                    servers = emptyList(),
                    errorCode = errDto?.code,
                    errorMessage = errDto?.message,
                )
            }
            runCatching { response.parseAs<VerifyResponseDto>() }
                .onFailure {
                    AnimePlayerLog.e(
                        AnimePlayerLog.Category.PARSING_ERROR,
                        "ap-player /verify JSON parse failed",
                        it,
                    )
                }
                .getOrNull()
                ?.let { dto ->
                    VerifyFetchResult(
                        code = response.code,
                        ticket = dto.ticket?.takeIf { it.isNotBlank() },
                        servers = dto.servers,
                    )
                } ?: VerifyFetchResult(
                code = response.code,
                ticket = null,
                servers = emptyList(),
            )
        }
    }

    suspend fun resolveServerUrl(
        baseUrl: String,
        episodeUrl: String,
        ticket: String,
        playerKey: String,
        baseHeaders: Headers,
        postLoad: suspend (url: String, headers: Headers, body: RequestBody) -> LoadResponseDto?,
    ): String? {
        val url = "${baseUrl.trimEnd('/')}/wp-json/ap-player/v2/load"
        val headers = apiHeadersFor(episodeUrl, baseUrl, baseHeaders)
        val body = LoadRequestDto(ticket = ticket, playerKey = playerKey).toJsonRequestBody()
        val dto = postLoad(url, headers, body) ?: return null
        val resolvedUrl = dto.url?.takeIf { it.isNotBlank() }
        if (!dto.success || resolvedUrl == null) {
            AnimePlayerLog.w(
                AnimePlayerLog.Category.EMPTY_RESPONSE,
                "ap-player /load unsuccessful success=${dto.success} msg=${AnimePlayerLog.sanitizeMessage(dto.message)}",
            )
            return null
        }
        return resolvedUrl
    }

    suspend fun fetchServerUrl(
        baseUrl: String,
        episodeUrl: String,
        ticket: String,
        playerKey: String,
        baseHeaders: Headers,
        client: OkHttpClient,
    ): String? = resolveServerUrl(baseUrl, episodeUrl, ticket, playerKey, baseHeaders) { url, headers, body ->
        client.post(
            url = url,
            headers = headers,
            body = body,
            ensureSuccess = false,
        ).use { response ->
            if (!response.isSuccessful) {
                val body = response.bodyString()
                val server = response.header("Server")
                val cfMitigated = response.header("cf-mitigated")
                val contentType = response.header("Content-Type")
                val bodyKind = classifyHttpErrorBody(response.code, body, server, cfMitigated)
                val apiErr = parseApiErrorSummary(body)
                val wireSummary = summarizeWireHeadersForLog(
                    response.networkResponse?.request?.headers,
                    extractUserAgent(headers),
                )
                val resHdrs = summarizeResponseHeadersForLog(response.headers)
                AnimePlayerLog.w(
                    AnimePlayerLog.Category.REQUEST_FAILURE,
                    "ap-player /load HTTP ${response.code} kind=$bodyKind srv=$server cf=$cfMitigated ct=$contentType len=${body.length} " +
                        "$apiErr wire=[$wireSummary] resHdrs=[$resHdrs]",
                )
                return@use null
            }
            runCatching { response.parseAs<LoadResponseDto>() }
                .onFailure {
                    AnimePlayerLog.e(
                        AnimePlayerLog.Category.PARSING_ERROR,
                        "ap-player /load JSON parse failed",
                        it,
                    )
                }
                .getOrNull()
        }
    }
}
