// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import android.util.Base64
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import java.net.URLEncoder
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class FullHDFilmizlesene : MainAPI() {
    private val cloudflare by lazy { CloudflareKiller() }
    override var mainUrl              = "https://www.fullhdfilmizlesene.now"
    override var name                 = "FullHDFilmizlesene"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/en-cok-izlenen-filmler-izle-hd/"            to "En Çok izlenen Filmler",
        "${mainUrl}/filmizle/imdb-puani-yuksek-filmler-izle-1/" to "IMDB Puanı Yüksek Filmler",
        "${mainUrl}/filmizle/aile-filmleri-hdf-izle/"           to "Aile Filmleri",
        "${mainUrl}/filmizle/aksiyon-filmleri-hdf-izle/"         to "Aksiyon Filmleri",
        "${mainUrl}/filmizle/animasyon-filmleri-fhd-izle/"      to "Animasyon Filmleri",
        "${mainUrl}/filmizle/belgesel-filmleri-izle/"           to "Belgeseller",
        "${mainUrl}/filmizle/bilim-kurgu-filmleri-izle-2/"      to "Bilim Kurgu Filmleri",
        "${mainUrl}/filmizle/bluray-filmler-izle/"              to "Blu Ray Filmler",
        "${mainUrl}/filmizle/cizgi-filmler-fhd-izle/"           to "Çizgi Filmler",
        "${mainUrl}/filmizle/dram-filmleri-hd-izle/"            to "Dram Filmleri",
        "${mainUrl}/filmizle/fantastik-filmler-hd-izle/"        to "Fantastik Filmler",
        "${mainUrl}/filmizle/gerilim-filmleri-fhd-izle/"        to "Gerilim Filmleri",
        "${mainUrl}/filmizle/gizem-filmleri-hd-izle/"           to "Gizem Filmleri",
        "${mainUrl}/filmizle/hint-filmleri-fhd-izle/"            to "Hint Filmleri",
        "${mainUrl}/filmizle/komedi-filmleri-fhd-izle/"         to "Komedi Filmleri",
        "${mainUrl}/filmizle/korku-filmleri-izle-3/"            to "Korku Filmleri",
        "${mainUrl}/filmizle/macera-filmleri-fhd-izle/"         to "Macera Filmleri",
        "${mainUrl}/filmizle/muzikal-filmler-izle/"             to "Müzikal Filmler",
        "${mainUrl}/filmizle/polisiye-filmleri-izle/"           to "Polisiye Filmleri",
        "${mainUrl}/filmizle/psikolojik-filmler-izle/"          to "Psikolojik Filmler",
        "${mainUrl}/filmizle/romantik-filmler-fhd-izle/"        to "Romantik Filmler",
        "${mainUrl}/filmizle/savas-filmleri-fhd-izle/"          to "Savaş Filmleri",
        "${mainUrl}/filmizle/suc-filmleri-izle/"                to "Suç Filmleri",
        "${mainUrl}/filmizle/tarih-filmleri-fhd-izle/"          to "Tarih Filmleri",
        "${mainUrl}/filmizle/western-filmler-hd-izle-3/"        to "Western Filmler",
        "${mainUrl}/filmizle/yerli-filmler-hd-izle/"            to "Yerli Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}", interceptor = cloudflare).document
        val home     = document.select("li.film").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("span.film-title")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.replace(Regex("""[^\p{L}\p{N} ]+"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (clean.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
        val response = app.get(
            "$mainUrl/autocomplete/q.php?q=$encoded",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json"),
            referer = "$mainUrl/",
            interceptor = cloudflare
        )
        val text = response.text.trim()
        if (text.isBlank() || text == "exit") return emptyList()
        val rows = runCatching { jacksonObjectMapper().readTree(text) }.getOrNull()
        if (rows == null || !rows.isArray) return emptyList()
        return rows.mapNotNull { row ->
            val prefix = row.path("prefix").asText("film")
            if (prefix != "film") return@mapNotNull null
            val slug = row.path("dizilink").asText().trim()
            val title = row.path("baslik").asText().trim()
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null
            val href = "$mainUrl/$prefix/$slug/"
            newMovieSearchResponse(title, href, TvType.Movie)
        }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = cloudflare).document

        val title           = document.selectFirst("div[class=izle-titles]")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("div img")?.attr("data-src"))
        val year            = document.selectFirst("div.dd a.category")?.text()?.split(" ")?.get(0)?.trim()?.toIntOrNull()
        val description     = document.selectFirst("div.ozet-ic > p")?.text()?.trim()
        val tags            = document.select("a[rel='category tag']").map { it.text() }
        val duration        = document.selectFirst("span.sure")?.text()?.split(" ")?.get(0)?.trim()?.toIntOrNull()
        val trailer         = Regex("""embedUrl": "(.*)"""").find(document.html())?.groupValues?.get(1)
        val actors          = document.select("div.film-info ul li:nth-child(2) a > span").map {
            Actor(it.text())
        }


        val recommendations = document.selectXpath("//div[span[text()='Benzer Filmler']]/following-sibling::section/ul/li").mapNotNull {
            val recName      = it.selectFirst("span.film-title")?.text() ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPosterUrl
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun atob(s: String): String {
        return String(Base64.decode(s, Base64.DEFAULT))
    }

    private fun rtt(s: String): String {
        fun rot13Char(c: Char): Char {
            return when (c) {
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                else -> c
            }
        }

        return s.map { rot13Char(it) }.joinToString("")
    }

    private fun assignedObject(script: String, variable: String): String? {
        val marker = Regex("""(?:^|[;\s])(?:var\s+|let\s+|const\s+)?(?:window\.)?""" + Regex.escape(variable) + """\s*=\s*\{""")
        for (match in marker.findAll(script)) {
            val start = match.range.last
            var depth = 0
            var quoted = false
            var escaped = false
            for (i in start until script.length) {
                val c = script[i]
                if (quoted) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                } else when (c) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return script.substring(start, i + 1) }
                }
            }
        }
        return null
    }

    private fun decodeSource(value: String): String? {
        val raw = value.trim()
        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("//")) return fixUrlNull(raw)
        return runCatching { fixUrlNull(atob(rtt(raw))) }.getOrNull()
    }

    private fun getVideoLinks(document: Document): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (script in document.select("script")) {
            val json = assignedObject(script.data(), "scx") ?: continue
            val root = runCatching { jacksonObjectMapper().readTree(json) }.getOrNull() ?: continue
            root.fields().forEach { (key, source) ->
                if (key in setOf("advid", "advidprox")) return@forEach
                val t = source.path("sx").path("t")
                fun add(label: String, raw: String) {
                    decodeSource(raw)?.let { result.add(label to it) }
                }
                when {
                    t.isArray -> t.forEach { if (it.isTextual) add(key, it.asText()) }
                    t.isObject -> t.fields().forEach { (lang, node) ->
                        if (node.isArray) node.forEach { if (it.isTextual) add(lang, it.asText()) }
                        else if (node.isTextual) add(lang, node.asText())
                    }
                    t.isTextual -> add(key, t.asText())
                }
            }
        }
        return result.distinctBy { it.first to it.second }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FHD", "loadLinks host=" + runCatching { java.net.URI(data).host }.getOrNull())
        val document = app.get(data, interceptor = cloudflare).document
        val videoLinks = getVideoLinks(document)
        if (videoLinks.isEmpty()) return false
        var emitted = false
        for ((label, videoUrl) in videoLinks) {
            runCatching {
                val target = if (videoUrl.contains("turbo.imgz.me")) "$label||$videoUrl" else videoUrl
                loadExtractor(target, "$mainUrl/", subtitleCallback) { link -> emitted = true; callback(link) }
            }.onFailure { Log.w("FHD", "extractor failed: ${it.javaClass.simpleName}") }
        }
        return emitted
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SCXData(
        @JsonProperty("atom")      val atom: AtomData?      = null,
        @JsonProperty("advid")     val advid: AtomData?     = null,
        @JsonProperty("advidprox") val advidprox: AtomData? = null,
        @JsonProperty("proton")    val proton: AtomData?    = null,
        @JsonProperty("fast")      val fast: AtomData?      = null,
        @JsonProperty("fastly")    val fastly: AtomData?    = null,
        @JsonProperty("tr")        val tr: AtomData?        = null,
        @JsonProperty("en")        val en: AtomData?        = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AtomData(
        @JsonProperty("sx") var sx: SXData
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SXData(
        @JsonProperty("t") var t: Any
    )
}
