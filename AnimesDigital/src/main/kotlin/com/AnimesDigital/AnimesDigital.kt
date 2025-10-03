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
        "$mainUrl/home" to "Últimos Episódios",
        "$mainUrl/animes-legendados-online" to "Animes Legendados", 
        "$mainUrl/animes-dublado" to "Animes Dublados",
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/desenhos-online" to "Desenhos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when {
            request.data.contains("home") -> {
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
                getAnimesFromAPI(page, request)
            }
        }
    }
    private suspend fun getSecurityToken(url: String): String? {
        return try {
           
            val document = app.get(url).document 
            document.selectFirst(".menu_filter_box")?.attr("data-secury")
        } catch (e: Exception) {
            null
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

        val dynamicToken = getSecurityToken(request.data) ?: "c1deb78cd4" // Fallback para o token antigo se falhar
        
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

        val postData = mapOf(
            "token" to dynamicToken, 
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
                val cleanHtml = escapedHtml.replace("\\\"", "\"").replace("\\/", "/") 
                
                val document = org.jsoup.Jsoup.parseBodyFragment(cleanHtml)
                
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

    private fun Element.toSearchResultAlternative(): SearchResponse? {
    val titleElement = selectFirst("a") ?: selectFirst(".title, .name, h1, h2, h3") ?: return null
    val href = titleElement.attr("href")
    val title = titleElement.text().trim()
    val posterUrl = selectFirst("img")?.attr("src") ?: selectFirst("img")?.attr("data-src")
    
    val isMovie = href.contains("/filme/", ignoreCase = true) || 
                  title.contains("filme", ignoreCase = true)
    
    return if (title.isNotEmpty() && href.isNotEmpty()) {
        if (isMovie) {
            // Usa newMovieSearchResponse para filmes
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        } else {
            // Usa newAnimeSearchResponse para animes
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
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
        val href = it.selectFirst("a")?.attr("href") ?: ""
        it.toSearchResultAlternative()
    }
}

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
       
        val isEpisode = url.contains("/video/a/")

        if (isEpisode) {
            return loadEpisode(url, document)
        } 
        
    
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
    val animeTitle = document.selectFirst(".info span:contains(Anime) + span")?.text() 
        ?: document.selectFirst("#anime_title")?.text()?.replace(" Episódio \\d+".toRegex(), "")?.trim()
        ?: title
    
    // Extrai número do episódio atual
    val currentEpisodeNumber = extractCurrentEpisodeNumber(url, title)
    
    // Cria o episódio atual
    val currentEpisode = newEpisode(url) {
        this.name = "Episódio $currentEpisodeNumber"
        this.episode = currentEpisodeNumber
    }
    
    // Lista de episódios da sidebar
    val sidebarEpisodes = document.select(".episode_list_episodes_item").mapNotNull { episodeElement ->
        val epUrl = episodeElement.attr("href")
        val epNum = episodeElement.selectFirst(".episode_list_episodes_num")?.text()?.toIntOrNull() ?: 1
        if (epNum == currentEpisodeNumber) return@mapNotNull null
        
        val urlWithIndex = "$epUrl|#|$epNum" 
        newEpisode(urlWithIndex) {
            this.name = "Episódio $epNum"
            this.episode = epNum
        }
    }

    // Combina todos os episódios
    val allEpisodes = mutableListOf<Episode>()
    allEpisodes.addAll(sidebarEpisodes)
    allEpisodes.add(currentEpisode)

    // EXTRAI O LINK DA PÁGINA PRINCIPAL DO ANIME DE VÁRIAS FORMAS
    val animeUrl = extractAnimeMainPageUrl(document, url)

    return newAnimeLoadResponse(animeTitle, url, TvType.Anime) {
        this.posterUrl = poster
        this.plot = description
        addEpisodes(DubStatus.Subbed, allEpisodes)

        // Adiciona recomendação para página principal
        if (animeUrl != null) {
            this.recommendations = listOf(
                newAnimeSearchResponse("Ver todos os episódios", fixUrl(animeUrl), TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
    }
}

// Função auxiliar para extrair URL da página principal
private fun extractAnimeMainPageUrl(document: org.jsoup.nodes.Document, currentUrl: String): String? {
    // Tenta de várias formas:
    
    // 1. Do elemento .epsL (seção de navegação)
    val epslLink = document.selectFirst(".epsL a[href]")?.attr("href")
    if (epslLink != null && epslLink.contains("/anime/a/")) {
        return epslLink
    }
    
    // 2. De qualquer link que contenha "/anime/a/"
    val animeLink = document.selectFirst("a[href*='/anime/a/']")?.attr("href")
    if (animeLink != null) {
        return animeLink
    }
    
    // 3. Tenta construir a URL a partir da URL atual
    val animeSlug = extractAnimeSlugFromUrl(currentUrl)
    if (animeSlug != null) {
        return "https://animesdigital.org/anime/a/$animeSlug"
    }
    
    return null
}

// Função para extrair slug do anime da URL
private fun extractAnimeSlugFromUrl(url: String): String? {
    val match = Regex("""/video/a/([^/]+)/""").find(url)
    return match?.groupValues?.get(1)
}


private fun extractCurrentEpisodeNumber(url: String, title: String): Int {
   
    val urlMatch = Regex("""/a/(\d+)/""").find(url)
    urlMatch?.let {
        // Se a URL tem um ID numérico, tenta mapear para número do episódio
        // Ou usa um fallback baseado no título
    }
    
    // Tenta extrair do título
    val titleMatch = Regex("""Epis[oó]dio\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)
    titleMatch?.let {
        return it.groupValues[1].toIntOrNull() ?: 1
    }
    
    // Fallback: tenta encontrar qualquer número no título
    val anyNumberMatch = Regex("""\b(\d+)\b""").find(title)
    return anyNumberMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
}

   private suspend fun loadAnime(url: String, document: org.jsoup.nodes.Document): LoadResponse? {
    val infoContainer = document.selectFirst(".single_anime, .single-content, .dados") ?: document

    // Título - agora pega do .dados h1
    val title = infoContainer.selectFirst(".dados h1, h1.single-title, h1")?.text()?.trim() 
        ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.let { content ->
            if (content.contains(" - Animes Online")) {
                content.substringBefore(" - Animes Online").trim()
            } else {
                content
            }
        }
        ?: document.selectFirst("h1, h2")?.text() ?: return null

    // Poster
    val poster = infoContainer.selectFirst(".foto img")?.attr("src") 
        ?: document.selectFirst("img[src*=/uploads/]")?.attr("src") 
        ?: document.selectFirst("meta[property=og:image]")?.attr("content")
    val posterUrl = fixUrlNull(poster)
    
    // Descrição - agora pega do .dados .sinopse
    val description = infoContainer.selectFirst(".dados .sinopse, .sinopse p")?.text()?.trim() 
        ?: document.selectFirst("meta[property=og:description]")?.attr("content")

    // Tags/Gêneros - agora pega do .dados .genres
    val tags = infoContainer.select(".dados .genres a, .generos a, .single-meta a[href*='genero']")
        .map { it.text().trim() }

    // Ano - extrai do .dados .info
    val year = infoContainer.selectFirst(".dados .info:contains(Ano)")?.text()?.replace("Ano", "")?.trim()?.toIntOrNull()
    
    val statusText = infoContainer.selectFirst(".status span")?.text()?.trim()
    val status = statusText.toStatus()

    val tvType = if (url.contains("/filme/", ignoreCase = true)) TvType.Movie else TvType.Anime
    
    val defaultDubStatus = when {
        url.contains("dublado", ignoreCase = true) || url.contains("desenhos", ignoreCase = true) -> DubStatus.Dubbed
        else -> DubStatus.Subbed
    }
    
    if (tvType == TvType.Movie) {
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            this.year = year
        }
    }

    // Carrega todos os episódios
    val allEpisodes = loadAllEpisodes(url, document)

    return newAnimeLoadResponse(title, url, tvType) {
        this.posterUrl = posterUrl
        this.plot = description
        this.tags = tags
        this.year = year

        if (allEpisodes.isNotEmpty()) addEpisodes(defaultDubStatus, allEpisodes)
    }
}

private suspend fun loadAllEpisodes(initialUrl: String, initialDocument: org.jsoup.nodes.Document): List<Episode> {
    val allEpisodes = mutableListOf<Episode>()
    
    // Carrega primeira página
    val firstPageEpisodes = extractEpisodesFromPage(initialDocument)
    allEpisodes.addAll(firstPageEpisodes)
    
    // Carrega páginas sequencialmente começando da página 2
    var page = 2
    var hasMorePages = true
    
    while (hasMorePages && page <= 20) {
        val pageUrl = buildNextPageUrl(initialUrl, page)
        
        try {
            val pageDocument = app.get(pageUrl).document
            val pageEpisodes = extractEpisodesFromPage(pageDocument)
            
            if (pageEpisodes.isNotEmpty()) {
                // Verifica se são episódios diferentes
                val newEpisodes = pageEpisodes.filter { newEp ->
                    allEpisodes.none { existingEp -> existingEp.episode == newEp.episode }
                }
                
                if (newEpisodes.isNotEmpty()) {
                    allEpisodes.addAll(newEpisodes)
                    page++
                } else {
                    hasMorePages = false
                }
            } else {
                hasMorePages = false
            }
            
        } catch (e: Exception) {
            hasMorePages = false
        }
    }
    
    return allEpisodes.sortedByDescending { it.episode }
}

private fun buildNextPageUrl(baseUrl: String, page: Int): String {
    return if (baseUrl.contains("/page/")) {
        baseUrl.replace(Regex("/page/\\d+/"), "/page/$page/")
    } else {
        "${baseUrl.removeSuffix("/")}/page/$page/"
    }
}

private fun isFirstPageRedirect(document: org.jsoup.nodes.Document, originalUrl: String): Boolean {
    val currentUrl = document.selectFirst("meta[property=og:url]")?.attr("content")
    return currentUrl != null && !currentUrl.contains("/page/") && originalUrl.contains("/page/")
}

// Função para extrair episódios de uma única página
private fun extractEpisodesFromPage(document: org.jsoup.nodes.Document): List<Episode> {
    val episodeLinks = document.select(".item_ep a")
    
    return episodeLinks.mapNotNull { epElement ->
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
    val isMoviePage = realUrl.contains("/filme/", ignoreCase = true)

    // CORREÇÃO PARA FILMES - tratamento simplificado
    if (isMoviePage) {
        // Para filmes, procura por iframes diretamente
        val iframes = document.select("iframe[src]")
        iframes.forEach { iframe ->
            val iframeSrc = iframe.attr("src") ?: return@forEach
            
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
            } else {
                // Tenta carregar extractor para outros tipos de players
                loadExtractor(iframeSrc, realUrl, subtitleCallback, callback)
                foundLinks = true
            }
        }
        return foundLinks
    }

    // CÓDIGO ORIGINAL PARA SÉRIES/EPISÓDIOS
    val playerElements = document.select(".tab-video iframe[src]")
    
    playerElements.forEach { iframe ->
        val iframeSrc = iframe.attr("src") ?: return@forEach
        
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
                val targetIframe = allIframes.getOrNull(episodeNum - 1)
                
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
