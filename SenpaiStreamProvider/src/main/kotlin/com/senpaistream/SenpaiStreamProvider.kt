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
        "/movies?sort=release_date&page=" to "Nouveautés Films",
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
        val url = "$mainUrl${request.data}$page"
        val document = app.get(url, interceptor = interceptor).document
        val items = document.select("div.relative.group.overflow-hidden").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, items)
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

            // Extract player link from wire:snapshot
            val playerUrl = Regex(""""link":"([^"]+)""").find(firstContainer)
                ?.groupValues?.get(1)
                ?.replace("\\", "") ?: ""

            val lData = LinkData(playerUrl)

            return newMovieLoadResponse(name, episodePageUrl, tvType, lData.toJson()) {
                this.posterUrl = cover
                this.posterHeaders = mapOf("Referer" to mainUrl)
                this.tags = tags
                this.plot = plot
                this.year = year
                LoadResponse.addActorNames(this, actors)
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
                LoadResponse.addActorNames(this, actors)
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
        episodeLinks.forEachIndexed { eIndex, e ->
            // For the first episode (eIndex == 0), extract playerUrl from wire:snapshot
            val playerUrl = if (eIndex == 0) {
                Regex(""""link":"([^"]+)""").find(wireSnapshot)
                    ?.groupValues?.get(1)
                    ?.replace("\\", "") ?: ""
            } else {
                ""
            }

            val epName = e.select("div:eq(0)").text()
            val epNumber = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
            val epTitle = e.select("div:eq(1)").text()

            // For first episode use player URL; for others use the href link
            val lData = if (eIndex > 0) {
                LinkData(e.attr("href"), isDirect = false)
            } else {
                LinkData(playerUrl)
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

        // If it's a direct link (from wire:snapshot), the url is the player URL
        // If it's not direct, it's an episode page URL that we need to fetch
        val playerUrl = if (linkData?.isDirect == false) {
            // Fetch the episode page and extract the player link from wire:snapshot
            val doc = app.get(url, interceptor = interceptor).document
            val snapshot = doc.selectFirst("div.container > div")
                ?.attr("wire:snapshot") ?: ""
            Regex(""""link":"([^"]+)""").find(snapshot)
                ?.groupValues?.get(1)
                ?.replace("\\", "") ?: url
        } else {
            url
        }

        if (playerUrl.isBlank()) return false

        // Try to extract video using loadExtractor (handles known embed sites)
        loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)

        return true
    }
}