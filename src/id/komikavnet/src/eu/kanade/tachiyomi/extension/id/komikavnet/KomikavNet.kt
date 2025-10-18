package eu.kanade.tachiyomi.extension.id.komikavnet

import android.app.Application
import eu.kanade.tachiyomi.network.GET
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KomikavNet : ParsedHttpSource(), ConfigurableSource {

    override val name = "Komikavnet"
    private val defaultBaseUrl = "https://komikav.net"
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient

    private val ITEMS_PER_PAGE = 18

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
    .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    override val baseUrl: String
    get() = preferences.getString("overrideBaseUrl", defaultBaseUrl)!!

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/popular/" else "$baseUrl/popular/?page=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) baseUrl else "$baseUrl/?page=$page"
        return GET(url, headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val baseSearch = "$baseUrl/search/$query/"
        val url = if (page <= 1) baseSearch else "$baseSearch?page=$page"
        return GET(url, headers)
    }

    override fun popularMangaSelector(): String =
    "div.grid > div.flex > div:first-child a.relative, div.grid a.relative"

    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun searchMangaSelector(): String = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = searchMangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element): SManga = searchMangaFromElement(element)

    override fun searchMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            val linkElement = element.selectFirst("a") ?: element
            setUrlWithoutDomain(linkElement.attr("href").trim())

            title = element.selectFirst("h2")?.text()?.trim().orEmpty()

            val imgElement = element.selectFirst("img")
            val rawSrc = imgElement?.absUrl("src")?.trim().orEmpty()
            thumbnail_url = if (rawSrc.isNotEmpty()) rawSrc.replace("lol", "li") else ""
        }
    }

    override fun popularMangaNextPageSelector(): String? = null
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun searchMangaNextPageSelector(): String? = null

    private fun <T> chunkForPage(all: List<T>, page: Int): List<T> {
        val per = ITEMS_PER_PAGE
        if (all.isEmpty()) return emptyList()
        val start = (page - 1) * per
        val end = (page * per).coerceAtMost(all.size)
        return if (start < all.size) all.subList(start, end) else emptyList()
    }

    private fun parsePagedResponse(
        response: Response,
        selector: String,
        mapper: (Element) -> SManga
    ): MangasPage {
        val doc = response.asJsoup()
        val pageNum = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val mapped = doc.select(selector).map {
            mapper(it)
        }
        val items = chunkForPage(mapped, pageNum)
        val hasNext = items.isNotEmpty() && items.size >= ITEMS_PER_PAGE
        return MangasPage(items, hasNext)
    }

    override fun popularMangaParse(response: Response): MangasPage =
    parsePagedResponse(response, popularMangaSelector(), ::popularMangaFromElement)

    override fun latestUpdatesParse(response: Response): MangasPage =
    parsePagedResponse(response, latestUpdatesSelector(), ::latestUpdatesFromElement)

    override fun searchMangaParse(response: Response): MangasPage =
    parsePagedResponse(response, searchMangaSelector(), ::searchMangaFromElement)

    override fun mangaDetailsParse(document: Document): SManga {
        if (document.text().contains("NEED LOGIN", true)) {
            throw Exception("⚠️ Login di webview, pakai akun abal abal !‼")
        }

        val manga = SManga.create()

        manga.title = document.selectFirst("h1.text-xl")?.text()?.trim().orEmpty()
        manga.thumbnail_url = document.selectFirst("img.w-full.rounded-md")?.attr("src").orEmpty()

        val genres = document.select("div.w-full.gap-4 a")
        .map {
            it.text().trim()
        }
        .filter {
            it.isNotEmpty()
        }
        .toMutableList()

        val typeText = document.selectFirst("div.relative.flex-shrink-0 div.mt-4 > div")
        ?.text()
        ?.trim()
        if (!typeText.isNullOrBlank() && genres.none {
            it.equals(typeText, ignoreCase = true)
        }) {
            genres.add(typeText)
        }

        val baseDesc = document.selectFirst("div.mt-4.w-full p")?.text()?.trim().orEmpty()
        manga.description = baseDesc + "\n"
        manga.genre = if (genres.isNotEmpty()) genres.joinToString(", ") else ""

        val statusText = document.selectFirst("div.w-full.rounded-r-full")?.text().orEmpty()
        manga.status = when {
            statusText.contains("on-going", true) -> SManga.ONGOING
            statusText.contains("completed", true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        val infoBlock = document.selectFirst("div.mt-4.flex.flex-col.gap-4 > div")
        val pList = infoBlock?.select("p.text-sm") ?: emptyList()

        fun extractNamesFromPIndex(index: Int): String? {
            return pList.getOrNull(index)
            ?.select("a")
            ?.map {
                it.text().substringBefore("(").trim()
            }
            ?.filter {
                it.isNotEmpty()
            }
            ?.joinToString(", ")
            ?.takeIf {
                it.isNotEmpty()
            }
        }

        manga.author = extractNamesFromPIndex(0)
        manga.artist = extractNamesFromPIndex(1)

        return manga
    }

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val href = element.attr("href").trim()
        try {
            chapter.setUrlWithoutDomain(href)
        } catch (_: Throwable) {
            chapter.url = href
        }

        chapter.name = element.selectFirst("p")?.text()?.trim() ?: element.text().trim()
        val timeText = element.selectFirst("p.text-xs")?.text()?.trim() ?: ""
        chapter.date_upload = parseDate(timeText)

        return chapter
    }

    override fun chapterListSelector(): String = "div.mt-4.flex a.group"

    private fun parseDate(date: String): Long {
        val trimmed = date.trim()
        val now = System.currentTimeMillis()
        val parts = trimmed.split(" ")
        if (parts.size < 2) return 0L

        val number = parts[0].toIntOrNull() ?: return 0L
        val unit = parts[1]

        val multiplier = when (unit) {
            "dtk" -> 1000L
            "mnt" -> 60_000L
            "jam" -> 3_600_000L
            "hari" -> 86_400_000L
            "mgg" -> 604_800_000L
            "bln" -> 2_592_000_000L
            "thn" -> 31_536_000_000L
            else -> 0L
        }

        return now - (number * multiplier)
    }

    override fun pageListParse(document: Document): List<Page> {
        val service = preferences.getString("resize_service_url", "") ?: ""
        return document.select("img.imgku")
        .mapIndexedNotNull {
            i, img ->
            val src = img.attr("src").trim()
            if (src.isBlank() || src.contains("banner")) {
                null
            } else {
                val finalUrl = if (service.isEmpty()) src else service + src
                Page(i, "", finalUrl)
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL (Pages)"
            summary = "Masukkan URL layanan resize gambar untuk halaman (page list)."
            setDefaultValue(null)
            dialogTitle = "Resize Service URL"
        }
        screen.addPreference(resizeServicePref)

        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = "overrideBaseUrl"
            title = "Ubah Domain"
            summary = "Update domain untuk ekstensi ini"
            setDefaultValue(baseUrl)
            dialogTitle = "Update domain untuk ekstensi ini"
            dialogMessage = "Original: $baseUrl"

            setOnPreferenceChangeListener {
                _, newValue ->
                val newUrl = newValue as String
                preferences.edit().putString("overrideBaseUrl", newUrl).apply()
                summary = "Current domain: $newUrl"
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    override fun imageUrlParse(document: Document): String = ""
}
