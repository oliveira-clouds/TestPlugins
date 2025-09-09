package com.Anroll

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.regex.Pattern


class Anroll : MainAPI() {
    override var mainUrl = "https://www.anroll.net"
    override var name = "Anroll"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

    // A função getMainPage agora cuida de ambas as listas
    override suspend fun getMainPage(page: Int, name: String): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()
        
        val scriptTag = document.selectFirst("script#__NEXT_DATA__")
        
        if (scriptTag != null) {
            val scriptContent = Parser.unescapeEntities(scriptTag.html(), false)
            try {
                val jsonObject = JSONObject(scriptContent)
                val pageProps = jsonObject.optJSONObject("props")?.optJSONObject("pageProps")
                
                // Extrai a lista de Últimos Lançamentos
                val releases = pageProps?.optJSONObject("releases")?.optJSONArray("recent_episodes")
                val latestEpisodes = releases?.let {
                    (0 until it.length()).mapNotNull { i ->
                        val item = it.getJSONObject(i)
                        val title = item.optString("titulo_episodio")
                        val url = item.optString("link")
                        val poster = item.optString("poster")
                        if (title.isNotEmpty() && url.isNotEmpty() && poster.isNotEmpty()) {
                            newAnimeSearchResponse(title, url, poster)
                        } else {
                            null
                        }
                    }
                } ?: emptyList()
                homePageList.add(HomePageList("Últimos Lançamentos", latestEpisodes))
                
                // Extrai a lista de Últimos Animes Adicionados
                val animes = pageProps?.optJSONObject("releases")?.optJSONArray("animes")
                val latestAnimes = animes?.let {
                    (0 until it.length()).mapNotNull { i ->
                        val item = it.getJSONObject(i)
                        val title = item.optString("titulo")
                        val url = item.optString("link")
                        val poster = item.optString("poster")
                        if (title.isNotEmpty() && url.isNotEmpty() && poster.isNotEmpty()) {
                            newAnimeSearchResponse(title, url, poster)
                        } else {
                            null
                        }
                    }
                } ?: emptyList()
                homePageList.add(HomePageList("Últimos Animes Adicionados", latestAnimes))

            } catch (e: Exception) {
                // Em caso de erro, retorna uma lista vazia
            }
        }
        return newHomePageResponse(homePageList)
    }

    // A função de busca agora usa a URL de pesquisa correta
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/buscar?s=$query"
        val document = app.get(searchUrl).document
        
        val searchResults = document.select("div.list-anime-items div.item").mapNotNull { element ->
            val title = element.selectFirst("div.title > a")?.text()
            val url = element.selectFirst("a")?.attr("href")
            val poster = element.selectFirst("div.img > img")?.attr("src")
            
            if (title != null && url != null && poster != null) {
                newAnimeSearchResponse(title, url, poster)
            } else {
                null
            }
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleElement = document.selectFirst("div#epinfo h1 a span") ?: return null
        val title = titleElement.text().trim()

        val poster = document.selectFirst("img[alt]")?.attr("src")?.let { fixUrlNull(it) }
        val plot = document.selectFirst("div.sinopse")?.text()

        val isEpisodePage = document.selectFirst("div#epinfo h2#current_ep") != null

        if (isEpisodePage) {
            val episodeText = document.selectFirst("h2#current_ep b")?.text()
            val episode = episodeText?.toIntOrNull() ?: 1

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                addEpisodes(DubStatus.Subbed, listOf(
                    newEpisode(url) {
                        this.name = "Episódio $episode"
                        this.episode = episode
                    }
                ))
            }
        } else {
            val episodes = document.select("div.epcontrol a").mapIndexed { index, epElement ->
                val epUrl = epElement.attr("href").let { fixUrl(it) }
                val epName = epElement.text().trim()
                val epNum = index + 1

                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                }
            }

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeDocument = app.get(data).document
        
        val scriptTag = episodeDocument.selectFirst("script#__NEXT_DATA__")
        
        var animeSlug: String? = null
        var episodeNumber: String? = null

        if (scriptTag != null) {
            val scriptContent = Parser.unescapeEntities(scriptTag.html(), false)
            try {
                val jsonObject = JSONObject(scriptContent)
                val pageProps = jsonObject.optJSONObject("props")?.optJSONObject("pageProps")
                
                val episodioData = pageProps?.optJSONObject("episodio")
                if (episodioData != null) {
                    animeSlug = episodioData.optJSONObject("anime")?.optString("slug_serie")
                    episodeNumber = episodioData.optString("n_episodio")
                }
                
                if (animeSlug == null || episodeNumber == null) {
                    val episodeData = pageProps?.optJSONObject("data")
                    if (episodeData != null) {
                        animeSlug = episodeData.optJSONObject("anime")?.optString("slug_serie")
                        episodeNumber = episodeData.optString("n_episodio")
                    }
                }
                
            } catch (e: Exception) {
                return false
            }
        } else {
            return false
        }
        
        if (animeSlug != null && episodeNumber != null) {
            val constructedUrl = "https://cdn-zenitsu-2-gamabunta.b-cdn.net/cf/hls/animes/$animeSlug/$episodeNumber.mp4/media-1/stream.m3u8"
            
            callback.invoke(
                newExtractorLink(
                    "Anroll",
                    "Anroll",
                    constructedUrl,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                }
            )
            return true
        }
        return false
    }
}
