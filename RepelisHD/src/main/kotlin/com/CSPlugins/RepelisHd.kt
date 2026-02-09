package com.CSPlugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        "pelicula/page/P_A_G_E/?language=Castellano" to "Pelis Castellano",
        "pelicula/page/P_A_G_E/?language=Latino" to "Pelis Latino",
        "series/page/P_A_G_E/?language=Castellano" to "Series Castellano",
        "series/page/P_A_G_E/?language=Latino" to "Series Latino",
    )

    companion object {
        fun getType(t: String): TvType = when {
            t.uppercase().contains("TV")  -> TvType.TvSeries
            else                          -> TvType.Movie
        }
        fun cacheImg(t: String?): String = "https://wsrv.nl/?url=${t}"
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
        val posterUrl = cacheImg(fixUrlNull(this.selectFirst("div.poster img")?.attr("src")))
        val year = this.selectFirst("div.data span")?.text()?.toIntOrNull()
        val type = getType(this.attr("class"))
        
        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?do=search&subaction=search&story=$query").document
        return document.select("div.items article").mapNotNull { it.toSearchResult() }
    }

 override suspend fun load(url: String): LoadResponse {
    Log.d("CS3debug", "load() - URL recibida: $url")
    val document = app.get(url).document
    val title = document.selectFirst("div.sheader div.data h1")?.text()?.trim() ?: "Desconocido"
    Log.d("CS3debug", "load() - Título: $title")
    
    val poster = document.selectFirst("div.sheader div.poster img")?.attr("src")
        ?.let { cacheImg(fixUrl(it)) }
    val description = document.selectFirst("div.wp-content, p.texto")?.text()?.trim()
    val year = document.selectFirst("div.extra span")?.text()?.trim()?.toIntOrNull()
    
    // Detectar tipo: si tiene temporadas, es serie
    val seasonDivs = document.select("div[id^='season-']")
    Log.d("CS3debug", "load() - Temporadas encontradas: ${seasonDivs.size}")
    val type = if (seasonDivs.isNotEmpty()) TvType.TvSeries else TvType.Movie
    Log.d("CS3debug", "load() - Tipo detectado: $type")
    
    return when (type) {
        TvType.TvSeries -> {
            val episodes = seasonDivs.flatMap { seasonDiv ->
                val seasonId = seasonDiv.attr("id")
                val seasonNum = seasonId.replace("season-", "").toIntOrNull()
                Log.d("CS3debug", "load() - Procesando $seasonId (Season $seasonNum)")
                
                seasonDiv.select("ul li").mapNotNull { li ->
                    val mainLink = li.selectFirst("a[data-num]") ?: return@mapNotNull null
                    val epNum = mainLink.attr("data-num") // "1x1", "2x5"
                    val epTitle = mainLink.attr("data-title")
                    
                    // Extraer temporada y episodio de "1x1"
                    val (season, episode) = Regex("""(\d+)x(\d+)""").find(epNum)?.let {
                        it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
                    } ?: (seasonNum to null)
                    
                    // Recolectar todos los mirrors (enlaces alternativos)
                    val mirrors = li.select("div.mirrors a[data-link]").mapNotNull { mirror ->
                        mirror.attr("data-link").takeIf { it.isNotBlank() }
                    }
                    
                    Log.d("CS3debug", "load() - Episodio $epNum: ${mirrors.size} mirrors encontrados")
                    
                    // Construir data: "SERIES|url1|url2|url3" (usar | como separador más seguro)
                    val dataUrl = " TV |" + mirrors.joinToString(" ")
                    
                    Log.d("CS3debug", "load() - Data para $epNum: ${dataUrl.take(100)}")
                    
                    newEpisode(dataUrl) {
                        this.name = epTitle
                        this.season = season
                        this.episode = episode
                    }
                }
            }
            
            Log.d("CS3debug", "load() - Total episodios creados: ${episodes.size}")
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
        TvType.Movie -> {
            // Extraer iframe src como data para la película
            val iframeSrc = document.selectFirst("iframe")?.attr("src") ?: url
            Log.d("CS3debug", "load() - Movie iframe: $iframeSrc")
            
            newMovieLoadResponse(title, url, TvType.Movie, iframeSrc) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
        else -> throw ErrorLoadingException("Tipo desconocido")
    }
}

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    Log.d("CS3debug", "loadLinks() - Data recibida: ${data}")
    
    if (data.contains("TV |")) {
        // Series: "mainurl/TV |url1 url2 url3"
        val urls = data.substringAfter("TV |").split(" ").filter { it.isNotBlank() }
        Log.d("CS3debug", "loadLinks() - Serie detectada, ${urls.size} URLs a procesar")
        
        urls.forEachIndexed { index, url ->
            Log.d("CS3debug", "loadLinks() - URL[$index]: $url")
        }
        
        urls.amap { url ->
            try {
                Log.d("CS3debug", "loadLinks() - Procesando extractor para: $url")
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("CS3debug", "loadLinks() - Error con $url: ${e.message}")
            }
        }
    } else {
        // Películas: iframe URL
        Log.d("CS3debug", "loadLinks() - Película detectada, cargando iframe")
        val document = app.get(data).document
        
        // Extraer todos los mirrors de todos los idiomas
        val allMirrors = document.select("ul._player-mirrors li[data-link]").mapNotNull { li ->
            val link = li.attr("data-link").let {
                if (it.startsWith("//")) "https:$it" else it
            }
            val language = li.parent()?.className()?.split(" ")?.firstOrNull { 
                it in listOf("latino", "castellano", "subtitulado") 
            } ?: "unknown"
            
            link to language
        }
        
        Log.d("CS3debug", "loadLinks() - Película: ${allMirrors.size} mirrors encontrados")
        
        // Procesar todos los enlaces en paralelo
        allMirrors.amap { (url, lang) ->
            try {
                Log.d("CS3debug", "loadLinks() - Procesando [$lang]: $url")
                loadExtractor(url, data, subtitleCallback) { link ->
                    CoroutineScope(Dispatchers.IO).launch {
                        callback(
                            newExtractorLink(
                                name = "${lang.replaceFirstChar { it.uppercase() }} [${link.source}]",
                                source = "${lang.replaceFirstChar { it.uppercase() }} [${link.source}]",
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
            } catch (e: Exception) {
                Log.e("CS3debug", "loadLinks() - Error con [$lang] $url: ${e.message}")
            }
        }
    }
    
    Log.d("CS3debug", "loadLinks() - Finalizando")
    return true
}

}
