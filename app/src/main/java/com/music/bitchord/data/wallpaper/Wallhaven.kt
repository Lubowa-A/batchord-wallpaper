package com.music.bitchord.data.wallpaper

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/**
 * One wallpaper, wherever it came from.
 *
 * A Wallhaven result carries its remote [fullUrl]; an image the user imported
 * from their own gallery carries a [localPath] into this app's private storage
 * instead. Serializable so a favourite can be listed offline without asking
 * the API for it again.
 */
@Serializable
data class Wallpaper(
    /** "wh:<id>" for Wallhaven, "local:<file name>" for an import. */
    val key: String,
    val thumbUrl: String? = null,
    val fullUrl: String? = null,
    val localPath: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fileSize: Long = 0,
    val fileType: String = "image/jpeg",
    /** The page the picture lives on, for credit. Null for imports. */
    val sourcePage: String? = null,
) {
    val isLocal: Boolean get() = localPath != null

    /** Width over height, falling back to a phone's shape before the size is known. */
    val aspect: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height else 9f / 16f

    /** What to hand an image loader for the full picture. */
    val fullModel: Any? get() = localPath?.let { java.io.File(it) } ?: fullUrl

    /** What to hand an image loader for the grid tile. */
    val thumbModel: Any? get() = localPath?.let { java.io.File(it) } ?: thumbUrl ?: fullUrl
}

/** The browse filters. Each maps to a real Wallhaven query, not a label only. */
enum class WallpaperCategory(
    /** Wallhaven's general/anime/people flags. */
    val flags: String,
    /** Keywords the category stands for; null for "everything". */
    val query: String?,
    /** Featured sorts by the month's toplist; the rest by relevance or date. */
    val sorting: String,
) {
    FEATURED("110", null, "toplist"),
    ANIME("010", null, "toplist"),
    NATURE("100", "nature", "relevance"),
    GAMING("110", "video games", "relevance"),
    DARK("110", "dark", "relevance"),
    MINIMALIST("110", "minimalism", "relevance"),
    ABSTRACT("110", "abstract", "relevance"),
}

class WallpaperPage(
    val items: List<Wallpaper>,
    val page: Int,
    val lastPage: Int,
) {
    val hasMore: Boolean get() = page < lastPage
}

/** Thrown for the answers worth telling the user about in their own words. */
class WallpaperSourceException(message: String) : IOException(message)

/**
 * Wallhaven's public search API.
 *
 * Only safe-for-work results are asked for (purity 100), which is also all the
 * API serves without a key. Results are filtered to portrait pictures at least
 * 1080x1920, the shape and size a phone wallpaper needs, so nothing in the
 * grid is a landscape photo that would be cropped to a sliver.
 *
 * Rate limit is 45 calls a minute; a 429 is reported rather than retried.
 */
object Wallhaven {
    private const val BASE = "https://wallhaven.cc/api/v1/search"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun search(
        category: WallpaperCategory,
        /** Typed search; replaces the category's own keywords, keeps its flags. */
        text: String?,
        page: Int,
    ): WallpaperPage = withContext(Dispatchers.IO) {
        val typed = text?.trim()?.takeIf { it.isNotEmpty() }
        val url = BASE.toHttpUrl().newBuilder().apply {
            addQueryParameter("purity", "100")
            addQueryParameter("categories", category.flags)
            addQueryParameter("ratios", "portrait")
            addQueryParameter("atleast", "1080x1920")
            (typed ?: category.query)?.let { addQueryParameter("q", it) }
            // A typed search is ranked by relevance whatever the category says;
            // a toplist ignores the keywords the user just typed.
            val sorting = if (typed != null) "relevance" else category.sorting
            addQueryParameter("sorting", sorting)
            if (sorting == "toplist") addQueryParameter("topRange", "1M")
            addQueryParameter("page", page.toString())
        }.build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        Http.client.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> Unit
                429 -> throw WallpaperSourceException("Too many requests. Wait a minute and try again.")
                else -> throw WallpaperSourceException("Wallhaven answered ${response.code}.")
            }
            val body = response.body?.string() ?: throw WallpaperSourceException("Empty response.")
            val parsed = json.decodeFromString(SearchResponse.serializer(), body)
            WallpaperPage(
                items = parsed.data.mapNotNull { it.toWallpaper() },
                page = parsed.meta.currentPage,
                lastPage = parsed.meta.lastPage,
            )
        }
    }

    @Serializable
    private class SearchResponse(
        val data: List<Item> = emptyList(),
        val meta: Meta = Meta(),
    )

    @Serializable
    private class Meta(
        @SerialName("current_page") val currentPage: Int = 1,
        @SerialName("last_page") val lastPage: Int = 1,
    )

    @Serializable
    private class Item(
        val id: String = "",
        val url: String? = null,
        val path: String? = null,
        @SerialName("dimension_x") val width: Int = 0,
        @SerialName("dimension_y") val height: Int = 0,
        @SerialName("file_size") val fileSize: Long = 0,
        @SerialName("file_type") val fileType: String = "image/jpeg",
        val thumbs: Thumbs = Thumbs(),
    ) {
        fun toWallpaper(): Wallpaper? {
            if (id.isEmpty() || path.isNullOrEmpty()) return null
            return Wallpaper(
                key = "wh:$id",
                // "original" keeps the picture's own shape; "large" is cropped.
                thumbUrl = thumbs.original ?: thumbs.large,
                fullUrl = path,
                width = width,
                height = height,
                fileSize = fileSize,
                fileType = fileType,
                sourcePage = url,
            )
        }
    }

    @Serializable
    private class Thumbs(
        val large: String? = null,
        val original: String? = null,
        val small: String? = null,
    )
}
