package com.music.bitchord.ui.wallpaper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.data.wallpaper.Wallhaven
import com.music.bitchord.data.wallpaper.Wallpaper
import com.music.bitchord.data.wallpaper.WallpaperCategory
import com.music.bitchord.data.wallpaper.WallpaperSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The gallery's browse state: which category, what was searched, the pages
 * loaded so far, and whether the next one is coming or failed.
 *
 * [error] is for the first page; [moreError] for a later one, so a failed
 * page 4 leaves pages 1–3 on screen with a retry under them instead of
 * blanking the grid.
 */
data class WallpaperBrowseState(
    val category: WallpaperCategory = WallpaperCategory.FEATURED,
    val query: String = "",
    val items: List<Wallpaper> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val moreError: String? = null,
)

class WallpaperViewModel : ViewModel() {
    private val _state = MutableStateFlow(WallpaperBrowseState())
    val state: StateFlow<WallpaperBrowseState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        reload()
    }

    fun selectCategory(category: WallpaperCategory) {
        if (category == _state.value.category && _state.value.query.isEmpty()) return
        _state.update { it.copy(category = category, query = "") }
        reload()
    }

    fun search(text: String) {
        val trimmed = text.trim()
        if (trimmed == _state.value.query) return
        _state.update { it.copy(query = trimmed) }
        reload()
    }

    fun retry() {
        if (_state.value.items.isEmpty()) reload() else loadMore(force = true)
    }

    /** Called as the grid nears its end. */
    fun loadMore(force: Boolean = false) {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore || s.items.isEmpty()) return
        if (s.moreError != null && !force) return
        fetch(page = s.page + 1, append = true)
    }

    private fun reload() {
        _state.update {
            it.copy(items = emptyList(), page = 0, hasMore = true, error = null, moreError = null)
        }
        fetch(page = 1, append = false)
    }

    private fun fetch(page: Int, append: Boolean) {
        job?.cancel()
        val category = _state.value.category
        val query = _state.value.query
        _state.update {
            if (append) it.copy(loadingMore = true, moreError = null) else it.copy(loading = true, error = null)
        }
        job = viewModelScope.launch {
            try {
                val result = Wallhaven.search(category, query, page)
                _state.update { s ->
                    // A newer category or search replaced this request.
                    if (s.category != category || s.query != query) return@update s
                    val merged = if (append) {
                        val seen = s.items.mapTo(HashSet()) { it.key }
                        s.items + result.items.filter { it.key !in seen }
                    } else {
                        result.items
                    }
                    s.copy(
                        items = merged,
                        page = result.page,
                        hasMore = result.hasMore,
                        loading = false,
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = when (e) {
                    is WallpaperSourceException -> e.message
                    is IOException -> "Could not reach Wallhaven. Check your connection."
                    else -> "Could not read the wallpaper list."
                } ?: "Could not load wallpapers."
                _state.update {
                    if (append) it.copy(loadingMore = false, moreError = message)
                    else it.copy(loading = false, error = message)
                }
            }
        }
    }
}
