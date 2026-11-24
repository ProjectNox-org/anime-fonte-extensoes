package eu.kanade.tachiyomi.animeextension.pt.animeplayer

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val WEBVIEW_WV_REGEX = Regex(";\\s*wv(?=\\))")
private val WEBVIEW_VERSION_REGEX = Regex("\\bVersion/\\d+(?:\\.\\d+)+\\s+")
private val MULTI_SPACE_REGEX = Regex(" {2,}")
private val BLOCKED_AD_HOSTS = setOf(
    "abscloud.org",
    "bauval.org",
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
)

class GuardJsBridge(private val onPayload: (String) -> Unit) {
    @JavascriptInterface
    fun onGuardUpdate(payloadJson: String) = onPayload(payloadJson)
}

internal class PlayerGuardResolver {

    enum class GuardStatus {
        SUCCESS,
        CLOUDFLARE_CHALLENGE_PENDING,
        AUTH_VERIFY_FAILURE,
        EMPTY_RESPONSE,
        REQUEST_FAILURE,
        PARSING_ERROR,
        TIMEOUT,
    }

    class ResolvedGuardState(
        val ticket: String?,
        val servers: List<VerifyServerDto>,
        val urls: List<String>,
        val status: GuardStatus = GuardStatus.EMPTY_RESPONSE,
        val stage: String = "init",
        val turnstileLoaded: Boolean = false,
        val turnstileRendered: Boolean = false,
        val verifyStatus: Int? = null,
        val loadStatus: Int? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val events: List<String> = emptyList(),
        val resolvedUserAgent: String? = null,
        val turnstileToken: String? = null,
        val playerKeys: List<String> = emptyList(),
    )

    private val context: Application by lazy { Injekt.get() }
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun resolve(
        episodeUrl: String,
        userAgent: String? = null,
    ): ResolvedGuardState {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AnimePlayerLog.w(AnimePlayerLog.Category.REQUEST_FAILURE, "PlayerGuardResolver called on main thread")
            return emptyState(status = GuardStatus.REQUEST_FAILURE, stage = "main_thread")
        }

        val episodeHost = episodeUrl.toHttpUrlOrNull()?.host?.lowercase()
        if (episodeHost == null) {
            AnimePlayerLog.w(AnimePlayerLog.Category.REQUEST_FAILURE, "invalid episode URL in guard resolver")
            return emptyState(status = GuardStatus.REQUEST_FAILURE, stage = "invalid_url")
        }

        val latch = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val kyozGraceScheduled = AtomicBoolean(false)
        val stateRef = AtomicReference(emptyState())
        val mainFrameErrorRef = AtomicReference<String?>(null)
        val loadUrlsRef = AtomicReference<List<String>>(emptyList())
        val interceptedKyozUrls = Collections.synchronizedList(mutableListOf<String>())
        val directUrls = Collections.synchronizedList(mutableListOf<String>())
        val eventTrail = Collections.synchronizedList(mutableListOf<String>())
        val loggedMilestones = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val webViewRef = AtomicReference<WebView?>(null)
        val resolvedUaRef = AtomicReference<String?>(null)

        fun recordEvent(event: String) {
            synchronized(eventTrail) {
                if (eventTrail.size < MAX_EVENT_TRAIL && eventTrail.lastOrNull() != event) {
                    eventTrail.add(event)
                }
            }
        }

        fun logMilestoneOnce(key: String, detail: String, event: String? = null) {
            if (loggedMilestones.add(key)) {
                event?.let(::recordEvent)
                AnimePlayerLog.d(detail)
            }
        }

        fun scheduleKyozGraceFallback() {
            if (kyozGraceScheduled.compareAndSet(false, true)) {
                recordEvent("kyoz_grace_wait")
                AnimePlayerLog.d("waiting up to ${KYOZ_RESOLVE_GRACE_MS}ms for kyoz iframe to resolve direct player url")
                handler.postDelayed(
                    {
                        if (finished.compareAndSet(false, true)) {
                            recordEvent("kyoz_grace_timeout")
                            latch.countDown()
                        }
                    },
                    KYOZ_RESOLVE_GRACE_MS,
                )
            }
        }

        fun updateState(parsed: ResolvedGuardState?) {
            if (parsed == null) return
            val mergedLoadUrls = if (parsed.urls.isNotEmpty()) {
                (loadUrlsRef.get() + parsed.urls).distinct().also { loadUrlsRef.set(it) }
            } else {
                loadUrlsRef.get()
            }
            val directSnapshot = synchronized(directUrls) { directUrls.toList() }
            val kyozSnapshot = synchronized(interceptedKyozUrls) { interceptedKyozUrls.toList() }
            val preferredUrls = selectPreferredUrls(
                loadUrls = mergedLoadUrls,
                interceptedKyozUrls = kyozSnapshot,
                directUrls = directSnapshot,
            )
            val current = stateRef.get()
            val mergedStage = parsed.stage.takeIf { it.isNotBlank() && it != "init" } ?: current.stage
            val updated = ResolvedGuardState(
                ticket = parsed.ticket ?: current.ticket,
                servers = parsed.servers.ifEmpty { current.servers },
                urls = preferredUrls,
                status = current.status,
                stage = mergedStage,
                turnstileLoaded = current.turnstileLoaded || parsed.turnstileLoaded,
                turnstileRendered = current.turnstileRendered || parsed.turnstileRendered,
                verifyStatus = parsed.verifyStatus ?: current.verifyStatus,
                loadStatus = parsed.loadStatus ?: current.loadStatus,
                errorCode = parsed.errorCode ?: current.errorCode,
                errorMessage = parsed.errorMessage ?: current.errorMessage,
                turnstileToken = parsed.turnstileToken ?: current.turnstileToken,
                playerKeys = parsed.playerKeys.ifEmpty { current.playerKeys },
            )
            stateRef.set(updated)

            logMilestoneOnce(
                "stage:${updated.stage}",
                "guard stage=${updated.stage} tsLoaded=${updated.turnstileLoaded} tsRendered=${updated.turnstileRendered} " +
                    "verify=${updated.verifyStatus} load=${updated.loadStatus} urls=${updated.urls.size} direct=${directSnapshot.size} servers=${updated.servers.size}",
                event = "stage:${updated.stage}",
            )

            val verifyCaptured = updated.turnstileToken != null && updated.playerKeys.isNotEmpty()
            if (!verifyCaptured && shouldWaitForDirectPlayerUrl(preferredUrls, directSnapshot, updated.servers.size)) {
                scheduleKyozGraceFallback()
                return
            }

            val successReady = verifyCaptured ||
                directSnapshot.isNotEmpty() ||
                preferredUrls.isNotEmpty() ||
                (updated.ticket != null && updated.servers.size > 1)
            val failureReady = !successReady && isTerminalFailureStage(updated.stage)
            if ((successReady || failureReady) && finished.compareAndSet(false, true)) {
                latch.countDown()
            }
        }

        fun recordDirectUrl(url: String) {
            synchronized(directUrls) {
                if (url !in directUrls) directUrls.add(url)
            }
            logMilestoneOnce(
                "direct:${AnimePlayerLog.sanitizeUrlForLog(url)}",
                "intercepted direct player url=${AnimePlayerLog.sanitizeUrlForLog(url)}",
                event = "direct_url_intercepted",
            )
            updateState(
                ResolvedGuardState(
                    ticket = null,
                    servers = emptyList(),
                    urls = emptyList(),
                    stage = "direct_url_intercepted",
                ),
            )
        }

        fun recordKyozDocumentUrl(url: String) {
            synchronized(interceptedKyozUrls) {
                if (url !in interceptedKyozUrls) interceptedKyozUrls.add(url)
            }
            logMilestoneOnce(
                "kyoz_doc:${AnimePlayerLog.sanitizeUrlForLog(url)}",
                "intercepted kyoz document request=${AnimePlayerLog.sanitizeUrlForLog(url)}",
                event = "kyoz_iframe_req",
            )
            scheduleKyozGraceFallback()
            updateState(
                ResolvedGuardState(
                    ticket = null,
                    servers = emptyList(),
                    urls = emptyList(),
                    stage = "kyoz_iframe_req",
                ),
            )
        }

        val bridge = GuardJsBridge { payloadJson ->
            updateState(parseBridgePayload(payloadJson))
        }

        val posted = handler.post {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                val webView = WebView(context).apply {
                    val metrics = context.resources?.displayMetrics
                    val viewportWidth = metrics?.widthPixels?.takeIf { it > 0 } ?: DEFAULT_VIEWPORT_WIDTH
                    val viewportHeight = metrics?.heightPixels?.takeIf { it > 0 } ?: DEFAULT_VIEWPORT_HEIGHT
                    measure(
                        View.MeasureSpec.makeMeasureSpec(viewportWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY),
                    )
                    layout(0, 0, viewportWidth, viewportHeight)
                    onResume()
                    resumeTimers()

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        useWideViewPort = true
                        loadWithOverviewMode = false
                        loadsImagesAutomatically = true
                        blockNetworkImage = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        val effectiveUa = resolveWebViewUserAgent(userAgent, userAgentString)
                        if (effectiveUa != null) {
                            userAgentString = effectiveUa
                        }
                        resolvedUaRef.set(effectiveUa ?: userAgentString)
                    }

                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(bridge, BRIDGE_NAME)
                    recordEvent("webview_init")

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message().orEmpty()
                            val level = consoleMessage?.messageLevel()
                            if (level == ConsoleMessage.MessageLevel.ERROR || "[Player Guard]" in msg || "turnstile" in msg.lowercase()) {
                                recordEvent("console_${level?.name?.lowercase() ?: "msg"}")
                                AnimePlayerLog.d("webview console [${level?.name}]: ${AnimePlayerLog.sanitizeMessage(msg)}")
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            if (request?.isForMainFrame != true) return false
                            val targetHost = request.url?.host?.lowercase() ?: return false
                            if (targetHost != episodeHost) {
                                recordEvent("nav_blocked:$targetHost")
                                AnimePlayerLog.d("blocked external main-frame navigation host=${AnimePlayerLog.sanitizeMessage(targetHost)}")
                                return true
                            }
                            return false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            if (isBlockedAdHost(reqUrl)) {
                                val adHost = reqUrl.toHttpUrlOrNull()?.host?.lowercase() ?: "ad"
                                logMilestoneOnce("ad:$adHost", "blocked popunder/ad host=$adHost")
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    204,
                                    "No Content",
                                    emptyMap(),
                                    ByteArrayInputStream(ByteArray(0)),
                                )
                            }
                            when {
                                "challenges.cloudflare.com/turnstile" in reqUrl ->
                                    logMilestoneOnce("cf_turnstile", "webview requesting turnstile api", event = "ts_api_req")

                                "/wp-json/ap-player/v2/verify" in reqUrl ->
                                    logMilestoneOnce("ap_verify", "webview calling ap-player/v2/verify", event = "ap_verify_req")

                                "/wp-json/ap-player/v2/load" in reqUrl ->
                                    logMilestoneOnce("ap_load", "webview calling ap-player/v2/load", event = "ap_load_req")
                            }
                            val directUrl = extractDirectPlayerUrl(reqUrl, request.requestHeaders)
                            if (directUrl != null) {
                                recordDirectUrl(directUrl)
                                // Prevent WebView from fetching Blogger UI once we captured the tokenized URL.
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    204,
                                    "No Content",
                                    emptyMap(),
                                    ByteArrayInputStream(ByteArray(0)),
                                )
                            }
                            if (isKyozDocumentRequest(reqUrl, request.requestHeaders)) {
                                recordKyozDocumentUrl(reqUrl)
                                return null
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                            super.onPageCommitVisible(view, url)
                            if (finished.get()) return
                            val committedHost = url?.toHttpUrlOrNull()?.host?.lowercase() ?: return
                            if (committedHost != episodeHost) return
                            recordEvent("page_commit")
                            AnimePlayerLog.d("webview page commit visible, attaching passive guard observer")
                            view?.evaluateJavascript(PASSIVE_OBSERVER_SCRIPT, null)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                val errSummary = "${error?.errorCode}:${AnimePlayerLog.sanitizeMessage(error?.description?.toString())}"
                                mainFrameErrorRef.compareAndSet(null, errSummary)
                                recordEvent("main_err:${error?.errorCode}")
                                AnimePlayerLog.w(
                                    AnimePlayerLog.Category.REQUEST_FAILURE,
                                    "webview main-frame error=$errSummary url=${AnimePlayerLog.sanitizeUrlForLog(request.url.toString())}",
                                )
                                if (finished.compareAndSet(false, true)) {
                                    latch.countDown()
                                }
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            val reqUrl = request?.url?.toString().orEmpty()
                            val status = errorResponse?.statusCode ?: return
                            val isMainFrame = request?.isForMainFrame == true
                            val isGuardOrChallenge = "/wp-json/ap-player/v2/" in reqUrl ||
                                "challenges.cloudflare.com" in reqUrl ||
                                KyozExtractor.isKyozUrl(reqUrl) ||
                                "blogger.com" in reqUrl
                            if (!isMainFrame && !isGuardOrChallenge) return

                            val host = reqUrl.toHttpUrlOrNull()?.host?.lowercase() ?: "unknown"
                            val safeUrl = AnimePlayerLog.sanitizeUrlForLog(reqUrl)
                            if ("/cdn-cgi/challenge-platform/" in reqUrl && "/pat/" in reqUrl && status == 401) {
                                recordEvent("pat_401")
                                AnimePlayerLog.d("webview http $status (expected PAT probe) $safeUrl")
                            } else {
                                recordEvent("http:$status:$host")
                                val mime = AnimePlayerLog.sanitizeMessage(errorResponse.mimeType).ifEmpty { "none" }
                                val resHdrs = KyozExtractor.summarizeResponseHeaderMapForLog(errorResponse.responseHeaders)
                                AnimePlayerLog.w(
                                    AnimePlayerLog.Category.REQUEST_FAILURE,
                                    "webview http $status main=$isMainFrame mime=$mime resHdrs=[$resHdrs] url=$safeUrl",
                                )
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (finished.get()) return
                            val loadedHost = url?.toHttpUrlOrNull()?.host?.lowercase() ?: return
                            if (loadedHost != episodeHost) {
                                recordEvent("other_host:$loadedHost")
                                AnimePlayerLog.d("webview finished on different host=${AnimePlayerLog.sanitizeMessage(loadedHost)}")
                                return
                            }
                            recordEvent("page_finished")
                            AnimePlayerLog.d("webview page finished, attaching passive guard observer")
                            view?.evaluateJavascript(PASSIVE_OBSERVER_SCRIPT, null)
                        }
                    }
                }
                webViewRef.set(webView)
                webView.loadUrl(
                    episodeUrl,
                    mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache"),
                )
            } catch (throwable: Throwable) {
                recordEvent("setup_err:${throwable.javaClass.simpleName}")
                AnimePlayerLog.e(
                    AnimePlayerLog.Category.REQUEST_FAILURE,
                    "webview setup failed",
                    throwable,
                )
                mainFrameErrorRef.compareAndSet(null, throwable.javaClass.simpleName)
                if (finished.compareAndSet(false, true)) {
                    latch.countDown()
                }
            }
        }

        val completed = if (posted) {
            runCatching { latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
        } else {
            recordEvent("post_failed")
            AnimePlayerLog.w(AnimePlayerLog.Category.REQUEST_FAILURE, "failed to post webview setup to main handler")
            false
        }

        val teardownLatch = CountDownLatch(1)
        val destroyed = handler.post {
            try {
                CookieManager.getInstance().flush()
                webViewRef.getAndSet(null)?.apply {
                    onPause()
                    stopLoading()
                    removeJavascriptInterface(BRIDGE_NAME)
                    destroy()
                }
            } catch (throwable: Throwable) {
                AnimePlayerLog.e(AnimePlayerLog.Category.REQUEST_FAILURE, "webview teardown failed", throwable)
            } finally {
                teardownLatch.countDown()
            }
        }
        if (destroyed) {
            runCatching { teardownLatch.await(TEARDOWN_ACK_SECONDS, TimeUnit.SECONDS) }
        }

        val rawFinalState = stateRef.get()
        val preferredFinalUrls = selectPreferredUrls(
            loadUrls = loadUrlsRef.get(),
            interceptedKyozUrls = synchronized(interceptedKyozUrls) { interceptedKyozUrls.toList() },
            directUrls = synchronized(directUrls) { directUrls.toList() },
        )
        val finalState = ResolvedGuardState(
            ticket = rawFinalState.ticket,
            servers = rawFinalState.servers,
            urls = preferredFinalUrls,
            status = rawFinalState.status,
            stage = rawFinalState.stage,
            turnstileLoaded = rawFinalState.turnstileLoaded,
            turnstileRendered = rawFinalState.turnstileRendered,
            verifyStatus = rawFinalState.verifyStatus,
            loadStatus = rawFinalState.loadStatus,
            errorCode = rawFinalState.errorCode,
            errorMessage = rawFinalState.errorMessage,
            turnstileToken = rawFinalState.turnstileToken,
            playerKeys = rawFinalState.playerKeys,
        )
        val finalStatus = classifyOutcome(
            state = finalState,
            timedOut = !completed,
            mainFrameError = mainFrameErrorRef.get(),
        )
        return ResolvedGuardState(
            ticket = finalState.ticket,
            servers = finalState.servers,
            urls = preferredFinalUrls,
            status = finalStatus,
            stage = finalState.stage,
            turnstileLoaded = finalState.turnstileLoaded,
            turnstileRendered = finalState.turnstileRendered,
            verifyStatus = finalState.verifyStatus,
            loadStatus = finalState.loadStatus,
            errorCode = finalState.errorCode,
            errorMessage = finalState.errorMessage,
            events = synchronized(eventTrail) { eventTrail.toList() },
            resolvedUserAgent = resolvedUaRef.get(),
            turnstileToken = finalState.turnstileToken,
            playerKeys = finalState.playerKeys,
        )
    }

    companion object {
        private const val BRIDGE_NAME = "AnreyalpGuardBridge"
        private const val TIMEOUT_SECONDS = 30L
        private const val KYOZ_RESOLVE_GRACE_MS = 6_000L
        private const val TEARDOWN_ACK_SECONDS = 2L
        private const val DEFAULT_VIEWPORT_WIDTH = 1080
        private const val DEFAULT_VIEWPORT_HEIGHT = 1920
        private const val MAX_EVENT_TRAIL = 24

        private val DOCUMENT_FETCH_DESTS = setOf("iframe", "document")

        private val TERMINAL_FAILURE_STAGES = setOf(
            "verify_failed",
            "load_failed",
            "panel_error",
            "turnstile_missing",
            "no_placeholder",
        )

        private val CHALLENGE_PENDING_ERROR_CODES = setOf(
            "turnstile_failed",
            "turnstile_expired",
            "turnstile_pending",
        )

        private fun emptyState(
            status: GuardStatus = GuardStatus.EMPTY_RESPONSE,
            stage: String = "init",
        ) = ResolvedGuardState(
            ticket = null,
            servers = emptyList(),
            urls = emptyList(),
            status = status,
            stage = stage,
        )

        fun isBlockedAdHost(url: String): Boolean {
            val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
            return BLOCKED_AD_HOSTS.any { blocked -> host == blocked || host.endsWith(".$blocked") }
        }

        fun isDirectPlayerUrl(url: String): Boolean = "blogger.com/video.g" in url || "cdn.animeson.com.br" in url

        fun extractDirectPlayerUrl(url: String, requestHeaders: Map<String, String>): String? {
            if (isDirectPlayerUrl(url)) return url
            val referer = requestHeaders.entries
                .firstOrNull { (name, _) -> name.equals("referer", ignoreCase = true) }
                ?.value
            return KyozExtractor.extractBloggerUrlFromReferer(referer)
        }

        fun isKyozDocumentRequest(url: String, requestHeaders: Map<String, String>): Boolean {
            if (!KyozExtractor.isKyozUrl(url)) return false
            val secFetchDest = requestHeaders.entries
                .firstOrNull { (name, _) -> name.equals("sec-fetch-dest", ignoreCase = true) }
                ?.value
                ?.trim()
                ?.lowercase()
            return if (secFetchDest != null) {
                secFetchDest in DOCUMENT_FETCH_DESTS
            } else {
                url.toHttpUrlOrNull()?.encodedPath?.startsWith("/media/") == true
            }
        }

        fun shouldWaitForDirectPlayerUrl(
            urls: List<String>,
            directUrls: List<String>,
            serverCount: Int,
        ): Boolean = directUrls.isEmpty() && serverCount <= 1 && urls.isNotEmpty() && urls.all(KyozExtractor::isKyozUrl)

        fun selectPreferredUrls(
            loadUrls: List<String>,
            interceptedKyozUrls: List<String>,
            directUrls: List<String>,
        ): List<String> = when {
            directUrls.isNotEmpty() && loadUrls.size <= 1 -> directUrls.distinct()
            loadUrls.isNotEmpty() -> (directUrls + loadUrls).distinct()
            else -> (directUrls + interceptedKyozUrls).distinct()
        }

        fun shouldRetryGuard(verifyStatus: Int?, attempt: Int): Boolean = verifyStatus == 410 && attempt < 2

        fun resolveWebViewUserAgent(appUserAgent: String?, defaultWebViewUserAgent: String?): String? {
            // Prefer WebView's own UA without the wv marker so Chromium Client Hints match Sec-CH-UA and TLS.
            val rawDefault = defaultWebViewUserAgent?.trim()?.takeIf { it.isNotEmpty() }
            if (rawDefault != null) {
                return rawDefault
                    .replace(WEBVIEW_WV_REGEX, "")
                    .replace(WEBVIEW_VERSION_REGEX, "")
                    .replace(MULTI_SPACE_REGEX, " ")
                    .trim()
            }
            return appUserAgent?.trim()?.takeIf { it.isNotEmpty() }
        }

        fun isTerminalFailureStage(stage: String?): Boolean = stage in TERMINAL_FAILURE_STAGES

        fun classifyOutcome(
            state: ResolvedGuardState,
            timedOut: Boolean,
            mainFrameError: String? = null,
        ): GuardStatus = when {
            (state.turnstileToken != null && state.playerKeys.isNotEmpty()) ||
                state.urls.isNotEmpty() ||
                (state.ticket != null && state.servers.size > 1) -> GuardStatus.SUCCESS
            mainFrameError != null -> GuardStatus.REQUEST_FAILURE
            state.stage == "verify_failed" || (state.verifyStatus != null && state.verifyStatus !in 200..299) ->
                GuardStatus.AUTH_VERIFY_FAILURE
            state.stage == "load_failed" || (state.loadStatus != null && state.loadStatus !in 200..299) ->
                GuardStatus.REQUEST_FAILURE
            state.stage == "panel_error" -> when (state.errorCode) {
                in CHALLENGE_PENDING_ERROR_CODES -> GuardStatus.CLOUDFLARE_CHALLENGE_PENDING
                "turnstile_not_loaded" -> GuardStatus.REQUEST_FAILURE
                else -> GuardStatus.AUTH_VERIFY_FAILURE
            }
            state.stage == "turnstile_missing" -> GuardStatus.REQUEST_FAILURE
            state.stage == "no_placeholder" -> GuardStatus.EMPTY_RESPONSE
            timedOut && state.turnstileRendered && state.verifyStatus == null -> GuardStatus.CLOUDFLARE_CHALLENGE_PENDING
            timedOut && !state.turnstileLoaded -> GuardStatus.REQUEST_FAILURE
            timedOut -> GuardStatus.TIMEOUT
            else -> GuardStatus.EMPTY_RESPONSE
        }

        fun parseBridgePayload(rawJson: String): ResolvedGuardState? = runCatching {
            val dto = rawJson.parseAs<GuardBridgePayloadDto>()
            val candidate = ResolvedGuardState(
                ticket = dto.ticket?.takeIf { it.isNotBlank() },
                servers = dto.servers,
                urls = dto.urls.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                stage = dto.stage?.takeIf { it.isNotBlank() } ?: "bridge",
                turnstileLoaded = dto.turnstileLoaded,
                turnstileRendered = dto.turnstileRendered,
                verifyStatus = dto.verifyStatus,
                loadStatus = dto.loadStatus,
                errorCode = dto.errorCode?.takeIf { it.isNotBlank() },
                errorMessage = dto.errorMessage?.takeIf { it.isNotBlank() }?.let(AnimePlayerLog::sanitizeMessage),
                turnstileToken = dto.turnstileToken?.takeIf { it.isNotBlank() },
                playerKeys = dto.playerKeys.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            )
            ResolvedGuardState(
                ticket = candidate.ticket,
                servers = candidate.servers,
                urls = candidate.urls,
                status = classifyOutcome(candidate, timedOut = false),
                stage = candidate.stage,
                turnstileLoaded = candidate.turnstileLoaded,
                turnstileRendered = candidate.turnstileRendered,
                verifyStatus = candidate.verifyStatus,
                loadStatus = candidate.loadStatus,
                errorCode = candidate.errorCode,
                errorMessage = candidate.errorMessage,
                turnstileToken = candidate.turnstileToken,
                playerKeys = candidate.playerKeys,
            )
        }.onFailure {
            AnimePlayerLog.e(AnimePlayerLog.Category.PARSING_ERROR, "guard bridge JSON parse failed", it)
        }.getOrNull()

        fun passiveObserverScriptForTest(): String = PASSIVE_OBSERVER_SCRIPT

        // Observes first-party fetch responses and panel state passively, and clicks only the site's
        // player placeholder once window.turnstile is ready; never touches Turnstile widgets or iframes.
        private val PASSIVE_OBSERVER_SCRIPT = """
            (function() {
                if (!window.__apGuardState) {
                    window.__apGuardState = {
                        ticket: null,
                        servers: [],
                        urls: [],
                        stage: 'init',
                        turnstileLoaded: false,
                        turnstileRendered: false,
                        verifyStatus: null,
                        loadStatus: null,
                        errorCode: null,
                        errorMessage: null,
                        turnstileToken: null,
                        playerKeys: []
                    };
                }
                function notifyHost() {
                    try {
                        if (window.$BRIDGE_NAME) {
                            window.$BRIDGE_NAME.onGuardUpdate(JSON.stringify(window.__apGuardState));
                        }
                    } catch (e) {}
                }
                if (!window.__apGuardHooked && typeof window.fetch === 'function') {
                    window.__apGuardHooked = true;
                    var origFetch = window.fetch;
                    window.fetch = function() {
                        var arg = arguments[0];
                        var rawUrl = typeof arg === 'string' ? arg : (arg && arg.url ? arg.url : '');
                        try {
                            var reqPath = new URL(rawUrl, location.href).pathname;
                            if (reqPath.indexOf('/wp-json/ap-player/v2/verify') !== -1) {
                                var init = arguments[1];
                                var rawBody = init && typeof init.body === 'string' ? init.body : '';
                                var parsedReq = rawBody ? JSON.parse(rawBody) : null;
                                if (parsedReq && parsedReq.turnstile_token && Array.isArray(parsedReq.player_keys) && parsedReq.player_keys.length > 0) {
                                    window.__apGuardState.turnstileToken = String(parsedReq.turnstile_token);
                                    window.__apGuardState.playerKeys = parsedReq.player_keys.map(String);
                                    window.__apGuardState.stage = 'verify_captured';
                                    notifyHost();
                                    return new Promise(function() {});
                                }
                            }
                        } catch (e) {}
                        return origFetch.apply(this, arguments).then(function(resp) {
                            try {
                                var path = new URL(rawUrl, location.href).pathname;
                                if (path.indexOf('/wp-json/ap-player/v2/verify') !== -1) {
                                    window.__apGuardState.verifyStatus = resp.status;
                                    if (resp.ok && resp.clone) {
                                        resp.clone().json().then(function(data) {
                                            if (data && data.ticket) {
                                                window.__apGuardState.ticket = data.ticket;
                                            }
                                            if (data && Array.isArray(data.servers)) {
                                                window.__apGuardState.servers = data.servers;
                                            }
                                            window.__apGuardState.stage = 'verify_ok';
                                            notifyHost();
                                        }).catch(function() {
                                            window.__apGuardState.stage = 'verify_failed';
                                            window.__apGuardState.errorCode = 'verify_invalid_json';
                                            notifyHost();
                                        });
                                    } else if (resp.clone) {
                                        resp.clone().json().catch(function() { return null; }).then(function(data) {
                                            window.__apGuardState.stage = 'verify_failed';
                                            window.__apGuardState.errorCode = 'verify_http_' + resp.status;
                                            window.__apGuardState.errorMessage = data && data.message ? String(data.message) : ('HTTP ' + resp.status);
                                            notifyHost();
                                        });
                                    }
                                } else if (path.indexOf('/wp-json/ap-player/v2/load') !== -1) {
                                    window.__apGuardState.loadStatus = resp.status;
                                    if (resp.ok && resp.clone) {
                                        resp.clone().json().then(function(data) {
                                            if (data && data.url && window.__apGuardState.urls.indexOf(data.url) === -1) {
                                                window.__apGuardState.urls.push(data.url);
                                                window.__apGuardState.stage = 'load_ok';
                                            } else {
                                                window.__apGuardState.stage = 'load_failed';
                                                window.__apGuardState.errorCode = 'load_empty_url';
                                                window.__apGuardState.errorMessage = data && data.message ? String(data.message) : '';
                                            }
                                            notifyHost();
                                        }).catch(function() {
                                            window.__apGuardState.stage = 'load_failed';
                                            window.__apGuardState.errorCode = 'load_invalid_json';
                                            notifyHost();
                                        });
                                    } else if (resp.clone) {
                                        resp.clone().json().catch(function() { return null; }).then(function(data) {
                                            window.__apGuardState.stage = 'load_failed';
                                            window.__apGuardState.errorCode = 'load_http_' + resp.status;
                                            window.__apGuardState.errorMessage = data && data.message ? String(data.message) : ('HTTP ' + resp.status);
                                            notifyHost();
                                        });
                                    }
                                }
                            } catch (e) {}
                            return resp;
                        });
                    };
                }
                if (!window.__apGuardTriggerTimer && !window.__apGuardClicked) {
                    var triggerAttempts = 0;
                    var totalTicks = 0;
                    window.__apGuardTriggerTimer = setInterval(function() {
                        totalTicks++;
                        if (window.__apGuardClicked) {
                            clearInterval(window.__apGuardTriggerTimer);
                            window.__apGuardTriggerTimer = null;
                            return;
                        }
                        if (document.readyState !== 'loading') {
                            triggerAttempts++;
                            var tsReady = typeof window.turnstile !== 'undefined' && typeof window.turnstile.render === 'function';
                            if (tsReady) {
                                window.__apGuardState.turnstileLoaded = true;
                                var placeholder = document.querySelector('.player-placeholder[data-player-key]');
                                if (!placeholder) {
                                    window.__apGuardState.stage = 'no_placeholder';
                                    notifyHost();
                                    clearInterval(window.__apGuardTriggerTimer);
                                    window.__apGuardTriggerTimer = null;
                                    return;
                                }
                                window.__apGuardClicked = true;
                                window.__apGuardState.stage = 'placeholder_clicked';
                                notifyHost();
                                placeholder.click();
                                clearInterval(window.__apGuardTriggerTimer);
                                window.__apGuardTriggerTimer = null;
                            } else if (triggerAttempts >= 50) {
                                window.__apGuardState.turnstileLoaded = false;
                                window.__apGuardState.stage = 'turnstile_missing';
                                window.__apGuardState.errorCode = 'turnstile_not_loaded';
                                notifyHost();
                                clearInterval(window.__apGuardTriggerTimer);
                                window.__apGuardTriggerTimer = null;
                            }
                        } else if (totalTicks >= 50) {
                            window.__apGuardState.turnstileLoaded = false;
                            window.__apGuardState.stage = 'turnstile_missing';
                            window.__apGuardState.errorCode = 'dom_still_loading';
                            notifyHost();
                            clearInterval(window.__apGuardTriggerTimer);
                            window.__apGuardTriggerTimer = null;
                        }
                    }, 250);
                }
                if (!window.__apGuardDomTimer) {
                    var domAttempts = 0;
                    window.__apGuardDomTimer = setInterval(function() {
                        domAttempts++;
                        try {
                            var iframe = document.querySelector('iframe.ap-player-guard-iframe');
                            if (iframe && iframe.src) {
                                if (window.__apGuardState.urls.indexOf(iframe.src) === -1) {
                                    window.__apGuardState.urls.push(iframe.src);
                                }
                                window.__apGuardState.stage = 'iframe_detected';
                                notifyHost();
                                clearInterval(window.__apGuardDomTimer);
                                window.__apGuardDomTimer = null;
                                return;
                            }
                            var panel = document.querySelector('.ap-player-guard-panel');
                            if (panel) {
                                var txt = (panel.innerText || panel.textContent || '').trim();
                                if (txt.indexOf('Não foi possível abrir o player') !== -1) {
                                    var code = 'panel_error';
                                    if (txt.indexOf('ainda não carregou') !== -1) {
                                        code = 'turnstile_not_loaded';
                                    } else if (txt.indexOf('Cloudflare falhou') !== -1) {
                                        code = 'turnstile_failed';
                                    } else if (txt.indexOf('expirou') !== -1) {
                                        code = 'turnstile_expired';
                                    }
                                    window.__apGuardState.stage = 'panel_error';
                                    window.__apGuardState.errorCode = code;
                                    window.__apGuardState.errorMessage = txt.slice(0, 120);
                                    notifyHost();
                                    clearInterval(window.__apGuardDomTimer);
                                    window.__apGuardDomTimer = null;
                                    return;
                                }
                                if (panel.querySelector('.ap-turnstile-slot') || txt.indexOf('Aguardando verificação') !== -1) {
                                    if (!window.__apGuardState.turnstileRendered) {
                                        window.__apGuardState.turnstileRendered = true;
                                        if (window.__apGuardState.stage === 'placeholder_clicked' || window.__apGuardState.stage === 'init') {
                                            window.__apGuardState.stage = 'turnstile_rendered';
                                        }
                                        notifyHost();
                                    }
                                }
                            }
                        } catch (e) {}
                        if (domAttempts >= 56) {
                            clearInterval(window.__apGuardDomTimer);
                            window.__apGuardDomTimer = null;
                        }
                    }, 500);
                }
            })();
        """.trimIndent()
    }
}
