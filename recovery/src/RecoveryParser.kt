package com.etumen.fhd

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.Document
import java.net.URI

/** Reconstructed from the v63 DEX protocol; does not claim to reproduce v72. */
object RecoveryParser {
    private val mapper = ObjectMapper()
    data class Target(val label: String, val url: String)
    data class Card(val title: String, val url: String, val poster: String?)

    fun searchCards(document: Document, base: String): List<Card> = document.select("li.film").mapNotNull { item ->
        val title = item.selectFirst("span.film-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        val url = httpUrl(item.selectFirst("a[href]")?.attr("href").orEmpty(), base)
            ?: return@mapNotNull null
        val img = item.selectFirst("img")
        val poster = img?.attr("data-src")?.ifBlank { img.attr("src") }?.takeIf { it.isNotBlank() }
        Card(title, url, poster?.let { httpUrl(it, base) })
    }.distinctBy { it.url }

    fun challenge(status: Int, headers: Map<String, String>, html: String): Boolean {
        val h = headers.mapKeys { it.key.lowercase() }
        return h["cf-mitigated"].equals("challenge", true) ||
            (status in listOf(403, 503) && h["server"].orEmpty().contains("cloudflare", true) &&
                listOf("/cdn-cgi/challenge-platform/", "cf_chl_opt", "Just a moment", "Performing security verification")
                    .any { html.contains(it, true) })
    }

    fun httpUrl(value: String, base: String = ""): String? = runCatching {
        require(value.isNotBlank())
        val uri = URI(base).resolve(value.trim())
        uri.toString().takeIf { uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null }
    }.getOrNull()

    /** Balanced JSON scanning handles multiline objects and braces/semicolons inside strings. */
    fun assignedObject(script: String, variable: String): String? {
        val pattern = Regex("(?:^|[;\\s])(?:var\\s+|let\\s+|const\\s+)?(?:window\\.)?" + Regex.escape(variable) + "\\s*=\\s*\\{")
        for (match in pattern.findAll(script)) {
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

    private fun rot13(s: String) = s.map {
        when (it) {
            in 'a'..'z' -> ('a'.code + (it - 'a' + 13) % 26).toChar()
            in 'A'..'Z' -> ('A'.code + (it - 'A' + 13) % 26).toChar()
            else -> it
        }
    }.joinToString("")

    fun targets(document: Document, decode: (String) -> ByteArray): List<Target> {
        val result = mutableListOf<Target>()
        for (script in document.select("script")) {
            val json = assignedObject(script.data(), "scx") ?: continue
            val root = runCatching { mapper.readTree(json) }.getOrNull() ?: continue
            for ((key, source) in root.fields()) {
                val target = source.path("sx").path("t")
                fun add(label: String, node: JsonNode) {
                    if (!node.isTextual) return
                    val raw = node.asText().trim()
                    val url = httpUrl(raw) ?: runCatching {
                        httpUrl(decode(rot13(raw)).toString(Charsets.UTF_8))
                    }.getOrNull()
                    if (url != null) result.add(Target(label, url))
                }
                when {
                    target.isArray -> target.forEach { add(key, it) }
                    target.isObject -> target.fields().forEach { (lang, value) ->
                        if (value.isArray) value.forEach { add(lang, it) } else add(lang, value)
                    }
                    target.isTextual -> add(key, target)
                }
            }
        }
        return result.distinctBy { it.label to it.url }
    }

    /** The two algorithms visible in the v63 RapidVid class, with tolerant field syntax. */
    fun rapidUrl(script: String, decode: (String) -> ByteArray): String? {
        val av = Regex("(?:[\"']?file[\"']?)\\s*:\\s*av\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)")
        for (match in av.findAll(script)) {
            val decoded = runCatching {
                val first = decode(match.groupValues[1].reversed())
                val adjusted = ByteArray(first.size) { i ->
                    ((first[i].toInt() and 255) - ("K9L"[i % 3].code % 5 + 1)).toByte()
                }
                decode(adjusted.toString(Charsets.UTF_8)).toString(Charsets.UTF_8)
            }.getOrNull()
            httpUrl(decoded.orEmpty())?.let { return it }
        }
        val file = Regex("(?:[\"']?file[\"']?)\\s*:\\s*(['\"])(.*?)\\1", RegexOption.DOT_MATCHES_ALL)
        for (match in file.findAll(script)) {
            val raw = match.groupValues[2].replace("\\/", "/").replace("\\u0026", "&")
            httpUrl(raw)?.let { return it }
            if (raw.length % 2 == 0 && raw.isNotEmpty() && raw.all { it in "0123456789abcdefABCDEF" }) {
                val value = raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
                httpUrl(value)?.let { return it }
            }
        }
        return null
    }

    fun tracks(script: String, base: String): List<Target> {
        val assignment = Regex("jwSetup\\.tracks\\s*=\\s*(\\[.*?])\\s*;", RegexOption.DOT_MATCHES_ALL)
        val node = assignment.find(script)?.groupValues?.get(1)?.let {
            runCatching { mapper.readTree(it) }.getOrNull()
        } ?: return emptyList()
        return node.mapNotNull {
            if (it.path("kind").asText() !in listOf("captions", "subtitles")) return@mapNotNull null
            val url = httpUrl(it.path("file").asText(), base) ?: return@mapNotNull null
            Target(it.path("label").asText().ifBlank { "Altyazı" }, url)
        }
    }
}
