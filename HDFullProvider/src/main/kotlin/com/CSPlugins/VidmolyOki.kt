package com.CSPlugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.delay

import android.util.Log


class VidmolyOki : ExtractorApi() {
    override val name = "Vidmolyme"
    override val mainUrl = "https://vidmoly.me"
    override val requiresReferer = true

    private fun String.addMarks(str: String): String {
        return this.replace(Regex("\"?$str\"?"), "\"$str\"")
    }

    override suspend fun getUrl(
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
