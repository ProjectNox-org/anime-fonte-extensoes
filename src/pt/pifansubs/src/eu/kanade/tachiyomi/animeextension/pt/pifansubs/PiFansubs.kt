package eu.kanade.tachiyomi.animeextension.pt.pifansubs

import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animeextension.pt.pifansubs.extractors.BlembedExtractor
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.annotation.Source
import keiyoushi.utils.useAsJsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class PiFansubs : DooPlay() {

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

    override val prefQualityValues = arrayOf("360p", "480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#featured-titles div.poster"

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val document = client.newCall(GET(baseUrl + episode.url, headers))
            .awaitSuccess()
            .useAsJsoup()
        val players = document.select("div.source-box:not(#source-player-trailer) iframe")
        return players.mapIndexedNotNull { index, player ->
            val url = getPlayerUrl(player).takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            val hosterName = when {
                "vidhide" in url -> "VidHide"
                "blembed" in url -> "Blembed"
                else -> "Player ${index + 1}"
            }
            Hoster(hosterUrl = url, hosterName = hosterName)
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = getPlayerVideos(hoster.hosterUrl).sortVideos()

    private fun getPlayerUrl(player: Element): String = player.attr("data-src").ifEmpty { player.attr("src") }.let {
        when {
            !it.startsWith("http") -> "https:$it"
            else -> it
        }
    }

    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val blembedExtractor by lazy { BlembedExtractor(client, headers) }

    private suspend fun getPlayerVideos(url: String): List<Video> = when {
        "https://vidhide" in url -> vidHideExtractor.videosFromUrl(url)
        "https://blembed" in url -> blembedExtractor.videosFromUrl(url)
        else -> emptyList()
    }

    // =========================== Anime Details ============================
    override fun Document.getDescription(): String = select("$additionalInfoSelector p")
        .eachText()
        .joinToString("\n\n") + "\n"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/episodios/page/$page", headers)
}
