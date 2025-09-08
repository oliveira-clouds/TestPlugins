package com.Anroll

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class Anroll : MainAPI() {
    override var mainUrl = "https://www.anroll.net"
    override var name = "Anroll"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "lancamentos" to "Últimos Lançamentos",
        "adicionados" to "Últimos Animes Adicionados"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())

        val document = app.get(mainUrl).document
        val items = mutableListOf<SearchResponse>()

        when (request.data) {
            "lancamentos" -> {
                document.select("ul.UVrQY li.release-item").forEach { element ->
                    parseLancamentoCard(element)?.let { items.add(it) }
                }
            }
            "adicionados" -> {
                document.select("ul.ctmcxR li.movielistitem").forEach { element ->
                    parseAdicionadoCard(element)?.let { items.add(it) }
                }
            }
        }

        return newHomePageResponse(request.name, items)
    }

    private fun parseLancamentoCard(element: Element): SearchResponse? {
        val link = element.selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = element.selectFirst("h1")?.text()?.trim() ?: return null
        val posterUrl = element.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }

        // Extrai o número do episódio
        val episodeText = element.selectFirst("span.episode-badge b")?.text()
        val episode = episodeText?.toIntOrNull() ?: 1

        // Extrai o tipo de áudio (LEG/DUB) da classe
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
        val searchUrl = "$mainUrl/?search=$query"
        val document = app.get(searchUrl).document

        val items = mutableListOf<SearchResponse>()
        document.select("ul.UVrQY li.release-item, ul.ctmcxR li.movielistitem").forEach { element ->
            val link = element.selectFirst("a[href]") ?: continue
            val href = fixUrl(link.attr("href"))
            val title = element.selectFirst("h1")?.text()?.trim() ?: continue
            val posterUrl = element.selectFirst("img")?.attr("src")?.let { fixUrlNull(it) }

            items.add(
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = posterUrl
                }
            )
        }

        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleElement = document.selectFirst("div#epinfo h1 a span") ?: return null
        val title = titleElement.text().trim()

        val poster = document.selectFirst("img[alt]")?.attr("src")?.let { fixUrlNull(it) }
        val plot = document.selectFirst("div.sinopse")?.text()

        // Verifica se é uma página de episódio (tem #epinfo) ou de anime (lista de episódios)
        val isEpisodePage = document.selectFirst("div#epinfo h2#current_ep") != null

        if (isEpisodePage) {
            // É uma página de episódio — retorna apenas este episódio
            val episodeText = document.selectFirst("h2#current_ep b")?.text()
            val episode = episodeText?.toIntOrNull() ?: 1

            return newAnimeLoadResponse(title, url, TvType.Anime, listOf(
                Episode(
                    data = url,
                    name = "Episódio $episode",
                    episode = episode
                )
            )) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            // É uma página de anime — lista todos os episódios
            val episodes = document.select("div.epcontrol a").mapIndexed { index, epElement ->
                val epUrl = epElement.attr("href").let { fixUrl(it) }
                val epName = epElement.text().trim()
                val epNum = index + 1

                Episode(
                    data = epUrl,
                    name = epName,
                    episode = epNum
                )
            }

            return newAnimeLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
         String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

      
        val videoSource = document.selectFirst("video source")?.attr("src")
        if (videoSource != null) {
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    fixUrl(videoSource),
                    "$mainUrl/",
                    Qualities.P1080.value, // ou Qualities.Unknown.value
                    headers = mapOf("Referer" to "$mainUrl/")
                )
            )
            return true
        }

        // Fallback: 
        val iframeSrc = document.selectFirst("iframe")?.attr("src")?.let { fixUrl(it) }
        if (iframeSrc != null) {
            loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)
            return true
        }

        return false
    }
}
