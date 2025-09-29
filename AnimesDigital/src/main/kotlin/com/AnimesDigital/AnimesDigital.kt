package com.AnimesDigital

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimesDigitalProvider : MainAPI() {
    override var mainUrl = "https://animesdigital.org"
    override var name = "Animes Digital"
    override val hasMainPage = true
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
        val home = document.select("div.itemE, div.itemA").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("a") ?: return null
        val href = titleElement.attr("href")
        val title = selectFirst(".title_anime")?.text()?.trim() ?: return null
        val episode = selectFirst(".number")?.text()?.trim()
        val posterUrl = selectFirst("img")?.attr("src")
        
        return newAnimeSearchResponse(title, href) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.itemE, div.itemA").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Verifica se é uma página de anime ou episódio
        val isEpisode = url.contains("/video/a/")
        
        if (isEpisode) {
            return loadEpisode(url, document)
        } else {
            return loadAnime(url, document)
        }
    }

    private suspend fun loadEpisode(url: String, document: org.jsoup.nodes.Document): LoadResponse {
        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: "Episódio"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        // Extrai informações do anime
        val animeTitle = document.selectFirst(".info span:contains(Anime) + span")?.text() ?: title
        val episodeNum = document.selectFirst(".info span:contains(Episódio) + span")?.text()?.toIntOrNull() ?: 1
        
        // Lista de episódios
        val episodes = document.select(".episode_list_episodes_item").map { episodeElement ->
            val epUrl = episodeElement.attr("href")
            val epNum = episodeElement.selectFirst(".episode_list_episodes_num")?.text()?.toIntOrNull() ?: 1
            newEpisode(epUrl) {
                this.name = "Episódio $epNum"
                this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(animeTitle, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private suspend fun loadAnime(url: String, document: org.jsoup.nodes.Document): LoadResponse {
        val title = document.selectFirst("h1, h2")?.text() ?: "Anime"
        val poster = document.selectFirst("img[src*=/uploads/]")?.attr("src")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        // Lista de episódios (se disponível na página do anime)
        val episodes = document.select(".episode_list_episodes_item").map { episodeElement ->
            val epUrl = episodeElement.attr("href")
            val epNum = episodeElement.selectFirst(".episode_list_episodes_num")?.text()?.toIntOrNull() ?: 1
            newEpisode(epUrl) {
                this.name = "Episódio $epNum"
                this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Extrai players disponíveis
        document.select(".tab-video").forEach { player ->
            val iframe = player.selectFirst("iframe")
            val iframeSrc = iframe?.attr("src") ?: return@forEach
            
            // Player 1 - M3U8 direto
            if (iframeSrc.contains("anivideo.net") && iframeSrc.contains("m3u8")) {
                val m3u8Url = extractM3u8Url(iframeSrc)
                m3u8Url?.let { url ->
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "Player FHD",
                            url,
                            data,
                            Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                }
            }
            // Player 2 - Link codificado
            else if (iframeSrc.contains("animesdigital.org/aHR0")) {
                val decodedUrl = decodeAnimesDigitalUrl(iframeSrc)
                decodedUrl?.let { url ->
                    if (url.contains(".mp4")) {
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "Player 2",
                                url,
                                data,
                                Qualities.Unknown.value
                            )
                        )
                    } else if (url.contains("m3u8")) {
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "Player 2",
                                url,
                                data,
                                Qualities.Unknown.value,
                                isM3u8 = true
                            )
                        )
                    }
                }
            }
        }
        
        return true
    }

    private fun extractM3u8Url(iframeSrc: String): String? {
        return try {
            // Extrai a URL do M3U8 do parâmetro 'd'
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
            // Remove a parte base e pega o código base64
            val base64Part = iframeSrc.substringAfter("animesdigital.org/").substringBefore("/")
            val decoded = android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT)
            String(decoded)
        } catch (e: Exception) {
            null
        }
    }
}