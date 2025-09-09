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

    override val mainPage = mainPageOf(
        "lancamentos" to "Últimos Lançamentos",
        "adicionados" to "Últimos Animes Adicionados"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = mutableListOf<SearchResponse>()
        
        when (request.data) {
            "lancamentos" -> {
                document.select("ul.UVrQY li.release-item").forEach { element ->
                    parseLancamentoCard(element)?.let { items.add(it) }
                }
            }
            "adicionados" -> {
                document.select("ul.ctmcR li.movielistitem").forEach { element ->
                    parseAdicionadoCard(element)?.let { items.add(it) }
                }
            }
        }
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = false
        )
    }

    private fun parseLancamentoCard(element: Element): SearchResponse? {
        val link = element.selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = element.selectFirst("h1")?.text()?.trim() ?: return null
        val posterUrl = element.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }

        val episodeText = element.selectFirst("span.episode-badge b")?.text()
        val episode = episodeText?.toIntOrNull() ?: 1

        val isDub = element.selectFirst("div#labels-column2 div.imwHAL") != null

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.addDubStatus(isDub, episode)
        }
    }

    private fun parseAdicionadoCard(element: Element): SearchResponse? {
        val link = element.selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = element.selectFirst("h1")?.text()?.trim() ?: return null
        val posterUrl = element.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?search=$query"
        val document = app.get(searchUrl).document

        return document.select("ul.UVrQY li.release-item, ul.ctmcR li.movielistitem").mapNotNull { element ->
            val link = element.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            val title = element.selectFirst("h1")?.text()?.trim() ?: return@mapNotNull null
            val posterUrl = element.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
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
    val episodePageContent = app.get(data).text
    
    // Expressão regular para encontrar o JSON dentro da tag script
    val jsonPattern = "<script id=\"__NEXT_DATA__\".*?>(.*?)</script>"
    val matcher = Pattern.compile(jsonPattern, Pattern.DOTALL).matcher(episodePageContent)
    
    var animeSlug: String? = null
    var episodeNumber: String? = null

    if (matcher.find()) {
        val jsonString = matcher.group(1)
        try {
            val jsonObject = JSONObject(jsonString)
            
            val props = jsonObject.optJSONObject("props")
            val pageProps = props?.optJSONObject("pageProps")
            val episodio = pageProps?.optJSONObject("episodio")
            val animeData = episodio?.optJSONObject("anime")
            
            animeSlug = animeData?.optString("slug_serie")
            episodeNumber = episodio?.optString("n_episodio")
            
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
