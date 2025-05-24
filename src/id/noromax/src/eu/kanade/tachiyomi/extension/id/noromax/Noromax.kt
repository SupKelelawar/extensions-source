package eu.kanade.tachiyomi.extension.id.noromax

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia

class Noromax : MangaThemesia(
    "Noromax",
    "https://noromax01.my.id",
    "id",
) {

    override fun pageListParse(document: Document): List<Page> {
    val resizeServiceUrl = getResizeServiceUrl()
    val imageElements = document.select("#readerarea img")
    return imageElements.mapNotNullIndexed { index, element ->
        val imageUrl = element.absUrl("src")
        // Kecualikan gambar yang berakhiran 999.png
        if (imageUrl.endsWith("999.png")) {
            null
        } else {
            Page(index, document.location(), "${resizeServiceUrl ?: ""}$imageUrl")
        }
    }
}

    // Site changed from ZeistManga to MangaThemesia
    override val versionId = 2

    override val hasProjectPage = true
}
