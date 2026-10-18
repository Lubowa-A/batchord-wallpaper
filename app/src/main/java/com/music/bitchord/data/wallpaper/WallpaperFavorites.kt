package com.music.bitchord.data.wallpaper

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The wallpapers the user has starred, newest first.
 *
 * Each entry is the whole [Wallpaper], not only its id, so the Favorites tab
 * lists without a network call and an imported picture can be a favourite too.
 * Kept in its own preference file: [AppSettings][com.music.bitchord.data.settings.AppSettings]
 * backs up its file wholesale, and a list of links into another phone's
 * private storage is nothing to restore.
 *
 * Imported pictures already live in the app's private imports folder, so a
 * starred import outlives the gallery entry it came from.
 */
object WallpaperFavorites {
    private const val PREFS = "wallpaper_favorites"
    private const val KEY = "items"
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Wallpaper.serializer())

    private var prefs: SharedPreferences? = null
    private val _items = MutableStateFlow<List<Wallpaper>>(emptyList())
    val items: StateFlow<List<Wallpaper>> = _items.asStateFlow()

    /** Idempotent; called from the screen so nothing loads until it is opened. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _items.value = runCatching {
            p.getString(KEY, null)?.let { json.decodeFromString(serializer, it) }
        }.getOrNull().orEmpty()
            // A starred import whose file was cleared with the app's storage.
            .filter { it.localPath == null || File(it.localPath).isFile }
    }

    fun isFavorite(key: String): Boolean = _items.value.any { it.key == key }

    fun toggle(context: Context, wallpaper: Wallpaper) {
        init(context)
        if (isFavorite(wallpaper.key)) remove(wallpaper) else add(wallpaper)
    }

    private fun add(wallpaper: Wallpaper) {
        save(listOf(wallpaper) + _items.value.filterNot { it.key == wallpaper.key })
    }

    private fun remove(wallpaper: Wallpaper) {
        // An un-starred import's file is left for the next import to sweep
        // (see WallpaperActions.importFromGallery): the preview it was
        // un-starred from may still be open and about to apply it.
        save(_items.value.filterNot { it.key == wallpaper.key })
    }

    private fun save(list: List<Wallpaper>) {
        _items.value = list
        prefs?.edit()?.putString(KEY, json.encodeToString(serializer, list))?.apply()
    }
}
