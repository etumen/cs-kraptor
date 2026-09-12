package com.etumen.fhd

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

/** Uses CloudStream's own WebView handling. No remote proxy, credentials or alternate site. */
object RecoveryHttp {
    private val cloudflare by lazy { CloudflareKiller() }
    @JvmStatic fun interceptor(): Interceptor = Interceptor { chain ->
        var response = chain.proceed(chain.request())
        if (isChallenge(response)) {
            response.close()
            response = cloudflare.intercept(chain)
        }
        if (isChallenge(response)) {
            response.close()
            throw IOException("FHD: Cloudflare doğrulaması tamamlanamadı. Sağlayıcının CloudStream içindeki tarayıcısında doğrulama gerekli.")
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("FHD: Site HTTP $code döndürdü.")
        }
        response
    }

    private fun isChallenge(response: Response): Boolean = RecoveryParser.challenge(
        response.code, response.headers.toMap(), response.peekBody(16384).string()
    )
}

object RecoveryProvider {
    private fun decode(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
    private suspend fun document(url: String, referer: String? = null) =
        app.get(url, referer = referer, interceptor = RecoveryHttp.interceptor()).document

    @JvmStatic suspend fun search(api: MainAPI, query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val doc = document("${api.mainUrl.trimEnd('/')}/arama/$encoded")
        return RecoveryParser.searchCards(doc, api.mainUrl).map { card ->
            api.newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                posterUrl = card.poster
            }
        }
    }

    @JvmStatic suspend fun loadLinks(
        api: MainAPI, data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = RecoveryParser.httpUrl(data)
            ?: throw ErrorLoadingException("FHD: Geçersiz film adresi.")
        val targets = RecoveryParser.targets(document(pageUrl), ::decode)
        if (targets.isEmpty()) throw ErrorLoadingException("FHD: Film sayfasında desteklenen scx oynatıcı verisi bulunamadı.")
        var emitted = 0
        var failed = 0
        var lastError: String? = null
        for (target in targets) {
            try {
                val url = if (URI(target.url).host == "turbo.imgz.me") "${target.label}||${target.url}" else target.url
                loadExtractor(url, api.mainUrl.trimEnd('/') + "/", subtitleCallback) { link ->
                    if (RecoveryParser.httpUrl(link.url) != null) { callback(link); emitted++ }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failed++
                lastError = e.message
                // Never log full signed media URLs, cookies or query parameters.
                Log.w("FHD_RECOVERY", "Extractor ${URI(target.url).host}: ${e.javaClass.simpleName}")
            }
        }
        if (emitted == 0) throw ErrorLoadingException(
            "FHD: ${targets.size} oynatıcı bulundu, medya bağlantısı üretilemedi ($failed hata). " +
                if (lastError?.contains("Cloudflare") == true) "Cloudflare doğrulaması gerekli." else "Extractor güncellemesi gerekebilir."
        )
        return true
    }

    @JvmStatic suspend fun rapid(
        api: ExtractorApi, url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val html = document(url, referer).html()
        var media = RecoveryParser.rapidUrl(html, ::decode)
        if (media == null) {
            // v63 also supports twice-packed JS followed by a hexadecimal file URL.
            val unpacked = getAndUnpack(getAndUnpack(html))
            media = RecoveryParser.rapidUrl(unpacked, ::decode)
        }
        val resolved = media ?: throw ErrorLoadingException("FHD: RapidVid dosya biçimi çözülemedi.")
        RecoveryParser.tracks(html, url).forEach { subtitleCallback(SubtitleFile(it.label, it.url)) }
        callback(newExtractorLink(api.name, api.name, resolved, ExtractorLinkType.M3U8) {
            this.referer = referer ?: url
            headers = mapOf("Referer" to (referer ?: url))
        })
    }
}
