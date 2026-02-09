package com.CSPlugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import android.util.Log
import java.util.*

class EstrenosCinesaa : MainAPI() {
    override var mainUrl              = "https://www.estrenoscinesaa.com"
    override var name                 = "EstrenosCinesaa"
    override val hasMainPage          = true
    override var lang                 = "es-es"
    override val hasDownloadSupport   = true
    override val hasQuickSearch       = true
    override val supportedTypes = setOf(
            TvType.Movie,
            TvType.TvSeries,
    )
    override val mainPage = mainPageOf(
        "movies" to "Películas",
        "tvshows" to "Series",
        "genre/marvel" to "Marvel",
        "genre/starwars" to "Star Wars",
        "genre/netflix" to "Netfix",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page").documentLarge
        Log.d("EstrenosCinesaa", " ")
        Log.d("EstrenosCinesaa", "${request.name} | $mainUrl/${request.data}/page/$page")
        val home     = document.select("#archive-content article, div.items > article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    companion object {
        fun getType(t: String): TvType = when {
            t.uppercase().contains("TV")  -> TvType.TvSeries
            else                          -> TvType.Movie
        }
        fun cacheImg(t: String): String = "https://wsrv.nl/?url=${t}"
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title     = this.selectFirst("h3")!!.text()
        val href      = this.selectFirst("a")!!.attr("href")
        val posterUrl = cacheImg(fixUrl(this.selectFirst("img")!!.attr("src")))
        val myType    = getType(this.attr("class"))
        Log.d("EstrenosCinesaa", "$title | $href | ${this.attr("class")}")
        return newAnimeSearchResponse(title, href, myType) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("EstrenosCinesaa", "SEARCH | $query")
        return app.get("${mainUrl}/?s=$query").documentLarge.select("div.result-item").mapNotNull { 
            val title = it.selectFirst("div.title")!!.text()+" ("+ it.selectFirst("span.year")!!.text() +")"
            val href = fixUrl(it.selectFirst("a")!!.attr("href"))
            val myType = getType(it.selectFirst("div.image a span")!!.text())
            val image = it.selectFirst("img")!!.attr("src")
            Log.d("EstrenosCinesaa", "$title | $href | ${it.selectFirst("div.image a span")!!.text()}")
            newMovieSearchResponse(title, href, myType){
                this.posterUrl = cacheImg(fixUrl(image))
            }            
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document    = app.get(url).documentLarge
        val title       = document.selectFirst("h1")?.text() ?: "Desconocido"
        val poster      = cacheImg(fixUrl(document.selectFirst("div.sheader img")!!.attr("src")))
        val backimage   = cacheImg(fixUrl(document.selectFirst("div.g-item a")!!.attr("href")))
        val description = document.selectFirst("div.wp-content")?.text()
        // val year        = document.select("span.date").text().takeLast(4).toIntOrNull()
        val type        = if (document.selectFirst("div.single_tabs a")?.text()?.contains("Episodios"))
            TvType.TvSeries else TvType.Movie
        val epsAnchor   = document.select("div.seasons li")

        return when (type) {
            TvType.TvSeries -> {
                val episodes: List<Episode>? = epsAnchor.map {
                    val epPoster = cacheImg(fixUrl(it.selectFirst("img")?.attr("src")))
                    val epHref   = it.selectFirst("a")?.attr("href")
                    newEpisode(epHref) {
                        this.posterUrl = epPoster
                    }
                }
                newAnimeLoadResponse(title, url, TvType.TvSeries) {
                    addEpisodes(status = DubStatus.None , episodes = episodes)
                    this.posterUrl = poster
                    this.plot = description
                    // this.tags = tags
                    // this.year = year
                } ?: null
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    // this.tags = tags
                    // this.year = year
                    // this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                }
            }
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc   = app.get(data).documentLarge
        val links = doc.select("div#dooplay_player_content div.source-box:not(#source-player-trailer) iframe")
            .mapNotNull { it.attr("src") }
        // Procesar todas las URLs en paralelo
        links.amap { oneLink ->
            loadExtractor(oneLink, mainUrl, subtitleCallback)
        }
        return true
    }

    private fun Element.getImageAttr(): String? {
        return this.attr("data-src")
            .takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: this.attr("src").takeIf { it.isNotBlank() && it.startsWith("http") }
    }
}
