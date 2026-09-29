package com.etumen.netshort

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URI

class NetShort : MainAPI() {
    override var mainUrl = "https://netshort.com"
    override var name = "NetShort"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/tr" to "NetShort • Ana Sayfa",
        "$mainUrl/tr/drama/all-plots" to "NetShort • Tüm Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val target = when {
            request.data.endsWith("/drama/all-plots") && page > 1 ->
                "${request.data}/page/$page"
            else ->
                request.data
        }

        val items = fetchCatalog(target)
        val hasNext = request.data.endsWith("/drama/all-plots") && page < 195 && items.isNotEmpty()

        Log.d(name, "getMainPage page=$page url=$target items=${items.size}")
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()

        // NetShort search is client-side. v1 stays on official NetShort pages and
        // filters public catalogue pages locally; no third-party proxy API.
        val pages = listOf(
            "$mainUrl/tr",
            "$mainUrl/tr/drama/all-plots",
            "$mainUrl/tr/drama/all-plots/page/2",
            "$mainUrl/tr/drama/all-plots/page/3",
            "$mainUrl/tr/drama/all-plots/page/4",
            "$mainUrl/tr/drama/all-plots/page/5",
            "$mainUrl/tr/drama/all-plots/page/6",
            "$mainUrl/tr/drama/all-plots/page/7",
            "$mainUrl/tr/drama/all-plots/page/8",
        )

        val out = mutableListOf<SearchResponse>()
        for (url in pages) {
            val pageItems = runCatching { fetchCatalog(url) }.getOrDefault(emptyList())
            out += pageItems.filter { it.name.contains(needle, ignoreCase = true) }
            if (out.size >= 40) break
        }

        return out.distinctBy { it.url }.take(40)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = browserHeaders(),
        ).document

        val rsc = extractRsc(document)
        val detail = extractJsonObjects(rsc, "\"videoEpisodeInfos\"")
            .firstOrNull { it.has("videoEpisodeInfos") && it.optString("shortPlayName").isNotBlank() }
            ?: return null

        val title = detail.optString("shortPlayNameNoHL")
            .ifBlank { detail.optString("shortPlayName") }
            .ifBlank { document.selectFirst("h1")?.text()?.substringBefore(" Bölüm ") ?: "NetShort" }

        val poster = detail.optString("shortPlayCover").ifBlank { null }
        val plot = detail.optString("shotIntroduce").ifBlank { null }
        val tags = buildList {
            val labels = detail.optJSONArray("labelList")
            if (labels != null) {
                for (i in 0 until labels.length()) {
                    val obj = labels.optJSONObject(i) ?: continue
                    val label = obj.optString("labelName")
                    if (label.isNotBlank()) add(label)
                }
            }
        }.distinct()

        val episodesJson = detail.optJSONArray("videoEpisodeInfos") ?: JSONArray()
        val episodes = mutableListOf<Episode>()
        for (i in 0 until episodesJson.length()) {
            val ep = episodesJson.optJSONObject(i) ?: continue
            val epNo = ep.optInt("episodeNo", 0)
            if (epNo <= 0) continue

            // Do not expose paid/locked episodes as playable entries.
            if (ep.optBoolean("isLock", true)) continue

            val epUrl = episodeUrl(url, epNo)
            episodes += newEpisode(epUrl) {
                this.name = "Bölüm $epNo"
                this.season = 1
                this.episode = epNo
                this.posterUrl = ep.optString("episodeCover").ifBlank { null }
            }
        }

        if (episodes.isEmpty()) {
            Log.w(name, "load: unlocked episode list empty for $url")
            return null
        }

        val recommendations = parseCatalog(rsc)
            .filterNot { it.url == normalizeSeriesUrl(url) }
            .take(20)

        Log.d(
            name,
            "load title=$title unlockedEpisodes=${episodes.size} total=${detail.optInt("totalEpisode", episodes.size)}"
        )

        return newTvSeriesLoadResponse(
            title,
            normalizeSeriesUrl(url),
            TvType.TvSeries,
            episodes.sortedBy { it.episode ?: Int.MAX_VALUE },
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val safeData = asciiUrl(data)
        val safeSeriesUrl = asciiUrl(normalizeSeriesUrl(data))
        val document = app.get(
            safeData,
            headers = browserHeaders(referer = safeSeriesUrl),
        ).document
        val rsc = extractRsc(document)

        val playObject = extractJsonObjects(rsc, "\"playVoucher\"")
            .firstOrNull {
                it.optString("playVoucher").startsWith("http") &&
                    !it.optBoolean("isLock", false)
            }

        val playUrl = playObject?.optString("playVoucher")
            ?.takeIf { it.startsWith("http") }
            ?: return false

        playObject.optJSONArray("subtitleList")?.let { subtitles ->
            for (i in 0 until subtitles.length()) {
                val sub = subtitles.optJSONObject(i) ?: continue
                val subUrl = sub.optString("url")
                if (subUrl.isBlank()) continue
                val subLang = sub.optString("subtitleLanguage").ifBlank { "TR" }
                subtitleCallback(SubtitleFile(subLang, subUrl))
            }
        }

        callback(
            newExtractorLink(
                source = name,
                name = "$name MP4",
                url = playUrl,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = safeData
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to safeData,
                    "Origin" to mainUrl,
                    "Accept" to "*/*",
                )
            }
        )

        return true
    }

    private suspend fun fetchCatalog(url: String): List<SearchResponse> {
        val document = app.get(url, headers = browserHeaders()).document
        val visibleUrls = document.select("a[href*='/tr/episode/']")
            .map { fixNetShortUrl(it.attr("href")) }
            .toSet()

        return parseCatalog(extractRsc(document))
            .filter { it.url in visibleUrls }
    }

    private fun parseCatalog(rsc: String): List<SearchResponse> {
        return extractJsonObjects(rsc, "\"shortPlayNameUrl\"")
            .mapNotNull { obj ->
                val rawUrl = obj.optString("shortPlayNameUrl")
                if (!rawUrl.contains("/episode/")) return@mapNotNull null

                val title = obj.optString("shortPlayNameNoHL")
                    .ifBlank { obj.optString("shortPlayName") }
                    .ifBlank { return@mapNotNull null }

                val poster = obj.optString("shortPlayCover")
                    .ifBlank { obj.optString("groupShortPlayCover") }
                    .ifBlank { null }

                val itemUrl = fixNetShortUrl(rawUrl)

                newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    private fun browserHeaders(referer: String = "$mainUrl/tr") = mapOf(
        "User-Agent" to userAgent,
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer" to asciiUrl(referer),
    )

    private fun asciiUrl(url: String): String =
        runCatching { URI(url).toASCIIString() }.getOrDefault(url)

    private fun fixNetShortUrl(raw: String): String {
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> "$mainUrl$raw"
            else -> "$mainUrl/$raw"
        }
    }

    private fun normalizeSeriesUrl(url: String): String {
        return url.replace(Regex("""-ep-\d+(?=$|[?#])"""), "")
    }

    private fun episodeUrl(seriesUrl: String, episodeNo: Int): String {
        val base = normalizeSeriesUrl(seriesUrl)
        return if (episodeNo <= 1) base else "$base-ep-$episodeNo"
    }

    private fun extractRsc(document: Document): String {
        val html = document.html()
        val pattern = Regex(
            """self\.__next_f\.push\(\[1,("(?:\\.|[^"\\])*")\]\)""",
            RegexOption.DOT_MATCHES_ALL,
        )

        return pattern.findAll(html)
            .mapNotNull { match ->
                decodeJsonStringLiteral(match.groupValues[1])
            }
            .joinToString("\n")
    }

    private fun decodeJsonStringLiteral(literal: String): String? {
        if (literal.length < 2 || literal.first() != '"' || literal.last() != '"') return null

        return runCatching {
            JSONObject("{\"v\":$literal}").getString("v")
        }.getOrNull()
    }

    private fun extractJsonObjects(text: String, marker: String): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var from = 0

        while (true) {
            val markerIndex = text.indexOf(marker, from)
            if (markerIndex < 0) break

            val raw = enclosingJsonObject(text, markerIndex)
            if (raw != null && seen.add(raw)) {
                runCatching { JSONObject(raw) }
                    .getOrNull()
                    ?.let(results::add)
            }

            from = markerIndex + marker.length
        }

        return results
    }

    private fun enclosingJsonObject(text: String, targetIndex: Int): String? {
        val stack = ArrayDeque<Int>()
        var inString = false
        var escaped = false

        for (i in 0 until minOf(targetIndex + 1, text.length)) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> stack.addLast(i)
                '}' -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }

        val start = stack.lastOrNull() ?: return null

        var depth = 0
        inString = false
        escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }

        return null
    }
}
