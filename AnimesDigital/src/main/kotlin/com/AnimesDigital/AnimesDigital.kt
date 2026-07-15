package com.AnimesDigital

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.DubStatus
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.json.JSONObject
import android.util.Base64

class AnimesDigitalProvider : MainAPI() {
    override var mainUrl = "https://animesdigital.org"
    override var name = "Animes Digital"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
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
            request.data.contains("home") || request.data.contains("lancamentos") -> {
                val document = app.get(request.data).document
                val home = document.select(".itemE, .itemA").mapNotNull {
                    it.toSearchResult()
                }
                newHomePageResponse(
                    list = HomePageList(
                        name = request.name,
                        list = home,
                        isHorizontalImages = true
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
                ?: document.select("script").mapNotNull { script ->
                    Regex("""token['":\s]+['"]([a-f0-9]+)['"]""").find(script.html())?.groupValues?.get(1)
                }.firstOrNull()
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

        val dynamicToken = getSecurityToken(request.data) ?: "c1deb78cd4"
        
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
            logError("Falha ao parsear API response", e)
        }
        return results
    }

    private fun extractTotalPage(jsonString: String): Int {
        return try {
            JSONObject(jsonString).optInt("total_page", 1)
        } catch (e: Exception) { 1 }
    }

    private suspend fun getFallbackPage(request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select(".itemA, .anime-item, .item, .post, [class*='anime']").mapNotNull {
            it.toSearchResultAlternative()
        }
        val hasNext = document.select(".pagination a.current + a, .pagination a:contains(Próximo)").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResultAlternative(): SearchResponse? {
        val titleElement = selectFirst("a") ?: selectFirst(".title, .name, h1, h2, h3") ?: return null
        val href = titleElement.attr("href")
        val title = titleElement.text().trim()
        val posterUrl = selectFirst("img")?.attr("src") ?: selectFirst("img")?.attr("data-src")
        
        val isMovie = href.contains("/filme/", ignoreCase = true) || title.contains("filme", ignoreCase = true)
        
        return if (title.isNotEmpty() && href.isNotEmpty()) {
            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = fixUrlNull(posterUrl) }
            } else {
                newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = fixUrlNull(posterUrl) }
            }
        } else null
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("a") ?: return null
        val href = titleElement.attr("href")
        val animeTitle = selectFirst(".title_anime")?.text()?.trim() ?: return null
        val episodeNumberText = selectFirst(".number")?.text()?.trim() ?: return null
        val posterUrl = selectFirst("img")?.attr("src")
        
        val episodeNumber = episodeNumberText.filter { it.isDigit() }.toIntOrNull() ?: 1
        val isDub = animeTitle.contains("dublado", ignoreCase = true) || href.contains("dublado", ignoreCase = true)
        val fullTitle = "$animeTitle - $episodeNumberText"
        
        return newAnimeSearchResponse(fullTitle, href, TvType.Anime) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.addDubStatus(isDub, episodeNumber)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.itemE, div.itemA").mapNotNull {
            it.toSearchResultAlternative()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        return when {
            url.contains("/video/a/") -> loadEpisode(url, document)
            url.contains("/filme/") -> loadMovie(url, document)
            else -> loadAnime(url, document)
        }
    }

    private fun getDubStatusFromDoc(document: Document): DubStatus {
        val audioText = document.selectFirst(".info:contains(Audio) span:last-child")?.text()?.lowercase() ?: ""
        return when {
            audioText.contains("português") || audioText.contains("dublado") -> DubStatus.Dubbed
            else -> DubStatus.Subbed
        }
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace("(?i)Assistir\\s*".toRegex(), "")
            .replace("(?i)Todos os Episódios".toRegex(), "")
            .replace("(?i)Online(\\s*em\\s*HD)?".toRegex(), "")
            .replace("(?i)- Filme(\\s*Filme)*".toRegex(), "")
            .replace("\\s+".toRegex(), " ").trim()
    }

    private suspend fun loadMovie(url: String, document: Document): LoadResponse? {
        val infoContainer = document.selectFirst(".descep_video") ?: document
        val rawTitle = infoContainer.selectFirst("h1")?.text()?.trim() ?: return null
        val title = cleanTitle(rawTitle)
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
        val description = infoContainer.selectFirst(".info:contains(Descrição) span:last-child")?.text()?.trim()
        val year = infoContainer.selectFirst(".info:contains(Data) span:last-child")?.text()?.trim()?.takeLast(4)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
        }
    }

    private suspend fun loadEpisode(url: String, document: Document): LoadResponse? {
        val title = document.selectFirst("#anime_title")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
        val animeTitle = document.selectFirst(".descep_video .info:contains(Anime) span:last-child")?.text()?.trim() ?: title
        val dubStatus = getDubStatusFromDoc(document)

        // Tenta extrair TODOS os episódios da sidebar direito na página do episódio
        val episodes = document.select(".sidebar_navigation_episodes a.episode_list_episodes_item").mapNotNull { epElement ->
            val epUrl = epElement.attr("href").takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: return@mapNotNull null
            val epNumStr = epElement.selectFirst(".episode_list_episodes_num")?.text()?.trim() ?: return@mapNotNull null
            val epNumFloat = epNumStr.toFloatOrNull() ?: return@mapNotNull null
            val epNumId = Math.round(epNumFloat * 10) // Hack para o .5
            
            newEpisode("$epUrl|#|$epNumId") {
                this.name = "Episódio $epNumStr"
                this.episode = epNumId
            }
        }

        // Fallback: Se a sidebar estiver vazia, usa apenas o episódio atual
        val finalEpisodes = if (episodes.isNotEmpty()) episodes else {
            val currentEpNumStr = document.selectFirst(".descep_video .info:contains(Episódio) span:last-child")?.text()?.trim()
                ?: extractCurrentEpisodeNumber(url, title).toString()
            val currentEpNumFloat = currentEpNumStr.toFloatOrNull() ?: 1f
            val currentEpNumId = Math.round(currentEpNumFloat * 10)
            
            listOf(newEpisode("$url|#|$currentEpNumId") {
                this.name = "Episódio $currentEpNumStr"
                this.episode = currentEpNumId
            })
        }

        return newAnimeLoadResponse(animeTitle, url, TvType.Anime) {
            this.posterUrl = poster
            addEpisodes(dubStatus, finalEpisodes)
        }
    }

    private fun extractCurrentEpisodeNumber(url: String, fallbackTitle: String): Int {
        val titleMatch = Regex("""Epis[oó]dio\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(fallbackTitle)
        return titleMatch?.groupValues?.get(1)?.toFloatOrNull()?.let { Math.round(it * 10) } ?: 1
    }

    private suspend fun loadAnime(url: String, document: Document): LoadResponse? {
        val infoContainer = document.selectFirst(".dados") ?: document

        val rawTitle = infoContainer.selectFirst("h1")?.text()?.trim() ?: return null
        val title = cleanTitle(rawTitle)
        val poster = document.selectFirst(".poster img")?.attr("src")?.let { fixUrlNull(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = infoContainer.selectFirst(".sinopse")?.text()?.trim()
        val tags = infoContainer.select(".genres .genre a").map { it.text().trim() }
        val year = infoContainer.selectFirst(".info:contains(Ano)")?.text()?.replace("Ano", "")?.trim()?.toIntOrNull()

        val dubStatus = getDubStatusFromDoc(document)
        val allEpisodes = loadAllEpisodes(url, document)

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            if (allEpisodes.isNotEmpty()) addEpisodes(dubStatus, allEpisodes)
        }
    }

    private suspend fun loadAllEpisodes(initialUrl: String, initialDocument: Document): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()
        allEpisodes.addAll(extractEpisodesFromPage(initialDocument))
        
        var page = 2
        var emptyPagesCount = 0
        
        while (emptyPagesCount < 2 && page <= 50) {
            val pageUrl = buildNextPageUrl(initialUrl, page)
            try {
                val pageDocument = app.get(pageUrl, timeout = 10000L).document
                val pageEpisodes = extractEpisodesFromPage(pageDocument)
                
                if (pageEpisodes.isEmpty()) {
                    emptyPagesCount++
                } else {
                    emptyPagesCount = 0
                    allEpisodes.addAll(pageEpisodes)
                }
                page++
            } catch (e: Exception) {
                break // 404 ou timeout, acabou a paginação
            }
        }
        
        return allEpisodes
            .distinctBy { it.episode } // Remove duplicatas (ex: dois Ep 1115)
            .sortedByDescending { it.episode }
    }

    private fun buildNextPageUrl(baseUrl: String, page: Int): String {
        val cleanBaseUrl = baseUrl.removeSuffix("/")
        return if (cleanBaseUrl.contains("/page/")) {
            cleanBaseUrl.replace(Regex("/page/\\d+$"), "/page/$page/")
        } else {
            "$cleanBaseUrl/page/$page/"
        }
    }

    private fun extractEpisodesFromPage(document: Document): List<Episode> {
        val episodeLinks = document.select(".item_ep a")
        
        return episodeLinks.mapNotNull { epElement ->
            // Usa fixUrl para lidar com as URLs "sujas" (ex: /123885c) que o site cria
            val epUrl = epElement.attr("href").takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: return@mapNotNull null
            
            var epTitle = epElement.selectFirst("div.title_anime")?.text()?.trim()
            if (epTitle.isNullOrEmpty()) {
                epTitle = epElement.selectFirst("img")?.attr("title")?.replace("Assistir ", "") ?: "Episódio"
            }
            epTitle = epTitle.replace("Episodio ", "Episódio ")
            
            val episodeNumber = extractEpisodeNumber(epTitle, epUrl)
            val urlWithIndex = "$epUrl|#|$episodeNumber"

            newEpisode(urlWithIndex) {
                this.name = epTitle
                this.episode = episodeNumber
            }
        }
    }

    private fun extractEpisodeNumber(title: String, url: String): Int {
        val patterns = listOf(
            Regex("""Epis[oó]dio\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""Cap\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""EP?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(title)
            if (match != null) {
                val numFloat = match.groupValues[1].toFloatOrNull() ?: continue
                return Math.round(numFloat * 10) // Transforma 38 em 380, e 38.5 em 385
            }
        }
        
        val urlMatch = Regex("""/a/(\d+)[a-z0-9]*/""").find(url)
        val urlNum = urlMatch?.groupValues?.get(1)?.toFloatOrNull()
        if (urlNum != null) return Math.round(urlNum * 10)
        
        return 0
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
        val episodeNumId = parts.getOrNull(1)?.toIntOrNull() ?: 10 // Padrão 10 (eq a Ep 1)
        val episodeIndex = (episodeNumId / 10) - 1 // Índice real começa no 0

        val document = app.get(realUrl).document
        val isMoviePage = realUrl.contains("/filme/", ignoreCase = true)

        // Mapeia os nomes dos players
        val playerTabs = document.select(".tabs_videos li").associate {
            it.attr("data-tab") to it.text()
        }

        val playerElements = if (isMoviePage) {
            document.select("iframe[src]")
        } else {
            document.select(".tab-video iframe[src]")
        }
        
        playerElements.forEach { iframe ->
            val iframeSrc = iframe.attr("src") ?: return@forEach
            val parentDivId = iframe.parent()?.id()
            val tabName = playerTabs["#$parentDivId"] ?: "Player"
            
            val quality = when {
                tabName.contains("FHD", ignoreCase = true) -> Qualities.P1080.value
                tabName.contains("HD", ignoreCase = true) -> Qualities.P720.value
                tabName.contains("SD", ignoreCase = true) -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }

            if (iframeSrc.contains("anivideo.net") && iframeSrc.contains("m3u8")) {
                val m3u8Url = extractM3u8Url(iframeSrc)
                m3u8Url?.let { url ->
                    callback.invoke(
                        newExtractorLink(name, tabName, url, ExtractorLinkType.M3U8) {
                            this.referer = realUrl 
                            this.quality = quality
                        }
                    )
                    foundLinks = true
                }
            } else if (iframeSrc.contains("animesdigital.org/aHR0")) {
                val decodedPageUrl = decodeAnimesDigitalUrl(iframeSrc)
                decodedPageUrl?.let { url ->
                    val playerPage = app.get(url).document
                    val allIframes = playerPage.select(".post-body iframe[src]")
                    
                    // Filtra propagandas e pega a lista de vídeos reais
                    val videoIframes = allIframes.filter { iframeElement ->
                        val src = iframeElement.attr("src").lowercase()
                        src.contains("blogger.com/video") || src.contains("drive.google.com") || 
                        src.contains("youtube.com/embed") || src.contains("player") || src.contains("video")
                    }
                    
                    val targetList = if (videoIframes.isNotEmpty()) videoIframes else allIframes
                    val targetLink = targetList.getOrNull(episodeIndex)?.attr("src")
                    
                    targetLink?.let { link ->
                        if (link.isNotBlank()) {
                            loadExtractor(link, url, subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                }
            } else if (iframeSrc.isNotBlank()) {
                loadExtractor(iframeSrc, realUrl, subtitleCallback, callback)
                foundLinks = true
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
        } catch (e: Exception) { null }
    }

    private fun decodeAnimesDigitalUrl(iframeSrc: String): String? {
        return try {
            val base64Part = iframeSrc.substringAfter("animesdigital.org/").substringBefore("/")
            val decoded = Base64.decode(base64Part, Base64.DEFAULT)
            String(decoded)
        } catch (e: Exception) { null }
    }

    private fun logError(message: String, e: Exception) {
        println("[$name] $message: ${e.message}")
    }
}
