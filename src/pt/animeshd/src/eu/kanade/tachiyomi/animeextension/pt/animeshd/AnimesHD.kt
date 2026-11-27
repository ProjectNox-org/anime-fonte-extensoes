package eu.kanade.tachiyomi.animeextension.pt.animeshd

import android.util.Log
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class AnimesHD : DooPlay() {
    override val fetchGenres = false
    override val prefQualityKey = "quality_selection"
    override val prefQualityDefault = "best"
    override val prefQualityValues = arrayOf("best", "1080p", "720p", "480p", "360p")
    override val prefQualityEntries = arrayOf("Maior disponível", "1080p", "720p", "480p", "360p")
    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/")

    // The site has a full archive but no public popularity ranking.
    override fun popularAnimeRequest(page: Int) = GET(pageUrl("animes", page), headers)
    override fun popularAnimeSelector() = "#archive-content article.item.tvshows > .poster"
    override fun popularAnimeNextPageSelector() = ".pagination a:has(#nextpagination), .resppages a:has(.fa-chevron-right)"
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun latestUpdatesSelector() = ".result-item .image a:has(.tvshows)"
    override fun latestUpdatesRequest(page: Int) = searchRequest(page, "")
    override fun popularAnimeParse(response: Response) = super.popularAnimeParse(response).also {
        debug("popular items=${it.animes.size} next=${it.hasNextPage}")
    }
    override fun latestUpdatesParse(response: Response) = super.latestUpdatesParse(response).also {
        debug("latest items=${it.animes.size} next=${it.hasNextPage}")
    }
    private fun pageUrl(path: String, page: Int) = "$baseUrl/${path.trim('/').let { if (it.isBlank()) "" else "$it/" }}${if (page > 1) "page/$page/" else ""}"
    private fun searchRequest(page: Int, query: String) = GET(
        pageUrl("", page).toHttpUrl().newBuilder().addQueryParameter("s", query)
            .addQueryParameter("post_type", "tvshows").addQueryParameter("orderby", "date")
            .addQueryParameter("order", "DESC").build(),
        headers,
    )
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): okhttp3.Request {
        val path = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart().orEmpty()
        return if (query.isNotBlank() || path.isBlank()) searchRequest(page, query) else GET(pageUrl(path, page), headers)
    }
    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val selector = if (response.request.url.queryParameter("s") != null) latestUpdatesSelector() else popularAnimeSelector()
        return AnimesPage(doc.select(selector).map(::popularAnimeFromElement).distinctBy { it.url }, doc.selectFirst(popularAnimeNextPageSelector()) != null)
            .also { debug("search items=${it.animes.size} next=${it.hasNextPage}") }
    }

    @Volatile private var genres: Array<Pair<String, String>> = emptyArray()
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("A busca por nome ignora o gênero."),
        GenreFilter(arrayOf("Todos" to "") + genres),
    )
    private class GenreFilter(values: Array<Pair<String, String>>) : UriPartFilter("Gênero", values)

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val header = document.selectFirst(".sheader") ?: error("Detalhes não encontrados")
        setUrlWithoutDomain(document.location())
        title = header.selectFirst("h1")!!.text()
        thumbnail_url = header.selectFirst(".poster img")?.getImageUrl()
        genre = header.select(".sgeneros a").eachText().joinToString()
        val fields = document.select("#info .custom_fields").associate {
            it.selectFirst("b")?.text().orEmpty() to it.selectFirst("span")?.text().orEmpty()
        }
        description = buildString {
            append(document.selectFirst("#info .wp-content > p")?.text().orEmpty())
            header.selectFirst(".date")?.text()?.takeLast(4)?.toIntOrNull()?.let { append("\n\nAno: $it") }
            for (key in listOf("Título Original", "Duração média")) {
                fields[key]?.takeIf(String::isNotBlank)?.let { append("\n$key: $it") }
            }
        }
        status = when (fields["Status"]?.lowercase()) {
            "em andamento", "em exibição" -> SAnime.ONGOING
            "completo", "finalizado", "concluído" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        val discovered = document.select("a[href*=/generos/]").map { it.text() to it.absUrl("href").toHttpUrl().encodedPath }
        genres = (genres.toList() + discovered).distinctBy { it.second }.sortedBy { it.first }.toTypedArray()
        debug("details cover=${thumbnail_url != null} genres=${header.select(".sgeneros a").size}")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val seasons = doc.select("#seasons > .se-c")
        return seasons.flatMap { season ->
            val number = season.selectFirst(".se-q .se-t")?.text().orEmpty()
            season.select("ul.episodios > li:has(.episodiotitle a)").map { episodeFromElement(it, number) }
        }.distinctBy { it.url }.reversed().also { debug("seasons=${seasons.size} episodes=${it.size}") }
    }
    override fun episodeFromElement(element: Element, seasonName: String) = SEpisode.create().apply {
        val link = element.selectFirst(".episodiotitle a")!!
        val number = element.selectFirst(".numerando")?.text()?.substringAfterLast('-')?.trim()
        setUrlWithoutDomain(link.absUrl("href"))
        name = "T$seasonName E${number.orEmpty()} - ${link.text()}"
        episode_number = number?.toFloatOrNull() ?: -1F
        date_upload = parseDate(element.selectFirst(".date")?.text())
    }
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/Sao_Paulo") }
    private fun parseDate(value: String?) = synchronized(dateFormat) { dateFormat.tryParse(value?.replace(".", "")) }

    private val blogger by lazy { BloggerExtractor(client) }
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val doc = client.newCall(GET(baseUrl + episode.url, headers)).awaitSuccess().asJsoup()
        val episodeUrl = doc.location().ifBlank { baseUrl + episode.url }
        val options = doc.select("#playeroptions li[data-post][data-type][data-nume]")
        return options.mapNotNull { option ->
            val nume = option.attr("data-nume")
            if (nume == "trailer") return@mapNotNull null
            val label = option.selectFirst(".title")?.text().orEmpty()
            val payload = Json.encodeToString(
                PlayerOption(
                    post = option.attr("data-post"),
                    type = option.attr("data-type"),
                    nume = nume,
                    label = label,
                    referer = episodeUrl,
                ),
            )
            Hoster(
                hosterUrl = "$episodeUrl#$nume",
                hosterName = label.ifBlank { "Player $nume" },
                internalData = payload,
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val option = runCatching { Json.decodeFromString<PlayerOption>(hoster.internalData) }.getOrNull()
            ?: return emptyList()
        val language = when {
            option.label.contains("dublado", true) -> "Dublado"
            option.label.contains("legendado", true) -> "Legendado"
            else -> "Idioma não informado"
        }
        val videos = try {
            withTimeoutOrNull(15_000) {
                val form = FormBody.Builder().add("action", "doo_player_ajax")
                    .add("post", option.post).add("type", option.type)
                    .add("nume", option.nume).build()
                val ajaxHeaders = headers.newBuilder().set("Referer", option.referer).set("X-Requested-With", "XMLHttpRequest").build()
                val embed = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, form)).awaitSuccess().parseAs<Player>()
                val url = embed.url.toHttpUrlOrNull() ?: return@withTimeoutOrNull emptyList()
                videoDebug("ajax=200 host=${url.host} language=$language")
                if (url.host != "www.blogger.com" && url.host != "blogger.com") {
                    videoDebug("unsupported host=${url.host}")
                    return@withTimeoutOrNull emptyList()
                }
                val mediaHeaders = headers.newBuilder().set("Referer", "https://www.blogger.com/").build()
                blogger.videosFromUrl(url.toString(), mediaHeaders).mapNotNull { video ->
                    val media = video.videoUrl.toHttpUrlOrNull() ?: return@mapNotNull null
                    if (!media.host.endsWith(".googlevideo.com") || media.encodedPath != "/videoplayback") return@mapNotNull null
                    val quality = when (media.queryParameter("itag")) {
                        "18" -> "360p"
                        "22" -> "720p"
                        "37" -> "1080p"
                        "59", "78" -> "480p"
                        null -> if (media.queryParameter("mime") == "video/mp4") "Unknown" else return@mapNotNull null
                        else -> return@mapNotNull null
                    }
                    Video(videoUrl = video.videoUrl, videoTitle = "Blogger - $language - $quality", headers = mediaHeaders)
                }
            }.orEmpty().also { videoDebug("videos=${it.size} format=MP4") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            videoDebug("player failed type=${e.javaClass.simpleName}")
            emptyList()
        }
        return videos.distinctBy { it.videoUrl }.sortVideos()
    }
    override fun List<Video>.sortVideos(): List<Video> {
        val preferred = preferences.getString(prefQualityKey, prefQualityDefault).orEmpty()
        return sortedWith(
            compareByDescending<Video> { preferred != "best" && resolution(it.videoTitle) == resolution(preferred) }
                .thenByDescending { resolution(it.videoTitle) }
                .thenBy { !it.videoTitle.contains("Dublado") },
        ).also { videoDebug("qualities=${it.joinToString { video -> video.videoTitle }}") }
    }
    private fun resolution(label: String) = Regex("(\\d+)p").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    @Serializable private class Player(@SerialName("embed_url") val url: String)

    @Serializable private class PlayerOption(val post: String, val type: String, val nume: String, val label: String, val referer: String)

    companion object {
        private fun debug(message: String) {
            if (BuildConfig.DEBUG) Log.d("ANIMESHD_DEBUG", message)
        }
        private fun videoDebug(message: String) {
            if (BuildConfig.DEBUG) Log.d("ANIMESHD_VIDEO", message)
        }
    }
}
