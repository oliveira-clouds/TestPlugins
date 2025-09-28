package com.Animeq

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class AnimeQ : MainAPI() {
    override var mainUrl = "https://animeq.blog"
    override var name = "AnimeQ"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val lang = "pt"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = mutableListOf<HomePageList>()

        // Seção de episódios recentes
        val recentEpisodes = document.select("article.episodes").mapNotNull { episode ->
            parseEpisode(episode)
        }

        if (recentEpisodes.isNotEmpty()) {
            home.add(HomePageList("Episódios Recentes", recentEpisodes))
        }

        // Seção de animes recentes
        val recentAnimes = document.select("article.tvshows").mapNotNull { anime ->
            parseAnime(anime)
        }

        if (recentAnimes.isNotEmpty()) {
            home.add(HomePageList("Animes Recentes", recentAnimes))
        }

        return HomePageResponse(home)
    }

    private fun parseEpisode(element: Element): AnimeSearchResponse? {
        return try {
            val title = element.selectFirst("h3 a")?.text() ?: return null
            val url = element.selectFirst("h3 a")?.attr("href") ?: return null
            val image = element.selectFirst("img")?.attr("src")
            val series = element.selectFirst("span.serie")?.text()
            
            AnimeSearchResponse(
                name = title,
                url = url,
                posterUrl = image,
                type = TvType.Anime,
                isDub = title.contains("dublado", ignoreCase = true) || 
                        series?.contains("dublado", ignoreCase = true) == true
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAnime(element: Element): AnimeSearchResponse? {
        return try {
            val title = element.selectFirst("h3 a")?.text() ?: return null
            val url = element.selectFirst("h3 a")?.attr("href") ?: return null
            val image = element.selectFirst("img")?.attr("src")
            
            AnimeSearchResponse(
                name = title,
                url = url,
                posterUrl = image,
                type = TvType.Anime
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item").mapNotNull { element ->
            val title = element.selectFirst("h3 a")?.text() ?: return@mapNotNull null
            val url = element.selectFirst("h3 a")?.attr("href") ?: return@mapNotNull null
            val image = element.selectFirst("img")?.attr("src")
            
            AnimeSearchResponse(
                name = title,
                url = url,
                posterUrl = image,
                type = TvType.Anime
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val description = document.selectFirst("div.entry-content")?.text()
        val poster = document.selectFirst("div.poster img")?.attr("src")
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
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
        // Implementar extração dos links de vídeo aqui
        // Normalmente você precisaria parsear o player do site
        return false
    }
}
