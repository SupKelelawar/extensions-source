package eu.kanade.tachiyomi.extension.id.shinigami

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Dns
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Locale

class Shinigami : HttpSource(), ConfigurableSource {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override val baseUrl: String
        get() = preferences.getString("overrideBaseUrl", "https://app.shinigami.asia")!!

    override val id = 3411809758861089969
    override val name = "Shinigami"

    private val apiUrl = "https://api.shngm.io"
    private val cdnUrl = "https://delivery.shngm.id"

    override val lang = "id"
    override val supportsLatest = true

    private val apiHeaders: Headers by lazy { apiHeadersBuilder().build() }

    // Custom DNS resolver
    private fun getDnsProvider(): Dns {
        return when (preferences.getString("dns_provider", "system")) {
            "cloudflare" -> CloudflareDns()
            "google" -> GoogleDns()
            "quad9" -> Quad9Dns()
            else -> Dns.SYSTEM
        }
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder().apply { removeAll("X-Requested-With") }.build()
            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .dns(getDnsProvider())
        .rateLimit(3)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("X-Requested-With", randomString((1..20).random()))

    private fun randomString(length: Int) = buildString {
        val charPool = ('a'..'z') + ('A'..'Z')
        repeat(length) { append(charPool.random()) }
    }

    private fun apiHeadersBuilder(): Headers.Builder = headersBuilder()
        .add("Accept", "application/json")
        .add("DNT", "1")
        .add("Origin", baseUrl)
        .add("Sec-GPC", "1")

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/v1/manga/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "30")
            .addQueryParameter("sort", "popularity")
            .build()
        return GET(url, apiHeaders)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val rootObject = response.parseAs<ShinigamiBrowseDto>()
        val projectList = rootObject.data.map(::popularMangaFromObject)
        val hasNextPage = rootObject.meta.page < rootObject.meta.totalPage
        return MangasPage(projectList, hasNextPage)
    }

    private fun popularMangaFromObject(obj: ShinigamiBrowseDataDto): SManga = SManga.create().apply {
        title = obj.title ?: ""
        thumbnail_url = obj.thumbnail?.let { 
            "https://wsrv.nl/?w=150&h=110&url=$it"
        }
        url = obj.mangaId ?: ""
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/v1/manga/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "30")
            .addQueryParameter("sort", "latest")
            .build()
        return GET(url, apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/v1/manga/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "30")
        if (query.isNotEmpty()) {
            url.addQueryParameter("q", query)
        }
        return GET(url.build(), apiHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun getMangaUrl(manga: SManga): String {
        return "$baseUrl/series/${manga.url}"
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        if (manga.url.startsWith("/series/")) {
            throw Exception("Migrate dari $name ke $name (ekstensi yang sama)")
        }
        return GET("$apiUrl/v1/manga/detail/${manga.url}", apiHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val mangaDetailsResponse = response.parseAs<ShinigamiMangaDetailDto>()
        val mangaDetails = mangaDetailsResponse.data
        return SManga.create().apply {
            author = mangaDetails.taxonomy["Author"]?.joinToString { it.name }.orEmpty()
            artist = mangaDetails.taxonomy["Artist"]?.joinToString { it.name }.orEmpty()
            status = mangaDetails.status.toStatus()
            description = mangaDetails.description
            val genres = mangaDetails.taxonomy["Genre"]?.joinToString { it.name }.orEmpty()
            val type = mangaDetails.taxonomy["Format"]?.joinToString { it.name }.orEmpty()
            genre = listOf(genres, type).filter { it.isNotBlank() }.joinToString()
        }
    }

    private fun Int.toStatus(): Int {
        return when (this) {
            1 -> SManga.ONGOING
            2 -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET("$apiUrl/v1/chapter/${manga.url}/list?page_size=3000", apiHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<ShinigamiChapterListDto>()
        return result.chapterList.map(::chapterFromObject)
    }

    private fun chapterFromObject(obj: ShinigamiChapterListDataDto): SChapter = SChapter.create().apply {
        date_upload = dateFormat.tryParse(obj.date)
        name = "Chapter ${obj.name.toString().replace(".0", "")} ${obj.title}"
        url = obj.chapterId
    }

    override fun pageListRequest(chapter: SChapter): Request {
        if (chapter.url.startsWith("/series/")) {
            throw Exception("Migrate dari $name ke $name (ekstensi yang sama)")
        }
        return GET("$apiUrl/v1/chapter/detail/${chapter.url}", apiHeaders)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<ShinigamiPageListDto>()
        val resizeServiceUrl = preferences.getString("resize_service_url", null)

        return result.pageList.chapterPage.pages.mapIndexed { index, imageName ->
            val originalImageUrl = "$cdnUrl${result.pageList.chapterPage.path}$imageName"
            val finalImageUrl = if (!resizeServiceUrl.isNullOrBlank()) {
                "$resizeServiceUrl$originalImageUrl"
            } else {
                originalImageUrl
            }
            Page(index = index, imageUrl = finalImageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .add("DNT", "1")
            .add("referer", "$baseUrl/")
            .add("sec-fetch-dest", "empty")
            .add("Sec-GPC", "1")
            .build()
        return GET(page.imageUrl!!, newHeaders)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val dnsProviderPref = ListPreference(screen.context).apply {
            key = "dns_provider"
            title = "DNS Provider"
            entries = arrayOf("System Default", "Cloudflare (1.1.1.1)", "Google (8.8.8.8)", "Quad9 (9.9.9.9)")
            entryValues = arrayOf("system", "cloudflare", "google", "quad9")
            setDefaultValue("system")
            summary = "Pilih DNS provider untuk mengatasi masalah koneksi atau blocking. Default: System"
            
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString("dns_provider", newValue as String).apply()
                true
            }
        }
        screen.addPreference(dnsProviderPref)

        val resizeServicePref = EditTextPreference(screen.context).apply {
            key = "resize_service_url"
            title = "Resize Service URL (Pages)"
            summary = "Masukkan URL layanan resize gambar untuk halaman (page list). Contoh: https://wsrv.nl/?url="
            setDefaultValue(null)
            dialogTitle = "Resize Service URL"
            dialogMessage = "URL akan digabungkan dengan URL gambar asli. Pastikan format URL benar."
        }
        screen.addPreference(resizeServicePref)

        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = "overrideBaseUrl"
            title = "Ubah Domain"
            summary = "Update domain untuk ekstensi ini"
            setDefaultValue(baseUrl)
            dialogTitle = "Update domain untuk ekstensi ini"
            dialogMessage = "Original: https://app.shinigami.asia"
            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue as String
                preferences.edit().putString("overrideBaseUrl", newUrl).apply()
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    // DNS Provider Classes
    private class CloudflareDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                InetAddress.getAllByName(hostname).toList()
            } catch (e: Exception) {
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    private class GoogleDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                InetAddress.getAllByName(hostname).toList()
            } catch (e: Exception) {
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    private class Quad9Dns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                InetAddress.getAllByName(hostname).toList()
            } catch (e: Exception) {
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    companion object {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH)
    }
}
