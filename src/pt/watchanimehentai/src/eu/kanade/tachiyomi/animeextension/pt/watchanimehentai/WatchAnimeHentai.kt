package eu.kanade.tachiyomi.animeextension.pt.watchanimehentai

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.head
import keiyoushi.network.rateLimit
import keiyoushi.source.AniSource
import keiyoushi.source.model.SAnimeUpdate
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.useAsJsoup
import okhttp3.CacheControl
import okhttp3.OkHttpClient

@Source
abstract class WatchAnimeHentai : AniSource() {

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Redirect targets (e.g. CDN domains) may be blocked by user DNS/network; intercept Location directly.
    private val noRedirectClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .build()
    }

    // ============================== Catalog ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val document = client.get(WatchAnimeHentaiParser.buildCatalogUrl(baseUrl, page), headers).useAsJsoup()
        return WatchAnimeHentaiParser.parseCatalogPage(document).toAnimesPage()
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val document = client.get(WatchAnimeHentaiParser.buildLatestUrl(baseUrl, page), headers).useAsJsoup()
        return WatchAnimeHentaiParser.parseLatestPage(document).toAnimesPage()
    }

    override fun getFilterList(): AnimeFilterList = WatchAnimeHentaiFilters.getFilterList()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val document = client.get(WatchAnimeHentaiParser.buildSearchUrl(baseUrl, page, query), headers).useAsJsoup()
            return WatchAnimeHentaiParser.parseSearchPage(document).toAnimesPage()
        }

        val genreSlug = filters.firstInstanceOrNull<GenreFilter>()?.selectedSlug().orEmpty()
        if (genreSlug.isNotEmpty()) {
            val document = client.get(WatchAnimeHentaiParser.buildGenreUrl(baseUrl, page, genreSlug), headers).useAsJsoup()
            return WatchAnimeHentaiParser.parseCatalogPage(document).toAnimesPage()
        }

        val letterSlug = filters.firstInstanceOrNull<LetterFilter>()?.selectedSlug().orEmpty()
        if (letterSlug.isNotEmpty()) {
            val document = client.get(WatchAnimeHentaiParser.buildLetterUrl(baseUrl, page, letterSlug), headers).useAsJsoup()
            return WatchAnimeHentaiParser.parseCatalogPage(document).toAnimesPage()
        }

        return getPopularAnime(page)
    }

    // ============================== Details ===============================

    override suspend fun fetchAnimeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeUpdate {
        if (!fetchDetails && !fetchEpisodes) return SAnimeUpdate(anime, episodes)

        var canonicalPath = anime.url
        var infoDoc = client.get(absoluteUrl(canonicalPath), headers).useAsJsoup()

        if (canonicalPath.startsWith("/pt/episodios/")) {
            val resolvedPath = WatchAnimeHentaiParser.extractAnimeInfoPathFromEpisode(infoDoc)
            if (resolvedPath != null) {
                canonicalPath = resolvedPath
                infoDoc = client.get(absoluteUrl(canonicalPath), headers).useAsJsoup()
            }
        }

        val updatedAnime = if (fetchDetails) {
            val parsed = WatchAnimeHentaiParser.parseAnimeDetails(infoDoc, canonicalPath)
            SAnime.create().apply {
                url = parsed.url
                title = parsed.title.ifEmpty { anime.title }
                thumbnail_url = parsed.thumbnailUrl ?: anime.thumbnail_url
                description = parsed.description
                genre = parsed.genres.joinToString().ifEmpty { null }
                author = parsed.author
            }
        } else {
            anime
        }

        val updatedEpisodes = if (fetchEpisodes) {
            WatchAnimeHentaiParser.parseEpisodeList(infoDoc).map { parsed ->
                SEpisode.create().apply {
                    url = parsed.url
                    name = parsed.name
                    episode_number = parsed.episodeNumber
                }
            }
        } else {
            episodes
        }

        return SAnimeUpdate(updatedAnime, updatedEpisodes)
    }

    // ============================== Videos ================================

    override suspend fun fetchHosterList(episode: SEpisode): List<Hoster> {
        val document = client.get(
            url = absoluteUrl(episode.url),
            headers = headers,
            cacheControl = CacheControl.FORCE_NETWORK,
        ).useAsJsoup()

        return WatchAnimeHentaiParser.parseHosters(document).map { hoster ->
            Hoster(
                hosterUrl = hoster.url,
                hosterName = hoster.name,
                internalData = if (hoster.isBr) INTERNAL_BR_TAG else "",
            )
        }
    }

    override suspend fun fetchVideoList(hoster: Hoster): List<Video> {
        val embedHeaders = WatchAnimeHentaiParser.embedHeadersFor(hoster.hosterUrl, baseUrl, headers)
        val document = client.get(
            url = hoster.hosterUrl,
            headers = embedHeaders,
            cacheControl = CacheControl.FORCE_NETWORK,
        ).useAsJsoup()

        val sources = WatchAnimeHentaiParser.parsePlayerSources(document)
        if (sources.isEmpty()) return emptyList()

        val isPreferred = hoster.internalData == INTERNAL_BR_TAG
        return sources.map { source ->
            val resolvedUrl = resolveSourceUrl(source.url)
            val title = listOfNotNull(
                hoster.hosterName.takeIf { it.isNotBlank() },
                source.label.takeIf { it.isNotBlank() },
            ).joinToString(" - ")
            Video(
                videoUrl = resolvedUrl,
                videoTitle = title.ifEmpty { "WatchAnimeHentai" },
                headers = headers,
                preferred = isPreferred,
            )
        }
    }

    private suspend fun resolveSourceUrl(url: String): String {
        if (!WatchAnimeHentaiParser.isP2RedirectUrl(url)) return url
        return noRedirectClient.head(
            url = url,
            headers = headers,
            cacheControl = CacheControl.FORCE_NETWORK,
            ensureSuccess = false,
        ).use { response ->
            WatchAnimeHentaiParser.normalizeLocationUrl(response.header("Location"), url) ?: url
        }
    }

    private fun absoluteUrl(pathOrUrl: String): String = if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
        pathOrUrl
    } else {
        baseUrl + pathOrUrl
    }

    private fun ParsedAnimePage.toAnimesPage(): AnimesPage {
        val list = animes.map { item ->
            SAnime.create().apply {
                url = item.url
                title = item.title
                thumbnail_url = item.thumbnailUrl
            }
        }
        return AnimesPage(list, hasNextPage)
    }

    companion object {
        private const val INTERNAL_BR_TAG = "br"
    }
}
