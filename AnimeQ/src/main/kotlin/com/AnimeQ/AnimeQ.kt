package com.animeq

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.json.JSONObject

class AnimeQProvider : MainAPI() {
    override var mainUrl = "https://animeq.blog"
    override var name = "AnimeQ"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    
    override val mainPage = mainPageOf(
        "episodios" to "Episódios Recentes",
        "animes" to "Animes Recentes",
        "filmes" to "Filmes"
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        val document = app.get(mainUrl).document

        when (request.data) {
            "episodios" -> {
                val episodes = document.select("article.episodes")
                episodes.forEach { episode ->
                    parseEpisodeElement(episode)?.let { items.add(it) }
                }
            }
            "animes" -> {
                val animes = document.select("article.tvshows")
                animes.forEach { anime ->
                    parseAnimeElement(anime)?.let { items.add(it) }
                }
            }
            "filmes" -> {
                // Para filmes, podemos buscar na página de filmes ou filtrar da página inicial
                val filmesDoc = app.get("$mainUrl/filme").document
                val filmes = filmesDoc.select("article.tvshows, article.movies")
                filmes.forEach { filme ->
                    parseMovieElement(filme)?.let { items.add(it) }
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

    private fun parseEpisodeElement(element: Element): SearchResponse? {
        return try {
            val title = element.selectFirst("h3 a")?.text() ?: return null
            val url = element.selectFirst("h3 a")?.attr("href") ?: return null
            val image = element.selectFirst("img")?.attr("src")
            val series = element.selectFirst("span.serie")?.text()
            val quality = element.selectFirst("span.quality")?.text()
            
            val isDub = title.contains("dublado", ignoreCase = true) || 
                        series?.contains("dublado", ignoreCase = true) == true

            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = fixUrlNull(image)
                dubStatus = if (isDub) EnumSet.of(DubStatus.Dubbed) else EnumSet.of(DubStatus.Subbed)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAnimeElement(element: Element): SearchResponse? {
        return try {
            val title = element.selectFirst("h3 a")?.text() ?: return null
            val url = element.selectFirst("h3 a")?.attr("href") ?: return null
            val image = element.selectFirst("img")?.attr("src")
            val rating = element.selectFirst("div.rating")?.text()?.toFloatOrNull()
            
            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = fixUrlNull(image)
                this.rating = rating
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseMovieElement(element: Element): SearchResponse? {
        return try {
            val title = element.selectFirst("h3 a")?.text() ?: return null
            val url = element.selectFirst("h3 a")?.attr("href") ?: return null
            val image = element.selectFirst("img")?.attr("src")
            
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = fixUrlNull(image)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        
        return document.select("article.item").mapNotNull { element ->
            val title = element.selectFirst("h3 a")?.text() ?: return@mapNotNull null
            val url = element.selectFirst("h3 a")?.attr("href") ?: return@mapNotNull null
            val image = element.selectFirst("img")?.attr("src")
            val typeElement = element.selectFirst(".mepo, .type")
            
            val isMovie = typeElement?.text()?.contains("filme", ignoreCase = true) == true ||
                         url.contains("/filme/", ignoreCase = true)
            
            if (isMovie) {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = fixUrlNull(image)
                }
            } else {
                newAnimeSearchResponse(title, url, TvType.Anime) {
                    this.posterUrl = fixUrlNull(image)
                }
            }
        }
    }
     
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val isEpisodePage = url.contains("/episodio/")
        val isAnimePage = url.contains("/anime/")
        val isMoviePage = url.contains("/filme/")

        if (isEpisodePage) {
            val title = document.selectFirst("h1.entry-title")?.text() ?: return null
            val poster = document.selectFirst("div.poster img")?.attr("src")?.let { fixUrlNull(it) }
            val plot = document.selectFirst("div.entry-content")?.text()
            
            // Extrair número do episódio do título
            val episodeMatch = Regex("Episódio\\s+(\\d+)").find(title)
            val episodeNumber = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            
            // Encontrar link para o anime principal
            val animeLink = document.selectFirst("a[href*=/anime/]")?.attr("href")
            
            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                addEpisodes(DubStatus.Subbed, listOf(
                    newEpisode(url) {
                        this.name = title
                        this.episode = episodeNumber
                    }
                ))

                // Recomendação para ver todos os episódios
                if (animeLink != null) {
                    this.recommendations = listOf(
                        newAnimeSearchResponse("Ver todos os episódios", fixUrl(animeLink), TvType.Anime) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        } else if (isAnimePage) {
            val title = document.selectFirst("h1.entry-title")?.text() ?: return null
            val poster = document.selectFirst("div.poster img")?.attr("src")?.let { fixUrlNull(it) }
            val plot = document.selectFirst("div.entry-content")?.text()
            
            // Extrair episódios da página do anime
            val episodes = mutableListOf<Episode>()
            val episodeElements = document.select("a[href*=/episodio/]")
            
            episodeElements.forEach { episodeElement ->
                val epUrl = episodeElement.attr("href")
                val epTitle = episodeElement.text()
                val epMatch = Regex("Episódio\\s+(\\d+)").find(epTitle)
                val epNumber = epMatch?.groupValues?.get(1)?.toIntOrNull()
                
                if (epNumber != null) {
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.episode = epNumber
                        }
                    )
                }
            }
            
            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = plot
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else if (isMoviePage) {
            val title = document.selectFirst("h1.entry-title")?.text() ?: return null
            val poster = document.selectFirst("div.poster img")?.attr("src")?.let { fixUrlNull(it) }
            val plot = document.selectFirst("div.entry-content")?.text()
            
            return newMovieLoadResponse(title, url, TvType.Movie) {
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
        val document = app.get(data).document
        
        // Procurar por iframes de vídeo
        val iframes = document.select("iframe[src]")
        iframes.forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                // Adicionar como link externo
                callback.invoke(
                    ExtractorLink(
                        name = "AnimeQ",
                        source = src,
                        url = src,
                        quality = Qualities.Unknown.value,
                        isM3u8 = src.contains(".m3u8"),
                        referer = mainUrl
                    )
                )
            }
        }
        
        // Procurar por players nativos
        val videoElements = document.select("video source[src]")
        videoElements.forEach { video ->
            val src = video.attr("src")
            if (src.isNotBlank()) {
                callback.invoke(
                    ExtractorLink(
                        name = "AnimeQ",
                        source = src,
                        url = src,
                        quality = Qualities.Unknown.value,
                        isM3u8 = src.contains(".m3u8"),
                        referer = mainUrl
                    )
                )
            }
        }
        
        // Procurar por scripts que contenham links de vídeo
        val scripts = document.select("script")
        scripts.forEach { script ->
            val scriptContent = script.html()
            // Procurar por URLs de vídeo comuns
            val videoUrls = Regex("(https?:\\/\\/[^\"'\\s]+\\.(mp4|m3u8|mkv|avi))").findAll(scriptContent)
            videoUrls.forEach { match ->
                val url = match.value
                callback.invoke(
                    ExtractorLink(
                        name = "AnimeQ",
                        source = url,
                        url = url,
                        quality = Qualities.Unknown.value,
                        isM3u8 = url.contains(".m3u8"),
                        referer = mainUrl
                    )
                )
            }
        }
        
        return iframes.isNotEmpty() || videoElements.isNotEmpty()
    }
}
