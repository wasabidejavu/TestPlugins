package com.senpaistream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
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

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Films",
        "$mainUrl/tv-shows" to "Séries",
        "$mainUrl/animes" to "Animés",
        "$mainUrl/trending" to "Tendances",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if(page == 1) request.data else "${request.data}?page=$page"
        val document = app.get(url).document
        val home = document.select("div.grid.grid-cols-2 > div").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h3")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        return newMovieSearchResponse(title, link, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/browse?s=$query"
        val document = app.get(url).document
        return document.select("div.grid.grid-cols-2 > div").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("img[alt='Cover']")?.attr("src")
        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val year = document.selectFirst("div.flex.items-center.text-gray-400 span:contains(20)")?.text()?.toIntOrNull()
        
        val type = if (url.contains("/movie/")) TvType.Movie else TvType.TvSeries

        // Extract Livewire snapshot for videos/episodes
        val livewireDiv = document.selectFirst("div[wire:snapshot]")
        val snapshot = livewireDiv?.attr("wire:snapshot")
        // We'll parse this in loadLinks or here? 
        // For Movies, the link might be in the snapshot.
        // For Series, we need to see how episodes are listed.
        
        if (type == TvType.Movie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            // For series, looking at the user's request, the URL structure is /series/ID/VF/Sxx/Exx.mp4
            // The page likely lists episodes. 
            // In the provided HTML (which was a movie), we saw `videos` in the snapshot.
            // We assume series page has similar structure or list of episodes.
            // Let's look for episode list in the HTML first.
            val episodes = document.select("div.episode-item").mapNotNull { 
                // Placeholder selector, need actual series page to verify
                val epNum = it.selectFirst(".episode-number")?.text()?.toIntOrNull()
                val epUrl = it.selectFirst("a")?.attr("href")
                if (epUrl == null) null
                else newEpisode(epUrl) {
                    this.episode = epNum
                }
            }
            
             return newTvSeriesLoadResponse(title, url, type, episodes) {
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
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val livewireDiv = document.selectFirst("div[wire:snapshot]") ?: return false
        val snapshotRaw = livewireDiv.attr("wire:snapshot")
        
        // Decoding the snapshot
        // The snapshot is HTML escaped JSON.
        val snapshotJson = AppUtils.parseJson<LivewireSnapshot>(snapshotRaw.replace("&quot;", "\""))
        
        // Navigate the complex Livewire structure
        // structure seems to be: data -> videos -> ...
        // We need a proper JSON parser or a helper function.
        // Since we can't easily debug, let's try to find the video file in the JSON string directly using Regex.
        
        val videoUrlRegex = Regex("""https://[^"]+\.r2\.cloudflarestorage\.com/[^"]+\.mp4\?[^"]+""")
        val match = videoUrlRegex.find(snapshotRaw.replace("&quot;", "\""))
        
        if (match != null) {
            val videoUrl = match.value
            callback.invoke(
                ExtractorLink(
                    this.name,
                    "SenpaiStream",
                    videoUrl,
                    "",
                    Qualities.Unknown.value,
                    false
                )
            )
            return true
        }
        
        return false
    }

    data class LivewireSnapshot(
        val data: LivewireData
    )
    
    data class LivewireData(
        val videos: Any? // Dynamic structure
    )
}