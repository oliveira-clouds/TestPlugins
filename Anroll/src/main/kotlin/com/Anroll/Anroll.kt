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
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.Movie)
    
         override val mainPage = mainPageOf(
        "releases" to "Lançamentos",
        "animes" to "Animes",
        "movies" to "Filmes"
    )
                  
           override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val scriptTag = document.selectFirst("script#__NEXT_DATA__")
            ?: return newHomePageResponse(request.name, emptyList())

        val scriptContent = scriptTag.data()
        val jsonObject = JSONObject(scriptContent)
        val lists = jsonObject.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optJSONObject("lists")
            ?: return newHomePageResponse(request.name, emptyList())
            
        val homepageLists = mutableListOf<HomePageList>()
        
        // Criando a lista de Lançamentos
        val releasesArray = lists.optJSONArray("releases")
        if (releasesArray != null) {
            val items = mutableListOf<SearchResponse>()
            (0 until releasesArray.length()).forEach { i ->
                val entry = releasesArray.optJSONObject(i)
                val title = entry?.optString("titulo") ?: ""
                val posterUrl = entry?.optString("poster") ?: ""
                val url = "$mainUrl/a/${entry?.optString("generate_id")}"
                items.add(
                    newAnimeSearchResponse(title, url, TvType.Anime) {
                        this.posterUrl = fixUrl(posterUrl)
                    }
                )
            }
            if (items.isNotEmpty()) {
                homepageLists.add(
                    HomePageList(
                        name = "Lançamentos",
                        list = items,
                        isHorizontalImages = false
                    )
                )
            }
        }
        
        // Criando a lista de Animes em Alta
        val animesArray = lists.optJSONArray("animes")
        if (animesArray != null) {
            val items = mutableListOf<SearchResponse>()
            (0 until animesArray.length()).forEach { i ->
                val entry = animesArray.optJSONObject(i)
                val title = entry?.optString("titulo") ?: ""
                val posterUrl = entry?.optString("poster") ?: ""
                val url = "$mainUrl/a/${entry?.optString("generate_id")}"
                items.add(
                    newAnimeSearchResponse(title, url, TvType.Anime) {
                        this.posterUrl = fixUrl(posterUrl)
                    }
                )
            }
            if (items.isNotEmpty()) {
                homepageLists.add(
                    HomePageList(
                        name = "Animes em Alta",
                        list = items,
                        isHorizontalImages = false
                    )
                )
            }
        }
        
        // Criando a lista de Filmes
        val moviesArray = lists.optJSONArray("movies")
        if (moviesArray != null) {
            val items = mutableListOf<SearchResponse>()
            (0 until moviesArray.length()).forEach { i ->
                val entry = moviesArray.optJSONObject(i)
                val title = entry?.optString("nome_filme") ?: ""
                val posterUrl = entry?.optString("capa_filme") ?: ""
                val url = "$mainUrl/a/${entry?.optString("generate_id")}"
                items.add(
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = fixUrl(posterUrl)
                    }
                )
            }
            if (items.isNotEmpty()) {
                homepageLists.add(
                    HomePageList(
                        name = "Filmes",
                        list = items,
                        isHorizontalImages = false
                    )
                )
            }
        }
        
        return HomePageResponse(homepageLists)
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
        val idSerie = animeData?.optInt("id_serie", 0)

        val episodes = mutableListOf<Episode>()

        if (idSerie != null && idSerie != 0) {
            val episodesUrl = "https://apiv3-prd.anroll.net/animes/$idSerie/episodes"
            
            try {
                val episodesResponse = app.get(episodesUrl)
                val episodesJsonArray = JSONObject(episodesResponse.text).optJSONArray("data")
                
                if (episodesJsonArray != null) {
                    (0 until episodesJsonArray.length()).mapNotNull { i ->
                        val ep = episodesJsonArray.optJSONObject(i)
                        val epNumber = ep?.optString("n_episodio")?.toIntOrNull()
                        val epGenId = ep?.optString("generate_id")
                        
                        if (epGenId != null && epNumber != null) {
                            episodes.add(
                                newEpisode("$mainUrl/e/$epGenId") {
                                    name = "Episódio $epNumber"
                                    episode = epNumber
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Se a API retornar um erro, o código simplesmente não adiciona os episódios.
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
            
            // Tentativa 1: Encontrar dados no padrão "episodio"
            val episodioData = pageProps?.optJSONObject("episodio")
            if (episodioData != null) {
                animeSlug = episodioData.optJSONObject("anime")?.optString("slug_serie")
                episodeNumber = episodioData.optString("n_episodio")
            }
            
            // Tentativa 2: Se falhar, tentar o padrão "data"
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
