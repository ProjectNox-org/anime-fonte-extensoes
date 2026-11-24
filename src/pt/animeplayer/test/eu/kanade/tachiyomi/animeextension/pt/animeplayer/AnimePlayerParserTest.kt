package eu.kanade.tachiyomi.animeextension.pt.animeplayer

import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

/**
 * Unit regression tests for AnimePlayer (#139) using sanitized fixtures.
 */
class AnimePlayerParserTest {

    private val baseUrl = "https://anreyalp.vip"
    private val episodeUrl = "$baseUrl/episodios/exemplo-episodio-1/"
    private val kyozPlayerUrl = "https://img.kyoz.net/media/0123456789abcdef0123456789abcdef0123456789abcdef.jpg"

    private val baseHeaders = Headers.headersOf(
        "Referer", "$baseUrl/",
    )

    // Sanitized episode page DOM from anreyalp.vip (2026-11-23)
    private val guardedEpisodeHtml = """
        <!DOCTYPE html>
        <html lang="pt-BR">
        <body>
            <span class="qualityx">Dublado HD</span>
            <div class="playex">
                <div id="option-1" class="player-placeholder active" data-loaded="0" data-player-key="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa">
                    <img src="$baseUrl/wp-content/uploads/2026/11/thumb.jpg" alt="Clique para assistir" class="placeholder-thumb">
                </div>
                <div id="option-2" class="player-placeholder" data-loaded="0" data-player-key="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"></div>
                <div id="option-dup" class="player-placeholder" data-loaded="0" data-player-key="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"></div>
            </div>
        </body>
        </html>
    """.trimIndent()

    // Sanitized HTML wrapper served with HTTP 200 and .jpg path by img.kyoz.net
    private val kyozWrapperHtml = """
        <!DOCTYPE html>
        <html lang="pt-BR">
        <head><title>Video Player</title></head>
        <body>
            <div id="root"></div>
            <script>
                (function() {
                    function parseRoute() {
                        return { type: 'token', token: "https:\/\/www.blogger.com\/video.g?token=FAKE_BLOGGER_TOKEN_XYZ123" };
                    }
                    window.__parseRoute = parseRoute;
                })();
            </script>
        </body>
        </html>
    """.trimIndent()

    @Before
    fun registerJson() {
        Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        AnimePlayerLog.sink = null
    }

    @After
    fun clearLogSink() {
        AnimePlayerLog.sink = null
    }

    private fun htmlResponse(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Forbidden")
        .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
        .build()

    private fun jsonResponse(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Error")
        .body(body.toResponseBody("application/json; charset=UTF-8".toMediaType()))
        .build()

    @Test
    fun `extracts distinct player keys and quality label from episode page`() {
        val doc = Jsoup.parseBodyFragment(guardedEpisodeHtml, episodeUrl)

        val keys = KyozExtractor.extractPlayerKeys(doc)
        val quality = KyozExtractor.extractQualityLabel(doc)

        assertEquals(
            listOf(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            ),
            keys,
        )
        assertEquals("HD", quality)
    }

    @Test
    fun `serializes and parses ap-player v2 verify and load contracts`() {
        val verifyReqJson = VerifyRequestDto(
            turnstileToken = "FAKE_TURNSTILE_TOKEN",
            playerKeys = listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        ).toJsonString()
        assertTrue(verifyReqJson.contains("\"turnstile_token\""))
        assertTrue(verifyReqJson.contains("\"player_keys\""))

        val verifyRespJson = """
            {
              "success": true,
              "ticket": "FAKE_TICKET_48_CHARS",
              "servers": [
                {"id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "label": "Servidor Principal", "index": 1}
              ]
            }
        """.trimIndent()
        val verifyDto = verifyRespJson.parseAs<VerifyResponseDto>()
        assertTrue(verifyDto.success)
        assertEquals("FAKE_TICKET_48_CHARS", verifyDto.ticket)
        assertEquals(1, verifyDto.servers.size)
        assertEquals("Servidor Principal", verifyDto.servers[0].label)

        val loadReqJson = LoadRequestDto(
            ticket = "FAKE_TICKET_48_CHARS",
            playerKey = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        ).toJsonString()
        assertTrue(loadReqJson.contains("\"player_key\""))

        val loadRespJson = """{"success": true, "url": "$kyozPlayerUrl"}"""
        val loadDto = loadRespJson.parseAs<LoadResponseDto>()
        assertTrue(loadDto.success)
        assertEquals(kyozPlayerUrl, loadDto.url)
    }

    @Test
    fun `extracts escaped and direct blogger url from kyoz html wrapper`() {
        assertEquals(
            "https://www.blogger.com/video.g?token=FAKE_BLOGGER_TOKEN_XYZ123",
            KyozExtractor.extractBloggerUrl(kyozWrapperHtml),
        )

        val directIframeHtml = """
            <html><body><iframe src="https://www.blogger.com/video.g?token=DIRECT_TOKEN_99"></iframe></body></html>
        """.trimIndent()
        assertEquals(
            "https://www.blogger.com/video.g?token=DIRECT_TOKEN_99",
            KyozExtractor.extractBloggerUrl(directIframeHtml),
        )

        assertNull(KyozExtractor.extractBloggerUrl("<html><head><title>403 Forbidden</title></head></html>"))
    }

    @Test
    fun `builds required kyoz iframe headers and detects kyoz hosts`() {
        assertTrue(KyozExtractor.isKyozUrl(kyozPlayerUrl))
        assertTrue(KyozExtractor.isKyozUrl("https://traffic.kyoz.net/media/abc.jpg"))
        assertFalse(KyozExtractor.isKyozUrl("https://www.blogger.com/video.g?token=abc"))

        val headers = KyozExtractor.iframeHeadersFor(episodeUrl, baseUrl, baseHeaders)
        assertEquals("$baseUrl/", headers["Referer"])
        assertTrue(headers["Accept"]?.contains("text/html") == true)
        assertEquals("1", headers["Upgrade-Insecure-Requests"])
        assertEquals("iframe", headers["Sec-Fetch-Dest"])
        assertEquals("navigate", headers["Sec-Fetch-Mode"])
        assertEquals("cross-site", headers["Sec-Fetch-Site"])

        val bloggerHeaders = KyozExtractor.bloggerHeadersFor(kyozPlayerUrl, baseHeaders)
        assertEquals("https://img.kyoz.net/", bloggerHeaders["Referer"])
    }

    @Test
    fun `mock http enforces kyoz headers and resolves blogger url via asJsoup`() = runBlocking {
        val strictKyozClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val valid = req.header("Referer")?.startsWith(baseUrl) == true &&
                    req.header("Sec-Fetch-Dest") == "iframe" &&
                    req.header("Sec-Fetch-Site") == "cross-site"
                if (valid) {
                    htmlResponse(req, 200, kyozWrapperHtml)
                } else {
                    htmlResponse(req, 403, "<html><head><title>403 Forbidden</title></head></html>")
                }
            }
            .build()

        val resolved = KyozExtractor.resolveBloggerUrlFromKyoz(
            kyozUrl = kyozPlayerUrl,
            episodeUrl = episodeUrl,
            baseUrl = baseUrl,
            baseHeaders = baseHeaders,
        ) { targetUrl, reqHeaders ->
            val req = Request.Builder().url(targetUrl).headers(reqHeaders).build()
            strictKyozClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                Jsoup.parseBodyFragment(res.body.string(), targetUrl).html()
            }
        }

        assertEquals("https://www.blogger.com/video.g?token=FAKE_BLOGGER_TOKEN_XYZ123", resolved)

        val unauthedResolved = KyozExtractor.resolveBloggerUrlFromKyoz(
            kyozUrl = kyozPlayerUrl,
            episodeUrl = episodeUrl,
            baseUrl = baseUrl,
            baseHeaders = Headers.EMPTY,
        ) { targetUrl, _ ->
            // Simulate missing required Sec-Fetch-* / Referer headers -> 403 Forbidden HTML
            val req = Request.Builder().url(targetUrl).build()
            strictKyozClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                res.body.string()
            }
        }
        assertNull(unauthedResolved)
    }

    @Test
    fun `resolveServerUrl posts ticket and player_key to ap-player load endpoint`() = runBlocking {
        var capturedAuthJson = ""
        val apiClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val buf = okio.Buffer()
                req.body?.writeTo(buf)
                capturedAuthJson = buf.readUtf8()
                assertEquals("/wp-json/ap-player/v2/load", req.url.encodedPath)
                assertEquals(episodeUrl, req.header("Referer"))
                assertEquals(baseUrl, req.header("Origin"))
                assertEquals("cors", req.header("Sec-Fetch-Mode"))
                assertEquals("same-origin", req.header("Sec-Fetch-Site"))
                jsonResponse(req, 200, """{"success":true,"url":"$kyozPlayerUrl"}""")
            }
            .build()

        val resolved = KyozExtractor.resolveServerUrl(
            baseUrl = baseUrl,
            episodeUrl = episodeUrl,
            ticket = "FAKE_TICKET_48",
            playerKey = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            baseHeaders = baseHeaders,
        ) { targetUrl, reqHeaders, reqBody ->
            val req = Request.Builder().url(targetUrl).headers(reqHeaders).post(reqBody).build()
            apiClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                res.body.string().parseAs<LoadResponseDto>()
            }
        }

        assertEquals(kyozPlayerUrl, resolved)
        assertTrue(capturedAuthJson.contains("FAKE_TICKET_48"))
        assertTrue(capturedAuthJson.contains("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
    }

    @Test
    fun `extracts legacy playex iframe and parses passive guard bridge payloads`() {
        assertEquals(
            "$baseUrl/episodios/ep-1/",
            KyozExtractor.absoluteUrl("$baseUrl/", "/episodios/ep-1/"),
        )
        assertEquals(
            "$baseUrl/episodios/ep-1/",
            KyozExtractor.absoluteUrl(baseUrl, "$baseUrl/episodios/ep-1/"),
        )

        val legacyHtml = """
            <div class="playex">
                <iframe src="https://anreyalp.vip/player/?link=https%3A%2F%2Fwww.blogger.com%2Fvideo.g%3Ftoken%3DFakeLegacy"></iframe>
            </div>
        """.trimIndent()
        val legacyDoc = Jsoup.parseBodyFragment(legacyHtml, episodeUrl)
        assertEquals(
            "https://www.blogger.com/video.g?token=FakeLegacy",
            KyozExtractor.extractLegacyIframeUrl(legacyDoc),
        )

        val bridgeJson = """
            {
              "ticket": "FAKE_TICKET_48",
              "servers": [
                {"id": "k1", "label": "Servidor Principal", "index": 1},
                {"id": "k2", "label": "Servidor Alternativo", "index": 2}
              ],
              "urls": ["$kyozPlayerUrl", "$kyozPlayerUrl"]
            }
        """.trimIndent()
        val state = PlayerGuardResolver.parseBridgePayload(bridgeJson)
        assertNotNull(state)
        assertEquals("FAKE_TICKET_48", state!!.ticket)
        assertEquals(2, state.servers.size)
        assertEquals(listOf(kyozPlayerUrl), state.urls)

        assertNull(PlayerGuardResolver.parseBridgePayload("not-json"))
    }

    @Test
    fun `sanitizes tokens keys tickets and kyoz media ids in diagnostic logs`() {
        val rawKyoz = "https://img.kyoz.net/media/0123456789abcdef0123456789abcdef0123456789abcdef.jpg"
        val sanitizedKyoz = AnimePlayerLog.sanitizeUrlForLog(rawKyoz)
        assertEquals("https://img.kyoz.net/media/<redacted>.jpg", sanitizedKyoz)
        assertFalse(sanitizedKyoz.contains("0123456789abcdef"))

        val rawBlogger = "https://www.blogger.com/video.g?token=SUPER_SECRET_TOKEN_1234567890"
        val sanitizedBlogger = AnimePlayerLog.sanitizeUrlForLog(rawBlogger)
        assertEquals("https://www.blogger.com/video.g?<redacted>", sanitizedBlogger)
        assertFalse(sanitizedBlogger.contains("SUPER_SECRET"))

        val rawCfPat = "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b/pat/a5f49/1795/abc/CiT7HQm-ujHLlEjp0EexK83rjosPUO"
        val sanitizedCfPat = AnimePlayerLog.sanitizeUrlForLog(rawCfPat)
        assertEquals("https://challenges.cloudflare.com/cdn-cgi/<redacted>", sanitizedCfPat)
        assertFalse(sanitizedCfPat.contains("CiT7HQm"))

        val dirtyMsg = "verify failed token=0.abcdef1234567890abcdef1234567890 ticket=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef cookie=cf_clearance=XYZ987"
        val cleanMsg = AnimePlayerLog.sanitizeMessage(dirtyMsg)
        assertFalse(cleanMsg.contains("abcdef1234567890"))
        assertFalse(cleanMsg.contains("deadbeef"))
        assertFalse(cleanMsg.contains("XYZ987"))
        assertTrue(cleanMsg.contains("<redacted>"))
    }

    @Test
    fun `resolves non-webview user agent cleanly without exposing wv marker`() {
        val nativeUa = PlayerGuardResolver.resolveWebViewUserAgent(
            appUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            defaultWebViewUserAgent = "Mozilla/5.0 (Linux; Android 12; LM-G910; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/157.0.8103.50 Mobile Safari/537.36",
        )
        assertEquals(
            "Mozilla/5.0 (Linux; Android 12; LM-G910) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.8103.50 Mobile Safari/537.36",
            nativeUa,
        )
        assertFalse(nativeUa!!.contains("; wv"))
        assertFalse(nativeUa.contains("Version/4.0"))

        val stripped = PlayerGuardResolver.resolveWebViewUserAgent(
            appUserAgent = null,
            defaultWebViewUserAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36",
        )
        assertNotNull(stripped)
        assertFalse(stripped!!.contains("; wv"))
        assertFalse(stripped.contains("Version/4.0"))
        assertTrue(stripped.contains("Chrome/124.0.0.0"))

        val appUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        assertEquals(
            appUa,
            PlayerGuardResolver.resolveWebViewUserAgent(
                appUserAgent = appUa,
                defaultWebViewUserAgent = "   ",
            ),
        )
    }

    @Test
    fun `extracts user agent header case-insensitively`() {
        val mixedHeaders = Headers.headersOf(
            "Referer", "$baseUrl/",
            "user-AGENT", "Mozilla/5.0 TestBrowser/1.0",
        )
        assertEquals("Mozilla/5.0 TestBrowser/1.0", KyozExtractor.extractUserAgent(mixedHeaders))
        assertNull(KyozExtractor.extractUserAgent(Headers.headersOf("Referer", "$baseUrl/")))
    }

    @Test
    fun `parses extended guard bridge payloads and classifies outcomes accurately`() {
        val capturedLogs = mutableListOf<String>()
        AnimePlayerLog.sink = { level, message -> capturedLogs += "$level:$message" }

        val successJson = """
            {
              "ticket": "FAKE_TICKET_48_CHARS",
              "servers": [{"id": "k1", "label": "Servidor Principal", "index": 1}],
              "urls": ["$kyozPlayerUrl"],
              "stage": "load_ok",
              "turnstileLoaded": true,
              "turnstileRendered": true,
              "verifyStatus": 200,
              "loadStatus": 200
            }
        """.trimIndent()
        val successState = PlayerGuardResolver.parseBridgePayload(successJson)
        assertNotNull(successState)
        assertEquals(
            PlayerGuardResolver.GuardStatus.SUCCESS,
            PlayerGuardResolver.classifyOutcome(successState!!, timedOut = false),
        )

        val pendingState = PlayerGuardResolver.ResolvedGuardState(
            ticket = null,
            servers = emptyList(),
            urls = emptyList(),
            stage = "turnstile_rendered",
            turnstileLoaded = true,
            turnstileRendered = true,
            verifyStatus = null,
        )
        assertEquals(
            PlayerGuardResolver.GuardStatus.CLOUDFLARE_CHALLENGE_PENDING,
            PlayerGuardResolver.classifyOutcome(pendingState, timedOut = true),
        )

        val verifyFailedState = PlayerGuardResolver.parseBridgePayload(
            """{"stage":"verify_failed","turnstileLoaded":true,"turnstileRendered":true,"verifyStatus":403}""",
        )!!
        assertEquals(
            PlayerGuardResolver.GuardStatus.AUTH_VERIFY_FAILURE,
            PlayerGuardResolver.classifyOutcome(verifyFailedState, timedOut = false),
        )
        assertTrue(PlayerGuardResolver.isTerminalFailureStage(verifyFailedState.stage))

        val panelErrorState = PlayerGuardResolver.parseBridgePayload(
            """{"stage":"panel_error","turnstileLoaded":true,"turnstileRendered":true,"errorCode":"turnstile_failed","errorMessage":"A verificacao Cloudflare falhou."}""",
        )!!
        assertEquals(
            PlayerGuardResolver.GuardStatus.CLOUDFLARE_CHALLENGE_PENDING,
            PlayerGuardResolver.classifyOutcome(panelErrorState, timedOut = false),
        )
        assertTrue(PlayerGuardResolver.isTerminalFailureStage(panelErrorState.stage))

        val missingTurnstileState = PlayerGuardResolver.parseBridgePayload(
            """{"stage":"turnstile_missing","turnstileLoaded":false,"turnstileRendered":false}""",
        )!!
        assertEquals(
            PlayerGuardResolver.GuardStatus.REQUEST_FAILURE,
            PlayerGuardResolver.classifyOutcome(missingTurnstileState, timedOut = false),
        )
        assertTrue(PlayerGuardResolver.isTerminalFailureStage(missingTurnstileState.stage))

        capturedLogs.clear()
        assertNull(PlayerGuardResolver.parseBridgePayload("{broken-json"))
        assertTrue(
            capturedLogs.any { it.contains(AnimePlayerLog.Category.PARSING_ERROR.name) },
        )
    }

    @Test
    fun `passive observer script waits for turnstile inspects panel and errors without automating challenge`() {
        val script = PlayerGuardResolver.passiveObserverScriptForTest()
        assertTrue(script.contains("document.readyState !== 'loading'"))
        assertTrue(script.contains("typeof window.turnstile"))
        assertTrue(script.contains(".player-placeholder[data-player-key]"))
        assertTrue(script.contains("/wp-json/ap-player/v2/verify"))
        assertTrue(script.contains("/wp-json/ap-player/v2/load"))
        assertTrue(script.contains("verifyStatus = resp.status"))
        assertTrue(script.contains("loadStatus = resp.status"))
        assertTrue(script.contains(".ap-player-guard-panel"))
        assertTrue(script.contains("ap-player-guard-iframe"))
        assertFalse(script.contains(".ap-turnstile-slot').click"))
        assertFalse(script.contains("ap-turnstile-slot input"))
    }

    @Test
    fun `logs extraction and empty response failures safely without leaking tokens`() = runBlocking {
        val capturedLogs = mutableListOf<String>()
        AnimePlayerLog.sink = { level, message -> capturedLogs += "$level:$message" }

        val extractedNull = KyozExtractor.resolveBloggerUrlFromKyoz(
            kyozUrl = kyozPlayerUrl,
            episodeUrl = episodeUrl,
            baseUrl = baseUrl,
            baseHeaders = baseHeaders,
        ) { _, _ ->
            "<html><body><div>No player token SECRET_HTML_MARKER</div></body></html>"
        }
        assertNull(extractedNull)
        assertTrue(capturedLogs.any { it.contains(AnimePlayerLog.Category.EXTRACTION_FAILURE.name) })
        assertFalse(capturedLogs.any { it.contains("SECRET_HTML_MARKER") })

        capturedLogs.clear()
        val serverUrl = KyozExtractor.resolveServerUrl(
            baseUrl = baseUrl,
            episodeUrl = episodeUrl,
            ticket = "SECRET_TICKET_DO_NOT_LOG_12345678901234567890",
            playerKey = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            baseHeaders = baseHeaders,
        ) { _, _, _ ->
            LoadResponseDto(success = false, url = null, message = "Ticket expirado para SECRET_TICKET_DO_NOT_LOG_12345678901234567890")
        }
        assertNull(serverUrl)
        assertTrue(capturedLogs.any { it.contains(AnimePlayerLog.Category.EMPTY_RESPONSE.name) })
        assertFalse(capturedLogs.any { it.contains("SECRET_TICKET") })
    }

    @Test
    fun `blocks known popunder ad hosts without blocking site turnstile kyoz or blogger`() {
        assertTrue(PlayerGuardResolver.isBlockedAdHost("https://abscloud.org/1/2496bdefff2179e0cf0d935b955c0343"))
        assertTrue(PlayerGuardResolver.isBlockedAdHost("https://bauval.org/14/6db260769fa0e6dc0bb4cedf11c7baf9"))
        assertTrue(PlayerGuardResolver.isBlockedAdHost("https://sub.abscloud.org/pop.js"))

        assertFalse(PlayerGuardResolver.isBlockedAdHost("https://anreyalp.vip/episodios/ep-1/"))
        assertFalse(PlayerGuardResolver.isBlockedAdHost("https://challenges.cloudflare.com/turnstile/v0/api.js"))
        assertFalse(PlayerGuardResolver.isBlockedAdHost("https://img.kyoz.net/media/abc.jpg"))
        assertFalse(PlayerGuardResolver.isBlockedAdHost("https://www.blogger.com/video.g?token=x"))
    }

    @Test
    fun `emits failure diagnostics at ERROR level for Anikku crash log visibility without leaking secrets`() {
        val capturedLogs = mutableListOf<Pair<String, String>>()
        AnimePlayerLog.sink = { level, message -> capturedLogs += level to message }

        AnimePlayerLog.w(
            AnimePlayerLog.Category.CLOUDFLARE_CHALLENGE_PENDING,
            "guard failed token=SECRET_123456789012345678901234",
        )

        assertEquals(1, capturedLogs.size)
        assertEquals("E", capturedLogs[0].first)
        assertTrue(capturedLogs[0].second.contains("[CLOUDFLARE_CHALLENGE_PENDING]"))
        assertFalse(capturedLogs[0].second.contains("SECRET_"))
    }

    @Test
    fun `ignores kyoz image and non-document subresources while accepting iframe documents`() {
        assertFalse(PlayerGuardResolver.isKyozDocumentRequest(kyozPlayerUrl, mapOf("Sec-Fetch-Dest" to "image")))
        assertFalse(PlayerGuardResolver.isKyozDocumentRequest("https://img.kyoz.net/thumb/poster.jpg", mapOf("sec-fetch-dest" to "image")))
        assertFalse(PlayerGuardResolver.isKyozDocumentRequest("https://img.kyoz.net/app.js", mapOf("Sec-Fetch-Dest" to "script")))
        assertFalse(PlayerGuardResolver.isKyozDocumentRequest("https://img.kyoz.net/assets/banner.jpg", emptyMap()))

        assertTrue(PlayerGuardResolver.isKyozDocumentRequest(kyozPlayerUrl, mapOf("Sec-Fetch-Dest" to "iframe")))
        assertTrue(PlayerGuardResolver.isKyozDocumentRequest(kyozPlayerUrl, mapOf("Sec-Fetch-Dest" to "document")))
    }

    @Test
    fun `waits for direct blogger url from kyoz iframe and prefers direct url over raw kyoz`() {
        val directBlogger = "https://www.blogger.com/video.g?token=ABC"
        assertTrue(PlayerGuardResolver.isDirectPlayerUrl(directBlogger))
        assertTrue(PlayerGuardResolver.isDirectPlayerUrl("https://cdn.animeson.com.br/ep1.mp4"))
        assertFalse(PlayerGuardResolver.isDirectPlayerUrl(kyozPlayerUrl))

        assertTrue(
            PlayerGuardResolver.shouldWaitForDirectPlayerUrl(
                urls = listOf(kyozPlayerUrl),
                directUrls = emptyList(),
                serverCount = 1,
            ),
        )
        assertFalse(
            PlayerGuardResolver.shouldWaitForDirectPlayerUrl(
                urls = listOf(kyozPlayerUrl),
                directUrls = listOf(directBlogger),
                serverCount = 1,
            ),
        )

        assertEquals(
            listOf(directBlogger),
            PlayerGuardResolver.selectPreferredUrls(
                loadUrls = listOf(kyozPlayerUrl),
                interceptedKyozUrls = listOf("https://img.kyoz.net/media/other.jpg"),
                directUrls = listOf(directBlogger),
            ),
        )
        assertEquals(
            listOf(kyozPlayerUrl),
            PlayerGuardResolver.selectPreferredUrls(
                loadUrls = listOf(kyozPlayerUrl),
                interceptedKyozUrls = listOf("https://img.kyoz.net/media/other.jpg"),
                directUrls = emptyList(),
            ),
        )
    }

    @Test
    fun `classifies verify HTTP 403 and 410 as auth verify failures and flags only 410 for single retry`() {
        val verify403 = PlayerGuardResolver.parseBridgePayload(
            """{"stage":"verify_failed","turnstileLoaded":true,"turnstileRendered":true,"verifyStatus":403}""",
        )!!
        assertEquals(
            PlayerGuardResolver.GuardStatus.AUTH_VERIFY_FAILURE,
            PlayerGuardResolver.classifyOutcome(verify403, timedOut = false),
        )

        val verify410 = PlayerGuardResolver.parseBridgePayload(
            """{"stage":"verify_failed","turnstileLoaded":true,"turnstileRendered":true,"verifyStatus":410}""",
        )!!
        assertEquals(
            PlayerGuardResolver.GuardStatus.AUTH_VERIFY_FAILURE,
            PlayerGuardResolver.classifyOutcome(verify410, timedOut = false),
        )

        assertTrue(PlayerGuardResolver.shouldRetryGuard(verifyStatus = 410, attempt = 1))
        assertFalse(PlayerGuardResolver.shouldRetryGuard(verifyStatus = 410, attempt = 2))
        assertFalse(PlayerGuardResolver.shouldRetryGuard(verifyStatus = 403, attempt = 1))
    }

    @Test
    fun `supports full episode referer override and webview user agent propagation`() {
        val originHeaders = KyozExtractor.iframeHeadersFor(episodeUrl, baseUrl, baseHeaders)
        assertEquals("$baseUrl/", originHeaders["Referer"])

        val fullHeaders = KyozExtractor.iframeHeadersFor(episodeUrl, baseUrl, baseHeaders, fullReferer = true)
        assertEquals(episodeUrl, fullHeaders["Referer"])

        val webViewUa = "Mozilla/5.0 (Linux; Android 12; LM-G910) AppleWebKit/537.36 Chrome/157.0.8103.50 Mobile Safari/537.36"
        val updated = KyozExtractor.withUserAgent(baseHeaders, webViewUa)
        assertEquals(webViewUa, updated["User-Agent"])
        assertEquals("$baseUrl/", updated["Referer"])

        assertEquals(baseHeaders, KyozExtractor.withUserAgent(baseHeaders, null))
        assertEquals(baseHeaders, KyozExtractor.withUserAgent(baseHeaders, "   "))
    }

    @Test
    fun `summarizes allowlisted response headers safely without leaking set-cookie`() {
        val responseHeaders = Headers.headersOf(
            "CF-Cache-Status", "BYPASS",
            "Content-Length", "0",
            "Vary", "Accept-Encoding",
            "X-Cache", "MISS",
            "Cache-Control", "no-store",
            "Set-Cookie", "starstruck_2a45=SECRET_COOKIE_VALUE_XYZ123; path=/",
            "X-Secret-Token", "SECRET_HEADER_999",
        )
        val summary = KyozExtractor.summarizeResponseHeadersForLog(responseHeaders)
        assertTrue(summary.contains("cf-cache-status=BYPASS"))
        assertTrue(summary.contains("content-length=0"))
        assertTrue(summary.contains("vary=Accept-Encoding"))
        assertTrue(summary.contains("x-cache=MISS"))
        assertTrue(summary.contains("cache-control=no-store"))
        assertFalse(summary.contains("SECRET_COOKIE_VALUE"))
        assertFalse(summary.contains("Set-Cookie", ignoreCase = true))
        assertFalse(summary.contains("SECRET_HEADER_999"))
    }

    @Test
    fun `resolves blogger url by retrying with full episode referer when origin referer returns 403`() = runBlocking {
        val seenReferers = mutableListOf<String?>()
        val resolved = KyozExtractor.resolveBloggerUrlWithRefererFallback(
            kyozUrl = kyozPlayerUrl,
            episodeUrl = episodeUrl,
            baseUrl = baseUrl,
            baseHeaders = baseHeaders,
        ) { _, reqHeaders ->
            val ref = reqHeaders["Referer"]
            seenReferers += ref
            if (ref == "$baseUrl/") {
                KyozExtractor.KyozFetchResult(
                    code = 403,
                    html = null,
                    server = "cloudflare",
                    cfMitigated = null,
                    contentType = "text/html; charset=UTF-8",
                    bodyLength = 0,
                    bodyKind = "empty",
                )
            } else {
                KyozExtractor.KyozFetchResult(
                    code = 200,
                    html = kyozWrapperHtml,
                    server = "cloudflare",
                    cfMitigated = null,
                    contentType = "text/html; charset=UTF-8",
                    bodyLength = kyozWrapperHtml.length,
                    bodyKind = "ok",
                )
            }
        }

        assertEquals(listOf("$baseUrl/", episodeUrl), seenReferers)
        assertEquals("https://www.blogger.com/video.g?token=FAKE_BLOGGER_TOKEN_XYZ123", resolved)
    }

    @Test
    fun `classifies kyoz http error bodies accurately`() {
        assertEquals("ok", KyozExtractor.classifyHttpErrorBody(200, "<html></html>", "cloudflare", null))
        assertEquals("empty", KyozExtractor.classifyHttpErrorBody(403, "   ", "cloudflare", null))
        assertEquals(
            "cf_challenge",
            KyozExtractor.classifyHttpErrorBody(403, "<html>Just a moment...</html>", "cloudflare", "challenge"),
        )
        assertEquals(
            "cf_challenge",
            KyozExtractor.classifyHttpErrorBody(403, "<div id=\"cf-chl-widget\"></div>", "cloudflare", null),
        )
        assertEquals(
            "player_expired",
            KyozExtractor.classifyHttpErrorBody(410, "<body>Player expirado. Atualize a pagina.</body>", "cloudflare", null),
        )
        assertEquals(
            "nginx_403",
            KyozExtractor.classifyHttpErrorBody(403, "<html><center><h1>403 Forbidden</h1></center><hr><center>nginx</center></html>", "cloudflare", null),
        )
        assertEquals(
            "cf_waf_403",
            KyozExtractor.classifyHttpErrorBody(403, "<html><title>Attention Required! | Cloudflare</title></html>", "cloudflare", null),
        )
        assertEquals(
            "html_other",
            KyozExtractor.classifyHttpErrorBody(502, "<!doctype html><html><body>Bad Gateway</body></html>", null, null),
        )
    }

    @Test
    fun `extracts direct blogger url from request url or referer including urlencoded forigin`() {
        val directBlogger = "https://www.blogger.com/video.g?token=ABC123SECRETTOKEN"
        assertEquals(
            directBlogger,
            PlayerGuardResolver.extractDirectPlayerUrl(directBlogger, emptyMap()),
        )

        assertEquals(
            directBlogger,
            KyozExtractor.extractBloggerUrlFromReferer(directBlogger),
        )
        assertEquals(
            directBlogger,
            PlayerGuardResolver.extractDirectPlayerUrl(
                url = "https://www.blogger.com/_/BloggerVideoPlayerUi/web-reports",
                requestHeaders = mapOf("referer" to directBlogger),
            ),
        )

        val ytReferer = "https://youtube.googleapis.com/embed/?ps=blogger&forigin=https%3A%2F%2Fwww.blogger.com%2Fvideo.g%3Ftoken%3DABC123SECRETTOKEN&vf=6"
        assertEquals(
            directBlogger,
            KyozExtractor.extractBloggerUrlFromReferer(ytReferer),
        )
        assertEquals(
            directBlogger,
            PlayerGuardResolver.extractDirectPlayerUrl(
                url = "https://youtube.googleapis.com/embed/establish_zw",
                requestHeaders = mapOf("Referer" to ytReferer),
            ),
        )

        assertNull(KyozExtractor.extractBloggerUrlFromReferer("https://anreyalp.vip/episodios/ep-1/"))
        assertNull(PlayerGuardResolver.extractDirectPlayerUrl("https://img.kyoz.net/app.js", mapOf("Referer" to "https://img.kyoz.net/")))
    }

    @Test
    fun `summarizes request headers for logs without leaking secrets`() {
        val headers = Headers.headersOf(
            "User-Agent", "Mozilla/5.0 (Linux; Android 12; LM-G910; wv) Chrome/157.0.8103.50 SECRET_UA_TOKEN_123456",
            "Referer", "https://anreyalp.vip/episodios/ep-1/?token=SECRET_REF_TOKEN",
            "Sec-Fetch-Dest", "iframe",
            "Sec-Fetch-Mode", "navigate",
            "Sec-Fetch-Site", "cross-site",
            "X-Requested-With", "app.anikku.beta",
            "Cookie", "cf_clearance=SUPER_SECRET_COOKIE_VALUE",
        )
        val hdrSummary = KyozExtractor.summarizeRequestHeadersForLog(headers)
        assertTrue(hdrSummary.contains("ua=chrome(wv)"))
        assertTrue(hdrSummary.contains("refHost=anreyalp.vip"))
        assertTrue(hdrSummary.contains("sfd=iframe"))
        assertTrue(hdrSummary.contains("sfm=navigate"))
        assertTrue(hdrSummary.contains("sfs=cross-site"))
        assertTrue(hdrSummary.contains("hasXrw=true"))
        assertTrue(hdrSummary.contains("hasCookie=true"))
        assertFalse(hdrSummary.contains("SECRET_UA_TOKEN"))
        assertFalse(hdrSummary.contains("SECRET_REF_TOKEN"))
        assertFalse(hdrSummary.contains("SUPER_SECRET_COOKIE_VALUE"))
    }

    @Test
    fun `parses api error json and sanitizes sensitive fields without leaking tokens`() {
        val jsonBody = """{"code":"invalid_session","message":"Sessao invalida para ticket=SECRET_TICKET_12345678901234567890"}"""
        val summary = KyozExtractor.parseApiErrorSummary(jsonBody)
        assertTrue(summary.contains("errCode=invalid_session"))
        assertTrue(summary.contains("errMsg=Sessao invalida"))
        assertFalse(summary.contains("SECRET_TICKET"))

        assertEquals("errCode=none errMsg=none", KyozExtractor.parseApiErrorSummary(""))
        assertEquals("errCode=none errMsg=none", KyozExtractor.parseApiErrorSummary("<html>403</html>"))
    }

    @Test
    fun `summarizes okHttp wire headers with exact ua match and cookie count without leaking cookie values`() {
        val expectedUa = "Mozilla/5.0 (Linux; Android 12; LM-G910) AppleWebKit/537.36 Chrome/157.0.8103.50 Mobile Safari/537.36"
        val wireHeaders = Headers.headersOf(
            "User-Agent", expectedUa,
            "Cookie", "sessCookie=SECRET_VAL_1; secondCookie=SECRET_VAL_2",
        )
        val matchSummary = KyozExtractor.summarizeWireHeadersForLog(wireHeaders, expectedUa)
        assertTrue(matchSummary.contains("ua=chrome"))
        assertTrue(matchSummary.contains("uaMatch=true"))
        assertTrue(matchSummary.contains("hasXrw=false"))
        assertTrue(matchSummary.contains("hasCookie=true"))
        assertTrue(matchSummary.contains("cookieCount=2"))
        assertFalse(matchSummary.contains("SECRET_VAL_1"))
        assertFalse(matchSummary.contains("sessCookie"))

        val mismatchHeaders = Headers.headersOf(
            "User-Agent", "okhttp/5.4.0",
            "X-Requested-With", "app.anikku.beta",
        )
        val mismatchSummary = KyozExtractor.summarizeWireHeadersForLog(mismatchHeaders, expectedUa)
        assertTrue(mismatchSummary.contains("ua=okhttp"))
        assertTrue(mismatchSummary.contains("uaMatch=false"))
        assertTrue(mismatchSummary.contains("hasXrw=true"))
        assertTrue(mismatchSummary.contains("hasCookie=false"))
        assertTrue(mismatchSummary.contains("cookieCount=0"))

        assertEquals("none", KyozExtractor.summarizeWireHeadersForLog(null, expectedUa))
    }

    @Test
    fun `summarizes webview error response header map using allowlist without leaking set-cookie`() {
        val summary = KyozExtractor.summarizeResponseHeaderMapForLog(
            mapOf(
                "Cache-Control" to "no-store",
                "CF-Cache-Status" to "BYPASS",
                "Content-Length" to "0",
                "Server" to "cloudflare",
                "Set-Cookie" to "starstruck=SECRET_999",
                "X-Private" to "SECRET_888",
            ),
        )
        assertTrue(summary.contains("cache-control=no-store"))
        assertTrue(summary.contains("cf-cache-status=BYPASS"))
        assertTrue(summary.contains("content-length=0"))
        assertTrue(summary.contains("server=cloudflare"))
        assertFalse(summary.contains("Set-Cookie", ignoreCase = true))
        assertFalse(summary.contains("SECRET_999"))
        assertFalse(summary.contains("SECRET_888"))
    }

    @Test
    fun `parses captured verify payload from guard bridge and classifies as success without leaking token`() {
        val capturedLogs = mutableListOf<String>()
        AnimePlayerLog.sink = { level, message -> capturedLogs += "$level:$message" }

        val bridgeJson = """
            {
              "stage": "verify_captured",
              "turnstileLoaded": true,
              "turnstileRendered": true,
              "turnstileToken": "SECRET_TS_TOKEN_1234567890123456",
              "playerKeys": [
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              ]
            }
        """.trimIndent()
        val state = PlayerGuardResolver.parseBridgePayload(bridgeJson)
        assertNotNull(state)
        assertEquals("SECRET_TS_TOKEN_1234567890123456", state!!.turnstileToken)
        assertEquals(listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), state.playerKeys)
        assertEquals(
            PlayerGuardResolver.GuardStatus.SUCCESS,
            PlayerGuardResolver.classifyOutcome(state, timedOut = false),
        )
        assertFalse(capturedLogs.any { it.contains("SECRET_TS_TOKEN") })

        val script = PlayerGuardResolver.passiveObserverScriptForTest()
        assertTrue(script.contains("verify_captured"))
        assertTrue(script.contains("turnstile_token"))
    }

    @Test
    fun `verifyAccess posts turnstile_token and player_keys with Accept-Language and parses ticket and servers`() = runBlocking {
        var capturedVerifyJson = ""
        val apiClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val buf = okio.Buffer()
                req.body?.writeTo(buf)
                capturedVerifyJson = buf.readUtf8()
                assertEquals("/wp-json/ap-player/v2/verify", req.url.encodedPath)
                assertEquals(baseUrl, req.header("Origin"))
                assertEquals(episodeUrl, req.header("Referer"))
                assertTrue(req.header("Accept-Language")?.contains("pt-BR") == true)
                assertEquals("cors", req.header("Sec-Fetch-Mode"))
                assertEquals("same-origin", req.header("Sec-Fetch-Site"))
                jsonResponse(
                    req,
                    200,
                    """{"success":true,"ticket":"FAKE_TICKET_48","servers":[{"id":"k1","label":"Servidor Principal","index":1}]}""",
                )
            }
            .build()

        val result = KyozExtractor.verifyAccess(
            baseUrl = baseUrl,
            episodeUrl = episodeUrl,
            turnstileToken = "FAKE_TS_TOKEN",
            playerKeys = listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            baseHeaders = baseHeaders,
        ) { targetUrl, reqHeaders, reqBody ->
            val req = Request.Builder().url(targetUrl).headers(reqHeaders).post(reqBody).build()
            apiClient.newCall(req).execute().use { res ->
                val dto = res.body.string().parseAs<VerifyResponseDto>()
                KyozExtractor.VerifyFetchResult(
                    code = res.code,
                    ticket = dto.ticket,
                    servers = dto.servers,
                )
            }
        }

        assertEquals(200, result.code)
        assertEquals("FAKE_TICKET_48", result.ticket)
        assertEquals(1, result.servers.size)
        assertTrue(capturedVerifyJson.contains("turnstile_token"))
        assertTrue(capturedVerifyJson.contains("player_keys"))
        assertTrue(capturedVerifyJson.contains("FAKE_TS_TOKEN"))
    }

    @Test
    fun `classifies json 403 error bodies distinctly from cloudflare waf html 403`() {
        assertEquals(
            "json_403",
            KyozExtractor.classifyHttpErrorBody(
                403,
                """{"code":"invalid_session","message":"Sessao invalida."}""",
                "cloudflare",
                null,
            ),
        )
    }

    @Test
    fun `apiHeadersFor includes Accept-Language matching browser requests`() {
        val apiHeaders = KyozExtractor.apiHeadersFor(episodeUrl, baseUrl, baseHeaders)
        assertTrue(apiHeaders["Accept-Language"]?.contains("pt-BR") == true)
    }
}
