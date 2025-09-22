package com.DoramasOnline

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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

    // Função para realizar a busca por filmes e doramas
    override suspend fun search(query: String): List<SearchResponse> {
        val searchApiUrl = "$mainUrl/wp-json/dooplay/search/?q=$query"
        val response = app.get(searchApiUrl)
        val jsonObject = JSONObject(response.text)
        val jsonArray = jsonObject.optJSONArray("results") ?: return emptyList()

        return (0 until jsonArray.length()).mapNotNull { i ->
            val item = jsonArray.optJSONObject(i)
            val title = item?.optString("title") ?: ""
            val url = item?.optString("url") ?: ""
            val type = item?.optString("type")
            val posterUrl = item?.optString("img") ?: ""

            if (title.isNotEmpty() && url.isNotEmpty()) {
                if (type == "movie") {
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = fixUrl(posterUrl)
                    }
                } else {
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = fixUrl(posterUrl)
                    }
                }
            } else {
                null
            }
        }
    }

    // Função para carregar os detalhes de um filme ou dorama
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: document.title()
        val plot = document.selectFirst("meta[name=description]")?.attr("content")
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val type = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie

        val episodes = mutableListOf<Episode>()

        // Se for uma série, busca os episódios na API do site
        if (type == TvType.TvSeries) {
            val scriptTag = document.selectFirst("script:containsData(dtGonza)")
            val dtGonzaString = scriptTag?.data() ?: ""
            val postId = Pattern.compile("(?<=post_id\":)[0-9]+")
                .matcher(dtGonzaString)
                .let { if (it.find()) it.group(0) else null }

            if (postId != null) {
                val apiRequestUrl = "$mainUrl/wp-json/dooplay/v2/?action=dt_episodes&id=$postId"
                val apiResponse = app.get(apiRequestUrl)
                val jsonObject = JSONObject(apiResponse.text)
                val episodesHtml = jsonObject.optString("html")
                if (episodesHtml != null) {
                    val episodeDoc = Jsoup.parse(episodesHtml)
                    episodeDoc.select("li").forEach {
                        val episodeUrl = it.selectFirst("a")?.attr("href") ?: return@forEach
                        val episodeNumber = it.selectFirst("a")?.text()?.toIntOrNull() ?: 0
                        val episodeName = it.selectFirst("a")?.attr("title") ?: ""
                        episodes.add(
                            newEpisode(episodeUrl) {
                                name = episodeName
                                episode = episodeNumber
                            }
                        )
                    }
                }
            }
        }

        return if (type == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.plot = plot
                // Alteração aqui: usa fixUrlNull para lidar com poster nulo
                this.posterUrl = fixUrlNull(poster)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.plot = plot
                // Alteração aqui: usa fixUrlNull para lidar com poster nulo
                this.posterUrl = fixUrlNull(poster)
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
        val document = app.get(data).document
        val scriptTag = document.selectFirst("script:containsData(dtGonza)")
        val dtGonzaString = scriptTag?.data() ?: ""
        val postId = Pattern.compile("(?<=post_id\":)[0-9]+")
            .matcher(dtGonzaString)
            .let { if (it.find()) it.group(0) else null }

        if (postId != null) {
            val apiRequestUrl = "$mainUrl/wp-json/dooplay/v2/?action=dt_players&post=$postId"
            val apiResponse = app.get(apiRequestUrl)
            val jsonObject = JSONObject(apiResponse.text)
            val playersHtml = jsonObject.optString("html")

            if (playersHtml != null) {
                val playersDoc = Jsoup.parse(playersHtml)
                playersDoc.select("iframe").forEach {
                    val playerUrl = it.attr("src")
                    if (playerUrl.isNotEmpty()) {
                        loadExtractor(playerUrl, data, subtitleCallback, callback)
                    }
                }
                return true
            }
        }
        return false
    }
}
