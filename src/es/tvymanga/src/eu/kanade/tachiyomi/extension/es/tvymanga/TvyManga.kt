package eu.kanade.tachiyomi.extension.es.tvymanga

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * tvymanga3.com is a plain WordPress blog: every chapter is a post. Most series have a category, but many
 * chapters are left in "Uncategorized" (and English ones in "INGLES"), so those are grouped by the series
 * name taken from the post title ("Kanojo, Okarishimasu 447" -> "Kanojo, Okarishimasu").
 */
@Source
abstract class TvyManga :
    KeiSource(),
    ConfigurableSource {

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    // ============================== Catalog ===============================

    private class Series(
        val key: String,
        val title: String,
        val url: String,
        val lang: String,
        val categoryId: Int?,
        val categoryCount: Int,
        val loosePosts: MutableList<PostDto> = mutableListOf(),
    ) {
        val chapterCount get() = categoryCount + loosePosts.size
    }

    private class Catalog(
        val series: List<Series>,
        val byCategory: Map<Int, Series>,
        val byKey: Map<String, Series>,
        val looseCategoryIds: Set<Int>,
        val englishCategoryId: Int?,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val catalogMutex = Mutex()
    private var catalogCache: Catalog? = null

    private suspend fun catalog(): Catalog = catalogMutex.withLock {
        catalogCache?.takeIf { System.currentTimeMillis() - it.createdAt < CATALOG_TTL_MS }
            ?: buildCatalog().also { catalogCache = it }
    }

    private suspend fun buildCatalog(): Catalog {
        val categories = fetchAllPages<CategoryDto>(apiUrl("categories").addQueryParameter("_fields", "id,name,slug,count"))
        val looseCats = categories.filter { it.slug in LOOSE_CATEGORY_SLUGS }
        val englishCat = categories.firstOrNull { it.slug == ENGLISH_CATEGORY_SLUG }

        val series = mutableListOf<Series>()
        val byKey = mutableMapOf<String, Series>()
        val byCategory = mutableMapOf<Int, Series>()

        categories
            .filter { it.count > 0 && it !in looseCats && it != englishCat }
            .forEach { cat ->
                val title = cat.name.decodeHtml()
                val s = Series(LANG_ES + ":" + title.toKey(), title, "/category/${cat.slug}/", LANG_ES, cat.id, cat.count)
                series += s
                byCategory[cat.id] = s
                byKey.putIfAbsent(s.key, s)
            }

        fun addLoose(posts: List<PostDto>, lang: String) {
            posts.forEach { post ->
                val title = post.title.rendered.decodeHtml()
                val name = seriesName(title)
                val key = lang + ":" + name.toKey()
                val s = byKey.getOrPut(key) {
                    Series(key, name, "/?s=${name.urlEncode()}&$LANG_PARAM=$lang", lang, null, 0).also { series += it }
                }
                s.loosePosts += post
            }
        }

        val looseFields = "id,date,slug,title,categories"
        looseCats.forEach { cat ->
            addLoose(fetchAllPages(postsUrl().addQueryParameter("categories", cat.id.toString()).addQueryParameter("_fields", looseFields)), LANG_ES)
        }
        englishCat?.let { cat ->
            addLoose(fetchAllPages(postsUrl().addQueryParameter("categories", cat.id.toString()).addQueryParameter("_fields", looseFields)), LANG_EN)
        }

        return Catalog(series, byCategory, byKey, looseCats.map { it.id }.toSet(), englishCat?.id)
    }

    private fun Catalog.seriesOf(post: PostDto): Series? {
        post.categories.firstNotNullOfOrNull { byCategory[it] }?.let { return it }
        val lang = if (englishCategoryId != null && englishCategoryId in post.categories) LANG_EN else LANG_ES
        return byKey[lang + ":" + seriesName(post.title.rendered.decodeHtml()).toKey()]
    }

    private fun Catalog.seriesOf(manga: SManga): Series? {
        val url = (baseUrl + manga.url).toHttpUrl()
        val path = url.encodedPath
        if (path.startsWith("/category/")) {
            val slug = path.removePrefix("/category/").trim('/')
            return series.firstOrNull { it.url == "/category/$slug/" }
        }
        val name = url.queryParameter("s") ?: return null
        val lang = url.queryParameter(LANG_PARAM) ?: LANG_ES
        return byKey["$lang:${name.toKey()}"]
    }

    private fun Series.toSManga() = SManga.create().apply {
        url = this@toSManga.url
        title = this@toSManga.title
        thumbnail_url = coverUrl()
    }

    /** Resolved by [coverInterceptor]: the newest chapter's post, whose first image is the thumbnail the site shows. */
    private fun Series.coverUrl(): String? {
        val url = postsUrl().addQueryParameter("per_page", "1").addQueryParameter("_fields", "content")
        when {
            categoryId != null -> url.addQueryParameter("categories", categoryId.toString())
            else -> url.addQueryParameter("slug", loosePosts.maxByOrNull { it.date }?.slug ?: return null)
        }
        return url.addQueryParameter(COVER_PARAM, "1").build().toString()
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(::coverInterceptor)

    private fun coverInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.queryParameter(COVER_PARAM) == null) return chain.proceed(request)

        val apiRequest = request.newBuilder()
            .url(request.url.newBuilder().removeAllQueryParameters(COVER_PARAM).build())
            .header("Accept", "application/json")
            .build()
        val html = chain.proceed(apiRequest).use { it.body.string() }
            .parseAs<List<PostDto>>().firstOrNull()?.content?.rendered.orEmpty()
        val cover = Jsoup.parseBodyFragment(html, baseUrl).select("img")
            .map { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
            .firstOrNull { it.isNotEmpty() }
            ?: throw IOException("Sin portada")
        // Image hosts like imgur reject foreign Referers, so only keep the User-Agent.
        val imageRequest = Request.Builder().url(cover)
            .apply { request.header("User-Agent")?.let { header("User-Agent", it) } }
            .build()
        return chain.proceed(imageRequest)
    }

    private fun languageMatches(lang: String, wanted: String) = wanted == LANG_ALL || lang == wanted

    private fun preferredLanguage() = preferences.getString(PREF_LANG_KEY, LANG_ES)!!

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = listPage(catalog().series, page, preferredLanguage(), "count", "")

    private fun listPage(all: List<Series>, page: Int, lang: String, sort: String, query: String): MangasPage {
        val key = query.toKey()
        val filtered = all
            .filter { languageMatches(it.lang, lang) }
            .filter { key.isEmpty() || key in it.key.substringAfter(':') }
            .let { list -> if (sort == "name") list.sortedBy { it.title.lowercase() } else list.sortedByDescending { it.chapterCount } }
        val chunk = filtered.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        return MangasPage(chunk.map { it.toSManga() }, page * PAGE_SIZE < filtered.size)
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val catalog = catalog()
        val lang = preferredLanguage()
        val url = postsUrl()
            .addQueryParameter("per_page", "50")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("_fields", "id,date,slug,title,categories")
            .build()
        val response = client.get(url, Fetch.XHR)
        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
        val posts = response.parseAs<List<PostDto>>()
        val mangas = posts.mapNotNull { catalog.seriesOf(it) }
            .filter { languageMatches(it.lang, lang) }
            .distinctBy { it.key }
            .map { it.toSManga() }
        return MangasPage(mangas, page < totalPages)
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val lang = filters.firstInstanceOrNull<LanguageFilter>()?.toUriPart()?.ifEmpty { null } ?: preferredLanguage()
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "count"
        return listPage(catalog().series, page, lang, sort, query)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("La búsqueda agrupa los capítulos por nombre de serie"),
        LanguageFilter(),
        SortFilter(),
    )

    // =========================== Details/Chapters ==========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val catalog = catalog()
        val series = catalog.seriesOf(manga) ?: throw Exception("Serie no encontrada, vuelve a buscarla")

        val fields = "id,date,slug,title"
        val ownKey = series.key.substringAfter(':')
        val matchKeys = mutableSetOf(ownKey)
        fun PostDto.nameKey() = seriesName(title.rendered.decodeHtml()).toKey()

        // The site sometimes files a chapter under another series' category: drop titles that name a different known series.
        fun PostDto.belongsElsewhere() = catalog.byKey["${series.lang}:${nameKey()}"].let { it != null && it !== series } && nameKey() !in matchKeys

        val posts = buildList {
            series.categoryId?.let { id ->
                val catPosts = fetchAllPages<PostDto>(postsUrl().addQueryParameter("categories", id.toString()).addQueryParameter("_fields", fields))
                // Category names often differ from chapter titles ("KANOJO OKARISHIMASU" vs "Kanojo, Okarishimasu 447").
                catPosts.groupingBy { it.nameKey() }.eachCount().maxByOrNull { it.value }?.key?.let(matchKeys::add)
                addAll(catPosts.filterNot { it.belongsElsewhere() })
            }
            addAll(series.loosePosts)
            if (series.lang == LANG_ES) {
                // Picks up this series' chapters that were misfiled under other categories.
                val search = postsUrl().addQueryParameter("search", series.title).addQueryParameter("_fields", fields)
                addAll(fetchAllPages<PostDto>(search, maxPages = 3).filter { it.nameKey() in matchKeys })
            }
        }.distinctBy { it.id }.sortedByDescending { it.date }

        val chapterList = posts.map { post ->
            val title = post.title.rendered.decodeHtml()
            SChapter.create().apply {
                url = "/${post.slug}/"
                name = title
                chapter_number = chapterNumber(title)
                date_upload = dateFormat.tryParseDateTime(post.date)
            }
        }

        val details = manga.apply {
            title = series.title
            status = SManga.UNKNOWN
            description = "Capítulos: ${chapterList.size}" + if (series.lang == LANG_EN) "\nIdioma: Inglés" else ""
            thumbnail_url = series.coverUrl()
            initialized = true
        }

        return SMangaUpdate(details, chapterList)
    }

    // ================================ Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val slug = chapter.url.trim('/')
        return pageUrls(slug).mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    private suspend fun pageUrls(slug: String): List<String> {
        val url = postsUrl().addQueryParameter("slug", slug).addQueryParameter("_fields", "content").build()
        val html = client.get(url, Fetch.XHR).parseAs<List<PostDto>>().firstOrNull()?.content?.rendered
            ?: throw Exception("Capítulo no encontrado")
        return Jsoup.parseBodyFragment(html, baseUrl).select("img")
            .filter { it.attr("width") != "0" } // 0x0 tracking pixel before the pages
            .map { img -> img.absUrl("data-src").ifEmpty { img.absUrl("src") } }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    // =============================== Helpers ==============================

    private fun apiUrl(endpoint: String): HttpUrl.Builder = "$baseUrl/wp-json/wp/v2/$endpoint".toHttpUrl().newBuilder()

    private fun postsUrl() = apiUrl("posts")

    private suspend inline fun <reified T> fetchAllPages(url: HttpUrl.Builder, maxPages: Int = Int.MAX_VALUE): List<T> {
        url.setQueryParameter("per_page", "100")
        val first = client.get(url.setQueryParameter("page", "1").build(), Fetch.XHR)
        val totalPages = minOf(first.header("X-WP-TotalPages")?.toIntOrNull() ?: 1, maxPages)
        val firstItems = first.parseAs<List<T>>()
        if (totalPages <= 1) return firstItems
        val pageUrls = (2..totalPages).map { url.setQueryParameter("page", it.toString()).build() }
        val rest = coroutineScope {
            pageUrls.map { async { client.get(it, Fetch.XHR).parseAs<List<T>>() } }.awaitAll()
        }
        return firstItems + rest.flatten()
    }

    private fun String.decodeHtml() = Parser.unescapeEntities(this, false).trim()

    private fun String.urlEncode() = java.net.URLEncoder.encode(this, "UTF-8")

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Idioma de los mangas"
            entries = arrayOf("Español", "Inglés", "Ambos")
            entryValues = arrayOf(LANG_ES, LANG_EN, LANG_ALL)
            setDefaultValue(LANG_ES)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        const val LANG_ES = "es"
        const val LANG_EN = "en"
        const val LANG_ALL = "all"

        private const val LANG_PARAM = "tvy"
        private const val COVER_PARAM = "tvycover"
        private const val PREF_LANG_KEY = "manga_language"
        private const val PAGE_SIZE = 30
        private const val CATALOG_TTL_MS = 30 * 60 * 1000L

        private val LOOSE_CATEGORY_SLUGS = setOf("uncategorized", "sin-categoria")
        private const val ENGLISH_CATEGORY_SLUG = "ingles"

        private val CHAPTER_WORD_REGEX = Regex("""(?i)^(.*?)[\s:,\-–]*\b(?:cap[ií]tulo|chapter|cap\.)\s*(\d+(?:[.,]\d+)?)""")
        private val LAST_NUMBER_REGEX = Regex("""^(.*?)\s*(?<![\d.,])(\d+(?:[.,]\d+)?)\D*$""")

        /** "Kanojo, Okarishimasu 447" -> "Kanojo, Okarishimasu"; "Jujutsu kaisen chapter 217【...】" -> "Jujutsu kaisen". */
        fun seriesName(title: String): String {
            val match = CHAPTER_WORD_REGEX.find(title) ?: LAST_NUMBER_REGEX.find(title)
            val name = match?.groupValues?.get(1)?.trim()?.trimEnd('-', '–', ':', ',', ' ')
            return name?.takeIf { it.isNotBlank() } ?: title
        }

        fun chapterNumber(title: String): Float {
            val match = CHAPTER_WORD_REGEX.find(title) ?: LAST_NUMBER_REGEX.find(title)
            return match?.groupValues?.get(2)?.replace(',', '.')?.toFloatOrNull() ?: -1f
        }

        /** Case/punctuation-insensitive key so "KANOJO OKARISHIMASU" matches "Kanojo, Okarishimasu". */
        fun String.toKey() = lowercase(Locale.ROOT)
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
            .replace(Regex("""[^a-z0-9]"""), "")
    }
}
