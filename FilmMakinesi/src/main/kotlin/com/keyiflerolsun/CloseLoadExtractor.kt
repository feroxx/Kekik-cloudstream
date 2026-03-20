package com.keyiflerolsun

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import android.util.Base64

class CloseLoad : ExtractorApi() {
    override val name = "CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    @OptIn(Prerelease::class)
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers2 = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0",
            "Referer" to "https://closeload.filmmakinesi.to/",
            "Origin" to "https://closeload.filmmakinesi.to"
        )

        try {
            val response = app.get(url, referer = mainUrl, headers = headers2)
            val html = response.text

            // WebView ile JS deşifre işlemi
            // 'app.context' Cloudstream'in sağladığı global context'tir.
            val realUrl = decryptWithWebView(com.lagradost.cloudstream3.CloudStreamApp.context, html)

            if (!realUrl.isNullOrBlank() && realUrl.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = realUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.P1080.value
                        headers = mapOf(
                            "Referer" to "https://closeload.filmmakinesi.to/",
                            "User-Agent" to headers2["User-Agent"]!!
                        )
                    }
                )
            } else {
                Log.e("Kekik_${this.name}", "Real URL deşifre edilemedi.")
            }

            processSubtitles(response.document, subtitleCallback)

        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Hata: ${e.message}")
        }
    }

    private suspend fun decryptWithWebView(context: Context?, html: String): String? = withContext(Dispatchers.Main) {
        try {
            // 1. KOTLIN TARAFI: Sadece ihtiyacımız olan şifreli JS bloğunu çekiyoruz
            // DOT_MATCHES_ALL parametresi satır atlamalarını da dahil etmemizi sağlar
            val packerRegex = """eval\(function\(p, a, c, k, e, d\).+?\.split\('\|'\)\)\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val scriptMatch = packerRegex.find(html)

            if (scriptMatch == null) {
                Log.e("Kekik_Extractor", "Deşifre Başarısız: HTML içinde Packer bloğu bulunamadı.")
                return@withContext null
            }

            // 2. KOTLIN TARAFI: JS tarafında syntax hatası yememek için Base64'e çeviriyoruz
            val rawEvalScript = scriptMatch.value
            val base64Script = Base64.encodeToString(rawEvalScript.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val webView = context?.let { WebView(it) }
            webView?.settings?.javaScriptEnabled = true

            // Logları takip etmeye devam edelim
            webView?.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    Log.d("Kekik_JS", "${consoleMessage?.messageLevel()}: ${consoleMessage?.message()}")
                    return true
                }
            }

            // 3. JAVASCRIPT TARAFI: Base64'ü çöz, Unpack et, Değişkeni Bul
            val jsToExecute = """
            (function() {
                try {
                    console.log("Base64 script alindi, decode ediliyor...");
                    // Base64'ten UTF-8 string'e güvenli dönüşüm
                    var rawScript = decodeURIComponent(escape(window.atob('$base64Script')));
                    
                    console.log("Unpack islemi basliyor...");
                    // eval'i paranteze cevirip calistiriyoruz ki icindeki string'i (unpacked code) alalim
                    var unpacked = eval(rawScript.replace('eval(', '('));
                    
                    if (!unpacked) return "Error: Unpack islemi basarisiz oldu.";
                    
                    console.log("Unpack basarili. Degiskenler hafizaya aliniyor...");
                    eval(unpacked); // Kodu calistir ve 3i, 3n vb. degiskenleri olustur
                    
                    // Player source regex'i: .2a({2a:VAR_NAME
                    var varNameMatch = unpacked.match(/\.2a\s*\(\s*\{\s*2a\s*:\s*([a-zA-Z0-9]+)/);
                    var targetVar = varNameMatch ? varNameMatch[1] : null;
                    
                    console.log("Tespit edilen degisken: " + targetVar);

                    if (targetVar) {
                        var finalUrl = eval(targetVar);
                        if (finalUrl && finalUrl.indexOf('http') !== -1) {
                            return finalUrl;
                        }
                    }
                    
                    // Fallback kontrolleri
                    console.log("Regex kacirdi, fallback deneniyor...");
                    if (typeof 3i !== 'undefined' && typeof 3i === 'string' && 3i.indexOf('http') !== -1) return 3i;
                    if (typeof 2K !== 'undefined' && typeof 2K === 'string' && 2K.indexOf('http') !== -1) return 2K;

                    return "Error: Link bulunamadi. Unpacked baslangici: " + unpacked.substring(0, 50);
                } catch(e) { 
                    console.error("Execution Error: " + e.message);
                    return "Error: " + e.message; 
                }
            })()
        """.trimIndent()

            suspendCoroutine { cont ->
                webView?.evaluateJavascript(jsToExecute) { result ->
                    val cleanResult = result?.trim()?.removeSurrounding("\"")

                    if (cleanResult == null || cleanResult == "null" || cleanResult.isEmpty() || cleanResult.startsWith("Error:")) {
                        Log.e("Kekik_Extractor", "Deşifre Başarısız: $cleanResult")
                        cont.resume(null)
                    } else {
                        Log.i("Kekik_Extractor", "Başarılı! Gerçek Link: $cleanResult")
                        cont.resume(cleanResult)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Kekik_Extractor", "Sistem Hatası: ${e.message}")
            null
        }
    }

    private fun processSubtitles(document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track").forEach { track ->
            val rawSrc = track.attr("src").trim()
            val label = track.attr("label").ifBlank { track.attr("srclang").ifBlank { "Altyazı" } }

            if (rawSrc.isNotBlank()) {
                val fullUrl = if (rawSrc.startsWith("http")) rawSrc else mainUrl + rawSrc
                if (fullUrl.startsWith("http")) {
                    subtitleCallback(SubtitleFile(label, fullUrl))
                }
            }
        }
    }
}