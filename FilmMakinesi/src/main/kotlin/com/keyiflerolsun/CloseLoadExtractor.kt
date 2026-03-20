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
            val realUrl = decryptWithWebView(com.lagradost.cloudstream3.CloudStreamApp.context, response.document)

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

    private suspend fun decryptWithWebView(context: Context?, document: Document): String? = withContext(Dispatchers.Main) {
        try {
            // 1. KOTLIN TARAFI: Regex eziyetine son! Jsoup ile scripti doğrudan yakalıyoruz.
            // HTML içindeki tüm <script> taglerini dolaş ve içinde packer fonksiyonu olanı bul.
            val scriptContent = document.select("script").map { it.data() }.firstOrNull {
                // Boşlukları silip arayarak, site sahibinin araya koyduğu tuzak boşlukları bypass ediyoruz
                it.replace("\\s".toRegex(), "").contains("eval(function(p,a,c,k,e,d)")
            }

            if (scriptContent.isNullOrBlank()) {
                Log.e("Kekik_Extractor", "Deşifre Başarısız: Document içinde Packer scripti bulunamadı.")
                return@withContext null
            }

            // 2. KOTLIN TARAFI: Syntax hatası yememek için yine Base64 Köprüsü kullanıyoruz
            val base64Script = Base64.encodeToString(scriptContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val webView = context?.let { WebView(it) }
            webView?.settings?.javaScriptEnabled = true

            webView?.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    Log.d("Kekik_JS", "${consoleMessage?.messageLevel()}: ${consoleMessage?.message()}")
                    return true
                }
            }

            // 3. JAVASCRIPT TARAFI: Eval Hijacking (Zekice Çözüm)
            val jsToExecute = """
                (function() {
                    try {
                        var rawScript = decodeURIComponent(escape(window.atob('$base64Script')));
                        
                        var interceptedCode = "";
                        var originalEval = window.eval;
                        
                        // Eval fonksiyonunu gasp ediyoruz (Hijack)
                        window.eval = function(code) {
                            interceptedCode = code; // Çözülmüş orijinal kodu yakaladık!
                            window.eval = originalEval; // Sistemi bozmamak için eval'i eski haline getiriyoruz
                            return originalEval(code); // Kodu normal şekilde çalıştır ki değişkenler oluşsun
                        };
                        
                        // Siteye ait scripti çalıştır. Bu işlem sırasında bizim sahte eval'imiz tetiklenecek.
                        try {
                            originalEval(rawScript);
                        } catch(e) {
                            console.log("Script calisirken onemsiz bir hata verdi (Görmezden gelinebilir): " + e.message);
                        }
                        
                        if (!interceptedCode) return "Error: Eval tetiklenmedi, kod yakalanamadi.";
                        
                        console.log("Kod basariyla gasp edildi! Degisken araniyor...");
                        
                        // Player'ın kaynak (src) atandığı satırı bul
                        var varNameMatch = interceptedCode.match(/\.2a\s*\(\s*\{\s*2a\s*:\s*([a-zA-Z0-9]+)/);
                        var targetVar = varNameMatch ? varNameMatch[1] : null;
                        
                        console.log("Tespit edilen degisken adi: " + targetVar);

                        // Degiskenin icindeki linki al
                        if (targetVar && typeof window[targetVar] !== 'undefined') {
                            var finalUrl = window[targetVar];
                            if (finalUrl && finalUrl.indexOf('http') !== -1) {
                                return finalUrl;
                            }
                        }
                        
                        // Fallback: Bilinen değişkenleri doğrudan kontrol et
                        if (typeof window['3i'] !== 'undefined' && typeof window['3i'] === 'string' && window['3i'].indexOf('http') !== -1) return window['3i'];
                        if (typeof window['2K'] !== 'undefined' && typeof window['2K'] === 'string' && window['2K'].indexOf('http') !== -1) return window['2K'];

                        return "Error: Link bulunamadi. Gasp edilen kod baslangici: " + interceptedCode.substring(0, 100);
                    } catch(e) { 
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