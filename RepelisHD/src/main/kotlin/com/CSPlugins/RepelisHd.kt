package com.CSPlugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.util.*

import android.util.Log

class RepelisHd : MainAPI() {
    override var mainUrl              = "https://repelishd.city"
    override var name                 = "RepelisHD"
    override val hasMainPage          = true
    override var lang                 = "es-es"
    override val hasDownloadSupport   = true
    override val hasQuickSearch       = true
    override val supportedTypes = setOf(
            TvType.Movie,
            TvType.TvSeries,
    )
    override val mainPage = mainPageOf(
        "cine/page/P_A_G_E/" to "Estrenos de Cine",
        "series/page/P_A_G_E/" to "Series Actualizadas",
        "https://repelishd.city/pelicula/page/P_A_G_E/?language=Castellano" to "Pelis Castellano",
        "https://repelishd.city/pelicula/page/P_A_G_E/?language=Latino" to "Pelis Latino",
        "https://repelishd.city/series/page/P_A_G_E/?language=Castellano" to "Series Castellano",
        "https://repelishd.city/series/page/P_A_G_E/?language=Latino" to "Series Latino",
    )

    companion object {
        fun getType(t: String): TvType = when {
            t.uppercase().contains("TV")  -> TvType.TvSeries
            else                          -> TvType.Movie
        }
        fun cacheImg(t: String): String = "https://wsrv.nl/?url=${t}"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data.replace("P_A_G_E","$page")}").documentLarge
        val home     = document.select("div.items article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data h3 a")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.poster img")?.attr("src"))
        val year = this.selectFirst("div.data span")?.text()?.toIntOrNull()
        val type = getType(this.attr("class"))
        
        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.items article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document    = app.get(url).documentLarge
        val title       = document.selectFirst("h1")?.text() ?: "Desconocido"
        val poster      = cacheImg(fixUrl(document.selectFirst("div.sheader img")!!.attr("src")))
        val backimage   = cacheImg(fixUrl(document.selectFirst("div.g-item a")?.attr("href") ?: ""))
        val description = document.selectFirst("div.wp-content")?.text()
        val year        = document.selectFirst("div.sheader div.data span.date")?.text()
            ?.split(" ")?.lastOrNull()?.toIntOrNull()
        val type        = if (document.selectFirst("div.single_tabs a")?.text()?.contains("Episodios") == true)
            TvType.TvSeries else TvType.Movie

        return when (type) {
            TvType.TvSeries -> {
                // Extraer episodios de ul.episodios li
                val episodes = document.select("ul.episodios li").mapNotNull { li ->
                    val epLink = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val epPoster = li.selectFirst("img")?.attr("src")?.let { cacheImg(fixUrl(it)) }
                    val epName = li.selectFirst("a")?.text()?.trim()
                    val numerando = li.selectFirst("div.numerando")?.text()?.trim() // "1 - 3"
                    
                    // Extraer temporada y episodio del numerando
                    val (season, episode) = numerando?.split("-")?.map { it.trim().toIntOrNull() }
                        ?.let { it.getOrNull(0) to it.getOrNull(1) } ?: (null to null)
                    
                    newEpisode(epLink) {
                        this.name = epName
                        this.season = season
                        this.episode = episode
                        this.posterUrl = epPoster
                    }
                }
                
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.year = year
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.year = year
                }
            }
            else -> throw ErrorLoadingException("Unknown TvType")
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Extraer iframes excluyendo el trailer
        val iframeSources = document.select("div#dooplay_player_content div.source-box:not(#source-player-trailer) iframe")
            .mapNotNull { it.attr("src").takeIf { url -> url.isNotBlank() } }
        
        // Procesar todos los iframes en paralelo
        iframeSources.amap { iframeUrl ->
            try {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            } catch (e: Exception) {
                // Ignorar errores de extractores individuales
            }
        }
        
        return true
    }
}
