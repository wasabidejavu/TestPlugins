package com.senpaistream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element

class SenpaiStreamProvider : MainAPI() {
    override var mainUrl = "https://senpai-stream.hair"
    override var name = "Senpai Stream"
    override val hasMainPage = true
    override var lang = "fr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Referer" to "https://senpai-stream.hair/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "Top 10 des films aujourd’hui" to "Top 10 Films",
        "Top 10 des séries aujourd’hui" to "Top 10 Séries",
        "Nouveaux films sur Senpai-Stream" to "Nouveaux Films",
        "Nouvelles séries sur Senpai-Stream" to "Nouvelles Séries",
        "Top 10 des animés aujourd’hui" to "Top 10 Animés",
        "$mainUrl/trending" to "Tendances",
        "$mainUrl/movies" to "Films",
        "$mainUrl/tv-shows" to "Séries",
        "$mainUrl/animes" to "Animés",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = if (!request.data.startsWith("http")) {
            val document = app.get(mainUrl, headers = headers).document
            val header = document.select("h3").find { it.text().contains(request.data, ignoreCase = true) }
            
            // Try to find the container: usually the next sibling, or inside the next sibling
            // We look for the grid/swiper container following the header
            var container = header?.nextElementSibling()
            while (container != null && !container.classNames().any { it.contains("grid") || it.contains("swiper") }) {
                 container = container.nextElementSibling()
            }
            
            // If direct sibling check failed, try a broader look (e.g. parent's sibling) if needed, 
            // but for now let's assume standard structure or fallback to finding cards within the 'next' structure.
            // Actually, if we can't find a specific container, let's just grab the next element that contains our card selector.
            
            val safeContainer = container ?: header?.parent()?.nextElementSibling() ?: document
            
            safeContainer.select("div.relative.group.overflow-hidden").mapNotNull {
                it.toSearchResponse()
            }
        } else {
            val url = if (page == 1) request.data else "${request.data}?page=$page"
            val document = app.get(url, headers = headers).document
            document.select("div.relative.group.overflow-hidden").mapNotNull {
                it.toSearchResponse()
            }
        }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = if (this.tagName() == "a") {
            this.attr("href")
        } else {
            this.selectFirst("a")?.attr("href")
        } ?: return null
        val title = this.selectFirst("h3")?.text()?.trim()
            ?: this.selectFirst("p")?.text()?.trim()
            ?: return null
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }

        val type = when {
            link.contains("/tv-show/") -> TvType.TvSeries
            link.contains("/anime/") -> TvType.Anime
            else -> TvType.Movie
        }
        return newMovieSearchResponse(title, fixUrl(link), type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.replace(" ", "%20")}"
        val document = app.get(url, headers = headers).document

        return document.select("div.relative.group.overflow-hidden").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("img[alt='Cover']")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        } ?: document.selectFirst("div.poster img, div.fposter img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val description = document.selectFirst("p.text-gray-400, meta[name='description']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }
        val yearStr = document.selectFirst("div.flex.items-center.text-gray-400 span:contains(20)")?.text()
        val year = yearStr?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val type = if (url.contains("/series/") || url.contains("/tv-show/") || url.contains("/anime/")) TvType.TvSeries else TvType.Movie

        if (type == TvType.TvSeries) {
            val episodes = document.select("div.grid.grid-cols-2.lg\\:grid-cols-6.2xl\\:grid-cols-8.gap-6.mt-4 > div.relative.group").mapNotNull { group ->
                val link = group.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epTitle = group.selectFirst("h3")?.text()?.trim() ?: "Episode"
                val releaseDate = group.selectFirst("div.text-xs.text-white\\/50.space-x-2")?.text()

                val seasonMatch = Regex("""Saison\s*(\d+)""").find(releaseDate ?: "")
                val episodeMatch = Regex("""Épisodes\s*(\d+)""").find(releaseDate ?: "")
                
                val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episodeNum = episodeMatch?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(fixUrl(link)) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = episodeNum
                    this.posterUrl = group.selectFirst("img")?.attr("src")
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data, headers = headers).document

        // Extract wire:snapshot from the video component
        // Usually in a div with wire:snapshot="{...}" and wire:id="..." 
        val wireDiv = document.selectFirst("div[wire:snapshot]") ?: return false
        val snapshotRaw = wireDiv.attr("wire:snapshot")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("\\/", "/")
        
        // We also need the component ID and name from the initial state
        val wireId = wireDiv.attr("wire:id")
        
        // Parse initial state to find "fingerprint" data
        // snapshotRaw is a JSON object. We can try to parse it with simple string checks or a proper parser if available.
        // Since we don't have a full JSON parser in standard library easily exposed without importing, 
        // we'll use regex/string manipulation carefully or standard app.mapper if Cloudstream exposes it.
        // Cloudstream exposes standard Jackson 'mapper'.

        try {
            val jsonNode = AppUtils.parseJson<Map<String, Any>>(snapshotRaw)
            val memo = jsonNode["memo"] as? Map<String, Any>
            val componentName = memo?.get("name") as? String
            val componentId = memo?.get("id") as? String ?: wireId
            val componentPath = memo?.get("path") as? String
            val componentMethod = memo?.get("method") as? String ?: "GET"
            
            // Check for direct video links in "data.videos"
            val dataObj = jsonNode["data"] as? Map<String, Any>
            var videoFound = false
            
            // Logic to parse "videos" array structure from snapshot
            // structure: "videos": [ [ [ { "source": "local", ... }, { "s": "arr" } ] ], { "s": "arr" } ]
            // It's a bit messy due to Livewire's compression.
            // Simplified regex approach for "source":"local"
            
            if (snapshotRaw.contains("\"source\":\"local\"")) {
                // If local, we need to make a Livewire call to unlock it?
                // Actually, often "local" just means it needs an interaction or it's a direct file path hidden elsewhere.
                // But let's try the POST simulation if we don't find a direct link.
                
                // Construct Livewire payload
                // We want to call "incrementSteps" or "hideAd" to simulate watching the ad.
                // Then the response might contain the video URL in the "effects" or "serverMemo".
                
                val payload = mapOf(
                    "fingerprint" to mapOf(
                        "id" to componentId,
                        "name" to componentName,
                        "locale" to "fr",
                        "path" to componentPath,
                        "method" to componentMethod,
                        "v" to "acj"
                    ),
                    "serverMemo" to memo,
                    "updates" to emptyList<Any>(),
                    "calls" to listOf(
                        mapOf(
                            "path" to "",
                            "method" to "hideAd",
                            "params" to emptyList<Any>()
                        )
                    )
                )

                val livewireHeaders = mapOf(
                    "X-Livewire" to "true",
                    "Content-Type" to "application/json",
                    "X-CSRF-TOKEN" to (document.selectFirst("meta[name='csrf-token']")?.attr("content") ?: ""),
                    "Origin" to mainUrl,
                    "Referer" to data,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )

                val response = app.post(
                    "$mainUrl/livewire/message/$componentName",
                    headers = livewireHeaders,
                    requestBody = payload.toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
                )

                val responseJson = AppUtils.parseJson<Map<String, Any>>(response.text)
                // Parse response for new effects/redirects/video urls
                // This part is speculative without seeing the exact response. 
                // However, often the 'local' source might just be a path we can construct.
                
                // FALLBACK: If "local", try to find any MP4 match in the raw page again or the response
                val broadMatch = Regex("""https?://[^"']+\.mp4""").find(response.text)
                if (broadMatch != null) {
                     callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "SenpaiStream",
                            url = broadMatch.value,
                        ) {
                            referer = mainUrl
                        }
                    )
                    videoFound = true
                }
            }
            
            if (!videoFound) {
                 // Try parsing the "videos" array from initial snapshot for other sources (e.g. embed)
                 // Or simple regex on the whole document again
                 Regex("""https?://[^"']+\.(?:mp4|m3u8)""").findAll(document.html()).forEach { match ->
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "SenpaiStream",
                            url = match.value,
                        ) {
                            referer = mainUrl
                        }
                    )
                 }
            }

        } catch (e: Exception) {
            // Fallback to simple regex if JSON parsing fails
            Regex("""https?://[^"']+\.(?:mp4|m3u8)""").findAll(document.html()).forEach { match ->
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "SenpaiStream",
                        url = match.value,
                    ) {
                        referer = mainUrl
                    }
                )
            }
        }

        return true
    }
}