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
        val home     = document.select("#archive-content article").mapNotNull { it.toSearchResult() }
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
        val posterUrl = cacheImg(fixUrlNull(this.selectFirst("img")?.getImageAttr()))
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
            val image = cacheImg(it.selectFirst("img")!!.attr("src")
            Log.d("EstrenosCinesaa", "$title | $href | ${it.selectFirst("div.image a span")!!.text()}")
            newMovieSearchResponse(title, href, myType){
                this.posterUrl = cacheImg(fixUrl(image))
            }            
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document    = app.get(url).documentLarge
        val title       = document.selectFirst("div.title")?.text() ?: "Desconocido"
        val poster      = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        val description = document.selectFirst("h2 ~ p.my-2")?.text()
        val tags        = document.select("a div.btn").map { it.text() }
        val year        = document.select(".span-tiempo").text().substringAfterLast(" de ").toIntOrNull()
        val epsAnchor   = document.select("div.row a[href*='/ver/']")

        return if (epsAnchor.size > 1) {
            val episodes: List<Episode>? = epsAnchor.map {
                val epPoster = it.select("img").attr("data-src")
                val epHref   = it.attr("href")

                newEpisode(epHref) {
                    this.posterUrl = epPoster
                }
            }

            newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else newMovieLoadResponse(title, url, TvType.AnimeMovie, epsAnchor.attr("href")) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).documentLarge
        document.select("#play-video a").map {
            val href = base64Decode(it.attr("data-player")).substringAfter("=")
            loadExtractor(
                href,
                "",
                subtitleCallback,
                callback
            )
        }
        return true
    }

    private fun Element.getImageAttr(): String? {
        return this.attr("data-src")
            .takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: this.attr("src").takeIf { it.isNotBlank() && it.startsWith("http") }
    }
}
