package eu.kanade.tachiyomi.animeextension.pt.animesonlinecc

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
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
abstract class AnimesOnlineCC : DooPlay() {

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "article.w_item_b > a"

    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div.pagination > a.arrow_pag > i.icon-caret-right"

    // =============================== Search ===============================
    override fun searchAnimeSelector() = "div#animation-2 > article > div.poster > a"
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // =========================== Anime Details ============================
    override val additionalInfoSelector = "div.wp-content"

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")!!
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            sheader.selectFirst("div.poster > img")!!.let {
                thumbnail_url = it.getImageUrl()
                title = it.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")!!.text()
                }
            }

            genre = sheader.select("div.data div.sgeneros > a")
                .eachText()
                .joinToString()

            description = doc.getDescription()
        }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val document = client.newCall(GET(baseUrl + episode.url, headers))
            .awaitSuccess()
            .useAsJsoup()
        val players = document.select("#playex iframe")
        return players.mapNotNull { player ->
            val url = player.attr("src").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val id = player.parent()?.attr("id").orEmpty()
            val language =
                player.ownerDocument()!!
                    .selectFirst("a.options[href=\"#$id\"]")
                    ?.text()
                    ?.trim().takeIf {
                        it?.lowercase() == "legendado" || it?.lowercase() == "dublado"
                    } ?: ""
            Hoster(
                hosterUrl = url,
                hosterName = language.ifBlank { "Blogger" },
                internalData = language,
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = when {
        "blogger.com" in hoster.hosterUrl -> bloggerExtractor.videosFromUrl(hoster.hosterUrl, headers, hoster.internalData)
        else -> emptyList()
    }.sortVideos()

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val language = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return sortedByDescending { it.hosterName.contains(language, ignoreCase = true) }
    }

    override val prefQualityValues = arrayOf("360p", "720p")
    override val prefQualityEntries = prefQualityValues

    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    // ============================== Filters ===============================
    override fun genresListRequest() = GET("$baseUrl/generos/", headers)
    override fun genresListSelector() = "a.genre-link"

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoLanguagePref = ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = PREF_LANGUAGE_TITLE
            entries = PREF_LANGUAGE_ENTRIES
            entryValues = PREF_LANGUAGE_VALUES
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"
        }

        screen.addPreference(videoLanguagePref)
        super.setupPreferenceScreen(screen)
    }

    // ============================= Utilities ==============================
    override val animeMenuSelector = "div.pag_episodes div.item a[href] i.icon-bars"

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        val language = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.videoTitle.lowercase().contains(language.lowercase()) },
                { it.videoTitle.lowercase().contains(quality.lowercase()) },
                { REGEX_QUALITY.find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "Legendado"
        private const val PREF_LANGUAGE_TITLE = "Língua preferida"
        private val PREF_LANGUAGE_VALUES = arrayOf("Legendado", "Dublado")
        private val PREF_LANGUAGE_ENTRIES = PREF_LANGUAGE_VALUES
    }
}
