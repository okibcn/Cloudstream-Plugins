package com.CSPlugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import android.util.Log
// import com.lagradost.cloudstream3.network.CloudflareKiller
// import com.lagradost.nicehttp.NiceResponse
// import java.util.*


class LATAnimeProvider : MainAPI() {
    override var mainUrl              = "https://latanime.org"
    override var name                 = "LATAnime"
    override val hasMainPage          = true
    override var lang                 = "es-mx"
    override val hasDownloadSupport   = true
    override val hasQuickSearch       = true
    override val supportedTypes = setOf(
            TvType.AnimeMovie,
            TvType.OVA,
            TvType.Anime,
    )
    override val mainPage = mainPageOf(
        "emision" to "Novedades",
        "animes?categoria=castellano" to "Series Castellano",
        "animes?categoria=latino" to "Series Latino",
        "animes?categoria=Película+Castellano" to "Películas Castellano",
        "animes?categoria=Película+Latino" to "Películas Latino",
        "animes?categoria=especial" to "Especial",
        "animes?categoria=donghua" to "Donghua",
        "genero/ecchi" to "Sin Censura",
        "animes" to "Todo el Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep      = if (request.data.contains('?')) "&" else "?"
        val pagedUrl = "$mainUrl/${request.data}${sep}p=$page"
        val document = app.get(pagedUrl).documentLarge
        val home     = document.select("div.row a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title     = this.select("h3").text()
        val href      = this.attr("href")
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())
        val isDub     = title.contains("Latino") || title.contains("Castellano")
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(isDub)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/buscar?q=$query").documentLarge
        return document.select("div.row a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document    = app.get(url).documentLarge
        val title       = document.selectFirst("h2")?.text() ?: "Desconocido"
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
        // concurrent loadExtractor calls
        document.select("#play-video a").amap { element ->
            val href = base64Decode(element.attr("data-player")).substringAfter("=")
            loadExtractor(
                href,
                "",
                subtitleCallback,
                callback
            )
        }
        return true
    }

    // override suspend fun loadLinks(
    //     data: String,
    //     isCasting: Boolean,
    //     subtitleCallback: (SubtitleFile) -> Unit,
    //     callback: (ExtractorLink) -> Unit
    // ): Boolean {
    //     val document = app.get(data).documentLarge
    //     document.select("#play-video a").map {
    //         val href = base64Decode(it.attr("data-player")).substringAfter("=")
    //         loadExtractor(
    //             href,
    //             "",
    //             subtitleCallback,
    //             callback
    //         )
    //     }
    //     return true
    // }

    private fun Element.getImageAttr(): String? {
        return this.attr("data-src")
            .takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: this.attr("src").takeIf { it.isNotBlank() && it.startsWith("http") }
    }
}

//     suspend fun customLoadExtractor(
//         url: String,
//         referer: String?,
//         subtitleCallback: (SubtitleFile) -> Unit,
//         callback: (ExtractorLink) -> Unit)
//     {
//         loadExtractor(url
//             .replaceFirst("https://hglink.to", "https://streamwish.to")
//             .replaceFirst("https://swdyu.com","https://streamwish.to")
//             .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
//             .replaceFirst("https://filemoon.link", "https://filemoon.sx")
//             .replaceFirst("https://sblona.com", "https://watchsb.com")
//             .replaceFirst("https://cybervynx.com", "https://streamwish.to")
//             .replaceFirst("https://dumbalag.com", "https://streamwish.to")
//             .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
//             .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
//             .replaceFirst("https://lulu.st", "https://lulustream.com")
//             .replaceFirst("https://uqload.io", "https://uqload.com")
//             .replaceFirst("https://do7go.com", "https://dood.la")
//             , referer, subtitleCallback, callback)
//     }
