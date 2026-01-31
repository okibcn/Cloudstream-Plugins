package com.CSPlugins

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.util.*
import java.util.Calendar

class HDFull : MainAPI() {
    override var mainUrl = "https://hdfull.love"
    override var name = "HDFull"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    //  usr:yji0r4c6 pass:@1YU1kc1
    var latestCookie: Map<String, String> = mapOf(
        "language" to "es",
        "PHPSESSID" to "hqh4vktr8m29pfd1dsthiatpk0",
        "guid" to "1525945|2fc755227682457813590604c5a6717d",
    )

    override val mainPage = mainPageOf(
        "peliculas-estreno/date/" to "Estrenos Cine",
        "peliculas/date/" to "Películas",
        "series/date/" to "Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}$page", cookies = latestCookie).documentLarge
        val home     = document.select("div.view").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = !request.data.contains("estreno")
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("h5 a")?.text() ?: "Desconocido"
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val type      = if (href.contains("/pelicula")) TvType.Movie else TvType.TvSeries
        val posterUrl = fixUrlNull(this.selectFirst("a img")?.attr("src"))
        val isDub     = this.select("img[src*=/spa.], img[src*=/lat.]").isNotEmpty()
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            if (type == TvType.Movie) addDubStatus(isDub)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar"
        val csfrDoc = app.post(
            url, cookies = latestCookie, referer = "$mainUrl/buscar", data = mapOf(
                "menu" to "search",
                "query" to query,
            )
        ).document
        val csfr = csfrDoc.selectFirst("input[value*='sid']")!!.attr("value")
        Log.d("TAG", "search: $csfr")
        val document = app.post(
            url, cookies = latestCookie, referer = "$mainUrl/buscar", data = mapOf(
                "__csrf_magic" to csfr,
                "menu" to "search",
                "query" to query,
            )
        ).documentLarge
        return document.select("div.view").mapNotNull { it.toSearchResult() }
    }

    data class EpisodeJson(
        val episode: String?,
        val season: String?,
        @field:JsonProperty("date_aired") val dateAired: String?,
        val thumbnail: String?,
        val permalink: String?,
        val show: Show?,
        val id: String?,
        val title: Title?,
        val languages: List<String>? = null
    )

    data class Show(
        val title: Title?,
        val id: String?,
        val permalink: String?,
        val thumbnail: String?
    )

    data class Title(
        val es: String?,
        val en: String?
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, cookies = latestCookie).document
        val tvType = if (url.contains("pelicula")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div#summary-title")?.text() ?: ""
        val backimage =
            doc.selectFirst("div#summary-fanart-wrapper")!!.attr("style").substringAfter("url(")
                .substringBefore(")").trim()
        val poster =
            doc.selectFirst("div#summary-overview-wrapper div.show-poster img.video-page-thumbnail")!!
                .attr("src")
        val description =
            doc.selectFirst("div#summary-overview-wrapper div.show-overview div.show-overview-text")!!
                .text()
        val tags =
            doc.selectFirst("div#summary-overview-wrapper div.show-details p:contains(Género:)")
                ?.text()?.substringAfter("Género:")
                ?.split(" ")
        val year = doc.selectFirst("div#summary-overview-wrapper div.show-details p")?.text()
            ?.substringAfter(":")?.trim()
            ?.toIntOrNull()
        var episodes = if (tvType == TvType.TvSeries) {
            val sid = doc.select("script").firstOrNull { it.html().contains("var sid =") }!!.html()
                .substringAfter("var sid = '").substringBefore("';")
            doc.select("div#non-mashable div.main-wrapper div.container-wrap div div.container div.span-24 div.flickr")
                .flatMap { seasonDiv ->
                    val seasonNumber = seasonDiv.selectFirst("a img")?.attr("original-title")
                        ?.substringAfter("Temporada")?.trim()?.toIntOrNull()
                    val result = app.post(
                        "$mainUrl/a/episodes", cookies = latestCookie, data = mapOf(
                            "action" to "season",
                            "start" to "0",
                            "limit" to "0",
                            "show" to sid,
                            "season" to "$seasonNumber",

                            )
                    )
                    val episodesJson = AppUtils.parseJson<List<EpisodeJson>>(result.document.text())
                    episodesJson.amap {
                        val episodeNumber = it.episode?.toIntOrNull()
                        val epTitle = it.title?.es?.trim() ?: "Episodio $episodeNumber"
                        val epurl = "$url/temporada-${it.season}/episodio-${it.episode}"
                        newEpisode(epurl){
                            this.name = epTitle
                            this.season = seasonNumber
                            this.episode = episodeNumber
                        }
                    }
                }
        } else listOf()

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url, tvType, episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                    this.year = year
                    this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                    this.year = year
                    this.posterHeaders = mapOf("Referer" to "$mainUrl/")
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
        val doc = app.get(data, cookies = latestCookie).document
        
        val hash = doc.select("script").firstOrNull {
            it.html().contains("var ad =")
        }?.html()?.substringAfter("var ad = '")
            ?.substringBefore("';")
        
        if (!hash.isNullOrEmpty()) {
            val json = decodeHash(hash)
            
            json.amap { item ->  // Cambiar forEach por amap
                val url = getUrlByProvider(item.provider, item.code)
                
                if (url.isNotEmpty()) {

                    if (url.contains("vidmoly")) {
                        Log.d("HDFull", "SOURCE: $url")
                        decodeVidmoly(url, data, subtitleCallback,callback)
                    }else{
                        loadExtractor(url, data, subtitleCallback,callback)
                    }
                    // try {
                    //     loadExtractor(url, data, subtitleCallback) { link ->
                    //         Log.d("HDFull", "✓ Link encontrado: ${link.name}")
                    //         CoroutineScope(Dispatchers.IO).launch {
                    //             callback.invoke(
                    //                 newExtractorLink(
                    //                     name = "${item.lang}[${link.source}]",
                    //                     source = "${item.lang}[${link.source}]",
                    //                     url = link.url,
                    //                 ) {
                    //                     this.quality = link.quality
                    //                     this.type = link.type
                    //                     this.referer = link.referer
                    //                     this.headers = link.headers
                    //                     this.extractorData = link.extractorData
                    //                 }
                    //             )
                    //         }
                    //     }
                    // } catch (e: Exception) {
                    //     Log.e("HDFull", "Error: ${e.message}")
                    // }
                }
            }
        }
        return true
    }

    data class ProviderCode(
        val id: String,
        val provider: String,
        val code: String,
        val lang: String,
        val quality: String
    )

    fun decodeHash(str: String): List<ProviderCode> {
        val decodedBytes = Base64.decode(str, Base64.DEFAULT)
        val decodedString = String(decodedBytes)
        val jsonString = decodedString.substrings(14)
        return AppUtils.parseJson<List<ProviderCode>>(jsonString)
    }

    fun String.obfs(key: Int, n: Int = 126): String {
        if (key % 1 != 0 || n % 1 != 0) {
            return this
        }
        val chars = this.toCharArray()
        for (i in chars.indices) {
            val c = chars[i].code
            if (c <= n) {
                chars[i] = ((chars[i].code + key) % n).toChar()
            }
        }
        return chars.concatToString()
    }

    fun String.substrings(key: Int, n: Int = 126): String {
        if (key % 1 != 0 || n % 1 != 0) {
            return this
        }
        return this.obfs(n - key)
    }

    fun getUrlByProvider(providerIdx: String, id: String): String {
        return when (providerIdx) {
            "1" -> "https://powvideo.org/$id"
            "2" -> "https://streamplay.to/$id"
            "6" -> "https://streamtape.com/v/$id"
            "12" -> "https://gamovideo.com/$id"
            "15" -> "https://mixdrop.bz/f/$id"
            "40" -> "https://vidmoly.me/w/$id"
            else -> ""
        }
    }


    private fun String.addMarks(str: String): String {
        return this.replace(Regex("\"?$str\"?"), "\"$str\"")
    }

    private fun decodeVidmoly(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("HDfull","VIDMOLYOKI: input url=$url")
        val headers  = mapOf(
            "user-agent"     to USER_AGENT,
            "Sec-Fetch-Dest" to "iframe"
        )
        val newUrl = if(url.contains("/w/"))
            url.replaceFirst("/w/", "/embed-")+".html"
            else url
        Log.d("HDfull","VIDMOLYME: processed url=$newUrl")
        var script: String? = null;
        var attemps = 0
        while (attemps < 10 && script.isNullOrEmpty()){
            attemps++
            script = app.get( 
                newUrl,
                headers = headers,
                referer = referer,
            ).document.select("script")
                .firstOrNull { it.data().contains("sources:") }?.data()
            if(script.isNullOrEmpty())
                delay(500)
        }
        val videoData = script?.substringAfter("sources: [")
            ?.substringBefore("],")?.addMarks("file")

        val subData = script?.substringAfter("tracks: [")?.substringBefore("]")?.addMarks("file")
            ?.addMarks("label")?.addMarks("kind")

        tryParseJson<Source>(videoData)?.file?.let { m3uLink ->
            M3u8Helper.generateM3u8(
                name,
                m3uLink,
                "$mainUrl/"
            ).forEach(callback)
        }

        tryParseJson<List<SubSource>>("[${subData}]")
            ?.filter { it.kind == "captions" }?.map {
                subtitleCallback.invoke(
                    newSubtitleFile(
                        it.label.toString(),
                        fixUrl(it.file.toString())
                    )
                )
            }

    }

    private data class Source(
        @JsonProperty("file") val file: String? = null,
    )

    private data class SubSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
    )


}

