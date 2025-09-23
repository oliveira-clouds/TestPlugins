package com.DoramasOnline

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.jsoup.parser.Parser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.json.JSONObject
import java.util.*
import java.util.regex.Pattern
import java.net.URLDecoder

class DoramasOnline : MainAPI() {
    
    override var mainUrl = "https://doramasonline.org"
    override var name = "Doramas Online"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)


    override val mainPage = mainPageOf(
        "doramas" to "Doramas",
        "filmes" to "Filmes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        var url = ""

        when (request.data) {
            "doramas" -> {
                url = "$mainUrl/br/series/page/$page/"
            }
            "filmes" -> {
                url = "$mainUrl/br/filmes/page/$page/"
            }
        }

        val document = app.get(url).document

        document.select("article.item").forEach { item ->
            val title = item.selectFirst("h3 a")?.text() ?: return@forEach
            val link = item.selectFirst("h3 a")?.attr("href") ?: return@forEach
            val posterUrl = item.selectFirst("img")?.attr("src") ?: ""
            val type = if (request.data == "doramas") TvType.TvSeries else TvType.Movie

            if (link.isNotEmpty()) {
                items.add(
                    newAnimeSearchResponse(title, link, type) {
                        this.posterUrl = fixUrl(posterUrl)
                    }
                )
            }
        }
        
        val hasNext = document.selectFirst("a.next.page-numbers") != null

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

  override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document

        return document.select("div.result-item").mapNotNull { element ->
            val titleElement = element.selectFirst("div.title a")
            val linkElement = element.selectFirst("div.title a")
            val posterElement = element.selectFirst("img")
            val typeElement = element.selectFirst("div.image span")

            if (titleElement != null && linkElement != null) {
                val title = titleElement.text().trim()
                val url = linkElement.attr("href")
                val posterUrl = posterElement?.attr("src") ?: ""
                
                val type = when (typeElement?.classNames()) {
                    setOf("tvshows") -> TvType.TvSeries
                    setOf("movies") -> TvType.Movie
                    else -> TvType.TvSeries
                }
                
                return@mapNotNull if (type == TvType.Movie) {
                    newMovieSearchResponse(title, url) {
                        this.posterUrl = posterUrl
                    }
                } else {
                    newTvSeriesSearchResponse(title, url) {
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

    val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: document.title()
    val plot = document.selectFirst("div.sbox p")?.text()?.trim()
    val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
    val type = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie

    val allEpisodes = mutableListOf<Episode>()

    if (type == TvType.TvSeries) {
        val seasonSections: Elements = document.select("div.se-c")

        for (seasonSection: Element in seasonSections) {
            val seasonTitle = seasonSection.selectFirst(".title")?.text()?.trim() ?: continue
            val seasonNumber = Pattern.compile("(\\d+)").matcher(seasonTitle).let { if (it.find()) it.group(1).toIntOrNull() else 1 }
            val episodeElements: Elements = seasonSection.select("ul.episodios li")

            for (episodeElement: Element in episodeElements) {
                val linkTag = episodeElement.selectFirst("a")
                val titleTag = episodeElement.selectFirst(".episodiotitle a")

                if (linkTag != null && titleTag != null) {
                    val episodeUrl = linkTag.attr("href")
                    val episodeName = titleTag.text().trim()
                    
                    val episodeNumberString = Pattern.compile("(\\d+)")
                        .matcher(episodeName)
                        .let { if (it.find()) it.group(1) else null }
                    
                    val episodeNumber = episodeNumberString?.toIntOrNull() ?: 0
                    
                    allEpisodes.add(
                        newEpisode(episodeUrl) {
                            name = episodeName
                            episode = episodeNumber
                            season = seasonNumber 
                        }
                    )
                }
            }
        }
    }
    
    return if (type == TvType.Movie) {
        newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.plot = plot
            this.posterUrl = poster
        }
    } else {
        newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes.reversed()) {
            this.plot = plot
            this.posterUrl = poster
        }
    }
}

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document
        
    val players = document.select("div.source-box div.pframe iframe.metaframe.rptss")
    var foundLinks = false
    
    players.forEachIndexed { index, iframe ->
        val playerUrl = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@forEachIndexed
        
        try {
            val finalUrl = if (playerUrl.contains("/aviso/")) {
                when {
                    playerUrl.contains("?url=") -> {
                        URLDecoder.decode(playerUrl.substringAfter("?url=").substringBefore("&"), "UTF-8")
                    }
                    playerUrl.contains("&url=") -> {
                        URLDecoder.decode(playerUrl.substringAfter("&url=").substringBefore("&"), "UTF-8")
                    }
                    else -> playerUrl
                }
            } else {
                playerUrl
            }
            
            if (finalUrl.startsWith("http")) {
                when {
                    finalUrl.contains("csst.online") -> {
                        // CsstOnline - usa loadExtractor como antes
                        loadExtractor(finalUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                    finalUrl.contains("rogeriobetin.com") -> {
                        // RogerioBetin - extrai o M3U8 da página
                        extractRogerioBetin(finalUrl, data, callback)
                        foundLinks = true
                    }
                    else -> {
                        // Outros - tenta loadExtractor
                        loadExtractor(finalUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
        } catch (e: Exception) {
            // Ignora erros
        }
    }
        
    return foundLinks
}

private suspend fun extractRogerioBetin(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
    try {
        val response = app.get(url)
        val content = response.text
        
        // Procura o M3U8 no JavaScript
        val m3u8Pattern = """sources:\s*\[\{"file":"([^"]+\.m3u8[^"]*)""".toRegex()
        val match = m3u8Pattern.find(content)
        
        match?.groupValues?.get(1)?.let { m3u8Url ->
            // Decodifica sequências de escape
            val cleanUrl = m3u8Url.replace("\\/", "/")
            
            callback.invoke(
                newExtractorLink(
                    "RogerioBetin",
                    "RogerioBetin", 
                    cleanUrl,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                }
            )
        }
    } catch (e: Exception) {
        // Se não conseguir extrair, fallback para loadExtractor
        loadExtractor(url, referer, {}, callback)
    }
}
}



