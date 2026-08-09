package com.CSPlugins

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.app

@CloudstreamPlugin
class HDFullPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(HDFull())
        registerExtractorAPI(MyVidmoly())
    }
}

// package com.CSPlugins

// import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
// import com.lagradost.cloudstream3.plugins.Plugin
// import android.content.Context

// @CloudstreamPlugin
// class HDFullPlugin: Plugin() {
//     override fun load(context: Context) {
//         // All providers should be added in this manner. Please don't edit the providers list directly.
//         registerMainAPI(HDFull())
//         registerExtractorAPI(MyVidmoly())
//     }
// }