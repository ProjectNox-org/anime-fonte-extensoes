package eu.kanade.tachiyomi.animeextension.pt.animeplayer

import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.useAsJsoup
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class AnimePlayer : DooPlay() {

    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div#archive-content article div.poster"

    override fun popularAnimeRequest(page: Int) = GET(
        if (page == 1) "$baseUrl/animes/" else "$baseUrl/animes/page/$page/",
        headers,
    )

    override fun popularAnimeNextPageSelector() = "a > i#nextpagination"

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodios"

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealAnimeDoc(document)
        setUrlWithoutDomain(doc.location())
        val content = doc.selectFirst("div#contenedor > div.data")!!
        doc.selectFirst("div.sheader div.poster > img")!!.let {
            thumbnail_url = it.getImageUrl()
            title = it.attr("alt").ifEmpty {
                content.selectFirst("div.data > h1")!!.text()
            }
        }

        genre = content.select("div.sgeneros > a")
            .eachText()
            .joinToString()
        description = content.selectFirst("div.wp-content > p")?.text()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        val seasonName = season.selectFirst("span.title")!!.text()
        return season.select(episodeListSelector()).mapNotNull { element ->
            runCatching {
                episodeFromElement(element, seasonName)
            }.onFailure { it.printStackTrace() }.getOrNull()
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element, seasonName: String) = SEpisode.create().apply {
        val epNum = element.selectFirst("div.episodiotitle p")!!.text()
            .trim()
            .let(episodeNumberRegex::find)
            ?.groupValues
            ?.last() ?: "0"
        val href = element.selectFirst("a[href]")!!
        episode_number = epNum.toFloatOrNull() ?: 0F
        name = "$seasonName x Episódio $epNum"
        setUrlWithoutDomain(href.absUrl("href"))
    }

    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("360p", "720p")
    override val prefQualityEntries = prefQualityValues

    private val bloggerExtractor by lazy { BloggerExtractor(client) }
    private val playerGuardResolver by lazy { PlayerGuardResolver() }
    private val cookielessClient by lazy { client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build() }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val episodeFullUrl = KyozExtractor.absoluteUrl(baseUrl, episode.url)
        val document = runCatching {
            client.get(
                url = episodeFullUrl,
                headers = headers,
                cacheControl = CacheControl.FORCE_NETWORK,
            ).useAsJsoup()
        }.onFailure { throwable ->
            AnimePlayerLog.e(
                AnimePlayerLog.Category.REQUEST_FAILURE,
                "episode page request failed for ${AnimePlayerLog.sanitizeUrlForLog(episodeFullUrl)}",
                throwable,
            )
        }.getOrThrow()

        val videos = resolveEpisodeVideos(episodeFullUrl, document).sortVideos()
        if (videos.isEmpty()) return emptyList()
        return listOf(Hoster(hosterUrl = episodeFullUrl, hosterName = "AnimePlayer", videoList = videos))
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty().sortVideos()

    private suspend fun resolveEpisodeVideos(episodeFullUrl: String, document: Document): List<Video> {
        val quality = KyozExtractor.extractQualityLabel(document)
        val legacyUrl = KyozExtractor.extractLegacyIframeUrl(document)
        val playerKeys = KyozExtractor.extractPlayerKeys(document)

        AnimePlayerLog.d(
            "resolveEpisodeVideos url=${AnimePlayerLog.sanitizeUrlForLog(episodeFullUrl)} " +
                "quality=$quality hasLegacy=${legacyUrl != null} playerKeys=${playerKeys.size}",
        )

        if (legacyUrl != null) {
            val legacyVideos = runCatching {
                resolveVideoUrl(legacyUrl, episodeFullUrl, quality, "")
            }.onFailure { throwable ->
                AnimePlayerLog.e(
                    AnimePlayerLog.Category.VIDEO_CONVERSION_FAILURE,
                    "legacy iframe extraction failed url=${AnimePlayerLog.sanitizeUrlForLog(legacyUrl)}",
                    throwable,
                )
            }.getOrDefault(emptyList())

            if (legacyVideos.isNotEmpty()) {
                AnimePlayerLog.d("resolved ${legacyVideos.size} video(s) from legacy playex iframe")
                return legacyVideos
            }
            AnimePlayerLog.w(
                AnimePlayerLog.Category.EXTRACTION_FAILURE,
                "legacy playex iframe returned 0 videos url=${AnimePlayerLog.sanitizeUrlForLog(legacyUrl)}",
            )
        }

        if (playerKeys.isEmpty()) {
            AnimePlayerLog.w(
                AnimePlayerLog.Category.EMPTY_RESPONSE,
                "no data-player-key placeholders and no playable legacy iframe on episode page",
            )
            return emptyList()
        }

        val userAgent = KyozExtractor.extractUserAgent(headers)
        var effectiveHeaders = headers
        val resolvedUrls = linkedMapOf<String, String>()
        var ticket: String? = null
        var servers: List<VerifyServerDto> = emptyList()
        var lastGuardEvents: List<String> = emptyList()

        for (attempt in 1..2) {
            val guardState = runCatching {
                playerGuardResolver.resolve(episodeFullUrl, userAgent)
            }.onFailure { throwable ->
                AnimePlayerLog.e(
                    AnimePlayerLog.Category.REQUEST_FAILURE,
                    if (attempt == 1) "PlayerGuardResolver threw exception" else "PlayerGuardResolver retry threw exception",
                    throwable,
                )
            }.getOrNull() ?: return emptyList()

            lastGuardEvents = guardState.events
            effectiveHeaders = KyozExtractor.withUserAgent(headers, guardState.resolvedUserAgent)

            AnimePlayerLog.d(
                "guard result status=${guardState.status} stage=${guardState.stage} " +
                    "tsLoaded=${guardState.turnstileLoaded} tsRendered=${guardState.turnstileRendered} " +
                    "capturedToken=${guardState.turnstileToken != null} verify=${guardState.verifyStatus} " +
                    "load=${guardState.loadStatus} hasTicket=${guardState.ticket != null} " +
                    "servers=${guardState.servers.size} urls=${guardState.urls.size} errorCode=${guardState.errorCode}",
            )

            val capturedToken = guardState.turnstileToken
            val capturedKeys = guardState.playerKeys
            if (capturedToken != null && capturedKeys.isNotEmpty()) {
                AnimePlayerLog.d(
                    "captured turnstile token in webview, running clean OkHttp verify/load keys=${capturedKeys.size} attempt=$attempt",
                )
                val verifyResult = KyozExtractor.fetchVerify(
                    baseUrl = baseUrl,
                    episodeUrl = episodeFullUrl,
                    turnstileToken = capturedToken,
                    playerKeys = capturedKeys,
                    baseHeaders = effectiveHeaders,
                    client = client,
                )
                if (PlayerGuardResolver.shouldRetryGuard(verifyResult.code, attempt)) {
                    AnimePlayerLog.d("clean OkHttp /verify returned HTTP 410, retrying guard resolution once with fresh page")
                    continue
                }
                val verifiedTicket = verifyResult.ticket
                if (verifyResult.code !in 200..299 || verifiedTicket.isNullOrBlank() || verifyResult.servers.isEmpty()) {
                    AnimePlayerLog.w(
                        AnimePlayerLog.Category.AUTH_VERIFY_FAILURE,
                        "clean OkHttp /verify did not return usable ticket/servers code=${verifyResult.code} servers=${verifyResult.servers.size}",
                    )
                    return emptyList()
                }
                AnimePlayerLog.d("clean OkHttp /verify succeeded servers=${verifyResult.servers.size}")
                ticket = verifiedTicket
                servers = verifyResult.servers
                for (server in servers) {
                    val url = KyozExtractor.fetchServerUrl(
                        baseUrl = baseUrl,
                        episodeUrl = episodeFullUrl,
                        ticket = verifiedTicket,
                        playerKey = server.id,
                        baseHeaders = effectiveHeaders,
                        client = client,
                    ) ?: continue
                    if (url !in resolvedUrls) {
                        resolvedUrls[url] = server.label
                    }
                }
                break
            }

            if (PlayerGuardResolver.shouldRetryGuard(guardState.verifyStatus, attempt)) {
                AnimePlayerLog.d("verify returned HTTP 410, retrying guard resolution once with fresh page")
                continue
            }

            if (guardState.status != PlayerGuardResolver.GuardStatus.SUCCESS) {
                val category = mapGuardStatusToCategory(guardState.status)
                AnimePlayerLog.w(
                    category,
                    "player guard did not unlock player: status=${guardState.status} stage=${guardState.stage} " +
                        "tsLoaded=${guardState.turnstileLoaded} tsRendered=${guardState.turnstileRendered} " +
                        "verify=${guardState.verifyStatus} load=${guardState.loadStatus} errorCode=${guardState.errorCode} " +
                        "errMsg=${AnimePlayerLog.sanitizeMessage(guardState.errorMessage)} " +
                        "trail=${guardState.events.joinToString(">")}",
                )
                return emptyList()
            }

            guardState.urls.forEachIndexed { idx, url ->
                val label = guardState.servers.getOrNull(idx)?.label.orEmpty()
                resolvedUrls[url] = label
            }
            ticket = guardState.ticket
            servers = guardState.servers
            val guardTicket = ticket
            if (guardTicket != null && guardState.urls.size < servers.size) {
                for (server in servers) {
                    val url = KyozExtractor.fetchServerUrl(
                        baseUrl = baseUrl,
                        episodeUrl = episodeFullUrl,
                        ticket = guardTicket,
                        playerKey = server.id,
                        baseHeaders = effectiveHeaders,
                        client = client,
                    ) ?: continue
                    if (url !in resolvedUrls) {
                        resolvedUrls[url] = server.label
                    }
                }
            }
            break
        }

        if (resolvedUrls.isEmpty()) {
            AnimePlayerLog.w(
                AnimePlayerLog.Category.EMPTY_RESPONSE,
                "guard succeeded but no player URLs resolved from servers=${servers.size} trail=${lastGuardEvents.joinToString(">")}",
            )
            return emptyList()
        }

        var videos = resolvedUrls.entries
            .flatMap { (playerUrl, serverLabel) ->
                val suffix = if (serverLabel.isNotBlank() && resolvedUrls.size > 1) "($serverLabel)" else ""
                runCatching { resolveVideoUrl(playerUrl, episodeFullUrl, quality, suffix, effectiveHeaders) }
                    .onFailure { throwable ->
                        AnimePlayerLog.e(
                            AnimePlayerLog.Category.VIDEO_CONVERSION_FAILURE,
                            "resolveVideoUrl failed for ${AnimePlayerLog.sanitizeUrlForLog(playerUrl)}",
                            throwable,
                        )
                    }
                    .getOrDefault(emptyList())
            }
            .distinctBy { it.videoUrl }

        val finalTicket = ticket
        if (videos.isEmpty() && finalTicket != null && servers.isNotEmpty()) {
            videos = remintKyozVideos(servers, finalTicket, episodeFullUrl, quality, effectiveHeaders)
        }

        if (videos.isEmpty()) {
            AnimePlayerLog.w(
                AnimePlayerLog.Category.VIDEO_CONVERSION_FAILURE,
                "resolved ${resolvedUrls.size} player URL(s) but produced 0 playable Video objects trail=${lastGuardEvents.joinToString(">")}",
            )
        } else {
            AnimePlayerLog.d("resolved ${videos.size} playable Video object(s)")
        }
        return videos
    }

    private suspend fun remintKyozVideos(
        servers: List<VerifyServerDto>,
        ticket: String,
        episodeFullUrl: String,
        quality: String,
        effectiveHeaders: Headers,
    ): List<Video> {
        AnimePlayerLog.d("re-minting fresh kyoz URL via clean OkHttp /load for ${servers.size} server(s)")
        val reminted = mutableListOf<Video>()
        for (server in servers) {
            val freshUrl = runCatching {
                KyozExtractor.fetchServerUrl(
                    baseUrl = baseUrl,
                    episodeUrl = episodeFullUrl,
                    ticket = ticket,
                    playerKey = server.id,
                    baseHeaders = effectiveHeaders,
                    client = client,
                )
            }.onFailure { throwable ->
                AnimePlayerLog.e(
                    AnimePlayerLog.Category.REQUEST_FAILURE,
                    "re-mint /load failed for server",
                    throwable,
                )
            }.getOrNull() ?: continue

            val suffix = if (server.label.isNotBlank() && servers.size > 1) "(${server.label})" else ""
            reminted += runCatching {
                resolveVideoUrl(freshUrl, episodeFullUrl, quality, suffix, effectiveHeaders)
            }.onFailure { throwable ->
                AnimePlayerLog.e(
                    AnimePlayerLog.Category.VIDEO_CONVERSION_FAILURE,
                    "re-minted resolveVideoUrl failed for ${AnimePlayerLog.sanitizeUrlForLog(freshUrl)}",
                    throwable,
                )
            }.getOrDefault(emptyList())
        }
        return reminted.distinctBy { it.videoUrl }
    }

    private fun mapGuardStatusToCategory(status: PlayerGuardResolver.GuardStatus): AnimePlayerLog.Category = when (status) {
        PlayerGuardResolver.GuardStatus.CLOUDFLARE_CHALLENGE_PENDING,
        PlayerGuardResolver.GuardStatus.TIMEOUT,
        -> AnimePlayerLog.Category.CLOUDFLARE_CHALLENGE_PENDING
        PlayerGuardResolver.GuardStatus.AUTH_VERIFY_FAILURE -> AnimePlayerLog.Category.AUTH_VERIFY_FAILURE
        PlayerGuardResolver.GuardStatus.PARSING_ERROR -> AnimePlayerLog.Category.PARSING_ERROR
        PlayerGuardResolver.GuardStatus.EMPTY_RESPONSE -> AnimePlayerLog.Category.EMPTY_RESPONSE
        PlayerGuardResolver.GuardStatus.REQUEST_FAILURE,
        PlayerGuardResolver.GuardStatus.SUCCESS,
        -> AnimePlayerLog.Category.REQUEST_FAILURE
    }

    private suspend fun resolveVideoUrl(
        url: String,
        episodeUrl: String,
        quality: String,
        suffix: String,
        requestHeaders: Headers = headers,
    ): List<Video> = when {
        KyozExtractor.isKyozUrl(url) -> {
            val bloggerUrl = KyozExtractor.resolveBloggerUrlWithRefererFallback(
                kyozUrl = url,
                episodeUrl = episodeUrl,
                baseUrl = baseUrl,
                baseHeaders = requestHeaders,
            ) { kyozUrl, reqHeaders ->
                KyozExtractor.fetchKyozHtmlDetailed(kyozUrl, reqHeaders, client)
            } ?: run {
                AnimePlayerLog.d("retrying kyoz HTML fetch without cookies")
                KyozExtractor.resolveBloggerUrlWithRefererFallback(
                    kyozUrl = url,
                    episodeUrl = episodeUrl,
                    baseUrl = baseUrl,
                    baseHeaders = requestHeaders,
                ) { kyozUrl, reqHeaders ->
                    KyozExtractor.fetchKyozHtmlDetailed(kyozUrl, reqHeaders, cookielessClient)
                }
            } ?: return emptyList()
            val bloggerHeaders = KyozExtractor.bloggerHeadersFor(url, requestHeaders)
            val videos = bloggerExtractor.videosFromUrl(bloggerUrl, bloggerHeaders, suffix)
            if (videos.isEmpty()) {
                AnimePlayerLog.w(
                    AnimePlayerLog.Category.VIDEO_CONVERSION_FAILURE,
                    "BloggerExtractor returned 0 videos for kyoz-resolved blogger token",
                )
            }
            videos
        }

        "blogger.com" in url -> {
            val bloggerHeaders = KyozExtractor.bloggerHeadersFor("https://img.kyoz.net/", requestHeaders)
            val videos = bloggerExtractor.videosFromUrl(url, bloggerHeaders, suffix)
            if (videos.isEmpty()) {
                AnimePlayerLog.w(
                    AnimePlayerLog.Category.VIDEO_CONVERSION_FAILURE,
                    "BloggerExtractor returned 0 videos for direct blogger URL",
                )
            }
            videos
        }

        "cdn.animeson.com.br" in url -> listOf(
            Video(
                videoUrl = url,
                videoTitle = if (suffix.isNotBlank()) "$quality $suffix" else quality,
                headers = requestHeaders,
            ),
        )

        else -> {
            AnimePlayerLog.w(
                AnimePlayerLog.Category.EXTRACTION_FAILURE,
                "unsupported player host=${AnimePlayerLog.sanitizeUrlForLog(url)}",
            )
            emptyList()
        }
    }

    // ============================== Filters ===============================
    override fun genresListSelector() = "ul.genres a"

    // ============================= Utilities ==============================
    override val animeMenuSelector = "div.pag_episodes div.item a[href] i.icon-bars"
}
