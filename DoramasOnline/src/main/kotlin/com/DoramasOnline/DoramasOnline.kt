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

// A classe principal do seu plugin, que herda de MainAPI
class DoramasOnline : MainAPI() {

    // Define a URL principal, o nome e outros metadados do plugin
    override var mainUrl = "https://doramasonline.org"
    override var name = "Doramas Online"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Define as categorias da página principal
    override val mainPage = mainPageOf(
        "doramas" to "Doramas",
        "filmes" to "Filmes"
    )

    // Função para carregar os itens da página principal
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

        // Itera sobre cada item de filme ou dorama
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

        // Verifica se há uma próxima página
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
                
                // Determina o tipo de conteúdo (Série ou Filme)
                val type = when (typeElement?.classNames()) {
                    setOf("tvshows") -> TvType.TvSeries
                    setOf("movies") -> TvType.Movie
                    else -> TvType.TvSeries
                }
                
                // Usa as funções de ajuda para criar a resposta de busca
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

                    // Extrai o número do episódio usando uma expressão regular
                    val episodeNumberString = Pattern.compile("(\\d+)")
                        .matcher(episodeName)
                        .let { if (it.find()) it.group(1) else null }
                    
                    val episodeNumber = episodeNumberString?.toIntOrNull() ?: 0
                    
                    allEpisodes.add(
                        newEpisode(episodeUrl) {
                            name = episodeName
                            episode = episodeNumber
                            season = seasonNumber // Adiciona o número da temporada ao episódio
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

    // Função para carregar os links de streaming
    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val mainPageHtml = app.get(data).text
    val playerUrlMatch = Regex("""<iframe[^>]*src=["'](https?:\/\/[^'"]*doramasonline\.org\/cdn[^'"]*)["']""").find(mainPageHtml)

    if (playerUrlMatch == null) {
        return false
    }

    val playerUrl = playerUrlMatch.groupValues[1]

    val playerHtml = app.get(playerUrl, headers = mapOf("Referer" to data)).text


    val videoUrlMatch = Regex("""file\s*:\s*['"](https?:\/\/[^'"]+)['"]""").find(playerHtml)
    
    if (videoUrlMatch == null) {
        return false
    }

    val videoUrl = videoUrlMatch.groupValues[1]
    
    // Invoca a função de callback com o link do vídeo encontrado.
    callback.invoke(
        newExtractorLink(
            "DoramaOnline",
            "DoramaOnline",
            videoUrl,
            ExtractorLinkType.VIDEO
        )
    )
    
    return true
}
}
