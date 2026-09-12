// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*


open class RapidVid : ExtractorApi() {
    private val cloudflare by lazy { CloudflareKiller() }
    override val name            = "RapidVid"
    override val mainUrl         = "https://rapidvid.net"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef = referer ?: ""
        val response = app.get(url, referer = extRef, interceptor = cloudflare)
        val html = response.text
        val document = response.document
        val mediaReferer = runCatching {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}/"
        }.getOrDefault(url)

        val p8 = Regex("""window\._p8\s*=\s*['\"]([^'\"]+)['\"]""").find(html)?.groupValues?.get(1)
        if (p8 != null) {
            val cfg = runCatching { ObjectMapper().readTree(decodeSecret(p8)) }.getOrNull()
            cfg?.path("ct")?.takeIf { it.isArray }?.forEach { track ->
                val file = track.path("file").asText()
                val label = track.path("label").asText("AltyazÄ±")
                if (file.startsWith("http")) subtitleCallback(newSubtitleFile(label, file))
            }
            val media = cfg?.path("cm")?.asText()?.takeIf { it.startsWith("http") }
                ?: cfg?.path("tm")?.asText()?.takeIf { it.startsWith("http") }
            if (media != null) {
                callback(newExtractorLink(name, name, media, ExtractorLinkType.M3U8) {
                    this.referer = mediaReferer
                    headers = mapOf("Referer" to mediaReferer)
                    quality = Qualities.Unknown.value
                })
                return
            }
        }

        val script = document.select("script").firstOrNull { it.data().contains("jwSetup.sources") }?.data().orEmpty()
        if (script.isEmpty()) return
        val jwTrack = script.substringAfter("jwSetup.tracks =", "").substringBefore(";")
        if (jwTrack.isNotBlank()) {
            runCatching {
                val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
                objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                objectMapper.readValue<List<CaptionData>>(jwTrack)
            }.getOrNull()?.forEach { track ->
                val label = track.label?.replace("\\u0131", "Ä±")?.replace("\\u0130", "Ä°")?.replace("\\u00fc", "Ã¼")?.replace("\\u00e7", "Ã§") ?: "AltyazÄ±"
                subtitleCallback(newSubtitleFile(label, track.file.replace("\\\\", "")))
            }
        }
        val jwSetup = script.substringAfter("jwSetup.sources =", "").substringBefore(";")
        val encoded = Regex("""av\(\s*['\"]([^'\"]+)['\"]\s*\)""").find(jwSetup)?.groupValues?.get(1)
        val direct = Regex("""file\s*:\s*['\"](https?://[^'\"]+)['\"]""").find(jwSetup)?.groupValues?.get(1)
        val media = encoded?.let { runCatching { av(it) }.getOrNull() } ?: direct
        if (!media.isNullOrBlank()) {
            callback(newExtractorLink(name, name, media.replace("\\/", "/"), ExtractorLinkType.M3U8) {
                this.referer = mediaReferer
                headers = mapOf("Referer" to mediaReferer)
                quality = Qualities.Unknown.value
            })
        } else Log.d("Rapid", "No playable source found.")
    }

    fun av(o: String): String {
        return decodeSecret(o)
    }


    fun decodeSecret(encodedString: String): String {
        val reversedBase64Input = encodedString.reversed()
        val tString = base64Decode(reversedBase64Input)
        val oBuilder = StringBuilder()
        val key = "K9L"
        for (index in tString.indices) {
            val keyChar = key[index % key.length]
            val offset = keyChar.code % 5 + 1

            val originalCharCode = tString[index].code
            val transformedCharCode = originalCharCode - offset
            oBuilder.append(transformedCharCode.toChar())
        }

        val finalResultBytes = base64Decode(oBuilder.toString())
        return finalResultBytes
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CaptionData(
    @JsonProperty("kind") var kind: String,
    @JsonProperty("file") var file: String,
    @JsonProperty("label") var label: String? = null,
)