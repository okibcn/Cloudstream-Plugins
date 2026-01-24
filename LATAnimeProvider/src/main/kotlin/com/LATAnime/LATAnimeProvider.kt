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
 
 
 
    // override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    //     val urls = listOf(
    //             Pair("$mainUrl/emision", "Novedades"),
    //             Pair("$mainUrl/buscar?q=Castellano", "Anime Castellano"),
    //             Pair("$mainUrl/buscar?q=Latino", "Anime Latino"),
    //             Pair("$mainUrl/animes?categoria=Película+Castellano", "Peliculas Castellano"),
    //             Pair("$mainUrl/animes?categoria=Película+Latino", "Peliculas Latino"),
    //             Pair("$mainUrl/animes?categoria=castellano", "Series Castellano"),
    //             Pair("$mainUrl/animes?categoria=latino", "Series Latino"),
    //             Pair("$mainUrl/animes", "Todo el Anime"),
    //             Pair("$mainUrl/animes?categoria=ova", "OVA"),
    //             Pair("$mainUrl/animes?categoria=sin-censura", "Sin Censura"),
    //     )

    //     val items = ArrayList<HomePageList>()
    //     val isHorizontal = true

    //     urls.amap { (url, name) ->
    //         val home = wGet(url).document.select("html body div.container div.row div.col-md-4.col-lg-3.col-xl-2.col-6.my-3").map {
    //             val title = it.selectFirst("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3 a div.series div.seriedetails h3.my-1")!!.text()
    //             val imgElement = it.selectFirst("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3 a div.series div.serieimg.shadown img.img-fluid2.shadow-sm")
    //             val poster =
    //                     if(imgElement?.attr("data-src")?.isEmpty() == false)
    //                         imgElement.attr("data-src") else
    //                             imgElement?.attr("src") ?: ""

    //             newAnimeSearchResponse(title, fixUrl(it.selectFirst("a")!!.attr("href"))) {
    //                 this.posterUrl = fixUrl(poster)
    //                 addDubStatus(getDubStatus(title))
    //                 this.posterHeaders = if (poster.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
    //             }
    //         }

    //         items.add(HomePageList(name, home))
    //     }
    //     if (items.size <= 0) throw ErrorLoadingException()
    //     return newHomePageResponse(items)
    // }

//     override suspend fun search(query: String): List<SearchResponse> {
//         return wGet("$mainUrl/buscar?q=$query").document.select("html body div.container div.row div.col-md-4.col-lg-3.col-xl-2.col-6.my-3").map {
//             val title = it.selectFirst("a div.series div.seriedetails h3.my-1")!!.text()
//             val href = fixUrl(it.selectFirst("a")!!.attr("href"))
//             val image = it.selectFirst("a div.series div.serieimg.shadown img.img-fluid2.shadow-sm")!!.attr("src")
//             newAnimeSearchResponse(title, href, TvType.Anime){
//                 this.posterUrl = fixUrl(image)
//                 this.dubStatus = if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
//                             DubStatus.Dubbed
//                     ) else EnumSet.of(DubStatus.Subbed)
// //                this.posterHeaders = if (image.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
//             }
//         }
//     }

//     override suspend fun load(url: String): LoadResponse? {
//         val doc = wGet(url).document
//         val poster = doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha img.img-fluid2")!!.attr("src")
//         val backimage = doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha img.img-fluid2")!!.attr("src")
//         val title = doc.selectFirst("div.col-lg-9.col-md-8 h2")!!.text()
//         val type = doc.selectFirst("div.chapterdetls2")?.text() ?: ""
//         val description = doc.selectFirst("div.col-lg-9.col-md-8 p.my-2.opacity-75")!!.text()
//         val genres = doc.select("div.col-lg-9.col-md-8 a div.btn").map { it.text() }
//         val status = when (doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha div.my-2")?.text()) {
//             "Estreno" -> ShowStatus.Ongoing
//             "Finalizado" -> ShowStatus.Completed
//             else -> null
//         }
//         // Log.d("depurando", "load: title $title")
//         val episodes = doc.select("div.container div.row div.row div a").map {
//             val name = it.selectFirst("div.cap-layout")!!.text()
//             val link = it!!.attr("href")
//             // Log.d("depurando", "load: link $link")
//             val epThumb = it.selectFirst(".animeimghv")?.attr("data-src")
//                     ?: it.selectFirst("div.animeimgdiv img.animeimghv")?.attr("src")
//             newEpisode(link){
//                 this.name = name
//             }
//         }
//         return newAnimeLoadResponse(title, url, getType(title)) {
//             posterUrl = poster
//             backgroundPosterUrl = backimage
//             addEpisodes(DubStatus.Subbed, episodes)
//             showStatus = status
//             plot = description
//             tags = genres
//             posterHeaders = if (poster.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
//         }
//     }

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

//     override suspend fun loadLinks(
//             data: String,
//             isCasting: Boolean,
//             subtitleCallback: (SubtitleFile) -> Unit,
//             callback: (ExtractorLink) -> Unit
//     ): Boolean {
//         wGet(data).document.select("li#play-video").amap {
//             val encodedurl = it.select("a").attr("data-player")
//             val urlDecoded = base64Decode(encodedurl)
//             // Log.d("depurando", "urlDecoded: $urlDecoded")
//             val url = (urlDecoded)
//                 .replace("https://monoschinos2.com/reproductor?url=", "")
//                 .replace("https://mojon.latanime.org/aqua/fn?url=", "")
//             customLoadExtractor(url, mainUrl, subtitleCallback, callback)
//         }
//         return true
//     }
// }