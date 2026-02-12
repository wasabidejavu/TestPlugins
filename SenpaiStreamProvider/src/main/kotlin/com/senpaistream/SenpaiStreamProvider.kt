package com.senpaistream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        val document = app.get(url).document
        val home = document.select("div.grid.grid-cols-2 > div, div.flex.flex-wrap.gap-4 > a").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
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

        return newMovieSearchResponse(title, fixUrl(link), TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val googleQuery = "site:senpai-stream.hair $query"
        val url = "https://www.google.com/search?q=${googleQuery.replace(" ", "+")}"
        val document = app.get(url).document
        
        return document.select("div.g").mapNotNull {
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            if (!link.contains("senpai-stream.hair")) return@mapNotNull null
            
            newMovieSearchResponse(title, fixUrl(link), TvType.Movie) {
                // No poster for google results easily
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("img[alt='Cover']")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        } ?: document.selectFirst("div.poster img, div.fposter img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val yearStr = document.selectFirst("div.flex.items-center.text-gray-400 span:contains(20)")?.text()
        val year = yearStr?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val type = if (url.contains("/series/") || url.contains("/tv-show/") || url.contains("/anime/")) TvType.TvSeries else TvType.Movie

        val episodes = document.select("ul.space-y-2 a, div.episode-list a").mapNotNull { episodeElement ->
            val episodeUrl = episodeElement.attr("href") ?: return@mapNotNull null
            val episodeTitle = episodeElement.selectFirst("span")?.text()?.trim() ?: episodeElement.text().trim()
            val episodeNum = Regex("""(\d+)""").find(episodeTitle)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(fixUrl(episodeUrl)) {
                this.name = episodeTitle
                this.episode = episodeNum
            }
        }

        return if (episodes.isNotEmpty() && type == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val document = app.get(fixUrl(data)).document

        // Strategy 1: Look for any http link ending in mp4/m3u8 within wire:snapshot
        val livewireDiv = document.selectFirst("div[wire:snapshot]")
        if (livewireDiv != null) {
            val snapshotRaw = livewireDiv.attr("wire:snapshot")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("\\/", "/")

            // Broader regex for any MP4/M3U8 URL
            val videoUrlRegex = Regex("""https?://[^"]+\.(?:mp4|m3u8)(?:\?[^"]*)?""")
            videoUrlRegex.findAll(snapshotRaw).forEach { match ->
                val videoUrl = match.value.replace("\\u0026", "&")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        // Strategy 2: Look for video URL in page scripts (broader regex)
        val scriptContent = document.select("script").mapNotNull { it.data().ifEmpty { null } }
            .joinToString("\n")

        val scriptVideoRegex = Regex("""["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""")
        scriptVideoRegex.findAll(scriptContent).forEach { match ->
            val videoUrl = match.groupValues[1].replace("\\u0026", "&")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // Strategy 3: Look for iframe embeds
        document.select("iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotEmpty() && !iframeSrc.contains("youtube")) {
                try {
                    val iframeDoc = app.get(fixUrl(iframeSrc)).document
                    val iframeScript = iframeDoc.select("script").mapNotNull { it.data().ifEmpty { null } }
                        .joinToString("\n")
                    scriptVideoRegex.findAll(iframeScript).forEach { iframeMatch ->
                        val videoUrl = iframeMatch.groupValues[1].replace("\\u0026", "&")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = videoUrl,
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                } catch (_: Exception) {
                    // Ignore iframe loading errors
                }
            }
        }

        return true
    }
}