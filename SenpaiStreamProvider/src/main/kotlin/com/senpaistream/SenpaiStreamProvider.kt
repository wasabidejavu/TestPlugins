package com.senpaistream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.MainPageRequest
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

    override val mainPage = listOf(
        MainPageRequest("$mainUrl/movies", "Films"),
        MainPageRequest("$mainUrl/tv-shows", "Séries"),
        MainPageRequest("$mainUrl/animes", "Animés"),
        MainPageRequest("$mainUrl/trending", "Tendances"),
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        val document = app.get(url).document
        val home = document.select("div.grid.grid-cols-2 > div").mapNotNull {
            it.toSearchResponse()
        }
        return HomePageResponse(listOf(HomePageList(request.name, home)))
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h3")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        return MovieSearchResponse(
            title,
            link,
            this@SenpaiStreamProvider.name,
            TvType.Movie,
            posterUrl,
            null,
            null
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("div.grid.grid-cols-2 > div").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("img[alt='Cover']")?.attr("src")
        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val yearStr = document.selectFirst("div.flex.items-center.text-gray-400 span:contains(20)")?.text()
        val year = yearStr?.filter { it.isDigit() }?.toIntOrNull()

        val type = if (url.contains("/movie/")) TvType.Movie else TvType.TvSeries

        val episodes = document.select("ul.space-y-2 a[href*='/movie/']").mapNotNull { episodeElement ->
            val episodeUrl = episodeElement.attr("href") ?: return@mapNotNull null
            val episodeTitle = episodeElement.selectFirst("span")?.text()?.trim() ?: episodeElement.text().trim()
            val episodeNum = episodeTitle.substringAfterLast(" ").filter { it.isDigit() }.toIntOrNull()

            Episode(
                data = episodeUrl,
                name = episodeTitle,
                episode = episodeNum
            )
        }

        return if (episodes.isNotEmpty()) {
            TvSeriesLoadResponse(
                title,
                url,
                this.name,
                TvType.TvSeries,
                episodes,
                poster,
                year,
                description,
                null
            )
        } else {
            MovieLoadResponse(
                title,
                url,
                this.name,
                TvType.Movie,
                url,
                poster,
                year,
                description,
                null
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isDownload: Boolean,
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