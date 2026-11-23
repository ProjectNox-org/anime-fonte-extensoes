package eu.kanade.tachiyomi.animeextension.pt.watchanimehentai

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

internal data class ParsedAnime(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
)

internal data class ParsedAnimePage(
    val animes: List<ParsedAnime>,
    val hasNextPage: Boolean,
)

internal data class ParsedAnimeDetails(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val description: String?,
    val genres: List<String>,
    val author: String?,
)

internal data class ParsedEpisode(
    val url: String,
    val name: String,
    val episodeNumber: Float,
)

internal data class ParsedHoster(
    val url: String,
    val name: String,
    val isBr: Boolean,
)

internal data class ParsedVideoSource(
    val url: String,
    val label: String,
)

private val WHITESPACE_REGEX = Regex("""\s+""")
private val EPISODE_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")

internal object WatchAnimeHentaiParser {

    fun buildCatalogUrl(baseUrl: String, page: Int): String = if (page <= 1) "$baseUrl/pt/lista-de-hentai/" else "$baseUrl/pt/lista-de-hentai/$page/"

    fun buildLatestUrl(baseUrl: String, page: Int): String = if (page <= 1) "$baseUrl/pt/ultimos-episodios-adicionados/" else "$baseUrl/pt/ultimos-episodios-adicionados/$page/"

    fun buildSearchUrl(baseUrl: String, page: Int, query: String): String {
        val normalized = query.trim().replace(WHITESPACE_REGEX, " ")
        val encoded = URLEncoder.encode(normalized, "UTF-8")
        return if (page <= 1) {
            "$baseUrl/pt/busca/$encoded/"
        } else {
            "$baseUrl/pt/busca/$encoded/?pagination=$page"
        }
    }

    fun buildGenreUrl(baseUrl: String, page: Int, slug: String): String = if (page <= 1) "$baseUrl/pt/genero/$slug/" else "$baseUrl/pt/genero/$slug/$page/"

    fun buildLetterUrl(baseUrl: String, page: Int, letter: String): String = if (page <= 1) "$baseUrl/pt/letra/$letter/" else "$baseUrl/pt/letra/$letter/$page/"

    fun parseCatalogPage(document: Document): ParsedAnimePage {
        val cards = document.select("div#archive-content article.item.tvshows").ifEmpty {
            document.select("article.item.tvshows")
        }
        val animes = cards.mapNotNull { card ->
            val link = card.selectFirst("div.data h3 a[href]")
                ?: card.selectFirst("a[href*=/pt/info/]")
                ?: return@mapNotNull null
            val path = link.relativePath() ?: return@mapNotNull null
            val img = card.selectFirst("div.poster img")
            val title = link.text().trim()
                .ifEmpty { img?.attr("alt")?.trim().orEmpty() }
                .ifEmpty { return@mapNotNull null }
            ParsedAnime(
                url = path,
                title = title,
                thumbnailUrl = img?.imageSrc(),
            )
        }
        return ParsedAnimePage(animes = animes, hasNextPage = hasNextPage(document))
    }

    fun parseLatestPage(document: Document): ParsedAnimePage {
        val seenTitles = HashSet<String>()
        val cards = document.select("div.animation-2.items article.item.se.episodes").ifEmpty {
            document.select("article.item.se.episodes")
        }
        val animes = cards.mapNotNull { card ->
            val link = card.selectFirst("a[href*=/pt/episodios/]")
                ?: card.selectFirst("a[href]")
                ?: return@mapNotNull null
            val path = link.relativePath() ?: return@mapNotNull null
            val img = card.selectFirst("img")
            val title = card.selectFirst("span.serie")?.text()?.trim()
                ?.ifEmpty { null }
                ?: card.selectFirst("div.season_m span.b")?.text()?.trim()?.ifEmpty { null }
                ?: img?.attr("alt")?.trim()?.ifEmpty { null }
                ?: return@mapNotNull null
            val dedupKey = title.lowercase()
            if (!seenTitles.add(dedupKey)) return@mapNotNull null
            ParsedAnime(
                url = path,
                title = title,
                thumbnailUrl = img?.imageSrc(),
            )
        }
        return ParsedAnimePage(animes = animes, hasNextPage = hasNextPage(document))
    }

    fun parseSearchPage(document: Document): ParsedAnimePage {
        val animes = document.select("section.search-results article.search-result").mapNotNull { card ->
            val link = card.selectFirst("h2.search-result-title a[href]")
                ?: card.selectFirst("a[href*=/pt/info/]")
                ?: return@mapNotNull null
            val path = link.relativePath() ?: return@mapNotNull null
            val img = card.selectFirst("a.search-result-poster img") ?: card.selectFirst("img")
            val title = link.text().trim()
                .ifEmpty { img?.attr("alt")?.trim().orEmpty() }
                .ifEmpty { return@mapNotNull null }
            ParsedAnime(
                url = path,
                title = title,
                thumbnailUrl = img?.imageSrc(),
            )
        }
        return ParsedAnimePage(animes = animes, hasNextPage = hasNextPage(document))
    }

    fun extractAnimeInfoPathFromEpisode(document: Document): String? {
        val backLink = document.selectFirst("a[href*=/pt/info/]:has(i.icon-bars)")
            ?: document.selectFirst("div.pag_episodes a[href*=/pt/info/]")
            ?: return null
        return backLink.relativePath()
    }

    fun parseAnimeDetails(document: Document, canonicalPath: String): ParsedAnimeDetails {
        val header = document.selectFirst("div.sheader")
        val data = header?.selectFirst("div.data") ?: document.selectFirst("div.data")
        val title = data?.selectFirst("h1")?.text()?.trim().orEmpty()
        val thumbnailUrl = header?.selectFirst("div.poster img")?.imageSrc()
        val genres = data?.select("div.sgeneros > a[href*=/pt/genero/]")?.mapNotNull {
            it.text().trim().takeIf(String::isNotEmpty)
        }.orEmpty()
        val author = document.select("div.custom_fields:has(b:contains(Produtora)) div.sgeneros > a")
            .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .joinToString(", ")
            .ifEmpty { null }
        val paragraphs = document.select("div#info1 div.wp-content p")
            .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
        val description = paragraphs.joinToString("\n\n")
            .ifEmpty { document.selectFirst("div#info1 div.wp-content")?.text()?.trim().orEmpty() }
            .ifEmpty { null }

        return ParsedAnimeDetails(
            url = canonicalPath,
            title = title,
            thumbnailUrl = thumbnailUrl,
            description = description,
            genres = genres,
            author = author,
        )
    }

    fun parseEpisodeList(document: Document): List<ParsedEpisode> = document.select("div#episodes article.item.se.episodes")
        .mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val path = link.relativePath() ?: return@mapNotNull null
            val name = card.selectFirst("div.season_m span.c")?.text()?.trim()?.ifEmpty { null }
                ?: card.selectFirst("div.data h3")?.ownText()?.trim()?.ifEmpty { null }
                ?: link.attr("title").trim().ifEmpty { null }
                ?: return@mapNotNull null
            val number = EPISODE_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 1F
            ParsedEpisode(url = path, name = name, episodeNumber = number)
        }
        .reversed()

    fun parseHosters(document: Document): List<ParsedHoster> {
        val iframes = document.select("iframe[data-infoplayer][src]").ifEmpty {
            document.select("iframe.player-resposive[src], div.playex iframe[src]")
        }
        val parsed = iframes.mapIndexedNotNull { index, iframe ->
            val rawSrc = iframe.absUrl("src").ifEmpty { iframe.attr("src").trim() }
            if (rawSrc.isBlank()) return@mapIndexedNotNull null
            val label = iframe.attr("data-infoplayer").trim().ifEmpty { "Player ${index + 1}" }
            val isBr = label.endsWith("BR", ignoreCase = true) || " BR" in label.uppercase()
            ParsedHoster(url = rawSrc, name = label, isBr = isBr)
        }
        return parsed.sortedBy { hosterSortRank(it.name, it.isBr) }
    }

    fun parsePlayerSources(document: Document): List<ParsedVideoSource> {
        val sources = document.select("video source[src], source[src]")
        return sources.mapNotNull { el ->
            val rawUrl = el.absUrl("src").ifEmpty { el.attr("src").trim() }
            if (rawUrl.isBlank() || "/pre-roll/" in rawUrl) return@mapNotNull null
            val label = el.attr("label").trim()
                .ifEmpty { el.attr("res").trim() }
                .ifEmpty { el.attr("size").trim() }
            ParsedVideoSource(url = rawUrl, label = label)
        }
    }

    fun embedHeadersFor(url: String, baseUrl: String, baseHeaders: Headers): Headers {
        val targetHost = url.toHttpUrlOrNull()?.host
        val baseHost = baseUrl.toHttpUrlOrNull()?.host
        val fetchSite = if (targetHost != null && targetHost.equals(baseHost, ignoreCase = true)) {
            "same-origin"
        } else {
            "cross-site"
        }
        return baseHeaders.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Sec-Fetch-Dest", "iframe")
            .set("Sec-Fetch-Mode", "navigate")
            .set("Sec-Fetch-Site", fetchSite)
            .build()
    }

    fun normalizeLocationUrl(location: String?, requestUrl: String): String? {
        val cleaned = location?.trim()?.takeIf { it.isNotEmpty() }?.replace(" ", "%20") ?: return null
        val base = requestUrl.toHttpUrlOrNull()
        return base?.resolve(cleaned)?.toString() ?: cleaned
    }

    fun isP2RedirectUrl(url: String): Boolean = "/players/p2/p2.php" in url

    private fun hosterSortRank(name: String, isBr: Boolean): Int {
        val upper = name.uppercase()
        return when {
            upper == "ALTERNATIVO BR" -> 0
            isBr -> 1
            "ALTERNATIVO" in upper -> 2
            else -> 3
        }
    }

    private fun hasNextPage(document: Document): Boolean = document.selectFirst("div.paginacao a[rel=next]") != null

    private fun Element.relativePath(): String? {
        val abs = absUrl("href").trim()
        if (abs.isNotEmpty()) {
            abs.toHttpUrlOrNull()?.let { httpUrl ->
                val query = httpUrl.encodedQuery?.let { "?$it" }.orEmpty()
                return httpUrl.encodedPath + query
            }
        }
        return attr("href").trim().takeIf { it.isNotEmpty() }
    }

    private fun Element.imageSrc(): String? = absUrl("data-lazy-src").ifEmpty { absUrl("src") }
        .ifEmpty { attr("data-lazy-src").ifEmpty { attr("src") }.trim() }
        .takeIf { it.isNotEmpty() }
}
