package com.CSPlugins

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*


class GnulaHDProvider : MainAPI() {

    override var mainUrl = "https://ww3.GnulaHD.nu"
    override var name = "GnulaHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
            TvType.Movie,
            TvType.TvSeries,
            TvType.Anime,
    )

    companion object {
        fun getType(t: String): TvType = when {
            t.contains("Serie")     -> TvType.TvSeries
            t.contains("Pelicula")  -> TvType.Movie
            t.contains("Anime")     -> TvType.Anime
            else                    -> TvType.TvSeries
        }
    }


    private val cloudflareKiller = CloudflareKiller()
    suspend fun appGetChildMainUrl(url: String): NiceResponse {
        // return app.get(url, interceptor = cloudflareKiller )
        return app.get(url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/ver/?type=Pelicula&order=latest", "Novedades Peliculas"),
            Pair("$mainUrl/ver/?status=&type=Serie&order=latest", "Novedades Series"),
            Pair("$mainUrl/ver/?status=&type=Anime&order=latest", "Novedades Anime"),
        )

        val items = ArrayList<HomePageList>()
        val isHorizontal = true

        urls.amap { (url, name) ->
            val home = appGetChildMainUrl(url).document.select("div.postbody article.bs").map {
                val title = it.selectFirst("a")!!.attr("title")
                val imgElement = it.selectFirst("a div.limit > img")
                val poster = imgElement?.attr("src") ?: ""

                newMovieSearchResponse(title, fixUrl(it.selectFirst("a")!!.attr("href"))) {
                    this.posterUrl = fixUrl(poster)
                    this.posterHeaders = if (poster.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
                }
            }

            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return appGetChildMainUrl("$mainUrl/?s=$query").document.select("div.postbody article.bs").map {
            val title = it.selectFirst("a")!!.attr("title")+" ("+ it.selectFirst("div.typez")!!.text() +")"
            val href = fixUrl(it.selectFirst("a")!!.attr("href"))
            val image = it.selectFirst("a div.limit > img")!!.attr("src")
            newMovieSearchResponse(title, href, getType(it.selectFirst("div.typez")!!.text())){
                this.posterUrl = fixUrl(image)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = appGetChildMainUrl(url).document
        val title = doc.selectFirst("div.postbody h1.entry-title")!!.text()
        val type = doc.select("span:has(b:matchesOwn(^Tipo:))").first()?.ownText()?.trim() ?: ""
        val poster = doc.selectFirst("div.postbody div.thumb img")!!.attr("src")
        val backimage = doc.selectFirst("div.postbody div.thumb img")!!.attr("src")
        val premiereYear: Int? = doc.select("span.split:has(b:matchesOwn(^Estreno:))").first()?.ownText()?.trim()?.takeLast(4)?.toIntOrNull()
        val description = doc.select("div.mindesc h3:first-of-type, div.mindesc p")
            .takeWhile { it.text() != "¿Para quién es?" } // stop when hitting the second h3
            .joinToString("\n") { it.text() }
        val genres = doc.select("div.postbody div.genxed a").map { it.text() }
        val status = null

        // Log.d("depurando", "load: $title")
        val episodes = doc.select("div.postbody div.eplister a").map {
                val name = it.selectFirst("div.epl-title")!!.text()
                val link = it.attr("href")
                val epThumb = null 
                // Log.d("depurando", "load: link $link")
                // val html = appGetChildMainUrl(link).document.selectFirst("div.player-embed > iframe")!!.attr("src").orEmpty()
                // Log.d("depurando", "load: subdoc $html")
                newEpisode(link){
                    this.name = name
                }
            }
            .sortedBy { it.name }

        return newAnimeLoadResponse(title, url, getType(type)) {
            posterUrl = poster
            backgroundPosterUrl = backimage
            addEpisodes(DubStatus.Dubbed, episodes)
            showStatus = status
            plot = description
            tags = genres
            year = premiereYear
            posterHeaders = if (poster.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = appGetChildMainUrl(data).document.selectFirst("div.player-embed > iframe")!!.attr("src")
        // Log.d("depurando", "loadLinks: embedUrl ${embedUrl.orEmpty()}")
        // val html = appGetChildMainUrl(embedUrl).document.selectFirst("div.columns")?.html().orEmpty().replace("\n", " ")
        // Log.d("depurando", "loadLinks: $html")
        val regex = Regex("""\?id=([^"]+)""")
        regex.findAll(appGetChildMainUrl(embedUrl).document.html().orEmpty())
            .map { it.groupValues[1] }
            .map { base64Decode(it) }
            .forEach { decodedUrl ->
                Log.d("depurando", "loadLinks: urlDecoded=$decodedUrl")
                loadExtractor(decodedUrl, mainUrl, subtitleCallback, callback)
            }
        return true
    }
}