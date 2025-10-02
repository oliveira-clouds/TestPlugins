package com.AnimesDigital

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        val document = app.get(request.data).document
        val home = document.select("div.itemE, div.itemA, div#animePost .itemA, div.list-animes .itemE").mapNotNull {
            it.toSearchResult()
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
        
        val isEpisode = url.contains("/video/a/")

        if (isEpisode) {
            return loadEpisode(url, document)
        } else {
            return loadAnime(url, document)
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
        val title = document.selectFirst("h1, h2")?.text() ?: return null
        val poster = document.selectFirst("img[src*=/uploads/]")?.attr("src")?.let { fixUrlNull(it) }
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        // Lista de episódios (se disponível na página do anime)
        val episodes = document.select(".episode_list_episodes_item").mapNotNull { episodeElement ->
            val epUrl = episodeElement.attr("href")
            val epNum = episodeElement.selectFirst(".episode_list_episodes_num")?.text()?.toIntOrNull() ?: 1
            val urlWithIndex = "$epUrl|#|$epNum"
            newEpisode(urlWithIndex) {
                this.name = "Episódio $epNum"
                this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var foundLinks = false
    
    // 1. Extrai a URL real E o número sequencial (episodeNum)
    val parts = data.split("|#|")
    val realUrl = parts[0] // A URL real (ex: https://animesdigital.org/video/a/128280/)
    val episodeNum = parts.getOrNull(1)?.toIntOrNull() ?: 1 // O número sequencial (ex: 10)

    val document = app.get(realUrl).document // Usamos a URL real para buscar players
    
    document.select(".tab-video").forEach { player ->
        val iframe = player.selectFirst("iframe")
        val iframeSrc = iframe?.attr("src") ?: return@forEach
        
        // Player 1 (MANTIDO)
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
        // Player 2 - Link codificado
        else if (iframeSrc.contains("animesdigital.org/aHR0")) {
            val decodedPageUrl = decodeAnimesDigitalUrl(iframeSrc)
            
            decodedPageUrl?.let { url ->
                val playerPage = app.get(url).document
                
                // 2. Seleciona TODOS os iframes no corpo do post.
                val allIframes = playerPage.select(".post-body iframe[src]")

                // 3. Define o índice alvo (Episódio 10 = índice 9).
                val targetIndex = episodeNum - 1
                
                // 4. Seleciona o iframe correto usando o índice.
                val targetIframe = allIframes.getOrNull(targetIndex)
                
                val finalLink = targetIframe?.attr("src")
                
                finalLink?.let { link ->
                    if (link.isNotBlank()) {
                        // 5. Carrega o link do player específico.
                        loadExtractor(link, url, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
        }
    }
    
    return foundLinks
}
    // ====================================================================================

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
        // Tenta usar o Base64 do Android (como você estava usando)
        return try {
            val base64Part = iframeSrc.substringAfter("animesdigital.org/").substringBefore("/")
            val decoded = android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT)
            String(decoded)
        } catch (e: Exception) {
            null
        }
    }
}