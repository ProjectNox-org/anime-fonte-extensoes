package eu.kanade.tachiyomi.animeextension.pt.watchanimehentai

import okhttp3.Headers
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for WatchAnimeHentai (project-nox-requests#140) using captured live HTML fixtures.
 */
class WatchAnimeHentaiTest {

    private val baseUrl = "https://www.watchanimehentai.com"

    private val baseHeaders = Headers.headersOf(
        "Referer", "$baseUrl/",
        "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0",
    )

    // Captured from /pt/lista-de-hentai/
    private val catalogHtml = """
        <html><body>
            <div id="archive-content" class="animation-2 items">
                <article class="item tvshows">
                    <div class="poster">
                        <img src="https://www.watchanimehentai.com/cdn/uploads/a0deeae1896af109127a1bd15741b0d7.jpeg" alt="Fuufu Koukan Modorenai Yoru" title="Fuufu Koukan Modorenai Yoru">
                        <a href="/pt/info/fuufu-koukan-modorenai-yoru/"><div class="see"></div></a>
                    </div>
                    <div class="data"><h3><a href="/pt/info/fuufu-koukan-modorenai-yoru/">Fuufu Koukan Modorenai Yoru</a></h3></div>
                </article>
                <article class="item tvshows">
                    <div class="poster">
                        <img src="/cdn/uploads/963635705e88bfef4d5d4167336d2e0f.jpeg" alt="Android wa Keiken Ninzuu ni Hairimasu ka">
                        <a href="/pt/info/android-wa-keiken-ninzuu-ni-hairimasu-ka/"><div class="see"></div></a>
                    </div>
                    <div class="data"><h3><a href="/pt/info/android-wa-keiken-ninzuu-ni-hairimasu-ka/">Android wa Keiken Ninzuu ni Hairimasu ka</a></h3></div>
                </article>
            </div>
            <div id="paginacao"><div class="paginacao"><a href="#" class="atual">1</a><a href="/pt/lista-de-hentai/2/" rel="next">»</a></div></div>
        </body></html>
    """.trimIndent()

    // Captured from /pt/ultimos-episodios-adicionados/ (includes duplicate series episodes)
    private val latestHtml = """
        <html><body>
            <div class="animation-2 items">
                <article class="item se episodes">
                    <a href="/pt/episodios/hitozukiai-ga-nigate-na-miboujin-no-yukionna-san-to-noroi-no-yubiwa-episodio-7/">
                        <div class="poster">
                            <img src="https://www.watchanimehentai.com/cdn/uploads/thumb7.png" alt="Yukionna Ep 7">
                            <div class="season_m"><span class="b">Yukionna-San</span><span class="c">Episódio 7</span></div>
                            <span class="serie">Hitozukiai Ga Nigate Na Miboujin No Yukionna-San</span>
                        </div>
                    </a>
                </article>
                <article class="item se episodes">
                    <a href="/pt/episodios/hitozukiai-ga-nigate-na-miboujin-no-yukionna-san-to-noroi-no-yubiwa-episodio-6/">
                        <div class="poster">
                            <img src="https://www.watchanimehentai.com/cdn/uploads/thumb6.png" alt="Yukionna Ep 6">
                            <div class="season_m"><span class="b">Yukionna-San</span><span class="c">Episódio 6</span></div>
                            <span class="serie">Hitozukiai Ga Nigate Na Miboujin No Yukionna-San</span>
                        </div>
                    </a>
                </article>
                <article class="item se episodes">
                    <a href="/pt/episodios/so-low-episodio-4/">
                        <div class="poster">
                            <img src="https://www.watchanimehentai.com/cdn/uploads/14fc3c78737de2b48c012e1b87479a4a.jpeg" alt="So_low Episódio 4">
                            <div class="season_m"><span class="b">So_low</span><span class="c">Episódio 4</span></div>
                            <span class="serie">So_low</span>
                        </div>
                    </a>
                </article>
            </div>
            <div id="paginacao"><div class="paginacao"><a href="#" class="atual">1</a><a href="/pt/ultimos-episodios-adicionados/2/">2</a></div></div>
        </body></html>
    """.trimIndent()

    // Captured from /pt/busca/harem/
    private val searchHtml = """
        <html><body>
            <header><h1>Você buscou por harem</h1></header>
            <section class="search-results">
                <div class="search-results-list">
                    <article class="search-result">
                        <a href="/pt/info/nuki-nuki-zupposism/" class="search-result-poster">
                            <img src="https://www.watchanimehentai.com/cdn/images/posters/nuki-nuki-zupposism.jpg" alt="Nuki Nuki Zupposism">
                        </a>
                        <div class="search-result-info">
                            <h2 class="search-result-title"><a href="/pt/info/nuki-nuki-zupposism/">Nuki Nuki Zupposism</a></h2>
                        </div>
                    </article>
                </div>
            </section>
            <div id="paginacao"><div class="paginacao"><a href="#" class="atual">1</a><a href="/pt/busca/harem/?pagination=2" rel="next">»</a></div></div>
        </body></html>
    """.trimIndent()

    // Captured from /pt/info/harem-shima-e-youkoso/ (with sidebar recommendations outside div#episodes)
    private val detailsHtml = """
        <html><body>
            <div class="sheader">
                <div class="poster"><img src="https://www.watchanimehentai.com/cdn/images/posters/harem-shima-e-youkoso.jpg" alt="Harem Shima e Youkoso"></div>
                <div class="data">
                    <h1>Harem Shima e Youkoso</h1>
                    <div class="sgeneros"><a href="/pt/genero/aventura/">Aventura</a><a href="/pt/genero/harem/">Harém</a></div>
                    <div id="info1"><div class="wp-content"><p>Um grupo de estudantes é levado para uma ilha misteriosa.</p></div>
                    <div class="custom_fields"><b>Produtora:</b><div class="sgeneros"><a href="/pt/estudio/collaboration-works/">Collaboration Works</a></div></div>
                    <div class="custom_fields"><b class="variante">Episódios</b><span class="valor">2</span></div>
                    </div>
                </div>
            </div>
            <div id="episodes" class="sbox fixidtab">
                <div class="items">
                    <article class="item se episodes">
                        <a href="/pt/episodios/harem-shima-e-youkoso-episodio-1/">
                            <div class="poster"><div class="season_m"><span class="b">Harem Shima e Youkoso</span><span class="c">Episódio 1</span></div></div>
                            <div class="data"><h3>Episódio 1</h3></div>
                        </a>
                    </article>
                    <article class="item se episodes">
                        <a href="/pt/episodios/harem-shima-e-youkoso-episodio-2/">
                            <div class="poster"><div class="season_m"><span class="b">Harem Shima e Youkoso</span><span class="c">Episódio 2</span></div></div>
                            <div class="data"><h3>Episódio 2</h3></div>
                        </a>
                    </article>
                </div>
            </div>
            <aside>
                <article class="item se seasons">
                    <div class="data"><h3><a href="/pt/info/elf-no-futagohime-willan-to-arsura/">Elf no Futagohime</a></h3></div>
                </article>
            </aside>
        </body></html>
    """.trimIndent()

    // Captured from /pt/episodios/harem-shima-e-youkoso-episodio-2/
    private val episodeHtml = """
        <html><body>
            <div class="pag_episodes">
                <div class="item"><a href="/pt/info/harem-shima-e-youkoso/"><i class="icon-bars"></i> <span>Lista de Episódios</span></a></div>
            </div>
            <div class="playex">
                <iframe data-infoplayer="Principal BR" src="https://www.hentaitube.online/players_sites/mt/hd.php?idplay=FAKE1"></iframe>
                <iframe data-infoplayer="Alternativo BR" src="/players/p2/?padrao=FAKE_BR"></iframe>
                <iframe data-infoplayer="Principal" src="https://www.hentaitube.online/players_sites/mt/hd.php?idplay=FAKE2"></iframe>
                <iframe data-infoplayer="Alternativo" src="/players/p2/?padrao=FAKE_EN"></iframe>
                <iframe data-infoplayer="Alternativo ESP" src="/players/p2/?padrao=FAKE_ES"></iframe>
            </div>
            <aside><a href="/pt/info/kangoku-senkan/">Kangoku Senkan</a></aside>
        </body></html>
    """.trimIndent()

    // Captured from /players/p2/?padrao=...
    private val p2PlayerHtml = """
        <html><body>
            <video id="video_2" class="video-js">
                <source src="https://server4.baixarhentais.com/lote1/pre-roll/sacanas3.mp4" type="video/mp4">
                <source src="/players/p2/p2.php?id=FAKE_TOKEN" type="video/mp4">
            </video>
        </body></html>
    """.trimIndent()

    @Test
    fun buildsExpectedUrlsForCatalogLatestSearchAndFilters() {
        assertEquals("$baseUrl/pt/lista-de-hentai/", WatchAnimeHentaiParser.buildCatalogUrl(baseUrl, 1))
        assertEquals("$baseUrl/pt/lista-de-hentai/3/", WatchAnimeHentaiParser.buildCatalogUrl(baseUrl, 3))

        assertEquals("$baseUrl/pt/ultimos-episodios-adicionados/", WatchAnimeHentaiParser.buildLatestUrl(baseUrl, 1))
        assertEquals("$baseUrl/pt/ultimos-episodios-adicionados/2/", WatchAnimeHentaiParser.buildLatestUrl(baseUrl, 2))

        val searchP1 = WatchAnimeHentaiParser.buildSearchUrl(baseUrl, 1, "  hitori   tsuma  ")
        assertEquals("$baseUrl/pt/busca/hitori+tsuma/", searchP1)
        assertFalse(searchP1.contains("%20"))
        assertEquals(
            "$baseUrl/pt/busca/oni/?pagination=2",
            WatchAnimeHentaiParser.buildSearchUrl(baseUrl, 2, "oni"),
        )

        assertEquals("$baseUrl/pt/genero/acao/", WatchAnimeHentaiParser.buildGenreUrl(baseUrl, 1, "acao"))
        assertEquals("$baseUrl/pt/genero/acao/2/", WatchAnimeHentaiParser.buildGenreUrl(baseUrl, 2, "acao"))
        assertEquals("$baseUrl/pt/letra/a/", WatchAnimeHentaiParser.buildLetterUrl(baseUrl, 1, "a"))
        assertEquals("$baseUrl/pt/letra/a/2/", WatchAnimeHentaiParser.buildLetterUrl(baseUrl, 2, "a"))
    }

    @Test
    fun parsesCatalogLatestAndSearchPages() {
        val catalog = WatchAnimeHentaiParser.parseCatalogPage(Jsoup.parseBodyFragment(catalogHtml, "$baseUrl/pt/lista-de-hentai/"))
        assertEquals(2, catalog.animes.size)
        assertEquals("/pt/info/fuufu-koukan-modorenai-yoru/", catalog.animes[0].url)
        assertEquals("Fuufu Koukan Modorenai Yoru", catalog.animes[0].title)
        assertEquals("$baseUrl/cdn/uploads/963635705e88bfef4d5d4167336d2e0f.jpeg", catalog.animes[1].thumbnailUrl)
        assertTrue(catalog.hasNextPage)

        val latest = WatchAnimeHentaiParser.parseLatestPage(Jsoup.parseBodyFragment(latestHtml, "$baseUrl/pt/ultimos-episodios-adicionados/"))
        assertEquals(2, latest.animes.size)
        assertEquals("Hitozukiai Ga Nigate Na Miboujin No Yukionna-San", latest.animes[0].title)
        assertEquals("/pt/episodios/hitozukiai-ga-nigate-na-miboujin-no-yukionna-san-to-noroi-no-yubiwa-episodio-7/", latest.animes[0].url)
        assertEquals("So_low", latest.animes[1].title)
        assertFalse(latest.hasNextPage)

        val latestWithNext = latestHtml.replace("""href="/pt/ultimos-episodios-adicionados/2/"""", """href="/pt/ultimos-episodios-adicionados/2/" rel="next"""")
        assertTrue(
            WatchAnimeHentaiParser.parseLatestPage(
                Jsoup.parseBodyFragment(latestWithNext, "$baseUrl/pt/ultimos-episodios-adicionados/"),
            ).hasNextPage,
        )

        val search = WatchAnimeHentaiParser.parseSearchPage(Jsoup.parseBodyFragment(searchHtml, "$baseUrl/pt/busca/harem/"))
        assertEquals(1, search.animes.size)
        assertEquals("/pt/info/nuki-nuki-zupposism/", search.animes[0].url)
        assertEquals("Nuki Nuki Zupposism", search.animes[0].title)
        assertTrue(search.hasNextPage)

        val emptySearch = WatchAnimeHentaiParser.parseSearchPage(Jsoup.parseBodyFragment("<html><body></body></html>", baseUrl))
        assertTrue(emptySearch.animes.isEmpty())
        assertFalse(emptySearch.hasNextPage)
    }

    @Test
    fun resolvesCanonicalAnimePathFromEpisodeAndParsesDetailsWithoutSidebarRecommendations() {
        val epDoc = Jsoup.parseBodyFragment(episodeHtml, "$baseUrl/pt/episodios/harem-shima-e-youkoso-episodio-2/")
        assertEquals("/pt/info/harem-shima-e-youkoso/", WatchAnimeHentaiParser.extractAnimeInfoPathFromEpisode(epDoc))

        val detDoc = Jsoup.parseBodyFragment(detailsHtml, "$baseUrl/pt/info/harem-shima-e-youkoso/")
        val details = WatchAnimeHentaiParser.parseAnimeDetails(detDoc, "/pt/info/harem-shima-e-youkoso/")
        assertEquals("Harem Shima e Youkoso", details.title)
        assertEquals(listOf("Aventura", "Harém"), details.genres)
        assertEquals("Collaboration Works", details.author)
        assertEquals("Um grupo de estudantes é levado para uma ilha misteriosa.", details.description)

        val episodes = WatchAnimeHentaiParser.parseEpisodeList(detDoc)
        assertEquals(2, episodes.size)
        assertEquals("/pt/episodios/harem-shima-e-youkoso-episodio-2/", episodes[0].url)
        assertEquals("Episódio 2", episodes[0].name)
        assertEquals(2F, episodes[0].episodeNumber, 0.001F)
        assertEquals(1F, episodes[1].episodeNumber, 0.001F)
    }

    @Test
    fun parsesAndPrioritizesHostersAndResolvesP2SourcesAndLocationHeader() {
        val epDoc = Jsoup.parseBodyFragment(episodeHtml, "$baseUrl/pt/episodios/harem-shima-e-youkoso-episodio-2/")
        val hosters = WatchAnimeHentaiParser.parseHosters(epDoc)

        assertEquals(
            listOf("Alternativo BR", "Principal BR", "Alternativo", "Alternativo ESP", "Principal"),
            hosters.map { it.name },
        )
        assertTrue(hosters[0].isBr)
        assertEquals("$baseUrl/players/p2/?padrao=FAKE_BR", hosters[0].url)

        val sources = WatchAnimeHentaiParser.parsePlayerSources(Jsoup.parseBodyFragment(p2PlayerHtml, "$baseUrl/players/p2/?padrao=FAKE_BR"))
        assertEquals(1, sources.size)
        assertEquals("$baseUrl/players/p2/p2.php?id=FAKE_TOKEN", sources.single().url)
        assertTrue(WatchAnimeHentaiParser.isP2RedirectUrl(sources.single().url))
        assertFalse(WatchAnimeHentaiParser.isP2RedirectUrl("https://cdn1.watchanimehentai.com/ptbr/video.mp4"))

        assertEquals(
            "https://server4.baixarhentais.com/english/Enjo%20Kouhai%20-%2012%20-%20English.mp4?v=1",
            WatchAnimeHentaiParser.normalizeLocationUrl(
                " https://server4.baixarhentais.com/english/Enjo Kouhai - 12 - English.mp4?v=1 ",
                "$baseUrl/players/p2/p2.php?id=FAKE_TOKEN",
            ),
        )
        assertNull(WatchAnimeHentaiParser.normalizeLocationUrl(null, "$baseUrl/players/p2/p2.php"))

        val sameOrigin = WatchAnimeHentaiParser.embedHeadersFor("$baseUrl/players/p2/?padrao=FAKE_BR", baseUrl, baseHeaders)
        assertEquals("iframe", sameOrigin["Sec-Fetch-Dest"])
        assertEquals("navigate", sameOrigin["Sec-Fetch-Mode"])
        assertEquals("same-origin", sameOrigin["Sec-Fetch-Site"])

        val crossSite = WatchAnimeHentaiParser.embedHeadersFor("https://www.hentaitube.online/players_sites/mt/hd.php?idplay=FAKE1", baseUrl, baseHeaders)
        assertEquals("cross-site", crossSite["Sec-Fetch-Site"])
    }

    @Test
    fun filtersContainExpectedDefaultsAndNoExcludedGenres() {
        val filters = WatchAnimeHentaiFilters.getFilterList()
        assertEquals(2, filters.size)

        val genreFilter = filters[0] as GenreFilter
        assertEquals("", genreFilter.selectedSlug())
        assertTrue(WatchAnimeHentaiFilters.GENRES.any { it.second == "acao" })
        assertTrue(WatchAnimeHentaiFilters.GENRES.any { it.second == "sem-censura" })

        val letterFilter = filters[1] as LetterFilter
        assertEquals("", letterFilter.selectedSlug())
        assertEquals(27, WatchAnimeHentaiFilters.LETTERS.size)
    }
}
