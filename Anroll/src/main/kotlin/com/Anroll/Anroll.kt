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
        "lancamentos" to "Últimos Lançamentos",
        "data_animes" to "Animes em Alta",
        "filmes" to "Filmes"
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        var hasNext = false

        when (request.data) {
          "lancamentos" -> {
                val document = app.get("$mainUrl/lancamentos").document
                val scriptTag = document.selectFirst("script#__NEXT_DATA__")
                    ?: return newHomePageResponse(request.name, emptyList())
                val scriptContent = scriptTag.data()
                val jsonObject = JSONObject(scriptContent)
                val data = jsonObject.optJSONObject("props")
                    ?.optJSONObject("pageProps")
                    ?.optJSONObject("data")
                    ?: return newHomePageResponse(request.name, emptyList())
                val lancamentosArray = data.optJSONArray("data_releases")
                if (lancamentosArray != null) {
                    (0 until lancamentosArray.length()).forEach { i ->
                        val entry = lancamentosArray.optJSONObject(i)
                        parseLancamentoJson(entry)?.let { items.add(it) }
                    }
                }
            }
            "data_animes" -> {
                val document = app.get(mainUrl).document
                val scriptTag = document.selectFirst("script#__NEXT_DATA__")
                    ?: return newHomePageResponse(request.name, emptyList())
                val scriptContent = scriptTag.data()
                val jsonObject = JSONObject(scriptContent)
                val data = jsonObject.optJSONObject("props")
                    ?.optJSONObject("pageProps")
                    ?.optJSONObject("data")
                    ?: return newHomePageResponse(request.name, emptyList())
                val listArray = data.optJSONArray(request.data)
                if (listArray != null) {
                    (0 until listArray.length()).forEach { i ->
                        val entry = listArray.optJSONObject(i)
                        val title: String
                        val url: String
                        val type: TvType
                        val generateId: String
                        val posterUrl: String

                        title = entry?.optString("titulo") ?: ""
                        generateId = entry?.optString("generate_id") ?: ""
                        url = "$mainUrl/a/$generateId"
                        type = TvType.Anime
                        posterUrl = document.select("a[href*=$generateId]").select("img").attr("src")

                        if (title.isNotEmpty() && generateId.isNotEmpty()) {
                            items.add(
                                newAnimeSearchResponse(title, url, type) {
                                    if (posterUrl.isNotEmpty()) this.posterUrl = fixUrl(posterUrl)
                                }
                            )
                        }
                    }
                }
            }
            "filmes" -> {
                val document = app.get("$mainUrl/filmes").document
                val scriptTag = document.selectFirst("script#__NEXT_DATA__")
                    ?: return newHomePageResponse(request.name, emptyList())
                val scriptContent = scriptTag.data()
                val jsonObject = JSONObject(scriptContent)
                val pageProps = jsonObject.optJSONObject("props")?.optJSONObject("pageProps")
                val data = pageProps?.optJSONObject("data")
                val moviesArray = data?.optJSONArray("data_movies") ?: return newHomePageResponse(request.name, emptyList())

                (0 until moviesArray.length()).forEach { i ->
                    val entry = moviesArray.optJSONObject(i)
                    val title = entry?.optString("nome_filme") ?: ""
                    val generateId = entry?.optString("generate_id") ?: ""
                    val slugFilme = entry?.optString("slug_filme") ?: ""

                    val url = "$mainUrl/f/$generateId"
                    val posterUrl = "https://static.anroll.net/images/filmes/capas/$slugFilme.jpg"

                    if (title.isNotEmpty() && url.isNotEmpty()) {
                        items.add(
                            newMovieSearchResponse(title, url, TvType.Movie) {
                                this.posterUrl = fixUrl(posterUrl)
                            }
                        )
                    }
                }
            }
        }
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = request.data == "lancamentos"
            ),
            hasNext = false
        )
    }

     private fun parseLancamentoJson(entry: JSONObject?): SearchResponse? {
    val episodeData = entry?.optJSONObject("episode") ?: return null
    val animeData = episodeData.optJSONObject("anime") ?: return null

    val title = animeData.optString("titulo")
    val generateId = episodeData.optString("generate_id")
    val isDub = animeData.optInt("dub") == 1
    val episodeNumber = episodeData.optString("n_episodio")?.toIntOrNull() ?: 1
    
    val slug = animeData.optString("slug_serie")

    // Verifica se os dados essenciais existem
    if (title.isEmpty() || generateId.isEmpty() || slug.isEmpty()) return null

    val url = "$mainUrl/e/$generateId"
    
    val screenshotUrl = if (slug.isNotEmpty()) {
        "https://static.anroll.net/images/animes/screens/$slug/${String.format("%03d", episodeNumber)}.jpg"
    } else {
        null
    }

    val seriesCoverUrl = if (slug.isNotEmpty()) {
        "https://static.anroll.net/images/animes/capas/$slug.jpg"
    } else {
        null
    }
    val finalPoster = screenshotUrl ?: seriesCoverUrl
    return newAnimeSearchResponse(title, url, TvType.Anime) {
        // Implementando o fallback: Tenta o screenshot, se falhar, usa a capa da série.
        this.posterUrl = finalPoster?.let{fixUrl(it)} 
        this.addDubStatus(isDub, episodeNumber)
    }
}
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "https://api-search.anroll.net/data?q=$query"
        val response = app.get(searchUrl)
        val jsonArray = JSONObject(response.text).optJSONArray("data") ?: return emptyList()

        return (0 until jsonArray.length()).mapNotNull { i ->
            val item = jsonArray.optJSONObject(i)
            val title = item?.optString("title")
            val type = item?.optString("type")
            val slug = item?.optString("slug")
            val genId = item?.optString("gen_id")
            val genericPath = item?.optString("generic_path")
            
            val url = if (genericPath != null && genericPath.isNotEmpty()) {
                "$mainUrl$genericPath"
            } else if (genId != null) {
                "$mainUrl/a/$genId"
            } else {
                return@mapNotNull null
            }
            val posterUrl = if (type == "movie" && slug != null) {
                "https://static.anroll.net/images/filmes/capas/$slug.jpg"
            } else if (type == "anime" && slug != null) {
                "https://static.anroll.net/images/animes/capas/$slug.jpg"
            } else {
                null
            }
            
            if (title != null && url.isNotEmpty()) {
                if (type == "movie") {
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                } else {
                    newAnimeSearchResponse(title, url, TvType.Anime) {
                        this.posterUrl = posterUrl
                    }
                }
            } else {
                null
            }
        }
    }
     
 override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val isEpisodePage = url.contains("/e/")
        val isSeriesPage = url.contains("/a/")
        val isMoviePage = url.contains("/f/")

     if (isEpisodePage) {
    val scriptTag = document.selectFirst("script#__NEXT_DATA__")
        ?: return null // Retorna null se a tag principal não for encontrada

    val scriptContent = Parser.unescapeEntities(scriptTag.html(), false)
    val jsonObject = JSONObject(scriptContent)
    val pageProps = jsonObject.optJSONObject("props")?.optJSONObject("pageProps")
    
    // Tenta encontrar os dados no objeto 'data'
    val episodeData = pageProps?.optJSONObject("data") ?: return null
    
    val animeData = episodeData.optJSONObject("anime") ?: return null

    // 1. Extração de dados da Série (Anime)
    val title = animeData.optString("titulo")?.trim() ?: return null
    val slugSerie = animeData.optString("slug_serie") ?: ""
    val animeSeriesGenId = animeData.optString("generate_id") ?: "" // ID da série
    
    // 2. Extração de dados do Episódio
    val episodeNumberText = episodeData.optString("n_episodio")
    val episode = episodeNumberText.toIntOrNull() ?: 1
    val episodeTitle = episodeData.optString("titulo_episodio")?.trim()
    val episodePlot = episodeData.optString("sinopse_episodio")

    // Constrói o link para a página da série
    val animeUrl = if (animeSeriesGenId.isNotEmpty()) {
        "$mainUrl/a/$animeSeriesGenId"
    } else {
        null
    }
    
    val episodePoster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
    val posterUrl = if (slugSerie.isNotEmpty()) {
           "https://static.anroll.net/images/animes/screens/$slugSerie/${"%03d".format(episode)}.jpg"
        } else {
            null
        }
        val seriesCoverUrl = if (slugSerie.isNotEmpty()) {
        "https://static.anroll.net/images/animes/capas/$slugSerie.jpg"
         } else {
        null
        }

    // Lógica para o nome do episódio
    val episodeName = if (!episodeTitle.isNullOrEmpty() && episodeTitle != "N/A") {
        episodeTitle 
    } else {
        "Episódio $episodeNumberText"
    }
    val finalPosterUrl = episodePoster ?: posterUrl ?: seriesCoverUrl
    return newAnimeLoadResponse(title, url, TvType.Anime) {
        this.posterUrl = episodePoster ?: posterUrl ?: seriesCoverUrl
        this.plot = episodePlot
        addEpisodes(DubStatus.Subbed, listOf(
            newEpisode(url) {
                this.name = episodeName
                this.episode = episode
                this.description = episodePlot
                this.posterUrl= fixUrl(finalPosterUrl)
            }
        ))

        // Adiciona a recomendação "Ver todos os episódios"
        if (animeUrl != null) {
            this.recommendations = listOf(
                newAnimeSearchResponse("Ver todos os episódios", fixUrl(animeUrl), TvType.Anime) {
                    this.posterUrl = fixUrl(episodePoster)
                }
            )
        }
    }
}  else if (isSeriesPage) {
            val scriptTag = document.selectFirst("script#__NEXT_DATA__")
                ?: return null

            val scriptContent = Parser.unescapeEntities(scriptTag.html(), false)
            val jsonObject = JSONObject(scriptContent)
            val pageProps = jsonObject.optJSONObject("props")?.optJSONObject("pageProps")
            val animeData = pageProps?.optJSONObject("data") ?: pageProps?.optJSONObject("anime")
            
            val title = animeData?.optString("titulo") ?: return null
            val plot = animeData.optString("sinopse")
            val idSerie = animeData?.optInt("id_serie", 0)
            val slugSerie = animeData?.optString("slug_serie") ?: ""
            val genres = animeData.optString("generos")?.split(",")?.map { it.trim() }
            val year = animeData.optInt("ano", 0)
            val censura = animeData.optString("censura")
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
                            val epPlot = ep?.optString("sinopse_episodio")
                            val epTitle = ep?.optString("titulo_episodio")
                            
                            if (epGenId != null && epNumber != null) {
                                val episodePoster = if (slugSerie.isNotEmpty()) {
                                    "https://static.anroll.net/images/animes/screens/$slugSerie/${String.format("%03d", epNumber)}.jpg"
                                } else {
                                    null
                                }
                                
                                episodes.add(
                                    newEpisode("$mainUrl/e/$epGenId") {
                                        name = if (!epTitle.isNullOrEmpty() && epTitle != "N/A") "$epTitle " else "Episódio $epNumber"
                                        episode = epNumber
                                        description = epPlot
                                        posterUrl = episodePoster
                                    }
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Se a API retornar um erro, o código simplesmente não adiciona os episódios.
                }
            }
            
            val htmlPoster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
            
            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = htmlPoster
                this.plot = plot
                this.year = if (year != 0) year else null 
                this.tags = genres
                addEpisodes(DubStatus.Subbed, episodes.reversed())
            }
        }else if (isMoviePage) {
            val scriptTag = document.selectFirst("script#__NEXT_DATA__")
                ?: return null

            val scriptContent = Parser.unescapeEntities(scriptTag.html(), false)
            val jsonObject = JSONObject(scriptContent)
            val pageProps = jsonObject.optJSONObject("props")?.optJSONObject("pageProps")
            val movieData = pageProps?.optJSONObject("data")?.optJSONObject("data_movie")

            val title = movieData?.optString("nome_filme") ?: return null
            val plot = movieData?.optString("sinopse_filme")
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
            
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        
        return null
    }

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
        if (data.contains("/f/")) {
            val document = app.get(data).document
            val scriptTag = document.selectFirst("script#__NEXT_DATA__")
            
            if (scriptTag != null) {
                val scriptContent = Parser.unescapeEntities(scriptTag.html(), false)
                val jsonObject = JSONObject(scriptContent)
                val pageProps = jsonObject.optJSONObject("props")?.optJSONObject("pageProps")
                val movieData = pageProps?.optJSONObject("data")?.optJSONObject("data_movie")
                val movieSlug = movieData?.optString("slug_filme")
                
                if (movieSlug != null) {
                    val constructedUrl = "https://cdn-zenitsu-2-gamabunta.b-cdn.net/cf/hls/movies/$movieSlug/movie.mp4/media-1/stream.m3u8"
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
            }
            return false
        }
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
