package com.CSPlugins

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*


class GnulaHDProvider : MainAPI() {

    override var mainUrl = "https://ww3.gnulahd.nu"
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

    override val mainPage = mainPageOf(
        "ver/?type=Pelicula&order=latest" to "Pelis",
        "ver/?status=&type=Serie&order=latest" to "Series",
        "ver/?status=&type=Anime&order=latest" to "Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page").documentLarge
        val home     = document.select("div.postbody article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val type      = getType(this.selectFirst("div.typez")!!.text())
        val posterUrl = fixUrlNull(this.selectFirst("a div.limit > img")?.attr("src") ?: "")
        var rawTitle  = this.selectFirst("a")?.attr("title") ?: "Desconocido"
        val langs     = this.select("div.caratula-flags-badge img")
            .mapNotNull { img -> img.attr("title").take(3) }
            .joinToString("/")
        val title = if (langs.isNotEmpty()) "$rawTitle [$langs]" else "$rawTitle"

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    private val cloudflareKiller = CloudflareKiller()
    suspend fun appGetChildMainUrl(url: String): NiceResponse {
        // return app.get(url, interceptor = cloudflareKiller )
        return app.get(url)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document.select("div.postbody article.bs").map {
            val rawTitle = it.selectFirst("a")!!.attr("title")
            val rawType  = it.selectFirst("div.typez")!!.text()
            val type     = getType(rawType)
            val href     = fixUrl(it.selectFirst("a")!!.attr("href"))
            val image    = it.selectFirst("a div.limit > img")!!.attr("src")
            val langs    = it.select("div.caratula-flags-badge img")
                .mapNotNull { img -> img.attr("title").take(3) }
                .joinToString("/")
            val title = "($rawType $langs) $rawTitle"
            newMovieSearchResponse(title, href, getType(it.selectFirst("div.typez")!!.text())){
                this.posterUrl = fixUrl(image)
            }
        }
    }

override suspend fun load(url: String): LoadResponse? {
    val doc = app.get(url).document
    val title = doc.selectFirst("div.postbody h1.entry-title")!!.text()
    val type = doc.select("span:has(b:matchesOwn(^Tipo:))").first()?.ownText()?.trim() ?: ""
    val poster = doc.selectFirst("div.postbody div.thumb img")!!.attr("src")
    val backimage = doc.selectFirst("div.postbody div.thumb img")!!.attr("src")
    val premiereYear: Int? = doc.select("span.split:has(b:matchesOwn(^Estreno:))").first()?.ownText()?.trim()?.takeLast(4)?.toIntOrNull()
    val description = doc.select("div.mindesc h3:first-of-type, div.mindesc p")
        .takeWhile { it.text() != "¿Para quién es?" }
        .joinToString("\n") { it.text() }
    val genres = doc.select("div.postbody div.genxed a").map { it.text() }
    val status = null

    val episodes = doc.select("div.postbody div.eplister a").mapNotNull {
        val name = it.selectFirst("div.epl-title")?.text() ?: return@mapNotNull null
        val link = it.attr("href")
        // Extraer temporada y episodio del formato "2x06"
        val epNum = it.selectFirst("div.epl-num")?.text()?.trim() ?: ""
        val parts = epNum.split("x")
        val season = parts.getOrNull(0)?.toIntOrNull()
        val episode = parts.getOrNull(1)?.toIntOrNull()
        
        newEpisode(link) {
            this.name = name
            this.season = season
            this.episode = episode
        }
    }

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
        val embedUrl = appGetChildMainUrl(data).document
            .selectFirst("div.player-embed > iframe")?.attr("src") ?: return false
        
        val script = appGetChildMainUrl(embedUrl).document
            .select("script")
            .firstOrNull { it.data().contains("var videosOriginal") }
            ?.data() ?: return false
        
        // Construir lista de (URL, idioma)
        val urlsWithLang = mapOf(
            "videosOriginal" to "VO",
            "videosLatino" to "Lat",
            "videosCastellano" to "Cas",
            "videosSubtitulado" to "Sub"
        ).flatMap { (varName, langCode) ->
            Regex("""var $varName = (\[.*?\]);""")
                .find(script)?.groupValues?.get(1)?.let { arrayContent ->
                    Regex("""\?id=([^"]+)""")
                        .findAll(arrayContent)
                        .map { match -> 
                            base64Decode(match.groupValues[1]) to langCode 
                        }
                        .toList()
                } ?: emptyList()
        }
        
        // Procesar todas las URLs en paralelo
        urlsWithLang.amap { (decodedUrl, langCode) ->
            try {
                loadExtractor(decodedUrl, mainUrl, subtitleCallback) { link ->
                    CoroutineScope(Dispatchers.IO).launch {
                        callback(
                            newExtractorLink(
                                name = "$langCode [${link.source}]",
                                source = "$langCode [${link.source}]",
                                url = link.url,
                            ) {
                                this.quality = link.quality
                                this.type = link.type
                                this.referer = link.referer
                                this.headers = link.headers
                                this.extractorData = link.extractorData
                            }
                        )
                    }
                }
            } catch (_: Exception) {}
        }
        
        return true
    }

}