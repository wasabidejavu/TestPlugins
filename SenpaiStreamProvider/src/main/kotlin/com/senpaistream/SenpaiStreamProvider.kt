package com.senpaistream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
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

    private val interceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "/browse?type=movie&sort=created_at&page=" to "Nouveaux films",
        "/browse?type=tv&sort=created_at&page=" to "Nouvelles séries",
        "homepage:Top 10 des films aujourd'hui" to "Top 10 films",
        "homepage:Top 10 des séries aujourd'hui" to "Top 10 séries",
        "homepage:Top 10 des animés aujourd'hui" to "Top 10 animés",
        "/trending?page=" to "Tendances",
    )

    data class VideoData(
        val url: String,
        val tvType: TvType = TvType.Movie,
        var name: String? = null
    )

    data class LinkData(
        val url: String,
        val isDirect: Boolean = true
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.data.startsWith("homepage:")) {
            // Top 10 sections: only return results for page 1
            if (page > 1) return newHomePageResponse(request.name, emptyList())
            val sectionTitle = request.data.removePrefix("homepage:")
            val document = app.get(mainUrl, interceptor = interceptor).document
            val items = parseTop10Section(document, sectionTitle)
            return newHomePageResponse(request.name, items)
        } else {
            // URL-based pages (browse, trending)
            val url = "$mainUrl${request.data}$page"
            val document = app.get(url, interceptor = interceptor).document
            val items = document.select("div.relative.group.overflow-hidden").mapNotNull {
                it.toSearchResponse()
            }
            return newHomePageResponse(request.name, items)
        }
    }

    private fun parseTop10Section(document: Document, sectionTitle: String): List<SearchResponse> {
        // Normalize quotes for matching: the HTML uses Unicode right single quote '
        val normalizedTitle = sectionTitle.replace("\u2019", "'").replace("'", "'").lowercase()

        // Find the h3 that contains the section title text
        val sectionHeader = document.select("h3").firstOrNull { h3 ->
            val normalizedH3 = h3.text().replace("\u2019", "'").replace("'", "'").lowercase()
            normalizedH3.contains(normalizedTitle)
        } ?: return emptyList()

        // The h3 is inside div.flex, which is inside div.pb-6 (the section container).
        // We need the grandparent to access the sibling swiper div that contains items.
        val sectionContainer = sectionHeader.parent()?.parent() ?: return emptyList()

        // Items are in swiper-slide divs with <a> elements linking to content
        val slides = sectionContainer.select("div.swiper-slide")

        return slides.mapNotNull { slide ->
            // Each slide has <a href="/movie/slug"> or <a href="/tv-show/slug"> etc.
            val link = slide.select("a[href]").firstOrNull { a ->
                val href = a.attr("href")
                href.contains("/movie/") || href.contains("/tv-show/") || href.contains("/anime/")
            } ?: return@mapNotNull null

            val href = link.attr("href")
            val img = slide.selectFirst("img[data-src], img[src]")
            val title = img?.attr("alt")
                ?: href.substringAfterLast("/").replace("-", " ")
            val posterUrl = img?.let {
                val dataSrc = it.attr("data-src")
                if (dataSrc.isNotEmpty()) dataSrc else it.attr("src")
            }

            val vData = generateVideoData(href)
            vData.name = title

            newMovieSearchResponse(title, vData.toJson(), vData.tvType) {
                this.posterUrl = posterUrl
                this.posterHeaders = mapOf("Referer" to mainUrl)
            }
        }.distinctBy { it.url } // Remove duplicates (each slide has 2 <a> with same href)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("a img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }

        val vData = generateVideoData(link)
        vData.name = title

        return newMovieSearchResponse(title, vData.toJson(), vData.tvType) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to mainUrl)
        }
    }

    private fun generateVideoData(link: String): VideoData {
        val tvType = when {
            link.contains("/tv-show") -> TvType.TvSeries
            link.contains("/anime") -> TvType.Anime
            else -> TvType.Movie
        }
        val url = if (tvType != TvType.Movie) {
            // Transform: /tv-show/slug-year or /anime/slug-year -> /episode/slug/1-1
            "$mainUrl/episode/${link.substringAfterLast("/")}/1-1"
        } else {
            link
        }
        return VideoData(url, tvType)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query"
        val document = app.get(url, interceptor = interceptor).document
        return document.select("div.relative.group.overflow-hidden").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = url

        // Try to parse as VideoData JSON (from search results), otherwise generate from URL
        val vData = tryParseJson<VideoData>(data) ?: generateVideoData(data)
        val episodePageUrl = vData.url
        val tvType = vData.tvType

        // Fetch the episode page (for series/anime) or the movie page
        val document = app.get(episodePageUrl, interceptor = interceptor).document

        // Get the title
        val name = vData.name ?: document.selectFirst("h1")?.text()?.trim() ?: "Unknown"

        // Get the first container for wire:snapshot data (cover, link, etc.)
        val firstContainer = document.selectFirst("div.container > div")
            ?.attr("wire:snapshot") ?: ""

        // Extract cover image from wire:snapshot JSON
        var cover = Regex(""""cover":"([^"]+)""").find(firstContainer)
            ?.groupValues?.get(1)
            ?.replace("\\", "")

        if (cover == null || cover == "null") {
            // Fallback: find first img.object-cover with a non-blank src
            val container = document.selectFirst("div.container")
            cover = container?.select("img.object-cover")
                ?.firstOrNull { !it.attr("src").isNullOrBlank() }
                ?.attr("src")
        }

        // Parse metadata info
        val infos = document.select("div.flex-1 > div.tracking-tighter > div")
        val actors = ArrayList<String>()
        val tags = ArrayList<String>()

        val plot = document.selectFirst("p")?.text() ?: ""

        // Genre tags
        tags.addAll(infos.select("div:contains(Genre) + div > a").map { it.text() })
        // Country tags
        tags.addAll(infos.select("div:contains(Pays) + div > a").map { it.text() })
        // Actors
        actors.addAll(infos.select("div:contains(Distribution) + div > a").map { it.text() })

        val infos_ = document.selectFirst(".flex-1 > .items-center") ?: Element("")

        if (tvType == TvType.Movie) {
            // Movie: extract duration, year, and player link
            val durationMatch = Regex("""\d+""").find(infos_.select("span:eq(1)").text())
            val duration = durationMatch?.value?.toIntOrNull()
            val year = infos_.select("span:eq(2)").text().toIntOrNull()

            // Extract embed URL from the video iframe src
            val embedUrl = document.selectFirst("iframe#video-iframe")?.attr("src")
                ?.let { if (it.startsWith("http")) it else "$mainUrl$it" } ?: ""

            val lData = LinkData(embedUrl)

            return newMovieLoadResponse(name, episodePageUrl, tvType, lData.toJson()) {
                this.posterUrl = cover
                this.posterHeaders = mapOf("Referer" to mainUrl)
                this.tags = tags
                this.plot = plot
                this.year = year
                this.actors = actors.map { ActorData(Actor(it)) }
                this.duration = duration
            }
        } else {
            // TV Series / Anime
            val year = infos_.selectFirst("span:eq(0)")?.text()?.toIntOrNull()
            val episodes = ArrayList<Episode>()

            // Find season buttons
            val seasonButtons = document.select("div.flex > button.w-full")

            // Process each season
            seasonButtons.forEachIndexed { index, s ->
                val seasonName = s.text()
                // Try to extract season number from button text ("Saison 1", "Saison 2", etc.)
                val seasonNum = Regex("""Saison\s*(\d+)""").find(seasonName)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)

                val seasonDocument: Document
                if (index > 0) {
                    // For seasons beyond the first, construct the URL and fetch
                    val seasonUrl = episodePageUrl.substringBeforeLast("/") + "/$seasonNum-1"
                    seasonDocument = app.get(seasonUrl, interceptor = interceptor).document
                    // Update firstContainer for this season's wire:snapshot
                    val seasonSnapshot = seasonDocument.selectFirst("div.container > div")
                        ?.attr("wire:snapshot") ?: ""
                    // Use season-specific snapshot for episode data
                    parseSeasonEpisodes(seasonDocument, seasonSnapshot, seasonNum, episodes)
                } else {
                    // First season: use the already-loaded document
                    parseSeasonEpisodes(document, firstContainer, seasonNum, episodes)
                }
            }

            // If no season buttons found, try to parse episodes from the current page anyway
            if (seasonButtons.isEmpty()) {
                parseSeasonEpisodes(document, firstContainer, 1, episodes)
            }

            return newTvSeriesLoadResponse(name, data, tvType, episodes) {
                this.posterUrl = cover
                this.posterHeaders = mapOf("Referer" to mainUrl)
                this.tags = tags
                this.plot = plot
                this.year = year
                this.actors = actors.map { ActorData(Actor(it)) }
            }
        }
    }

    private fun parseSeasonEpisodes(
        document: Document,
        wireSnapshot: String,
        season: Int,
        episodes: ArrayList<Episode>
    ) {
        val episodeLinks = document.select("div.mx-3 > a")

        // Extract embed URL from iframe for the first episode
        val firstEpisodeEmbedUrl = document.selectFirst("iframe#video-iframe")?.attr("src")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" } ?: ""

        episodeLinks.forEachIndexed { eIndex, e ->
            val epName = e.select("div:eq(0)").text()
            val epNumber = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
            val epTitle = e.select("div:eq(1)").text()

            // For first episode use embed URL from iframe; for others use the href link
            val lData = if (eIndex > 0) {
                LinkData(e.attr("href"), isDirect = false)
            } else {
                LinkData(firstEpisodeEmbedUrl)
            }

            episodes.add(newEpisode(lData.toJson()) {
                this.name = epTitle
                this.season = season
                this.episode = epNumber
            })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkData = tryParseJson<LinkData>(data)

        val url = linkData?.url ?: data
        if (url.isBlank()) return false

        // Get the embed URL
        val embedUrl = if (linkData?.isDirect == false) {
            // Non-direct: it's an episode page URL, fetch it and extract the iframe src
            val doc = app.get(url, interceptor = interceptor).document
            doc.selectFirst("iframe#video-iframe")?.attr("src")
                ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                ?: return false
        } else {
            // Direct: URL is already the embed URL
            url
        }

        if (embedUrl.isBlank()) return false

        // Fetch the embed page and extract the actual video URL
        val embedDoc = app.get(embedUrl, referer = mainUrl, interceptor = interceptor).document

        // Try multiple selectors: media-player src, video src, source src
        val videoUrl = embedDoc.selectFirst("media-player")?.attr("src")
            ?: embedDoc.selectFirst("video")?.attr("src")
            ?: embedDoc.selectFirst("source")?.attr("src")
            ?: ""

        if (videoUrl.isBlank()) return false

        // Determine if it's M3U8 (HLS) or direct video
        val isM3u8 = videoUrl.contains(".m3u8")

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = isM3u8,
            )
        )

        return true
    }
}