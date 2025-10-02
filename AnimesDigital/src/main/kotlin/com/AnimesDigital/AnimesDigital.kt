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
                // Página de lançamentos - scraping tradicional
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
                // Todas as outras categorias - usar API
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
                
                // Selecionar o item
                val searchResult = document.selectFirst(".itemA")?.toSearchResult()
                
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
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = false
        )
    }

    // Método alternativo para extrair dados de elementos diferentes
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

    // Seu método original
    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("a") ?: return null
        val href = titleElement.attr("href")
        val title = selectFirst(".title_anime")?.text()?.trim() ?: return null
        val posterUrl = selectFirst("img")?.attr("src")
        
        return newAnimeSearchResponse(title, href) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.itemE, div.itemA").mapNotNull {
            it.toSearchResult()
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
        val episodeNum = document.selectFirst(".info span:contains(Episódio) + span")?.text()?.toIntOrNull() ?: 1
        
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

        // EXTRAÇÃO DE METADADOS
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

       val episodesList = document.select(".item_ep")

val episodes = episodesList.mapNotNull { epContainer ->
    // 1. Encontrar a tag <a> dentro do .item_ep
    val epElement = epContainer.selectFirst("a")

    if (epElement != null) {
        // 2. Extrair o link (href) da tag <a>
        val epUrl = epElement.attr("href")

        // 3. Extrair o título da sub-tag com classe .title_anime
        val titleElement = epElement.selectFirst(".title_anime")
        val epTitle = titleElement?.text()?.trim() ?: ""

        if (epUrl.isNotEmpty() && epTitle.isNotEmpty()) {
            val epNumMatch = Regex("Episódio\\s+(\\d+)|Cap\\.\\s+(\\d+)|(\\d+)").find(epTitle)
            val episodeNumber = epNumMatch?.groupValues?.lastOrNull()?.toIntOrNull() ?: 0

            val urlWithIndex = "$epUrl|#|$episodeNumber"

            newEpisode(urlWithIndex) {
                this.name = epTitle
                this.episode = episodeNumber
            }
        } else null
    } else null
}
.reversed()

val dubEpisodes = episodes.filter { episode ->
    episode.name?.contains("dublado", ignoreCase = true) == true || defaultDubStatus == DubStatus.Dubbed
}
val subEpisodes = episodes.filter { episode ->
    episode.name?.contains("dublado", ignoreCase = true) != true || defaultDubStatus == DubStatus.Subbed
}

return newAnimeLoadResponse(title, url, tvType) {
    this.posterUrl = posterUrl
    this.plot = description
    this.tags = tags

    if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
    if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
     }
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