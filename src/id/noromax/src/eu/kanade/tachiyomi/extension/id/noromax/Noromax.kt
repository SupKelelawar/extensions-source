package eu.kanade.tachiyomi.extension.id.noromax

import android.app.Application
import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Noromax : MangaThemesia(
    "Noromax",
    "https://ngomik.org",
    "id",
    "/manga",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))
), ConfigurableSource {

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private fun getResizeServiceUrl(): String? {
        return preferences.getString("resize_service_url", null)
    }

    override var baseUrl = preferences.getString(BASE_URL_PREF, super.baseUrl)!!

    override val client = super.client.newBuilder()
        .rateLimit(4)
        .build()

    // Untuk menyesuaikan thumbnail di hasil pencarian
    override fun searchMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            val originalThumbnailUrl = element.select("img").imgAttr()
            thumbnail_url = "${getResizeServiceUrl() ?: ""}$originalThumbnailUrl"
            title = element.select("a").attr("title")
            setUrlWithoutDomain(element.select("a").attr("href"))
        }
    }

    // Untuk menyesuaikan thumbnail di halaman detail manga
    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
        val seriesDetails = document.select(seriesThumbnailSelector)
        val originalThumbnailUrl = seriesDetails.imgAttr()
        thumbnail_url = "${getResizeServiceUrl() ?: ""}$originalThumbnailUrl"
        title = document.selectFirst(seriesThumbnailSelector)!!.attr("title")
    }

    // MENYEDERHANAKAN PAGE LIST PARSE LANGSUNG DARI #readerarea img & filter 999.png
    override fun pageListParse(document: Document): List<Page> {
        val resizeServiceUrl = getResizeServiceUrl()
        val imageElements = document.select("#readerarea img")
        return imageElements.mapNotNullIndexed { index, element ->
            val imageUrl = element.absUrl("src")
            if (imageUrl.endsWith("999.png")) null
            else Page(index, document.location(), "${resizeServiceUrl ?: ""}$imageUrl")
        }
    }

    override fun setupPreferenceScreen(screen:ServicePref)
       {

        // Preference untuk mengubah base URL
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(baseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Original: $baseUrl"

            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue as String
                baseUrl = newUrl
                preferences.edit().putString(BASE_URL_PREF, newUrl).apply()
                summary = "Current domain: $newUrl"
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    companion object {
        private const val BASE_URL_PREF_TITLE = "Ubah Domain"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "Update domain untuk ekstensi ini"
    }

    override val hasProjectPage = true
}