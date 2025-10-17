package eu.kanade.tachiyomi.extension.id.ngomikid

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import java.text.SimpleDateFormat
import java.util.Locale

class NgomikID : MangaThemesia(
    "NgomikID", 
    "https://id.ngomik.cloud",
    "id") {
    dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("id"))
}
