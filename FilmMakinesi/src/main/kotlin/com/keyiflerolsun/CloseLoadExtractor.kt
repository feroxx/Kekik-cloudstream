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
            // 1. KOTLIN TARAFI: HTML içindeki şifreli (Packer) JS bloğunu Jsoup ile alıyoruz.
            val scriptContent = document.select("script").map { it.data() }.firstOrNull {
                it.replace("\\s".toRegex(), "").contains("eval(function(p,a,c,k,e,d)")
            }

            if (scriptContent.isNullOrBlank()) {
                Log.e("Kekik_Extractor", "Deşifre Başarısız: Packer scripti bulunamadı.")
                return@withContext null
            }

            val base64Script = Base64.encodeToString(scriptContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val webView = context?.let { WebView(it) }
            webView?.settings?.javaScriptEnabled = true

            webView?.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    Log.d("Kekik_JS", "${consoleMessage?.messageLevel()}: ${consoleMessage?.message()}")
                    return true
                }
            }

            // 3. JAVASCRIPT TARAFI: Deep Proxy Sandboxing (Zekice Çözüm)
            val jsToExecute = """
            (function() {
                try {
                    var rawScript = decodeURIComponent(escape(window.atob('$base64Script')));
                    
                    window.extractedUrl = null;
                    window.proxyDepth = 0;
                    
                    // Sahte Linkleri ekarte edip gerçek M3U8/MP4/TXT linkini bulan filtre
                    function isValidUrl(s) {
                        return typeof s === 'string' && 
                               s.indexOf('http') === 0 && 
                               s.length > 20 && 
                               s.indexOf(' ') === -1 && 
                               (s.indexOf('mp4') !== -1 || s.indexOf('m3u8') !== -1 || s.indexOf('master') !== -1 || s.indexOf('hls') !== -1);
                    }
                    
                    // Deep Proxy: Hata yutan, sonsuz derinlikte sahte obje oluşturucu
                    function createDeepProxy(name) {
                        var noop = function(){};
                        return new Proxy(noop, {
                            get: function(target, prop) {
                                if (prop === 'toString' || prop === 'valueOf') return function(){ return name; };
                                if (prop === Symbol.toPrimitive) return function(){ return name; };
                                return createDeepProxy(name + '.' + String(prop));
                            },
                            apply: function(target, thisArg, argsList) {
                                for (var i = 0; i < argsList.length; i++) {
                                    var arg = argsList[i];
                                    
                                    // Argüman direkt string URL ise yakala
                                    if (isValidUrl(arg)) {
                                        window.extractedUrl = arg;
                                    } 
                                    // Argüman obje ise (örn: {src: "http..."}) içindeki değerleri kontrol et
                                    else if (arg && typeof arg === 'object') {
                                        for (var k in arg) {
                                            if (isValidUrl(arg[k])) {
                                                window.extractedUrl = arg[k];
                                            }
                                        }
                                    } 
                                    // Argüman bir fonksiyonsa (örn: .ready(function(){...})) anında çalıştır
                                    else if (typeof arg === 'function') {
                                        if (window.proxyDepth < 20) {
                                            window.proxyDepth++;
                                            try { arg(); } catch(e) { console.error("Callback Hatasi: " + e.message); }
                                            window.proxyDepth--;
                                        }
                                    }
                                }
                                return createDeepProxy(name + '()');
                            },
                            set: function() { return true; } // Atamalarda çökmemesi için (örn: videojs.Vhs.GOAL = 90)
                        });
                    }

                    // Eksik olan kütüphaneleri ($, videojs, document) sahte (Proxy) objelerle dolduruyoruz
                    // Artık $(document).ready(...) veya videojs.Player... hata vermeyecek!
                    window.$ = window.jQuery = createDeepProxy('$');
                    window.videojs = createDeepProxy('videojs');
                    window.document = createDeepProxy('document');
                    
                    var interceptedCode = "";
                    var originalEval = window.eval;
                    
                    // Eval Hijack
                    window.eval = function(code) {
                        interceptedCode = code;
                        window.eval = originalEval;
                        return originalEval(code); // Kodu çalıştır ki değişkenler hesaplansın
                    };
                    
                    // Packer scriptini başlat
                    try {
                        originalEval(rawScript);
                    } catch(e) {
                        console.log("Onemsiz Packer Hatasi (Görmezden gelinebilir): " + e.message);
                    }
                    
                    // Eğer Proxy tuzağımıza URL düştüyse işlemi bitir
                    if (window.extractedUrl) {
                        console.log("MUKEMMEL: URL Proxy Sandigi'na dustu!");
                        return window.extractedUrl;
                    }

                    return "Error: URL bulunamadı. Proxy bos kaldi.";
                } catch(e) { 
                    return "Error: Fatal JS Error - " + e.message; 
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