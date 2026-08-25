package com.scrapeflix.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.scrapeflix.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.*

data class ProfileSuggestion(
    val item: String,
    val title: String,
    val image: String,
    val link: String,
    val description: String,
    val confidence: Int,
    val sampleTitles: List<String>
)

data class LivePreviewItem(
    val title: String,
    val url: String,
    val imageUrl: String?,
    val description: String?
)

private fun Element.resolveUrl(raw: String): String {
    val trimmed = raw.trim()
    return try {
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else java.net.URL(java.net.URL(baseUri()), trimmed).toString()
    } catch (e: Exception) { trimmed }
}

private fun firstFromSrcset(srcset: String): String? =
    srcset.split(",").firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeUnless { it.isBlank() }

private fun Element.bestImage(): String? {
    // 1) inline CSS background-image (very common on card/poster grids)
    val bgRegex = Regex("""url\(\s*['\"]?([^'\")]+)['\"]?\s*\)""")
    val bgHost = if (attr("style").contains("background-image")) this else selectFirst("[style*=background-image]")
    bgHost?.let { el ->
        bgRegex.find(el.attr("style"))?.groupValues?.get(1)?.let { raw ->
            if (raw.isNotBlank() && !raw.startsWith("data:")) return el.resolveUrl(raw)
        }
    }
    // 2) plain <img> — lazy-load attribute'ları ÖNCE kontrol et, çünkü çoğu site "src"
    //    alanına şeffaf bir placeholder (genelde base64 "data:" resmi) koyup gerçek
    //    görseli data-src/data-lazy-src gibi alanlarda tutuyor.
    selectFirst("img")?.let { img ->
        sequenceOf("data-src", "data-lazy-src", "data-original", "data-lazy", "src")
            .map { img.absUrl(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { return it }
        val srcset = img.attr("srcset").ifBlank { img.attr("data-srcset") }
        if (srcset.isNotBlank()) firstFromSrcset(srcset)?.takeUnless { it.startsWith("data:") }?.let { return img.resolveUrl(it) }
    }
    // 3) <picture><source srcset=...>
    selectFirst("picture source[srcset]")?.let { src ->
        firstFromSrcset(src.attr("srcset"))?.takeUnless { it.startsWith("data:") }?.let { return src.resolveUrl(it) }
    }
    return null
}

private fun Element.guessTitle(): String =
    selectFirst("h1,h2,h3,h4,h5,h6,[class*=title],[class*=name]")?.text()?.trim()
        .takeUnless { it.isNullOrBlank() } ?: selectFirst("a[href]")?.text()?.trim().orEmpty()

private fun normalizeUrl(url: String) = if (url.trim().startsWith("http://") || url.trim().startsWith("https://")) url.trim() else "https://${url.trim()}"

data class MenuLink(val label: String, val url: String)

private fun normalizeUrlKey(url: String): String = url.substringBefore('#').trimEnd('/')

/** Bir listeleme/menü sayfasında "sonraki sayfa" (sayfalama) linkini bulmaya çalışır. */
private fun findNextPageUrl(doc: Document, baseUrl: String): String? {
    doc.selectFirst("link[rel=next]")?.absUrl("href")?.takeUnless { it.isBlank() }?.let { return it }
    val candidates = doc.select(
        "a[rel=next], .pagination a[href], [class*=pagination] a[href], [class*=page-numbers] a[href], nav[class*=page] a[href]"
    )
    for (a in candidates) {
        val t = a.text().trim().lowercase(Locale.getDefault())
        val rel = a.attr("rel")
        if (rel == "next" || t in setOf("sonraki", "next", "»", "ileri", ">", "sonraki sayfa")) {
            val href = a.absUrl("href")
            if (href.isNotBlank() && normalizeUrlKey(href) != normalizeUrlKey(baseUrl)) return href
        }
    }
    return null
}

/** Sitenin nav/menü alanlarındaki alt sayfa linklerini, görünen menü etiketiyle birlikte bulur
 *  (aynı domain, tekrarsız). Bu etiket, o alt sayfadan çıkan içeriklerin başlığı olarak
 *  kullanılacak — uygulama kendi kategorilerini uydurmuyor, sitenin kendi menüsünü yansıtıyor. */
private fun discoverSubPages(doc: Document, baseUrl: String, limit: Int = 15): List<MenuLink> {
    val baseHost = try { java.net.URI(baseUrl).host } catch (e: Exception) { null } ?: return emptyList()
    val base = baseUrl.trimEnd('/')
    return doc.select("nav a[href], header a[href], [class*=menu] a[href], [class*=nav] a[href], [class*=categor] a[href], [id*=menu] a[href], [id*=nav] a[href]")
        .mapNotNull { a ->
            val href = a.absUrl("href").trim()
            val label = a.text().trim()
            if (href.isBlank() || label.isBlank() || label.length > 40) return@mapNotNull null
            if (!href.startsWith("http") || href.trimEnd('/') == base || href.contains("#")) return@mapNotNull null
            if ((try { java.net.URI(href).host } catch (e: Exception) { null }) != baseHost) return@mapNotNull null
            MenuLink(label, href)
        }
        .distinctBy { it.url }
        .take(limit)
}

private val yearRegex = Regex("""(19|20)\d{2}""")

/** Kartın içindeki metinden yıl bilgisini yakalamaya çalışır: önce yıl/tarih benzeri
 *  class'lara bakar, bulamazsa kartın tüm metninde 4 haneli bir yıl arar. */
private fun Element.guessYear(): String? {
    selectFirst("[class*=year], [class*=yil], [class*=tarih], [class*=date]")?.text()?.let { t ->
        yearRegex.find(t)?.value?.let { return it }
    }
    return yearRegex.find(text())?.value
}

/** Kartın içindeki puan/imdb/rating benzeri class'lardan kısa bir metin yakalamaya çalışır. */
private fun Element.guessRating(): String? {
    val el = selectFirst("[class*=rating], [class*=puan], [class*=imdb], [class*=score], [class*=oy]") ?: return null
    val t = el.text().trim()
    return t.takeIf { it.isNotBlank() && it.length <= 12 }
}

/** Verilen bir HTML dokümanından, sitenin selector'larına göre içerik kartlarını çıkarır.
 *  categoryLabel, bu sayfanın hangi site menüsünden geldiğini belirtir (uygulamanın kendi
 *  kategorisi değil, sitenin kendi menü adı). */
private fun extractItems(doc: Document, site: SiteEntity, categoryLabel: String): List<ScrapedItemEntity> {
    val selector = site.itemSelector.ifBlank { "article, .card, .item" }
    return doc.select(selector).mapNotNull { el ->
        val link = if (el.tagName() == "a") el else el.selectFirst(site.linkSelector.ifBlank { "a[href]" })
        val href = link?.absUrl("href").orEmpty(); if (href.isBlank()) return@mapNotNull null
        val title = el.selectFirst(site.titleSelector.ifBlank { "h1,h2,h3,h4,h5,h6,[class*=title],[class*=name]" })
            ?.text()?.trim().takeUnless { it.isNullOrBlank() } ?: link?.text()?.trim().orEmpty()
        val image = el.selectFirst(site.imageSelector.ifBlank { "img" })?.let { it.bestImage() }
        val desc = el.selectFirst(site.descriptionSelector.ifBlank { "p,[class*=description],[class*=summary]" })?.text()?.trim()
        if (title.length < 2) null else ScrapedItemEntity(
            siteId = site.id, title = title, url = href, imageUrl = image,
            description = desc?.ifBlank { null }, category = categoryLabel.ifBlank { "Diğer" },
            year = el.guessYear(), rating = el.guessRating()
        )
    }
}

/** İçeriği tıklandığında tarayıcı yerine cihazdaki uygun uygulamalar arasından seçim yaptırır.
 *  Bu fonksiyon sadece zaten akış linki olarak DOĞRULANMIŞ URL'lerle çağrılır (resolveStreamUrl /
 *  sniffStreamUrlViaWebView tarafından bulunmuş), bu yüzden uzantı belirsiz/gizlenmiş olsa bile
 *  (ör. ".txt" ile maskelenmiş m3u8) MIME tipi her zaman video/akış olarak zorlanır — aksi halde
 *  Android bu isteği sadece tarayıcılara yönlendirir ve video oynatıcı uygulamalar hiç çıkmaz. */
private fun openContent(context: Context, url: String) {
    val cleanUrl = url.substringBefore('?')
    val ext = cleanUrl.substringAfterLast('.', "").lowercase()
    val mimeType = when (ext) {
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mpd" -> "application/dash+xml"
        else -> "video/*"
    }
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), mimeType)
    }
    val chooser = Intent.createChooser(viewIntent, "Hangi uygulamada oynatılsın?").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (e: Exception) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(url), mimeType)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e2: Exception) { /* açılacak uygulama yok */ }
    }
}

/** İçerik sayfasının hostuna göre Referer header'ı üretir (hotlink korumasını aşmak için). */
private fun refererFor(url: String): String = try {
    val u = java.net.URI(url)
    "${u.scheme}://${u.host}/"
} catch (e: Exception) { url }

private val streamUrlRegex = Regex("""https?://[^\s"'<>\\]+\.(m3u8|mp4|mpd)(\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)

data class StreamCandidate(val label: String, val url: String)
data class PageAnalysis(val description: String?, val year: String?, val rating: String?, val candidates: List<StreamCandidate>)

private fun guessStreamLabel(url: String, index: Int): String {
    val lower = url.lowercase()
    return when {
        lower.contains("canli") || lower.contains("live") -> "Canlı Yayın"
        lower.contains("1080") -> "1080p"
        lower.contains("720") -> "720p"
        lower.contains("480") -> "480p"
        lower.contains("360") -> "360p"
        lower.endsWith(".m3u8") || lower.contains("/hls/") -> "HLS Akışı ${index + 1}"
        lower.endsWith(".mp4") -> "MP4 ${index + 1}"
        else -> "Akış Linki ${index + 1}"
    }
}

/** İçerik sayfasını tek seferde analiz eder: özet, yıl, puan ve bulunabilen TÜM akış linki
 *  adaylarını (birden fazla sunucu/kalite seçeneği olabilir) birlikte döndürür. */
private fun analyzePage(pageUrl: String): PageAnalysis {
    return try {
        val doc = Jsoup.connect(pageUrl).userAgent("Mozilla/5.0 (Android) ScrapeFlix/0.15").timeout(15000).followRedirects(true).get()

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.takeUnless { it.isBlank() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeUnless { it.isBlank() }
            ?: doc.selectFirst("[class*=ozet], [class*=summary], [class*=synopsis], [class*=description], [class*=aciklama], [class*=konu]")
                ?.text()?.trim()?.takeUnless { it.isBlank() || it.length < 15 }
        val year = doc.guessYear()
        val rating = doc.guessRating()

        val candidates = mutableListOf<StreamCandidate>()
        doc.select("video source[src]").forEach { src ->
            val u = src.absUrl("src"); if (u.isBlank()) return@forEach
            val lbl = src.attr("label").ifBlank { src.attr("res") }.ifBlank { guessStreamLabel(u, candidates.size) }
            candidates += StreamCandidate(lbl, u)
        }
        doc.selectFirst("video[src]")?.absUrl("src")?.takeUnless { it.isBlank() }?.let {
            if (candidates.none { c -> c.url == it }) candidates += StreamCandidate("Video", it)
        }
        doc.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]").forEach { m ->
            val c = m.attr("content")
            if (c.isNotBlank() && candidates.none { it.url == c }) candidates += StreamCandidate("Video (meta)", c)
        }
        // "Sunucu 1/2/3" gibi sekme/link butonları — her biri farklı bir kaynak/embed olabilir
        doc.select("[data-src], [data-embed], [data-video], [data-player], [class*=server] a[href], [class*=sunucu] a[href], [class*=alternatif] a[href]")
            .forEach { el ->
                val u = sequenceOf("data-src", "data-embed", "data-video", "data-player", "href")
                    .map { el.absUrl(it) }.firstOrNull { it.isNotBlank() } ?: return@forEach
                if (looksLikeStreamUrl(u) && candidates.none { it.url == u }) {
                    val label = el.text().trim().ifBlank { guessStreamLabel(u, candidates.size) }
                    candidates += StreamCandidate(label, u)
                }
            }
        // Gövdedeki/script içindeki tüm m3u8/mp4/mpd linklerini regex ile topla
        val html = doc.outerHtml()
        streamUrlRegex.findAll(html).forEach { m ->
            val u = m.value
            if (candidates.none { it.url == u }) candidates += StreamCandidate(guessStreamLabel(u, candidates.size), u)
        }
        // Bir seviye derine in: player iframe içinde embed edilmiş olabilir
        val iframeSrc = doc.selectFirst("iframe[src]")?.absUrl("src")
        if (!iframeSrc.isNullOrBlank()) {
            try {
                val iframeDoc = Jsoup.connect(iframeSrc).userAgent("Mozilla/5.0 (Android) ScrapeFlix/0.15")
                    .referrer(pageUrl).timeout(12000).followRedirects(true).get()
                iframeDoc.selectFirst("video source[src]")?.absUrl("src")?.takeUnless { it.isBlank() }?.let {
                    if (candidates.none { c -> c.url == it }) candidates += StreamCandidate(guessStreamLabel(it, candidates.size), it)
                }
                streamUrlRegex.findAll(iframeDoc.outerHtml()).forEach { m ->
                    val u = m.value
                    if (candidates.none { it.url == u }) candidates += StreamCandidate(guessStreamLabel(u, candidates.size), u)
                }
            } catch (e: Exception) { /* embed alınamadı */ }
        }

        PageAnalysis(description, year, rating, candidates.distinctBy { it.url }.take(12))
    } catch (e: Exception) { PageAnalysis(null, null, null, emptyList()) }
}

/** Bilinen statik varlık uzantıları — bunlar için içerik-tipi sorgusu yapmaya gerek yok
 *  (performans için, ve yanlış pozitifleri azaltmak için). */
private val staticAssetExtensions = setOf(
    "css", "js", "png", "jpg", "jpeg", "gif", "svg", "webp", "woff", "woff2", "ico", "json", "ttf", "eot", "map"
)

/** Analitik/reklam altyapısı gibi sık geçen ama içerik sorgusuna değmeyecek host'lar. */
private val skipSniffHostKeywords = listOf(
    "google-analytics", "googletagmanager", "doubleclick", "facebook.", "fbcdn", "adservice",
    "adsystem", "gstatic.com", "googlesyndication", "scorecardresearch", "hotjar", "sentry.io",
    "cloudflareinsights", "yandex", "criteo"
)

/** Bir URL'in gerçek video/akış kaynağı olma ihtimalini uzantı ve yol kalıplarına göre
 *  değerlendirir. Bazı siteler akış dosyasını gizlemek için ".txt" gibi sahte uzantılar
 *  kullanıyor (ör: .../hls/xxx.mp4/txt/sublist_2.txt) — bu yüzden salt uzantı kontrolü
 *  yetmiyor, klasör/yol kalıplarına da bakılıyor. */
private fun looksLikeStreamUrl(url: String): Boolean {
    val clean = url.substringBefore('?').lowercase()
    if (clean.endsWith(".m3u8") || clean.endsWith(".mp4") || clean.endsWith(".mpd") ||
        clean.endsWith(".ts") || clean.endsWith(".m4s") || clean.endsWith(".webm")) return true
    if (clean.contains("/hls/") || clean.contains("/dash/")) return true
    if (Regex("""\.mp4[/.]""").containsMatchIn(clean)) return true
    if (listOf("playlist", "sublist", "chunklist", "manifest", "master.m3u8", "index.m3u8").any { clean.contains(it) }) return true
    return false
}

private fun shouldSkipContentSniff(url: String): Boolean {
    val lower = url.lowercase()
    if (skipSniffHostKeywords.any { lower.contains(it) }) return true
    val ext = lower.substringBefore('?').substringAfterLast('.', "")
    return ext in staticAssetExtensions
}

/** Uzantısı belirsiz/kılık değiştirmiş isteklerde gerçek içerik tipine/gövde imzasına bakar:
 *  Content-Type video/mpegurl vb. içeriyorsa, veya gövde bir HLS playlist imzasıyla
 *  (#EXTM3U) başlıyorsa akış kaynağı olarak kabul eder. Sadece ilk birkaç yüz byte okunur. */
private fun sniffContentLooksLikeStream(url: String, referer: String): Boolean {
    var conn: java.net.HttpURLConnection? = null
    return try {
        conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            setRequestProperty("Referer", referer)
            setRequestProperty("Range", "bytes=0-600")
        }
        conn.connect()
        val contentType = conn.contentType?.lowercase().orEmpty()
        val looksVideoType = listOf("mpegurl", "video/", "mp2t", "dash+xml", "x-mpegurl", "octet-stream").any { contentType.contains(it) }
        var bodyLooksPlaylist = false
        if (!looksVideoType) {
            try {
                val buf = CharArray(400)
                val len = conn.inputStream.reader().read(buf)
                val head = if (len > 0) String(buf, 0, len) else ""
                if (head.trimStart().startsWith("#EXTM3U")) bodyLooksPlaylist = true
            } catch (e: Exception) { /* okunamadı */ }
        }
        looksVideoType || bodyLooksPlaylist
    } catch (e: Exception) { false } finally {
        try { conn?.disconnect() } catch (e: Exception) { }
    }
}

/** Sayfa yüklendikten sonra "oynat" düğmesine, reklam geçme/kapatma düğmesine benzeyen
 *  elemanları ve <video> etiketlerini tıklayıp/oynatıp gerçek player'ı ve varsa reklamı
 *  geçmeyi tetikleyen JS. click/mousedown/mouseup/touch olaylarının hepsini gönderir,
 *  çünkü bazı player'lar sade "click" yerine bunları dinliyor. */
private const val PLAY_TRIGGER_JS = """
(function(){
  function fire(el){
    try {
      ['mousedown','mouseup','click','touchstart','touchend'].forEach(function(type){
        var ev;
        try { ev = new MouseEvent(type, {bubbles:true, cancelable:true, view:window}); }
        catch(e){ ev = document.createEvent('MouseEvent'); ev.initEvent(type, true, true); }
        el.dispatchEvent(ev);
      });
      if (el.click) el.click();
    } catch(e){}
  }
  try {
    document.querySelectorAll('video').forEach(function(v){ try{ v.muted = true; v.play().catch(function(){}); }catch(e){} });
  } catch(e){}
  var sels = ['.vjs-big-play-button','.jw-icon-playback','.jwplayer .jw-display-icon-container',
    '.plyr__control--overlaid','.play-button','.playbtn','[class*=play-btn]','[class*=playBtn]',
    '[class*=player-play]','[class*=play_button]','[aria-label=Play]','[aria-label=play]',
    '[aria-label=Oynat]','[title=Play]','[title=Oynat]','[title=oynat]','[id*=play]','[class*=play]',
    '[class*=skip]','[class*=atla]','[class*=close-ad]','[class*=closead]','[class*=ad-close]',
    '[class*=skip-ad]','video'];
  for (var i=0;i<sels.length;i++){
    try {
      var els = document.querySelectorAll(sels[i]);
      for (var j=0;j<els.length && j<6;j++){ fire(els[j]); }
    } catch(e){}
  }
})();
"""

/**
 * 1DM benzeri yöntem: sayfayı gizli bir WebView'de gerçekten çalıştırıp (JavaScript dahil),
 * yükleme bittikten sonra uzun bir süre boyunca tekrar tekrar "oynat"/"reklamı geç" tıklaması
 * simüle eder (reklamların geçmesi zaman aldığı ve genelde birden fazla tıklama gerektirdiği
 * için), bu sırada atılan tüm ağ isteklerini dinler. Bilinen video uzantılı/yollu istekleri
 * anında yakalar; şüpheli ama uzantısı gizlenmiş istekler için gerçek içerik tipine/gövde
 * imzasına bakar. İlk aday bulunduktan sonra birkaç saniye daha dinlemeye devam eder ki
 * varsa alternatif sunucu/kalite linkleri de yakalansın (hepsi kullanıcıya listelenecek).
 */
private suspend fun sniffStreamUrlsViaWebView(context: Context, pageUrl: String, timeoutMs: Long = 34000L): List<StreamCandidate> =
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var resolved = false
            var settleScheduled = false
            val found = mutableListOf<StreamCandidate>()
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context.applicationContext)
            val sniffExecutor = java.util.concurrent.Executors.newFixedThreadPool(3)

            fun finish() {
                handler.post {
                    if (resolved) return@post
                    resolved = true
                    handler.removeCallbacksAndMessages(null)
                    try { webView.stopLoading(); webView.destroy() } catch (e: Exception) { /* yok say */ }
                    try { sniffExecutor.shutdownNow() } catch (e: Exception) { }
                    if (cont.isActive) cont.resume(found.toList())
                }
            }

            fun addCandidate(url: String) {
                handler.post {
                    if (resolved) return@post
                    if (found.none { it.url == url }) found += StreamCandidate(guessStreamLabel(url, found.size), url)
                    if (!settleScheduled) {
                        settleScheduled = true
                        // İlk bulgudan sonra birkaç saniye daha bekle: alternatif kaynak/kalite
                        // seçenekleri genelde art arda kısa aralıklarla yükleniyor.
                        handler.postDelayed({ finish() }, 6000L)
                    }
                }
            }

            try {
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.mediaPlaybackRequiresUserGesture = false
                webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            } catch (e: Exception) { /* ayar hatası - yine de devam et */ }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (!resolved) {
                        if (looksLikeStreamUrl(url)) {
                            addCandidate(url)
                        } else if (!request.isForMainFrame && !shouldSkipContentSniff(url) &&
                            (url.startsWith("http://") || url.startsWith("https://"))
                        ) {
                            // Ağır olmasın diye ayrı bir thread havuzunda, WebView'in kendi
                            // isteğini engellemeden içerik tipini kontrol et.
                            sniffExecutor.execute {
                                if (!resolved && sniffContentLooksLikeStream(url, pageUrl)) addCandidate(url)
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    // Reklamların geçmesi zaman alabiliyor ve genelde birden fazla tıklama
                    // gerekiyor; bu yüzden uzun bir pencerede sık aralıklarla tekrar dene.
                    for (delayMs in longArrayOf(600L, 1500L, 3000L, 5000L, 7500L, 10500L, 14000L, 18000L, 23000L, 29000L)) {
                        handler.postDelayed({
                            if (!resolved) {
                                try { view.evaluateJavascript(PLAY_TRIGGER_JS, null) } catch (e: Exception) { /* yok say */ }
                            }
                        }, delayMs)
                    }
                }
            }

            handler.postDelayed({ finish() }, timeoutMs)
            cont.invokeOnCancellation {
                handler.post {
                    try { webView.stopLoading(); webView.destroy() } catch (e: Exception) { }
                    try { sniffExecutor.shutdownNow() } catch (e: Exception) { }
                }
            }

            try {
                webView.loadUrl(pageUrl)
            } catch (e: Exception) {
                finish()
            }
        }
    }


data class EpisodeInfo(val title: String, val url: String)

/** Bir dizi/anime detay sayfasındaki bölüm linklerini bulmaya çalışır (metin veya
 *  class/href içinde "bölüm/bolum/episode/ep" ve ardından bir sayı geçen linkler). */
private fun extractEpisodes(doc: Document, baseUrl: String): List<EpisodeInfo> {
    val baseHost = try { java.net.URI(baseUrl).host } catch (e: Exception) { null } ?: return emptyList()
    val numberedWord = Regex("""(b[öo]l[üu]m|episode|epizod|ep)\D{0,3}\d{1,4}""", RegexOption.IGNORE_CASE)
    val wordThenNumber = Regex("""\d{1,4}\D{0,3}(b[öo]l[üu]m|episode|epizod)""", RegexOption.IGNORE_CASE)
    val hintWords = listOf("episode", "bolum", "bölüm", "epizod", "ep-", "ep_")

    return doc.select("a[href]").mapNotNull { a ->
        val href = a.absUrl("href").trim()
        if (href.isBlank() || href.trimEnd('/') == baseUrl.trimEnd('/')) return@mapNotNull null
        val host = try { java.net.URI(href).host } catch (e: Exception) { null }
        if (host != baseHost) return@mapNotNull null
        val text = a.text().trim()
        val hay = (text + " " + a.attr("class") + " " + a.attr("href")).lowercase(Locale.getDefault())
        val looksEpisode = numberedWord.containsMatchIn(hay) || wordThenNumber.containsMatchIn(hay) ||
            hintWords.any { hay.contains(it) }
        if (!looksEpisode) return@mapNotNull null
        EpisodeInfo(title = text.ifBlank { "Bölüm" }, url = href)
    }.distinctBy { it.url }.take(200)
}

class ScrapeViewModel(private val db: AppDatabase) : ViewModel() {
    val sites = db.siteDao().observeSites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allItems = db.itemDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var selectedSiteId by mutableStateOf<Long?>(null); private set
    var watchFilterSiteId by mutableStateOf<Long?>(null); private set
    fun setWatchFilter(siteId: Long?) { watchFilterSiteId = siteId }
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var suggestions by mutableStateOf<List<ProfileSuggestion>>(emptyList()); private set
    var previewHtml by mutableStateOf<String?>(null); private set
    var previewSiteId by mutableStateOf<Long?>(null); private set
    var previewBusy by mutableStateOf(false); private set
    var previewError by mutableStateOf(""); private set
    var livePreviewItems by mutableStateOf<List<LivePreviewItem>>(emptyList()); private set
    var streamBusy by mutableStateOf(false); private set
    var episodeParent by mutableStateOf<ScrapedItemEntity?>(null); private set
    var detailItem by mutableStateOf<ScrapedItemEntity?>(null); private set
    var detailInfo by mutableStateOf<PageAnalysis?>(null); private set
    var detailInfoBusy by mutableStateOf(false); private set
    fun openDetail(item: ScrapedItemEntity) {
        detailItem = item; detailInfo = null; detailInfoBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            val info = analyzePage(item.url)
            withContext(Dispatchers.Main) { detailInfoBusy = false; detailInfo = info }
        }
    }
    fun closeDetail() { detailItem = null; detailInfo = null }
    var streamCandidates by mutableStateOf<List<StreamCandidate>>(emptyList()); private set
    var streamCandidatesTitle by mutableStateOf<String?>(null); private set
    fun closeStreamPicker() { streamCandidates = emptyList(); streamCandidatesTitle = null }
    var episodes by mutableStateOf<List<EpisodeInfo>>(emptyList()); private set
    var episodesBusy by mutableStateOf(false); private set
    private var previewJob: kotlinx.coroutines.Job? = null

    fun loadPreviewHtml(site: SiteEntity) {
        if (previewSiteId == site.id && previewHtml != null) return
        previewBusy = true; previewError = ""; previewSiteId = site.id
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val html = Jsoup.connect(site.url).userAgent("Mozilla/5.0 (Android) ScrapeFlix/0.5").timeout(20000).followRedirects(true).get().html()
                withContext(Dispatchers.Main) {
                    previewHtml = html
                    previewBusy = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    previewHtml = null
                    previewBusy = false
                    previewError = e.message ?: "HTML alınamadı"
                }
            }
        }
    }

    fun updateLivePreview(
        html: String?,
        itemSelector: String,
        titleSelector: String,
        imageSelector: String,
        linkSelector: String,
        descriptionSelector: String
    ) {
        if (html.isNullOrBlank()) return
        previewJob?.cancel()
        previewBusy = true
        previewError = ""
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val doc = Jsoup.parse(html)
                val selector = itemSelector.trim()
                if (selector.isBlank()) {
                    withContext(Dispatchers.Main) {
                        livePreviewItems = emptyList()
                        previewBusy = false
                        previewError = "İçerik selector boş."
                    }
                    return@launch
                }
                val elements = doc.select(selector)
                val items = elements.mapNotNull { el ->
                    val link = if (el.tagName() == "a") el else el.selectFirst(linkSelector.ifBlank { "a[href]" })
                    val href = link?.absUrl("href").orEmpty()
                    val title = el.selectFirst(titleSelector.ifBlank { "h1,h2,h3,h4,h5,h6,[class*=title],[class*=name]" })
                        ?.text()?.trim().takeUnless { it.isNullOrBlank() } ?: link?.text()?.trim().orEmpty()
                    val image = el.selectFirst(imageSelector.ifBlank { "img" })?.let { it.bestImage() }
                    val desc = el.selectFirst(descriptionSelector.ifBlank { "p,[class*=description],[class*=summary]" })
                        ?.text()?.trim().takeUnless { it.isNullOrBlank() }
                    if (title.length < 2) null else LivePreviewItem(title, href, image, desc)
                }.distinctBy { if (it.url.isBlank()) it.title else it.url }.take(30)
                withContext(Dispatchers.Main) {
                    livePreviewItems = items
                    previewBusy = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    livePreviewItems = emptyList()
                    previewBusy = false
                    previewError = "Selector hatası: ${e.message ?: "geçersiz selector"}"
                }
            }
        }
    }

    fun clearPreview() {
        previewJob?.cancel()
        previewHtml = null
        previewSiteId = null
        livePreviewItems = emptyList()
        previewError = ""
        previewBusy = false
    }

    fun select(site: SiteEntity) { selectedSiteId = site.id }

    /** İçerik kartına tıklandığında çağrılır. Kategori artık sitenin kendi menü adı olduğu
     *  için (Film/Dizi gibi sabit bir sözlük yok), her tıklamada önce sayfada bölüm linki
     *  var mı diye bakılır; yoksa (tek parçalı içerik) doğrudan akış linki(leri) aranır. */
    fun openItem(context: Context, item: ScrapedItemEntity) = loadEpisodes(context, item)

    /** Detay sayfasını tarayıp bölüm linklerini bulur ve diyalogda gösterir.
     *  Hiç bölüm bulunamazsa (aslında tek parçalık bir sayfaysa) doğrudan akış linklerini arar. */
    fun loadEpisodes(context: Context, item: ScrapedItemEntity) {
        if (episodesBusy) return
        episodesBusy = true; episodeParent = item; episodes = emptyList()
        message = "Bölümler aranıyor: ${item.title}"
        viewModelScope.launch(Dispatchers.IO) {
            val found = try {
                val doc = Jsoup.connect(item.url).userAgent("Mozilla/5.0 (Android) ScrapeFlix/0.15").timeout(15000).followRedirects(true).get()
                extractEpisodes(doc, item.url)
            } catch (e: Exception) { emptyList() }
            withContext(Dispatchers.Main) {
                episodesBusy = false
                if (found.isNotEmpty()) {
                    episodes = found; message = ""
                } else {
                    episodeParent = null
                    findStreams(context, item.title, item.url)
                }
            }
        }
    }

    fun closeEpisodes() { episodeParent = null; episodes = emptyList() }

    /** Bir içeriğin (veya seçilen bölümün) TÜM akış linki adaylarını bulur ve kullanıcıya
     *  bir liste olarak sunar — tarayıcıya değil, seçilen bir uygulamayla oynatılabilsin diye
     *  hiçbiri otomatik açılmaz. Önce hızlı statik HTML analizini dener (birden fazla
     *  sunucu/kalite <source> etiketi varsa hepsini toplar); hiçbir şey bulamazsa 1DM benzeri
     *  yöntemle sayfayı gizli bir WebView'de çalıştırıp ağ isteklerini dinleyerek arar. */
    fun findStreams(context: Context, title: String, url: String) {
        if (streamBusy) return
        streamBusy = true; streamCandidatesTitle = title; streamCandidates = emptyList()
        message = "Akış linkleri aranıyor: $title"
        viewModelScope.launch {
            val analysis = withContext(Dispatchers.IO) { analyzePage(url) }
            var candidates = analysis.candidates
            if (candidates.isEmpty()) {
                message = "Sayfa açılıp oynatma/reklam geçme tetikleniyor (biraz sürebilir): $title"
                candidates = sniffStreamUrlsViaWebView(context, url)
            }
            streamBusy = false
            streamCandidates = candidates
            message = if (candidates.isEmpty()) "Akış linki bulunamadı: $title" else ""
        }
    }

    fun pickStream(context: Context, url: String) {
        openContent(context, url)
        closeStreamPicker()
    }

    fun addSite(name: String, url: String) = viewModelScope.launch(Dispatchers.IO) {
        val clean = normalizeUrl(url); val id = db.siteDao().insert(SiteEntity(name = name.ifBlank { clean }, url = clean))
        withContext(Dispatchers.Main) { selectedSiteId = id; message = "Site eklendi. Analiz başlatabilirsin." }
    }
    fun deleteSite(site: SiteEntity) = viewModelScope.launch(Dispatchers.IO) {
        db.itemDao().deleteForSite(site.id); db.siteDao().delete(site)
        withContext(Dispatchers.Main) { if (selectedSiteId == site.id) selectedSiteId = null }
    }
    fun updateProfile(site: SiteEntity, item: String, title: String, image: String, link: String, desc: String) = viewModelScope.launch(Dispatchers.IO) {
        db.siteDao().update(site.copy(itemSelector=item, titleSelector=title, imageSelector=image, linkSelector=link, descriptionSelector=desc, profileStatus="Hazır"))
        withContext(Dispatchers.Main) { message = "Profil kaydedildi." }
    }

    fun analyze(site: SiteEntity) {
        if (busy) return; busy = true; selectedSiteId = site.id; suggestions = emptyList(); message = "HTML analiz ediliyor..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val doc = Jsoup.connect(site.url).userAgent("Mozilla/5.0 (Android) ScrapeFlix/0.4").timeout(20000).followRedirects(true).get()
                val selectorScores = linkedMapOf<String, Int>()
                val samples = linkedMapOf<String, MutableList<String>>()
                val roots = doc.select("article,li,section,div,[class*=card],[class*=item],[class*=movie],[class*=film],[class*=post],[class*=show],[class*=episode]")
                roots.forEach { el ->
                    val link = el.selectFirst("a[href]") ?: return@forEach
                    val title = el.guessTitle()
                    val href = link.absUrl("href")
                    if (title.length < 2 || href.isBlank()) return@forEach
                    val cls = el.classNames().firstOrNull()?.takeIf { it.isNotBlank() }
                    val css = if (cls != null) ".${cls.replace(Regex("[^A-Za-z0-9_-]"), "")}" else el.tagName()
                    var score = 2
                    if (el.selectFirst("img") != null) score += 2
                    if (title.length in 3..120) score += 2
                    if (href != site.url) score += 1
                    selectorScores[css] = (selectorScores[css] ?: 0) + score
                    samples.getOrPut(css) { mutableListOf() }.add(title)
                }
                val best = selectorScores.maxByOrNull { it.value }?.key ?: "article, .card, .item"
                val confidence = ((selectorScores[best] ?: 0) * 4).coerceIn(55, 98)
                val suggestion = ProfileSuggestion(best, "h1,h2,h3,h4,h5,h6,[class*=title],[class*=name]", "img", "a[href]", "p,[class*=description],[class*=summary]", confidence, samples[best]?.distinct()?.take(5).orEmpty())
                val updated = site.copy(name = if (site.name == site.url) doc.title().ifBlank { site.name } else site.name, itemSelector=best, titleSelector=suggestion.title, imageSelector=suggestion.image, linkSelector=suggestion.link, descriptionSelector=suggestion.description, profileStatus="Öneri hazır")
                db.siteDao().update(updated)
                withContext(Dispatchers.Main) { suggestions = listOf(suggestion); message = "Öneri hazır. Önizleyip profili kaydedebilirsin."; busy = false }
            } catch (e: Exception) { withContext(Dispatchers.Main) { message = "Analiz başarısız: ${e.message ?: "Bilinmeyen hata"}"; busy = false } }
        }
    }

    fun scrape(site: SiteEntity) {
        if (busy) return; busy = true; selectedSiteId = site.id; message = "Ana sayfa taranıyor..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ua = "Mozilla/5.0 (Android) ScrapeFlix/0.15"
                suspend fun fetch(url: String): Document? = try {
                    Jsoup.connect(url).userAgent(ua).timeout(12000).followRedirects(true).get()
                } catch (e: Exception) { null }

                val visited = mutableSetOf<String>()
                val found = mutableListOf<ScrapedItemEntity>()
                var order = 0
                var pageBudget = 500 // toplam sayfa isteği bütçesi (site sonsuz sayfalıysa taramayı sınırlar)
                val maxDepth = 2 // 1: ana menü, 2: onun alt menüsü
                val semaphore = Semaphore(4)
                var pagesScanned = 0
                val startTime = System.currentTimeMillis()
                val maxDurationMs = 6 * 60_000L // güvenlik sınırı: 6 dakika
                fun timeLeft() = System.currentTimeMillis() - startTime < maxDurationMs

                visited.add(normalizeUrlKey(site.url))
                val mainDoc = Jsoup.connect(site.url).userAgent(ua).timeout(20000).followRedirects(true).get()
                pagesScanned++

                val level1 = discoverSubPages(mainDoc, site.url).filter { visited.add(normalizeUrlKey(it.url)) }
                withContext(Dispatchers.Main) { message = if (level1.isNotEmpty()) "${level1.size} menü bulundu, taranıyor..." else "Menü bulunamadı, ana sayfa taranıyor..." }

                var currentLevel = level1.map { MenuLink(it.label, it.url) to 1 }
                var depth = 1
                while (currentLevel.isNotEmpty() && depth <= maxDepth && pageBudget > 0 && timeLeft()) {
                    val batch = currentLevel.take(pageBudget)
                    val results = batch.map { (link, d) ->
                        async { semaphore.withPermit { Triple(link, d, fetch(link.url)) } }
                    }.awaitAll()
                    pageBudget -= batch.size

                    val nextLevel = mutableListOf<Pair<MenuLink, Int>>()
                    for ((link, d, docMaybe) in results) {
                        val doc0: Document = docMaybe ?: continue
                        var page: Document = doc0
                        extractItems(page, site, link.label).forEach { found += it.copy(sortOrder = order++) }
                        pagesScanned++

                        // Bu menünün TÜM sayfalamasını (2., 3., ... son sayfaya kadar) takip et —
                        // tek sınır kalan bütçe ve genel zaman güvenliği.
                        while (pageBudget > 0 && timeLeft()) {
                            val nextUrl = findNextPageUrl(page, link.url) ?: break
                            if (!visited.add(normalizeUrlKey(nextUrl))) break
                            val nextDoc = fetch(nextUrl) ?: break
                            pageBudget--; pagesScanned++
                            extractItems(nextDoc, site, link.label).forEach { found += it.copy(sortOrder = order++) }
                            page = nextDoc
                            if (pagesScanned % 5 == 0) {
                                withContext(Dispatchers.Main) { message = "$pagesScanned sayfa tarandı (${link.label})..." }
                            }
                        }

                        // Bu sayfanın kendi alt menüsünü keşfet (bir sonraki derinlik için).
                        if (d < maxDepth) {
                            discoverSubPages(doc0, link.url).forEach { child ->
                                if (visited.add(normalizeUrlKey(child.url))) {
                                    nextLevel += MenuLink("${link.label} · ${child.label}", child.url) to (d + 1)
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { message = "$pagesScanned sayfa tarandı, devam ediliyor..." }
                    currentLevel = nextLevel
                    depth++
                }

                // Ana sayfa içerikleri en son eklenir ki bir içerik birden fazla yerde görünüyorsa
                // sitenin gerçek (daha spesifik) menü adı kazansın; anasayfada olup hiçbir menüde
                // geçmeyenler "Ana Sayfa" başlığı altında toplanır.
                extractItems(mainDoc, site, "Ana Sayfa").forEach { found += it.copy(sortOrder = order++) }

                val deduped = found.distinctBy { it.url }.take(5000)
                db.itemDao().deleteForSite(site.id); if (deduped.isNotEmpty()) db.itemDao().insertAll(deduped)
                db.siteDao().update(site.copy(lastUpdated=System.currentTimeMillis(),itemCount=deduped.size,profileStatus="Aktif"))
                withContext(Dispatchers.Main) { message = "$pagesScanned sayfa tarandı, ${deduped.size} içerik bulundu."; busy = false }
            } catch (e: Exception) { withContext(Dispatchers.Main) { message="Tarama başarısız: ${e.message ?: "Bilinmeyen hata"}"; busy=false } }
        }
    }

}

class VmFactory(private val context: Context): ViewModelProvider.Factory { override fun <T:ViewModel> create(c:Class<T>):T { @Suppress("UNCHECKED_CAST") return ScrapeViewModel(AppDatabase.get(context)) as T } }
enum class Page { Home, Sites, Watch, Settings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun App(vm:ScrapeViewModel=viewModel(factory=VmFactory(LocalContext.current))) {
    var page by remember{mutableStateOf(Page.Home)}; var add by remember{mutableStateOf(false)}; var editor by remember{mutableStateOf<SiteEntity?>(null)}; var preview by remember{mutableStateOf(false)}
    MaterialTheme(colorScheme=darkColorScheme(background=Color(0xFF080808),surface=Color(0xFF151515),primary=Color(0xFFE50914))) {
        Scaffold(containerColor=Color(0xFF080808),topBar={TopAppBar(title={Text("SCRAPEFLIX",fontWeight=FontWeight.Bold)},colors=TopAppBarDefaults.topAppBarColors(containerColor=Color.Black,titleContentColor=Color.White),actions={IconButton({add=true}){Icon(Icons.Default.Add,"Yeni site")}})},bottomBar={NavigationBar(containerColor=Color.Black){NavigationBarItem(page==Page.Home,{page=Page.Home},icon={Icon(Icons.Default.Home,null)},label={Text("Ana")});NavigationBarItem(page==Page.Sites,{page=Page.Sites},icon={Icon(Icons.Default.Language,null)},label={Text("Siteler")});NavigationBarItem(page==Page.Watch,{page=Page.Watch},icon={Icon(Icons.Default.PlayArrow,null)},label={Text("İçerikler")});NavigationBarItem(page==Page.Settings,{page=Page.Settings},icon={Icon(Icons.Default.Settings,null)},label={Text("Ayarlar")})}}){pad->Box(Modifier.padding(pad).fillMaxSize()){when(page){Page.Home->HomeScreen(vm){page=Page.Sites};Page.Sites->SitesScreen(vm, {add=true}, {editor=it}) {s->vm.setWatchFilter(s.id);page=Page.Watch};Page.Watch->WatchScreen(vm);Page.Settings->SettingsScreen()}}}
        if(add)AddSiteDialog({add=false}){n,u->vm.addSite(n,u);add=false;page=Page.Sites}
        editor?.let{
            LaunchedEffect(it.id) { vm.loadPreviewHtml(it) }
            SiteEditorDialog(
                it, vm.suggestions, vm.previewHtml, vm.previewBusy, vm.previewError, vm.livePreviewItems,
                { editor=null; vm.clearPreview() },
                { vm.analyze(it); vm.loadPreviewHtml(it) },
                { vm.scrape(it) },
                { vm.deleteSite(it); editor=null; vm.clearPreview() },
                { item,title,image,link,desc->vm.updateProfile(it,item,title,image,link,desc) },
                { html,item,title,image,link,desc -> vm.updateLivePreview(html,item,title,image,link,desc) }
            )
        }
        if(preview){} 
    }
}

@Composable fun HomeScreen(vm:ScrapeViewModel,open:()->Unit){val sites by vm.sites.collectAsState();val all by vm.allItems.collectAsState();LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){item{Text("Ana Sayfa",fontSize=28.sp,fontWeight=FontWeight.Bold);Text("${sites.size} site • ${all.size} içerik",color=Color.Gray)};item{Button(open,Modifier.fillMaxWidth()){Icon(Icons.Default.Language,null);Spacer(Modifier.width(8.dp));Text("Sitelerimi Yönet")}};items(sites){s->SiteCard(s,{vm.analyze(s)},{vm.scrape(s)},{vm.deleteSite(s)})}}}

@Composable fun SitesScreen(vm:ScrapeViewModel,onAdd:()->Unit,onEdit:(SiteEntity)->Unit,onOpenContent:(SiteEntity)->Unit){val sites by vm.sites.collectAsState();Column(Modifier.fillMaxSize().padding(16.dp)){Row(Modifier.fillMaxWidth(),Arrangement.SpaceBetween,Alignment.CenterVertically){Column{Text("Sitelerim",fontSize=26.sp,fontWeight=FontWeight.Bold);Text("Analiz • önizleme • profil",color=Color.Gray)};FilledTonalButton(onAdd){Icon(Icons.Default.Add,null);Text(" Ekle")}};Spacer(Modifier.height(14.dp));if(vm.busy)LinearProgressIndicator(Modifier.fillMaxWidth());if(vm.message.isNotBlank())Text(vm.message,color=Color.LightGray,modifier=Modifier.padding(vertical=8.dp));LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){items(sites){s->SiteCard(s,{vm.analyze(s)},{vm.scrape(s)},{vm.deleteSite(s)},{onOpenContent(s)});TextButton({onEdit(s)}){Icon(Icons.Default.Edit,null);Text(" Profil / Önizleme")}}}}}

@Composable fun SiteCard(site:SiteEntity,onAnalyze:()->Unit,onScrape:()->Unit,onDelete:()->Unit,onOpenContent:()->Unit={}){Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF191919))){Column(Modifier.padding(14.dp)){Text(site.name,fontSize=19.sp,fontWeight=FontWeight.Bold);Text(site.url,color=Color.Gray,maxLines=1);Text("${site.itemCount} içerik • ${site.profileStatus}",color=Color.LightGray,fontSize=13.sp);Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){TextButton(onAnalyze){Icon(Icons.Default.Search,null);Text(" Analiz")};TextButton(onScrape){Icon(Icons.Default.Refresh,null);Text(" Tara")};TextButton(onDelete){Icon(Icons.Default.Delete,null);Text(" Sil")}};if(site.itemCount>0)TextButton(onOpenContent,Modifier.fillMaxWidth()){Icon(Icons.Default.PlayArrow,null);Text(" Bu Sitenin İçeriklerini Gör (${site.itemCount})")}}}}

@Composable fun WatchScreen(vm: ScrapeViewModel) {
    val items by vm.allItems.collectAsState()
    val sites by vm.sites.collectAsState()
    val context = LocalContext.current
    var q by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf("Orijinal") }
    val filterSiteId = vm.watchFilterSiteId
    val base = items.filter {
        (filterSiteId == null || it.siteId == filterSiteId) && (q.isBlank() || it.title.contains(q, true))
    }
    fun sorted(list: List<ScrapedItemEntity>): List<ScrapedItemEntity> = when (sortMode) {
        "İsim" -> list.sortedBy { it.title.lowercase(Locale.getDefault()) }
        "Yıl" -> list.sortedByDescending { it.year?.toIntOrNull() ?: -1 }
        "Puan" -> list.sortedByDescending { it.rating?.let { r -> Regex("""\d+(\.\d+)?""").find(r)?.value?.toDoubleOrNull() } ?: -1.0 }
        else -> list
    }
    // Uygulamanın kendi sabit kategorileri yok: her bölüm başlığı doğrudan sitenin kendi
    // menüsünden geliyor (item.category = o menünün görünen adı). Tek site seçiliyken
    // doğrudan o sitenin menü yapısı gösterilir; "Tümü"de önce site adına, sonra o sitenin
    // kendi menüsüne göre gruplanır.
    val siteNameById = remember(sites) { sites.associateBy({ it.id }, { it.name }) }
    data class Section(val header: String, val list: List<ScrapedItemEntity>)
    val sections: List<Section> = remember(base, filterSiteId, siteNameById, sortMode) {
        if (filterSiteId != null) {
            base.groupBy { it.category }.map { (cat, list) -> Section(cat, sorted(list)) }
        } else {
            base.groupBy { siteNameById[it.siteId] ?: "Bilinmeyen Site" }.flatMap { (siteName, siteItems) ->
                siteItems.groupBy { it.category }.map { (cat, list) -> Section("$siteName · $cat", sorted(list)) }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("İçerikler", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (sites.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Site", color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(selected = filterSiteId == null, onClick = { vm.setWatchFilter(null) }, label = { Text("Tümü (${items.size})") })
                }
                items(sites) { s ->
                    FilterChip(selected = filterSiteId == s.id, onClick = { vm.setWatchFilter(s.id) }, label = { Text("${s.name} (${s.itemCount})") })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("İçerik ara") })
        Spacer(Modifier.height(8.dp))
        Text("Sırala", color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Orijinal", "İsim", "Yıl", "Puan").forEach {
                FilterChip(selected = sortMode == it, onClick = { sortMode = it }, label = { Text(it) })
            }
        }
        Spacer(Modifier.height(8.dp))
        if (vm.streamBusy || vm.episodesBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.message.isNotBlank()) Text(vm.message, color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        Spacer(Modifier.height(2.dp))
        if (sections.isEmpty()) Text("Sonuç bulunamadı. Bir siteyi tarayarak başlayabilirsin.", color = Color.Gray)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            sections.forEach { section ->
                item(key = "hdr-${section.header}") {
                    Text(
                        section.header,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFFE50914),
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                    )
                }
                item(key = "row-${section.header}") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                        items(section.list, key = { it.id }) { it2 -> PosterCard(context, it2) { vm.openDetail(it2) } }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
    vm.episodeParent?.let { parent ->
        AlertDialog(
            onDismissRequest = { vm.closeEpisodes() },
            confirmButton = { TextButton({ vm.closeEpisodes() }) { Text("Kapat") } },
            title = { Text(parent.title) },
            text = {
                Column(Modifier.heightIn(max = 420.dp)) {
                    if (vm.episodesBusy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("Bölümler aranıyor...", color = Color.Gray)
                    } else if (vm.episodes.isEmpty()) {
                        Text("Bölüm bulunamadı.", color = Color.Gray)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(vm.episodes) { ep ->
                                Text(
                                    ep.title,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                            vm.closeEpisodes()
                                            vm.findStreams(context, ep.title, ep.url)
                                        }
                                        .padding(vertical = 12.dp)
                                )
                                HorizontalDivider(color = Color(0xFF2A2A2A))
                            }
                        }
                    }
                }
            }
        )
    }
    vm.detailItem?.let { item ->
        DetailDialog(vm = vm, context = context, item = item)
    }
    vm.streamCandidatesTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { vm.closeStreamPicker() },
            confirmButton = { TextButton({ vm.closeStreamPicker() }) { Text("Kapat") } },
            title = { Text("Akış Linkleri") },
            text = {
                Column(Modifier.heightIn(max = 420.dp)) {
                    Text(title, color = Color.Gray, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    if (vm.streamBusy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(vm.message.ifBlank { "Aranıyor..." }, color = Color.Gray, fontSize = 12.sp)
                    } else if (vm.streamCandidates.isEmpty()) {
                        Text("Akış linki bulunamadı.", color = Color.Gray)
                    } else {
                        Text("${vm.streamCandidates.size} kaynak bulundu — birini seç:", color = Color.LightGray, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(vm.streamCandidates) { c ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { vm.pickStream(context, c.url) }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, tint = Color(0xFFE50914))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(c.label, fontWeight = FontWeight.Medium)
                                        Text(c.url, color = Color.Gray, fontSize = 10.sp, maxLines = 1)
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF2A2A2A))
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun PosterCard(context: Context, item: ScrapedItemEntity, onClick: () -> Unit) {
    Column(modifier = Modifier.width(118.dp).clickable { onClick() }) {
        if (!item.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(context)
                    .data(item.imageUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Android) ScrapeFlix/0.14")
                    .addHeader("Referer", refererFor(item.url))
                    .crossfade(true)
                    .build(),
                contentDescription = item.title,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF232323)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Movie, null, tint = Color(0xFF555555)) }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, color = Color.White)
        if (item.year != null || item.rating != null) {
            Row {
                item.year?.let { Text(it, color = Color(0xFFD4AF37), fontSize = 10.sp) }
                if (item.year != null && item.rating != null) Text(" • ", color = Color.Gray, fontSize = 10.sp)
                item.rating?.let { Text("★$it", color = Color(0xFFD4AF37), fontSize = 10.sp) }
            }
        }
    }
}

/** Bir içeriğe dokunulduğunda açılan ayrı detay kartı: görsel, başlık, yıl/puan, özet ve
 *  gerçek "İzle" eylemi (bölüm var mı diye bakar, akış linkini bulur, uygulama seçtirir). */
@Composable
private fun DetailDialog(vm: ScrapeViewModel, context: Context, item: ScrapedItemEntity) {
    val info = vm.detailInfo
    val year = item.year ?: info?.year
    val rating = item.rating ?: info?.rating
    val description = item.description?.takeUnless { it.isBlank() } ?: info?.description
    Dialog(onDismissRequest = { vm.closeDetail() }) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141414))
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!item.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(item.imageUrl)
                            .addHeader("User-Agent", "Mozilla/5.0 (Android) ScrapeFlix/0.15")
                            .addHeader("Referer", refererFor(item.url))
                            .crossfade(true)
                            .build(),
                        contentDescription = item.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(220.dp)
                    )
                }
                Column(Modifier.padding(18.dp)) {
                    Text(item.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    if (year != null || rating != null) {
                        Row {
                            year?.let { Text(it, color = Color(0xFFD4AF37), fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                            if (year != null && rating != null) Text(" • ", color = Color.Gray)
                            rating?.let { Text("★ $it", color = Color(0xFFD4AF37), fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(item.category, color = Color.Gray, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    if (vm.detailInfoBusy && description == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Özet aranıyor...", color = Color.Gray, fontSize = 13.sp)
                        }
                    } else {
                        Text(description ?: "Özet bulunamadı.", color = Color.LightGray, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { vm.closeDetail(); vm.openItem(context, item) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("İzle")
                        }
                        OutlinedButton(onClick = { vm.closeDetail() }) { Text("Kapat") }
                    }
                }
            }
        }
    }
}

@Composable
fun SiteEditorDialog(
    site: SiteEntity,
    suggestions: List<ProfileSuggestion>,
    previewHtml: String?,
    previewBusy: Boolean,
    previewError: String,
    previewItems: List<LivePreviewItem>,
    onDismiss: () -> Unit,
    onAnalyze: () -> Unit,
    onScrape: () -> Unit,
    onDelete: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit,
    onPreviewChange: (String?, String, String, String, String, String) -> Unit
) {
    var item by remember(site.id, site.itemSelector) { mutableStateOf(site.itemSelector) }
    var title by remember(site.id, site.titleSelector) { mutableStateOf(site.titleSelector) }
    var image by remember(site.id, site.imageSelector) { mutableStateOf(site.imageSelector) }
    var link by remember(site.id, site.linkSelector) { mutableStateOf(site.linkSelector) }
    var desc by remember(site.id, site.descriptionSelector) { mutableStateOf(site.descriptionSelector) }

    LaunchedEffect(previewHtml, item, title, image, link, desc) {
        if (previewHtml != null) {
            kotlinx.coroutines.delay(250)
            onPreviewChange(previewHtml, item, title, image, link, desc)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f),
        title = {
            Column {
                Text("Site Profil Editörü", fontWeight = FontWeight.Bold)
                Text(site.name, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (suggestions.isNotEmpty()) {
                    val s = suggestions.first()
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF202020)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text("Otomatik öneri • %${s.confidence} güven", fontWeight = FontWeight.Bold)
                            if (s.sampleTitles.isNotEmpty()) {
                                Text(
                                    "Örnek: ${s.sampleTitles.joinToString(" • ")}",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }

                Text("Selector'lar", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    item, { item = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("İçerik selector") },
                    singleLine = true
                )
                OutlinedTextField(
                    title, { title = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Başlık selector") },
                    singleLine = true
                )
                OutlinedTextField(
                    image, { image = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Görsel selector") },
                    singleLine = true
                )
                OutlinedTextField(
                    link, { link = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Link selector") },
                    singleLine = true
                )
                OutlinedTextField(
                    desc, { desc = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Açıklama selector") },
                    singleLine = true
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Canlı yakalama", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    if (previewBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.weight(1f))
                    Text("${previewItems.size} kart", color = Color.Gray, fontSize = 12.sp)
                }

                if (previewError.isNotBlank()) {
                    Text(previewError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                } else if (previewHtml == null && !previewBusy) {
                    Text("HTML önizlemesi hazırlanıyor...", color = Color.Gray, fontSize = 12.sp)
                }

                if (previewItems.isEmpty() && previewHtml != null && !previewBusy && previewError.isBlank()) {
                    Text(
                        "Bu selector ile kart bulunamadı. İçerik selector'ını değiştir.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        items(previewItems) { p ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF191919))
                            ) {
                                Row(
                                    Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!p.imageUrl.isNullOrBlank()) {
                                        val ctx = LocalContext.current
                                        AsyncImage(
                                            model = coil.request.ImageRequest.Builder(ctx)
                                                .data(p.imageUrl)
                                                .addHeader("User-Agent", "Mozilla/5.0 (Android) ScrapeFlix/0.7")
                                                .addHeader("Referer", refererFor(site.url))
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = p.title,
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = Modifier.size(64.dp, 82.dp).clip(RoundedCornerShape(6.dp))
                                        )
                                        Spacer(Modifier.width(9.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(p.title, fontWeight = FontWeight.Bold, maxLines = 2)
                                        if (p.description != null) {
                                            Text(p.description, color = Color.Gray, fontSize = 11.sp, maxLines = 2)
                                        }
                                        Text(
                                            p.url.ifBlank { "Link bulunamadı" },
                                            color = Color.Gray,
                                            fontSize = 10.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button({ onSave(item, title, image, link, desc); onDismiss() }) {
                Text("Profili Kaydet")
            }
        },
        dismissButton = {
            Row {
                TextButton(onAnalyze) { Text("Yeniden Analiz") }
                TextButton(onScrape) { Text("Tara") }
                TextButton(onDelete) { Text("Sil") }
                TextButton(onDismiss) { Text("Kapat") }
            }
        }
    )
}

@Composable fun AddSiteDialog(onDismiss:()->Unit,onAdd:(String,String)->Unit){var n by remember{mutableStateOf("")};var u by remember{mutableStateOf("")};AlertDialog(onDismissRequest=onDismiss,title={Text("Yeni Site Ekle")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(n,{n=it},label={Text("Site adı")});OutlinedTextField(u,{u=it},label={Text("Site adresi")})}},confirmButton={Button({onAdd(n,u)},enabled=u.isNotBlank()){Text("Kaydet")}},dismissButton={TextButton(onDismiss){Text("İptal")}})}

@Composable fun SettingsScreen(){Column(Modifier.fillMaxSize().padding(20.dp)){Text("Ayarlar",fontSize=28.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(16.dp));Text("ScrapeFlix v0.15.0",fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));Text("v0.15: Tarama artık her menünün TÜM sayfalarını (sayfalama) takip ediyor, sabit sayfa sınırı yok. Detay kartı açılınca özet/yıl/puan sayfadan otomatik aranıyor. İzle artık tek link açmıyor — bulunan TÜM akış linklerini (varsa canlı yayın dahil) listeleyip seçim yaptırıyor. İçerikler sırasını isim/yıl/puana göre değiştirebilirsin.",color=Color.Gray)}}

class MainActivity:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{App()}}}
