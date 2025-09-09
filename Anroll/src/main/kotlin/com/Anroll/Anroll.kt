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
        val searchUrl = "https://api-search.anroll.net/data?q=$query"
        val response = app.get(searchUrl)
        val jsonArray = JSONObject(response.text).optJSONArray("data") ?: return emptyList()

        return (0 until jsonArray.length()).mapNotNull { i ->
            val item = jsonArray.optJSONObject(i)
            val title = item?.optString("title")
            val poster = item?.optString("poster")
            val genId = item?.optString("gen_id")
            val genericPath = item?.optString("generic_path")
            
            val url = if (genericPath != null && genericPath.isNotEmpty()) {
                "$mainUrl$genericPath"
            } else if (genId != null) {
                "$mainUrl/a/$genId"
            } else {
                return@mapNotNull null
            }

            if (title != null && url != null) {
                newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                }
            } else {
                null
            }
        }
    }
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val scriptTag = document.selectFirst("script#__NEXT_DATA__")
            ?: return null

        val scriptContent = Parser.unescapeEntities(scriptTag.html(), false)
        val jsonObject = JSONObject(scriptContent)
        val pageProps = jsonObject.optJSONObject("props")?.optJSONObject("pageProps")
        val animeData = pageProps?.optJSONObject("data")
            ?: pageProps?.optJSONObject("anime")

        val title = animeData?.optString("titulo") ?: return null
        val poster = animeData.optString("poster")
        val plot = animeData.optString("sinopse")

        val episodes = mutableListOf<Episode>()

        val totalEpisodes = animeData.optInt("episodes")

        val animeSlug = animeData.optString("slug_serie")
        
        if (totalEpisodes > 0 && animeSlug.isNotEmpty()) {
            (1..totalEpisodes).forEach { i ->
                val epUrl = "$mainUrl/episodio/$animeSlug-$i"
                episodes.add(
                    newEpisode(epUrl) {
                        name = "Episódio $i"
                        episode = i
                    }
                )
            }
        }
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes.reversed())
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
