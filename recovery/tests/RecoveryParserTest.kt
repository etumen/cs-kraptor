package com.etumen.fhd

import org.jsoup.Jsoup
import java.util.Base64

/** Synthetic protocol fixtures. These are NOT live-site or Android playback tests. */
fun main() {
    var count = 0
    fun test(name: String, body: () -> Unit) { body(); count++; println("PASS $name") }
    val decode: (String) -> ByteArray = { Base64.getDecoder().decode(it) }
    val base = "https://example.test"
    fun targets(js: String) = RecoveryParser.targets(Jsoup.parse("<script>$js</script>"), decode)

    test("search: title, relative URL and lazy poster") {
        val doc = Jsoup.parse("""<li class="film"><a href="/film/test/"><img data-src="/p.jpg"><span class="film-title"> Türkçe Film </span></a></li>""")
        check(RecoveryParser.searchCards(doc, base) == listOf(RecoveryParser.Card("Türkçe Film", "$base/film/test/", "$base/p.jpg")))
    }
    test("search: missing href is rejected; duplicates collapse; src fallback") {
        val card = """<li class="film"><a href="/film/a"><span class="film-title">A</span><img src="/a.jpg"></a></li>"""
        val doc = Jsoup.parse(card + card + "<li class=film><span class=film-title>Missing</span></li>")
        val cards = RecoveryParser.searchCards(doc, base)
        check(cards.size == 1 && cards[0].poster == "$base/a.jpg")
    }
    test("search: genuine empty result stays empty") { check(RecoveryParser.searchCards(Jsoup.parse("<ul></ul>"), base).isEmpty()) }
    test("scx: no spaces and newline JSON") {
        val js = "scx={\n\"atom\":{\"sx\":{\"t\":[\"$base/embed\"]}}\n};"
        check(targets(js).single().url == "$base/embed")
        check(!js.contains("scx =")) // v63's script-selection predicate rejects this fixture.
    }
    test("scx: quoted braces and semicolons do not truncate JSON") {
        val js = """var scx = {"note":"}; { quoted","atom":{"sx":{"t":["https://example.test/a?x=a,b"]}}};"""
        check(targets(js).single().url == "$base/a?x=a,b")
    }
    test("scx: one malformed encoded target does not discard a valid sibling") {
        check(targets("""scx = {"a":{"sx":{"t":["!!!","$base/ok"]}}};""").single().url == "$base/ok")
    }
    test("scx: scalar and language maps and language arrays") {
        val t = targets("""window.scx={"a":{"sx":{"t":"$base/a"}},"b":{"sx":{"t":{"tr":"$base/tr","en":["$base/en"]}}}};""")
        check(t.map { it.label } == listOf("a", "tr", "en"))
    }
    test("scx: later script remains usable after unrelated and malformed scripts") {
        val doc = Jsoup.parse("""<script>var x=1;</script><script>scx = {bad};</script><script>let scx={"a":{"sx":{"t":["$base/a"]}}};</script>""")
        check(RecoveryParser.targets(doc, decode).single().url == "$base/a")
    }
    test("scx: duplicate targets and non-object metadata") {
        check(targets("""scx={"x":null,"a":{"sx":{"t":["$base/a","$base/a"]}}};""").size == 1)
    }
    test("scx: ROT13+Base64 fixture") {
        // ROT13 of a standard base64 encoding of https://example.test/video.
        val encoded = "nUE0pUZ6Yl9yrTSgpTkyYaEyp3DiqzyxMJ8="
        check(targets("""scx={"a":{"sx":{"t":["$encoded"]}}};""").single().url == "$base/video")
    }
    test("URL validation rejects scripts, blank values and credentials") {
        for (bad in listOf("javascript:alert(1)", "", "https://u:p@example.test/a", "file:///a"))
            check(RecoveryParser.httpUrl(bad, base) == null)
    }
    test("Cloudflare: cf-mitigated header is conclusive") {
        check(RecoveryParser.challenge(403, mapOf("CF-Mitigated" to "challenge"), ""))
    }
    test("Cloudflare: bare 403 is not diagnosed as Cloudflare") {
        check(!RecoveryParser.challenge(403, mapOf("server" to "nginx"), "Forbidden"))
        check(!RecoveryParser.challenge(403, mapOf("server" to "cloudflare"), "Forbidden"))
        check(RecoveryParser.challenge(503, mapOf("server" to "cloudflare"), "Just a moment"))
    }
    test("RapidVid: direct file supports whitespace and escaped slashes") {
        check(RecoveryParser.rapidUrl("""jwSetup.sources=[{ "file" : "https:\/\/example.test\/master.m3u8" }];""", decode) == "$base/master.m3u8")
    }
    test("RapidVid: hexadecimal v63 fallback") {
        check(RecoveryParser.rapidUrl("""{"file":"68747470733a2f2f6578616d706c652e746573742f762e6d337538"}""", decode) == "$base/v.m3u8")
    }
    test("RapidVid: malformed av is isolated from subsequent usable sources") {
        check(RecoveryParser.rapidUrl("""{file:av('!!!')},{file:'$base/v.m3u8'}""", decode) == "$base/v.m3u8")
    }
    test("RapidVid: v63 reverse Base64 K9L offset fixture") {
        val encrypted = "2gFNPdnNrd2dTZDZuV1bO9WeJZWdIpkZuxjeOljTKZWMUtkY"
        check(RecoveryParser.rapidUrl("{file : av('$encrypted')}", decode) == "$base/v.m3u8")
    }
    test("RapidVid: invalid captions cannot suppress the media URL") {
        check(RecoveryParser.tracks("jwSetup.tracks = [broken];", base).isEmpty())
        check(RecoveryParser.rapidUrl("""jwSetup.tracks = [broken]; {file:'$base/v.m3u8'}""", decode) == "$base/v.m3u8")
    }
    test("RapidVid: only subtitle/caption tracks, with relative URLs") {
        val tracks = RecoveryParser.tracks("""jwSetup.tracks = [{"kind":"captions","file":"/tr.vtt","label":"Türkçe"},{"kind":"thumbnails","file":"/thumb.jpg"}];""", base)
        check(tracks == listOf(RecoveryParser.Target("Türkçe", "$base/tr.vtt")))
    }
    println("$count offline parser tests passed; live playback NOT tested")
}
