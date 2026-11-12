package eu.kanade.tachiyomi.extension.es.tvymanga

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

class LanguageFilter :
    UriPartFilter(
        "Idioma",
        arrayOf(
            Pair("Según ajustes", ""),
            Pair("Español", TvyManga.LANG_ES),
            Pair("Inglés", TvyManga.LANG_EN),
            Pair("Ambos", TvyManga.LANG_ALL),
        ),
    )

class SortFilter :
    UriPartFilter(
        "Ordenar por",
        arrayOf(
            Pair("Más capítulos", "count"),
            Pair("Nombre (A-Z)", "name"),
        ),
    )
