package com.scrapeflix.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.withContext
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
            if (raw.isNotBlank()) return el.resolveUrl(raw)
        }
    }
    // 2) plain <img> with common lazy-load attributes
    selectFirst("img")?.let { img ->
        sequenceOf("src", "data-src", "data-lazy-src", "data-original")
            .map { img.absUrl(it) }.firstOrNull { it.isNotBlank() }?.let { return it }
        val srcset = img.attr("srcset").ifBlank { img.attr("data-srcset") }
        if (srcset.isNotBlank()) firstFromSrcset(srcset)?.let { return img.resolveUrl(it) }
    }
    // 3) <picture><source srcset=...>
    selectFirst("picture source[srcset]")?.let { src ->
        firstFromSrcset(src.attr("srcset"))?.let { return src.resolveUrl(it) }
    }
    return null
}

private fun Element.guessTitle(): String =
    selectFirst("h1,h2,h3,h4,h5,h6,[class*=title],[class*=name]")?.text()?.trim()
        .takeUnless { it.isNullOrBlank() } ?: selectFirst("a[href]")?.text()?.trim().orEmpty()

private fun normalizeUrl(url: String) = if (url.trim().startsWith("http://") || url.trim().startsWith("https://")) url.trim() else "https://${url.trim()}"

/** Sitenin nav/menü alanlarındaki alt sayfa linklerini bulur (aynı domain, tekrarsız). */
private fun discoverSubPages(doc: Document, baseUrl: String, limit: Int = 15): List<String> {
    val baseHost = try { java.net.URI(baseUrl).host } catch (e: Exception) { null } ?: return emptyList()
    val base = baseUrl.trimEnd('/')
    return doc.select("nav a[href], header a[href], [class*=menu] a[href], [class*=nav] a[href], [class*=categor] a[href], [id*=menu] a[href], [id*=nav] a[href]")
        .mapNotNull { a -> a.absUrl("href").trim().ifBlank { null } }
        .filter { href ->
            href.startsWith("http") &&
                href.trimEnd('/') != base &&
                !href.contains("#") &&
                (try { java.net.URI(href).host } catch (e: Exception) { null }) == baseHost
        }
        .distinct()
        .take(limit)
}

/** Verilen bir HTML dokümanından, sitenin selector'larına göre içerik kartlarını çıkarır. */
private fun extractItems(doc: Document, site: SiteEntity): List<ScrapedItemEntity> {
    val selector = site.itemSelector.ifBlank { "article, .card, .item" }
    return doc.select(selector).mapNotNull { el ->
        val link = if (el.tagName() == "a") el else el.selectFirst(site.linkSelector.ifBlank { "a[href]" })
        val href = link?.absUrl("href").orEmpty(); if (href.isBlank()) return@mapNotNull null
        val title = el.selectFirst(site.titleSelector.ifBlank { "h1,h2,h3,h4,h5,h6,[class*=title],[class*=name]" })
            ?.text()?.trim().takeUnless { it.isNullOrBlank() } ?: link?.text()?.trim().orEmpty()
        val image = el.selectFirst(site.imageSelector.ifBlank { "img" })?.let { it.bestImage() }
        val desc = el.selectFirst(site.descriptionSelector.ifBlank { "p,[class*=description],[class*=summary]" })?.text()?.trim()
        if (title.length < 2) null else ScrapedItemEntity(siteId = site.id, title = title, url = href, imageUrl = image, description = desc?.ifBlank { null }, category = guessCategoryStatic(title, href))
    }
}

private fun guessCategoryStatic(title: String, url: String): String {
    val s = (title + " " + url).lowercase(Locale.getDefault())
    return when {
        listOf("anime", "episode", "ova").any { s.contains(it) } -> "Anime"
        listOf("series", "season", "dizi").any { s.contains(it) } -> "Dizi"
        listOf("documentary", "belgesel").any { s.contains(it) } -> "Belgesel"
        else -> "Film"
    }
}

/** İçeriği tıklandığında tarayıcı yerine cihazdaki uygun uygulamalar arasından seçim yaptırır. */
private fun openContent(context: Context, url: String) {
    val cleanUrl = url.substringBefore('?')
    val ext = cleanUrl.substringAfterLast('.', "").lowercase()
    val mimeType = when (ext) {
        "mp4", "mkv", "webm", "avi", "mov", "3gp", "ts" -> "video/*"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mpd" -> "application/dash+xml"
        else -> null
    }
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        if (mimeType != null) setDataAndType(Uri.parse(url), mimeType) else data = Uri.parse(url)
    }
    val chooser = Intent.createChooser(viewIntent, "İçeriği şununla aç").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (e: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e2: Exception) { /* açılacak uygulama yok */ }
    }
}

/** İçerik sayfasının hostuna göre Referer header'ı üretir (hotlink korumasını aşmak için). */
private fun refererFor(url: String): String = try {
    val u = java.net.URI(url)
    "${u.scheme}://${u.host}/"
} catch (e: Exception) { url }

private val streamUrlRegex = Regex("""https?://[^\s"'<>\\]+\.(m3u8|mp4|mpd)(\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)

/** İçerik detay sayfasını analiz ederek gerçek video/akış linkini bulmaya çalışır. */
private fun resolveStreamUrl(pageUrl: String): String? {
    return try {
        val doc = Jsoup.connect(pageUrl).userAgent("Mozilla/5.0 (Android) ScrapeFlix/0.7").timeout(15000).followRedirects(true).get()

        doc.selectFirst("video[src]")?.absUrl("src")?.takeUnless { it.isBlank() }?.let { return it }
        doc.selectFirst("video source[src]")?.absUrl("src")?.takeUnless { it.isBlank() }?.let { return it }
        doc.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player:stream]")
            .firstOrNull()?.attr("content")?.takeUnless { it.isBlank() }?.let { return it }

        val html = doc.outerHtml()
        streamUrlRegex.find(html)?.value?.let { return it }

        // Bir seviye derine in: player genelde iframe içinde embed edilmiş olabilir
        val iframeSrc = doc.selectFirst("iframe[src]")?.absUrl("src")
        if (!iframeSrc.isNullOrBlank()) {
            try {
                val iframeDoc = Jsoup.connect(iframeSrc).userAgent("Mozilla/5.0 (Android) ScrapeFlix/0.7")
                    .referrer(pageUrl).timeout(12000).followRedirects(true).get()
                iframeDoc.selectFirst("video[src]")?.absUrl("src")?.takeUnless { it.isBlank() }?.let { return it }
                iframeDoc.selectFirst("video source[src]")?.absUrl("src")?.takeUnless { it.isBlank() }?.let { return it }
                streamUrlRegex.find(iframeDoc.outerHtml())?.value?.let { return it }
            } catch (e: Exception) { /* embed alınamadı */ }
        }
        null
    } catch (e: Exception) { null }
}

class ScrapeViewModel(private val db: AppDatabase) : ViewModel() {
    val sites = db.siteDao().observeSites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allItems = db.itemDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var selectedSiteId by mutableStateOf<Long?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var suggestions by mutableStateOf<List<ProfileSuggestion>>(emptyList()); private set
    var previewHtml by mutableStateOf<String?>(null); private set
    var previewSiteId by mutableStateOf<Long?>(null); private set
    var previewBusy by mutableStateOf(false); private set
    var previewError by mutableStateOf(""); private set
    var livePreviewItems by mutableStateOf<List<LivePreviewItem>>(emptyList()); private set
    var streamBusy by mutableStateOf(false); private set
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

    /** İçerik kartına tıklandığında: sayfadaki gerçek akış linkini bulur ve doğrudan
     *  o linki, cihazdaki uyumlu uygulamalar arasından seçim yaptırarak açar (tarayıcı yok). */
    fun openStream(context: Context, item: ScrapedItemEntity) {
        if (streamBusy) return
        streamBusy = true; message = "Akış linki aranıyor: ${item.title}"
        viewModelScope.launch(Dispatchers.IO) {
            val streamUrl = resolveStreamUrl(item.url)
            withContext(Dispatchers.Main) {
                streamBusy = false
                if (streamUrl != null) {
                    message = ""
                    openContent(context, streamUrl)
                } else {
                    message = "Akış linki bulunamadı: ${item.title}"
                }
            }
        }
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
                val mainDoc = Jsoup.connect(site.url).userAgent("Mozilla/5.0 (Android) ScrapeFlix/0.6").timeout(20000).followRedirects(true).get()
                val subPages = discoverSubPages(mainDoc, site.url)
                withContext(Dispatchers.Main) { message = if (subPages.isNotEmpty()) "${subPages.size} alt menü bulundu, taranıyor..." else "Alt menü bulunamadı, ana sayfa taranıyor..." }
                val allDocs = mutableListOf(mainDoc)
                if (subPages.isNotEmpty()) {
                    val semaphore = Semaphore(4)
                    val fetched = subPages.map { link ->
                        async {
                            semaphore.withPermit {
                                try {
                                    Jsoup.connect(link).userAgent("Mozilla/5.0 (Android) ScrapeFlix/0.6").timeout(12000).followRedirects(true).get()
                                } catch (e: Exception) { null }
                            }
                        }
                    }.awaitAll()
                    allDocs.addAll(fetched.filterNotNull())
                }
                val found = allDocs.flatMap { extractItems(it, site) }.distinctBy { it.url }.take(500)
                db.itemDao().deleteForSite(site.id); if (found.isNotEmpty()) db.itemDao().insertAll(found)
                db.siteDao().update(site.copy(lastUpdated=System.currentTimeMillis(),itemCount=found.size,profileStatus="Aktif"))
                withContext(Dispatchers.Main) { message="${allDocs.size} sayfa tarandı, ${found.size} içerik bulundu."; busy=false }
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
        Scaffold(containerColor=Color(0xFF080808),topBar={TopAppBar(title={Text("SCRAPEFLIX",fontWeight=FontWeight.Bold)},colors=TopAppBarDefaults.topAppBarColors(containerColor=Color.Black,titleContentColor=Color.White),actions={IconButton({add=true}){Icon(Icons.Default.Add,"Yeni site")}})},bottomBar={NavigationBar(containerColor=Color.Black){NavigationBarItem(page==Page.Home,{page=Page.Home},icon={Icon(Icons.Default.Home,null)},label={Text("Ana")});NavigationBarItem(page==Page.Sites,{page=Page.Sites},icon={Icon(Icons.Default.Language,null)},label={Text("Siteler")});NavigationBarItem(page==Page.Watch,{page=Page.Watch},icon={Icon(Icons.Default.PlayArrow,null)},label={Text("İçerikler")});NavigationBarItem(page==Page.Settings,{page=Page.Settings},icon={Icon(Icons.Default.Settings,null)},label={Text("Ayarlar")})}}){pad->Box(Modifier.padding(pad).fillMaxSize()){when(page){Page.Home->HomeScreen(vm){page=Page.Sites};Page.Sites->SitesScreen(vm, {add=true}) {editor=it};Page.Watch->WatchScreen(vm);Page.Settings->SettingsScreen()}}}
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

@Composable fun SitesScreen(vm:ScrapeViewModel,onAdd:()->Unit,onEdit:(SiteEntity)->Unit){val sites by vm.sites.collectAsState();Column(Modifier.fillMaxSize().padding(16.dp)){Row(Modifier.fillMaxWidth(),Arrangement.SpaceBetween,Alignment.CenterVertically){Column{Text("Sitelerim",fontSize=26.sp,fontWeight=FontWeight.Bold);Text("Analiz • önizleme • profil",color=Color.Gray)};FilledTonalButton(onAdd){Icon(Icons.Default.Add,null);Text(" Ekle")}};Spacer(Modifier.height(14.dp));if(vm.busy)LinearProgressIndicator(Modifier.fillMaxWidth());if(vm.message.isNotBlank())Text(vm.message,color=Color.LightGray,modifier=Modifier.padding(vertical=8.dp));LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){items(sites){s->SiteCard(s,{vm.analyze(s)},{vm.scrape(s)},{vm.deleteSite(s)});TextButton({onEdit(s)}){Icon(Icons.Default.Edit,null);Text(" Profil / Önizleme")}}}}}

@Composable fun SiteCard(site:SiteEntity,onAnalyze:()->Unit,onScrape:()->Unit,onDelete:()->Unit){Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF191919))){Column(Modifier.padding(14.dp)){Text(site.name,fontSize=19.sp,fontWeight=FontWeight.Bold);Text(site.url,color=Color.Gray,maxLines=1);Text("${site.itemCount} içerik • ${site.profileStatus}",color=Color.LightGray,fontSize=13.sp);Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){TextButton(onAnalyze){Icon(Icons.Default.Search,null);Text(" Analiz")};TextButton(onScrape){Icon(Icons.Default.Refresh,null);Text(" Tara")};TextButton(onDelete){Icon(Icons.Default.Delete,null);Text(" Sil")}}}}}

@Composable fun WatchScreen(vm: ScrapeViewModel) {
    val items by vm.allItems.collectAsState()
    val context = LocalContext.current
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("Tümü") }
    val filtered = items.filter { (q.isBlank() || it.title.contains(q, true)) && (cat == "Tümü" || it.category == cat) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("İçerikler", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("İçerik ara") })
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Tümü", "Film", "Dizi", "Anime", "Belgesel").forEach {
                FilterChip(selected = cat == it, onClick = { cat = it }, label = { Text(it) })
            }
        }
        Spacer(Modifier.height(8.dp))
        if (vm.streamBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.message.isNotBlank()) Text(vm.message, color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        Spacer(Modifier.height(2.dp))
        if (filtered.isEmpty()) Text("Sonuç bulunamadı.", color = Color.Gray) else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { vm.openStream(context, item) },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF191919))
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!item.imageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = coil.request.ImageRequest.Builder(context)
                                    .data(item.imageUrl)
                                    .addHeader("User-Agent", "Mozilla/5.0 (Android) ScrapeFlix/0.7")
                                    .addHeader("Referer", refererFor(item.url))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = item.title,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.size(90.dp, 70.dp).clip(RoundedCornerShape(6.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Column {
                            Text(item.title, fontWeight = FontWeight.Bold, maxLines = 2)
                            Text(item.category, color = Color.Gray, fontSize = 12.sp)
                            item.description?.let { Text(it, color = Color.Gray, maxLines = 2, fontSize = 12.sp) }
                        }
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

@Composable fun SettingsScreen(){Column(Modifier.fillMaxSize().padding(20.dp)){Text("Ayarlar",fontSize=28.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(16.dp));Text("ScrapeFlix v0.7.0",fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));Text("v0.7: Görsel yakalama güçlendirildi (background-image, srcset, Referer/User-Agent header desteği). İçeriğe tıklandığında artık gerçek akış linki (mp4/m3u8) otomatik bulunup tarayıcı yerine cihazdaki bir video oynatıcı seçtiriliyor. Premium uygulama logosu eklendi.",color=Color.Gray)}}

class MainActivity:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{App()}}}
