package eu.kanade.tachiyomi.animeextension.pt.betteranimeio

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.annotation.Source
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class BetterAnimeIo : DooPlay() {
    private val contentUrl = "$baseUrl/animes"

    private val extractor by lazy { BetterAnimeIoExtractor(client) }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div#featured-titles article.item div.poster"

    override fun popularAnimeRequest(page: Int): Request = GET(contentUrl, headers)

    // =============================== Latest ===============================
    override fun latestUpdatesSelector() = "div#archive-content article.item div.poster"

    override fun latestUpdatesRequest(page: Int): Request = GET("$contentUrl/page/$page", headers)

    // ============================== Episodes ==============================
    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        val seasonName = season.selectFirst("span.se-t")?.text() ?: "1"
        return season.select(episodeListSelector()).mapNotNull { element ->
            runCatching {
                episodeFromElement(element, seasonName)
            }.onFailure { it.printStackTrace() }.getOrNull()
        }
    }

    override fun episodeFromElement(element: Element, seasonName: String): SEpisode = SEpisode.create().apply {
        val link = element.selectFirst(".episodiotitle a")!!
        val episodeText = link.text()
        val epNum = episodeText.substringBefore(" -").trim()

        episode_number = epNum.toFloatOrNull() ?: 0F
        name = "$episodeSeasonPrefix $seasonName x $epNum"
        setUrlWithoutDomain(link.attr("href"))

        element.selectFirst(".timeAgo[data-time]")?.attr("data-time")?.let { dateStr ->
            date_upload = parseIsoDate(dateStr)
        }
    }

    private fun parseIsoDate(dateStr: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
            .parse(dateStr)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    // ============================== Filters ===============================
    override fun genresListRequest(): Request = GET("$baseUrl/episodios/", headers)

    override fun genresListSelector() = "nav.genres ul.genres li a"

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val document = client.newCall(GET(baseUrl + episode.url, headers))
            .awaitSuccess()
            .useAsJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.mapIndexedNotNull { index, player ->
            val payload = PlayerOptionPayload(
                type = player.attr("data-type"),
                post = player.attr("data-post"),
                nume = player.attr("data-nume"),
            )
            if (payload.post.isBlank() || payload.nume.isBlank()) return@mapIndexedNotNull null
            val hosterName = player.selectFirst("span.title")?.text()?.trim()
                ?.takeIf(String::isNotBlank) ?: "Player ${index + 1}"
            Hoster(
                hosterUrl = baseUrl + episode.url,
                hosterName = hosterName,
                internalData = Json.encodeToString(payload),
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val payload = hoster.internalData.parseAs<PlayerOptionPayload>()
        val url = getPlayerUrl(payload)
        if (url.isEmpty()) return emptyList()

        return when {
            "jwplayer?source=" in url || "jwplayer/?source=" in url -> {
                val encodedSource = url.toHttpUrl().queryParameter("source") ?: return emptyList()
                extractor.extractVideosFromApi(encodedSource).sortVideos()
            }
            else -> emptyList()
        }
    }

    private suspend fun getPlayerUrl(payload: PlayerOptionPayload): String = try {
        client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/${payload.post}/${payload.type}/${payload.nume}", headers))
            .awaitSuccess()
            .bodyString()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
    } catch (_: Exception) {
        ""
    }

    @Serializable
    data class PlayerOptionPayload(
        val type: String,
        val post: String,
        val nume: String,
    )

    // ============================= Utilities ==============================
    override val prefQualityValues = arrayOf("360p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues
}
