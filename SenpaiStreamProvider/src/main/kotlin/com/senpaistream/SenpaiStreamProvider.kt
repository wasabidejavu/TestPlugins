package com.senpaistream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
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

    // Normalize curly/smart apostrophes and special chars to plain ASCII for text comparison
    private fun normalizeText(text: String): String {
        return text
            .replace("\u2019", "'")  // Right single quotation mark
            .replace("\u2018", "'")  // Left single quotation mark
            .replace("\u00e9", "é")  // Already fine, but be safe
            .replace("\u2013", "-")  // En dash
            .replace("\u2014", "-")  // Em dash
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = if (!request.data.startsWith("http")) {
            val document = app.get(mainUrl, headers = headers).document
            val normalizedSearch = normalizeText(request.data)
            val header = document.select("h3").find {
                normalizeText(it.text()).contains(normalizedSearch, ignoreCase = true)
            }
            
            // Find the container: look for the grid/swiper container following the header
            // For Top 10 sections: h3 is inside a div, and the swiper is a sibling div
            // We need to go up to the parent and look for swiper/grid among siblings
            var container = header?.parent()?.nextElementSibling()
            if (container == null || !container.classNames().any { cn -> 
                cn.contains("grid") || cn.contains("swiper") 
            }) {
                // Try traversing siblings more broadly
                container = header?.nextElementSibling()
                while (container != null && !container.classNames().any { cn -> 
                    cn.contains("grid") || cn.contains("swiper") 
                }) {
                    container = container.nextElementSibling()
                }
            }
            
            val safeContainer = container ?: header?.parent()?.parent() ?: document
            
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

    // Parse episodes from a container element (document or parsed HTML fragment)
    private fun parseEpisodesFromElement(container: Element, seasonIndex: Int): List<Episode> {
        return container.select("div.relative.group").mapNotNull { group ->
            val link = group.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epTitle = group.selectFirst("h3")?.text()?.trim() ?: "Episode"
            val metaDiv = group.selectFirst("div.text-xs")
            val metaText = metaDiv?.text() ?: ""

            val seasonMatch = Regex("""Saison\s*(\d+)""").find(metaText)
            val episodeMatch = Regex("""[EÉ]pisodes?\s*(\d+)""").find(metaText)

            val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: seasonIndex
            val episodeNum = episodeMatch?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(fixUrl(link)) {
                this.name = epTitle
                this.season = seasonNum
                this.episode = episodeNum
                this.posterUrl = group.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifEmpty { img.attr("src") }
                }
            }
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
            val allEpisodes = mutableListOf<Episode>()

            // Parse season 1 episodes from the initial page
            val episodeGrid = document.selectFirst("div.grid.grid-cols-2")
            if (episodeGrid != null) {
                allEpisodes.addAll(parseEpisodesFromElement(episodeGrid, 1))
            }

            // Find the season-component wire:snapshot to get season data
            val seasonComponent = document.select("div[wire\\:snapshot]").find { div ->
                div.attr("wire:snapshot").contains("season-component")
            }

            if (seasonComponent != null) {
                // Parse all season buttons from the dropdown
                val seasonButtons = seasonComponent.select("button[wire\\:click*=updateSeason]")
                val seasonIds = seasonButtons.mapNotNull { btn ->
                    Regex("""updateSeason\(['"]?(\d+)['"]?\)""").find(btn.attr("wire:click"))?.groupValues?.get(1)
                }

                // Get the wire:snapshot for Livewire requests
                // Jsoup auto-decodes HTML entities (&quot; -> "), so attr() gives us clean JSON
                val snapshotRaw = seasonComponent.attr("wire:snapshot")
                val csrfToken = document.selectFirst("meta[name='csrf-token']")?.attr("content") ?: ""

                // For each additional season (skip the first one, already parsed)
                if (seasonIds.size > 1) {
                    for (i in 1 until seasonIds.size) {
                        try {
                            val seasonId = seasonIds[i]
                            // Build Livewire v3 update payload using proper JSON serialization
                            val payloadMap = mapOf(
                                "_token" to csrfToken,
                                "components" to listOf(
                                    mapOf(
                                        "snapshot" to snapshotRaw,
                                        "updates" to emptyMap<String, Any>(),
                                        "calls" to listOf(
                                            mapOf(
                                                "path" to "",
                                                "method" to "updateSeason",
                                                "params" to listOf(seasonId)
                                            )
                                        )
                                    )
                                )
                            )
                            val payload = payloadMap.toJson()

                            val livewireHeaders = mapOf(
                                "X-Livewire" to "true",
                                "Content-Type" to "application/json",
                                "X-CSRF-TOKEN" to csrfToken,
                                "Origin" to mainUrl,
                                "Referer" to url,
                                "User-Agent" to headers["User-Agent"]!!,
                                "Accept" to "application/json"
                            )

                            val response = app.post(
                                "$mainUrl/livewire/update",
                                headers = livewireHeaders,
                                requestBody = payload.toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
                            )

                            // Parse HTML from Livewire response
                            // Livewire v3 returns: {"components":[{"effects":{"html":"..."}, ...}]}
                            val responseText = response.text
                            val htmlMatch = Regex(""""html"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(responseText)
                            if (htmlMatch != null) {
                                val htmlContent = htmlMatch.groupValues[1]
                                    .replace("\\n", "\n")
                                    .replace("\\t", "\t")
                                    .replace("\\\"", "\"")
                                    .replace("\\\\", "\\")
                                    .replace("\\/", "/")
                                val seasonDoc = Jsoup.parse(htmlContent)
                                allEpisodes.addAll(parseEpisodesFromElement(seasonDoc, i + 1))
                            }
                        } catch (e: Exception) {
                            // If a season fails to load, continue with others
                            continue
                        }
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
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