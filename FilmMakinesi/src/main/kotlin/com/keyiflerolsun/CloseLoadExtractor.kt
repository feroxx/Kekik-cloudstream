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
            val webView = context?.let { WebView(it) }
            webView?.settings?.javaScriptEnabled = true

            // WebView içindeki console.log() çıktılarını Logcat'e (Kekik_JS) yönlendiriyoruz
            webView?.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    Log.d("Kekik_JS", "${consoleMessage?.messageLevel()}: ${consoleMessage?.message()}")
                    return true
                }
            }

            val safeHtml = html.replace("`", "\\`").replace("$", "\\$")

            val jsToExecute = """
            (function() {
                try {
                    console.log("Deşifre işlemi başlatıldı...");
                    var htmlContent = `${safeHtml}`;
                    
                    // 1. Step: Packer tespiti
                    var scriptMatch = htmlContent.match(/eval\(function\(p,a,c,k,e,d\).+?\.split\('\|'\)\)\)/);
                    if (!scriptMatch) return "Error: Packer script bloğu bulunamadı";
                    
                    console.log("Packer bloğu yakalandı, unpack ediliyor...");
                    var rawScript = scriptMatch[0];
                    var unpacked = eval(rawScript.replace('eval(', '('));
                    
                    if (!unpacked) return "Error: Unpack işlemi başarısız oldu";
                    console.log("Unpack başarılı. İçerik execute ediliyor...");

                    // 2. Step: Unpacked kodu çalıştır (Fonksiyonları tanımla)
                    eval(unpacked);
                    
                    // 3. Step: Değişken tespiti
                    // Player init satırını ara: .2a({2a:DEGISKEN
                    var varNameMatch = unpacked.match(/\.2a\s*:\s*([a-zA-Z0-9]+)/) || 
                                       unpacked.match(/\.2a\s*\(\s*\{\s*2a\s*:\s*([a-zA-Z0-9]+)/);
                    
                    var targetVar = varNameMatch ? varNameMatch[1] : null;
                    console.log("Tespit edilen değişken adı: " + targetVar);

                    if (targetVar) {
                        var finalUrl = eval(targetVar);
                        if (finalUrl && finalUrl.indexOf('http') !== -1) {
                            console.log("Gerçek URL başarıyla çözüldü.");
                            return finalUrl;
                        }
                    }
                    
                    // Fallback: Bilinen değişkenleri tek tek dene
                    console.log("Regex başarısız, manuel fallback deneniyor...");
                    if (typeof 3i !== 'undefined' && 3i.indexOf('http') !== -1) return 3i;
                    if (typeof 2K !== 'undefined' && 2K.indexOf('http') !== -1) return 2K;

                    return "Error: Link değişkeni (3i/2K) bulunamadı veya boş.";
                } catch(e) { 
                    console.error("JS Execution Error: " + e.message);
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
                        Log.i("Kekik_Extractor", "Başarılı! URL: $cleanResult")
                        cont.resume(cleanResult)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Kekik_Extractor", "WebView Critical Error: ${e.message}")
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