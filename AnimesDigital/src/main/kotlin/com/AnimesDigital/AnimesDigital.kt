package com.AnimesDigital

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.DubStatus
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.util.Base64

class AnimesDigitalProvider : MainAPI() {
    override var mainUrl = "https://animesdigital.org"
    override var name = "Animes Digital"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val supportedTypes = setOf(TvType.Anime)
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Últimos Episódios",
        "$mainUrl/animes-legendados-online" to "Animes Legendados", 
        "$mainUrl/animes-dublado" to "Animes Dublados",
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/desenhos-online" to "Desenhos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when {
            request.data.contains("lancamentos") -> {
                // Página de lançamentos - scraping tradicional (usa toSearchResult, pois é lista de episódios)
                val document = app.get(request.data).document
                val home = document.select(".itemE, .itemA").mapNotNull {
                    it.toSearchResult()
                }
                newHomePageResponse(
                    list = HomePageList(
                        name = request.name,
                        list = home,
                        isHorizontalImages = false
                    ),
                    hasNext = false
                )
            }
            else -> {
                // Todas as outras categorias - usar API (precisa da correção na parseApiResponse)
                getAnimesFromAPI(page, request)
            }
        }
    }

    private suspend fun getAnimesFromAPI(page: Int, request: MainPageRequest): HomePageResponse {
        val (typeUrl, filterAudio) = when {
            request.data.contains("animes-dublado") -> "animes" to "dublado"
            request.data.contains("animes-legendados") -> "animes" to "legendado"
            request.data.contains("filmes") -> "filmes" to "0"
            request.data.contains("desenhos") -> "desenhos" to "0"
            else -> "animes" to "animes"
        }

        // Construir referrer exato como o navegador
        val referrerParams = mapOf(
            "filter_letter" to "0",
            "type_url" to typeUrl,
            "filter_audio" to filterAudio,
            "filter_order" to "name",
            "filter_genre_add" to "",
            "filter_genre_del" to "",
            "pagina" to page.toString(),
            "search" to "0",
            "limit" to "30"
        )
        
        val referrerQuery = referrerParams.map { (k, v) -> "$k=$v" }.joinToString("&")
        val referrerUrl = if (request.data.contains("?")) {
            "${request.data}&$referrerQuery"
        } else {
            "${request.data}?$referrerQuery"
        }

        val filtersJson = """{"filter_data":"filter_letter=0&type_url=$typeUrl&filter_audio=$filterAudio&filter_order=name","filter_genre_add":[],"filter_genre_del":[]}"""

        // ATENÇÃO: O token da API (c1deb78cd4) é estático e pode expirar novamente, 
        // causando a falha no carregamento. Se a lista parar de carregar, 
        // este token será o provável culpado.
        val postData = mapOf(
            "token" to "c1deb78cd4", 
            "pagina" to page.toString(),
            "search" to "0",
            "limit" to "30",
            "type" to "lista",
            "filters" to filtersJson
        )

        try {
            val response = app.post(
                url = "$mainUrl/func/listanime",
                headers = mapOf(
                    "accept" to "application/json, text/javascript, */*; q=0.01",
                    "content-type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "x-requested-with" to "XMLHttpRequest",
                    "referer" to referrerUrl
                ),
                data = postData
            )

            val jsonString = response.text
            val home = parseApiResponse(jsonString)
            
            val totalPage = extractTotalPage(jsonString)
            val hasNext = page < totalPage

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = false
                ),
                hasNext = hasNext
            )
        } catch (e: Exception) {
            // Fallback para scraping se a API falhar
            return getFallbackPage(request)
        }
    }

    private fun parseApiResponse(jsonString: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        try {
            val jsonObject = JSONObject(jsonString)
            val resultsArray = jsonObject.optJSONArray("results") ?: return emptyList()

            for (i in 0 until resultsArray.length()) {
                val escapedHtml = resultsArray.optString(i) ?: continue

                // Limpar as barras de escape
                val cleanHtml = escapedHtml.replace("\\\"", "\"").replace("\\/", "/") 
                
                // Parsear o HTML
                val document = org.jsoup.Jsoup.parseBodyFragment(cleanHtml)
                
                // CORREÇÃO: Usar toSearchResultAlternative() para listas de Animes/Filmes, 
                // pois eles não têm número de episódio (.number).
                val searchResult = document.selectFirst(".itemA")?.toSearchResultAlternative() 
                
                if (searchResult != null) {
                    results.add(searchResult)
                }
            }
        } catch (e: Exception) {
            // Ignorar erro
        }
        
        return results
    }

    private fun extractTotalPage(jsonString: String): Int {
        return try {
            val jsonObject = JSONObject(jsonString)
            jsonObject.optInt("total_page", 1)
        } catch (e: Exception) {
            1
        }
    }

    // Método fallback caso a API não funcione
    private suspend fun getFallbackPage(request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        
        // Tentar diferentes seletores possíveis
        val home = document.select(".itemA, .anime-item, .item, .post, [class*='anime']").mapNotNull {
            it.toSearchResultAlternative()
        }
        
        // Tentativa de verificar se há mais páginas no fallback
        val hasNext = document.select(".pagination a.current + a, .pagination a:contains(Próximo)").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    // Método alternativo para extrair dados de elementos diferentes (para listas de Séries/Filmes)
    private fun Element.toSearchResultAlternative(): SearchResponse? {
        val titleElement = selectFirst("a") ?: selectFirst(".title, .name, h1, h2, h3") ?: return null
        val href = titleElement.attr("href")
        val title = titleElement.text().trim()
        val posterUrl = selectFirst("img")?.attr("src") ?: selectFirst("img")?.attr("data-src")
        
        return if (title.isNotEmpty() && href.isNotEmpty()) {
            newAnimeSearchResponse(title, href) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        } else {
            null
        }
    }

    
    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("a") ?: return null
        val href = titleElement.attr("href")
        val animeTitle = selectFirst(".title_anime")?.text()?.trim() ?: return null
        val episodeNumberText = selectFirst(".number")?.text()?.trim() ?: return null
        val posterUrl = selectFirst("img")?.attr("src")
        
        
        val episodeNumber = episodeNumberText.filter { it.isDigit() }.toIntOrNull() ?: 1
        
    
        val isDub = animeTitle.contains("dublado", ignoreCase = true) || 
                     href.contains("dublado", ignoreCase = true) ||
                     episodeNumberText.contains("dublado", ignoreCase = true)
        
        val fullTitle = "$animeTitle - $episodeNumberText"
        
        return newAnimeSearchResponse(fullTitle, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.addDubStatus(isDub, episodeNumber)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.itemE, div.itemA").mapNotNull {
            it.toSearchResultAlternative() // Usando o alternativo para resultados de busca que são séries
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Se a URL for de episódio, chama loadEpisode
        val isEpisode = url.contains("/video/a/")

        if (isEpisode) {
            return loadEpisode(url, document)
        } 
        
        // Se for URL de detalhes de Anime/Filme
        return loadAnime(url, document)
    }

    private fun String?.toStatus(): Int? {
        val statusText = this?.lowercase() ?: return null
        return when {
            statusText.contains("completo") -> 2 // Completed
            statusText.contains("em lançamento") -> 1 // Ongoing
            else -> null
        }
    }

    private suspend fun loadEpisode(url: String, document: org.jsoup.nodes.Document): LoadResponse? {
        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        // Extrai informações do anime
        val animeTitle = document.selectFirst(".info span:contains(Anime) + span")?.text() ?: title
        
        // Lista de episódios
        val episodes = document.select(".episode_list_episodes_item").mapNotNull { episodeElement ->
            val epUrl = episodeElement.attr("href")
            val epNum = episodeElement.selectFirst(".episode_list_episodes_num")?.text()?.toIntOrNull() ?: 1
            val urlWithIndex = "$epUrl|#|$epNum" 
            newEpisode(urlWithIndex) {
                this.name = "Episódio $epNum"
                this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(animeTitle, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private suspend fun loadAnime(url: String, document: org.jsoup.nodes.Document): LoadResponse? {
        val infoContainer = document.selectFirst(".single_anime, .single-content") ?: document

        val title = infoContainer.selectFirst("h1.single-title, h1")?.text()?.trim() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.let { content ->
                if (content.contains(" - Animes Online")) {
                    content.substringBefore(" - Animes Online").trim()
                } else {
                    content
                }
            }
            ?: document.selectFirst("h1, h2")?.text() ?: return null

        val poster = infoContainer.selectFirst(".foto img")?.attr("src") 
            ?: document.selectFirst("img[src*=/uploads/]")?.attr("src") 
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val posterUrl = fixUrlNull(poster)
        
        val description = infoContainer.selectFirst(".sinopse p")?.text()?.trim() 
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = infoContainer.select(".generos a, .single-meta a[href*='genero']").map { it.text().trim() }
        
        val statusText = infoContainer.selectFirst(".status span")?.text()?.trim()
        val status = statusText.toStatus()

        val tvType = if (url.contains("/filmes/", ignoreCase = true)) TvType.Movie else TvType.Anime
        
        val defaultDubStatus = when {
            url.contains("dublado", ignoreCase = true) || url.contains("desenhos", ignoreCase = true) -> DubStatus.Dubbed
            else -> DubStatus.Subbed
        }

        val episodeLinks = document.select(".item_ep a")

        val episodes = episodeLinks.mapNotNull { epElement ->
            val epUrl = epElement.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            
            val titleElement = epElement.selectFirst("div.title_anime")
            var epTitle = titleElement?.text()?.trim()
            
            if (epTitle.isNullOrEmpty()) {
                val imgTag = epElement.selectFirst("img")
                epTitle = imgTag?.attr("title")?.replace("Assistir ", "") ?: "Título Desconhecido"
            }
            
            epTitle = epTitle?.replace("Episodio ", "Episódio ") ?: "Episódio"
            
            val episodeNumber = extractEpisodeNumber(epTitle, epUrl)
            
            val urlWithIndex = "$epUrl|#|$episodeNumber"

            newEpisode(urlWithIndex) {
                this.name = epTitle
                this.episode = episodeNumber
            }
        }.reversed() 


        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            this.status = status

            if (episodes.isNotEmpty()) addEpisodes(defaultDubStatus, episodes)
        }
    }

    // Função auxiliar para extrair número do episódio
    private fun extractEpisodeNumber(title: String, url: String): Int {
        // Tenta extrair do título primeiro
        val patterns = listOf(
            Regex("""Epis[oó]dio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Cap\.?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d+)\b""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(title)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: continue
            }
        }
        
        // Fallback: tenta extrair da URL
        val urlMatch = Regex("""[\/\-](\d+)[\/\-]?""").find(url)
        return urlMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        val parts = data.split("|#|")
        val realUrl = parts[0]
        val episodeNum = parts.getOrNull(1)?.toIntOrNull() ?: 1

        val document = app.get(realUrl).document
        
        document.select(".tab-video").forEach { player ->
            val iframe = player.selectFirst("iframe")
            val iframeSrc = iframe?.attr("src") ?: return@forEach
            
            if (iframeSrc.contains("anivideo.net") && iframeSrc.contains("m3u8")) {
                val m3u8Url = extractM3u8Url(iframeSrc)
                m3u8Url?.let { url ->
                    callback.invoke(
                        newExtractorLink(
                            name, "Player FHD", url, ExtractorLinkType.M3U8
                        ) {
                            this.referer = realUrl 
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
            }
            else if (iframeSrc.contains("animesdigital.org/aHR0")) {
                val decodedPageUrl = decodeAnimesDigitalUrl(iframeSrc)
                
                decodedPageUrl?.let { url ->
                    val playerPage = app.get(url).document
                    
                    val allIframes = playerPage.select(".post-body iframe[src]")
                    val targetIndex = episodeNum - 1
                    
                    val targetIframe = allIframes.getOrNull(targetIndex)
                    
                    val finalLink = targetIframe?.attr("src")
                    
                    finalLink?.let { link ->
                        if (link.isNotBlank()) {
                            loadExtractor(link, url, subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                }
            }
        }
        
        return foundLinks
    }

    private fun extractM3u8Url(iframeSrc: String): String? {
        return try {
            val params = iframeSrc.split("?").last().split("&")
            params.find { it.startsWith("d=") }?.substringAfter("=")?.let { encodedUrl ->
                java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeAnimesDigitalUrl(iframeSrc: String): String? {
        return try {
           val base64Part = iframeSrc.substringAfter("animesdigital.org/").substringBefore("/")
           val decoded = Base64.getDecoder().decode(base64Part) 
           String(decoded)
        } catch (e: Exception) {
            null
        }
    }
}
