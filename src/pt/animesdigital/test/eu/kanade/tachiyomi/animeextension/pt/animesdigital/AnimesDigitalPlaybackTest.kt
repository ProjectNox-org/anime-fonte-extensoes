package eu.kanade.tachiyomi.animeextension.pt.animesdigital

import eu.kanade.tachiyomi.animeextension.pt.animesdigital.extractors.ScriptExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for project-nox-requests#156 (Animes Digital playback failure).
 */
class AnimesDigitalPlaybackTest {

    private val baseUrl = "https://animesdigital.org"

    private val baseHeaders = Headers.headersOf(
        "Referer", "$baseUrl/",
        "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0",
    )

    // Sanitized JWPlayer setup script captured from api.anivideo.net (2026-11-23)
    private val jwPlayerScript = """
        var player = jwplayer('video-player');
        const playerInstance = jwplayer('video-player').setup({
            image: "https:\/\/animesdigital.org\/wp-content\/uploads\/2026\/10\/thumb.jpg",
            playlist: [{
                id: 'player',
                image: "https:\/\/animesdigital.org\/wp-content\/uploads\/2026\/10\/thumb.jpg",
                sources: [{
                    file: "https:\/\/cdn-s01.mywallpaper-4k-image.net\/stream\/sv\/d\/sample-anime\/07.mp4\/index.m3u8"
                }],
                type: "video/mp4"
            }],
            width: "100%"
        });
    """.trimIndent()

    // Sanitized episode page div#player HTML from animesdigital.org/video/a/139824/
    private val episodeHtml = """
        <!DOCTYPE html>
        <html>
        <body>
            <ul class="player_tabs">
                <li data-tab="#player1">Player FHD</li>
                <li data-tab="#player2">Player 2</li>
            </ul>
            <div id="player">
                <div id="player1" class="tab-video" data-id="139824" data-video="1">
                    <iframe class="metaframe rptss no-lazy" src="https://api.anivideo.net/cdn/v1.FAKE_TOKEN?i=thumb"></iframe>
                </div>
                <div id="player2" class="tab-video" data-id="139824" data-video="2">
                    <iframe class="metaframe rptss no-lazy" src="https://animesdigital.org/aHR0cHM6Ly9leGFtcGxlLmJsb2dzcG90LmNvbS8=/6/bg.mp4?p=2"></iframe>
                </div>
            </div>
        </body>
        </html>
    """.trimIndent()

    private fun htmlResponse(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Forbidden")
        .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
        .build()

    private fun iframeEnforcingClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.header("Sec-Fetch-Dest") != "iframe") {
                return@addInterceptor htmlResponse(req, 403, "Sinal interrompido.")
            }
            val url = req.url.toString()
            when {
                "api.anivideo.net" in url -> htmlResponse(
                    req,
                    200,
                    "<html><body><script>$jwPlayerScript</script></body></html>",
                )
                "/bg.mp4" in url -> htmlResponse(
                    req,
                    200,
                    """<html><body><iframe src="https://www.blogger.com/video.g?token=FAKE_BLOGGER"></iframe></body></html>""",
                )
                "protected-embed.example" in url -> htmlResponse(
                    req,
                    200,
                    """<html><body><script>sources: [{ file: "https://cdn.example.com/unlocked/video.m3u8", label: "1080p" }]</script></body></html>""",
                )
                else -> htmlResponse(req, 404, "Not Found")
            }
        }
        .build()

    @Test
    fun extractsHlsSourceFromRealApiAnivideoScript() {
        val videos = ScriptExtractor.videosFromScript(jwPlayerScript, baseHeaders)

        assertEquals(1, videos.size)
        assertEquals(
            "https://cdn-s01.mywallpaper-4k-image.net/stream/sv/d/sample-anime/07.mp4/index.m3u8",
            videos.single().videoUrl,
        )
        assertEquals("Animes Digital", videos.single().videoTitle)
        assertEquals(baseHeaders, videos.single().headers)
    }

    @Test
    fun doesNotCorruptUrlsContainingSrcSubstringOrMixedQuotes() {
        val script = """
            jwplayer('video-player').setup({
                sources: [{
                    file: "https://cdn.example.com/src/ep01/index.m3u8",
                    label: '720p'
                }]
            });
        """.trimIndent()

        val videos = ScriptExtractor.videosFromScript(script, baseHeaders)

        assertEquals(1, videos.size)
        assertEquals("https://cdn.example.com/src/ep01/index.m3u8", videos.single().videoUrl)
        assertEquals("720p", videos.single().videoTitle)
    }

    @Test
    fun extractsPlayerSrcAndDiscardsEmptyOrNonHttpEntries() {
        val script = """
            player.src([
                { src: "https://cdn.example.com/v480.mp4", label: "480p" },
                { src: "" },
                { file: "/relative/invalid.mp4" }
            ]);
        """.trimIndent()

        val videos = ScriptExtractor.videosFromScript(script, baseHeaders)

        assertEquals(1, videos.size)
        assertEquals("https://cdn.example.com/v480.mp4", videos.single().videoUrl)
        assertEquals("480p", videos.single().videoTitle)
    }

    @Test
    fun returnsEmptyListWhenScriptHasNoSources() {
        val videos = ScriptExtractor.videosFromScript("console.log('ready');", baseHeaders)
        assertTrue(videos.isEmpty())
    }

    @Test
    fun buildsSameOriginAndCrossSiteIframeHeaders() {
        val sameOrigin = AnimesDigital.embedHeadersFor(
            url = "https://animesdigital.org/aHR0cHM6.../6/bg.mp4?p=2",
            baseUrl = baseUrl,
            baseHeaders = baseHeaders,
        )
        assertEquals("$baseUrl/", sameOrigin["Referer"])
        assertEquals("iframe", sameOrigin["Sec-Fetch-Dest"])
        assertEquals("navigate", sameOrigin["Sec-Fetch-Mode"])
        assertEquals("same-origin", sameOrigin["Sec-Fetch-Site"])

        val crossSite = AnimesDigital.embedHeadersFor(
            url = "https://api.anivideo.net/cdn/v1.FAKE_TOKEN",
            baseUrl = baseUrl,
            baseHeaders = baseHeaders,
        )
        assertEquals("$baseUrl/", crossSite["Referer"])
        assertEquals("iframe", crossSite["Sec-Fetch-Dest"])
        assertEquals("navigate", crossSite["Sec-Fetch-Mode"])
        assertEquals("cross-site", crossSite["Sec-Fetch-Site"])
    }

    @Test
    fun parsesEpisodeHostersAndResolvesBothServersUsingIframeHeaders() = runBlocking {
        val episodeDoc = Jsoup.parseBodyFragment(episodeHtml, "$baseUrl/video/a/139824/")
        val hosters = AnimesDigital.parseHosters(episodeDoc)

        assertEquals(2, hosters.size)
        assertEquals("Player FHD", hosters[0].hosterName)
        assertEquals("Player 2", hosters[1].hosterName)

        val client = iframeEnforcingClient()
        val seenBloggerUrls = mutableListOf<String>()
        val fetchDoc: suspend (String, Headers) -> org.jsoup.nodes.Document = { url, headers ->
            val request = Request.Builder().url(url).headers(headers).build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw HttpException(res.code)
                Jsoup.parseBodyFragment(res.body.string(), res.request.url.toString())
            }
        }
        val fetchBlogger: suspend (String, Headers) -> List<Video> = { url, headers ->
            seenBloggerUrls += url
            listOf(Video(videoUrl = "https://rr4.googlevideo.com/videoplayback?id=fake", videoTitle = "Blogger - 720p", headers = headers))
        }

        val playerFhdElement = AnimesDigital.parseHosterElement(hosters[0])!!
        val playerFhdVideos = AnimesDigital.resolveElement(
            element = playerFhdElement,
            baseUrl = baseUrl,
            baseHeaders = baseHeaders,
            fetchBlogger = fetchBlogger,
            fetchEmbedDocument = fetchDoc,
            resolveProtectedUrl = { null },
        )
        assertEquals(1, playerFhdVideos.size)
        assertEquals(
            "https://cdn-s01.mywallpaper-4k-image.net/stream/sv/d/sample-anime/07.mp4/index.m3u8",
            playerFhdVideos.single().videoUrl,
        )

        val player2Element = AnimesDigital.parseHosterElement(hosters[1])!!
        val player2Videos = AnimesDigital.resolveElement(
            element = player2Element,
            baseUrl = baseUrl,
            baseHeaders = baseHeaders,
            fetchBlogger = fetchBlogger,
            fetchEmbedDocument = fetchDoc,
            resolveProtectedUrl = { null },
        )
        assertEquals(listOf("https://www.blogger.com/video.g?token=FAKE_BLOGGER"), seenBloggerUrls)
        assertEquals("Blogger - 720p", player2Videos.single().videoTitle)
    }

    @Test
    fun resolvesProtectedCoverAndHandlesBlankIframeWithoutCrashing() = runBlocking {
        val client = iframeEnforcingClient()
        val fetchDoc: suspend (String, Headers) -> org.jsoup.nodes.Document = { url, headers ->
            val request = Request.Builder().url(url).headers(headers).build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw HttpException(res.code)
                Jsoup.parseBodyFragment(res.body.string(), res.request.url.toString())
            }
        }

        val protectedEl = Jsoup.parseBodyFragment(
            """<a class="ad-protected-cover" href="$baseUrl/ad/123">Go</a>""",
            baseUrl,
        ).selectFirst(AnimesDigital.VIDEO_SELECTOR)!!

        val videos = AnimesDigital.resolveElement(
            element = protectedEl,
            baseUrl = baseUrl,
            baseHeaders = baseHeaders,
            fetchBlogger = { _, _ -> emptyList() },
            fetchEmbedDocument = fetchDoc,
            resolveProtectedUrl = { "https://protected-embed.example/embed/1" },
        )
        assertEquals(1, videos.size)
        assertEquals("https://cdn.example.com/unlocked/video.m3u8", videos.single().videoUrl)
        assertEquals("1080p", videos.single().videoTitle)

        val blankIframe = Jsoup.parseBodyFragment("""<iframe></iframe>""", baseUrl).selectFirst("iframe")!!
        val emptyResult = AnimesDigital.resolveElement(
            element = blankIframe,
            baseUrl = baseUrl,
            baseHeaders = baseHeaders,
            fetchBlogger = { _, _ -> emptyList() },
            fetchEmbedDocument = fetchDoc,
            resolveProtectedUrl = { null },
        )
        assertTrue(emptyResult.isEmpty())
        assertNull(AnimesDigital.parseHosterElement(eu.kanade.tachiyomi.animesource.model.Hoster(internalData = "<div></div>")))
    }
}
