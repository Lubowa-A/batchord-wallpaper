package com.music.bitchord.ui.wallpaper

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.music.bitchord.R
import com.music.bitchord.data.wallpaper.Wallpaper
import com.music.bitchord.data.wallpaper.WallpaperActions
import com.music.bitchord.data.wallpaper.WallpaperCategory
import com.music.bitchord.data.wallpaper.WallpaperFavorites
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SearchField
import com.music.bitchord.ui.components.ShimmerBox
import com.music.bitchord.ui.screens.SegmentedControl
import kotlinx.coroutines.launch

private const val TAB_BROWSE = 0
private const val TAB_FAVORITES = 1
private val TILE_SHAPE = RoundedCornerShape(14.dp)

/**
 * The Wallpapers page: browse Wallhaven by category or search, keep favourites,
 * import a picture from the phone, and open any of them full screen to apply
 * or save. Opened from Settings → Appearance; drawn under the app's own top bar.
 */
@Composable
fun WallpapersScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: WallpaperViewModel = viewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { WallpaperFavorites.init(context) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val favorites by WallpaperFavorites.items.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(TAB_BROWSE) }
    var searchText by rememberSaveable { mutableStateOf(state.query) }
    var previewing by remember { mutableStateOf<Wallpaper?>(null) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            WallpaperActions.importFromGallery(context, uri)
                .onSuccess { previewing = it }
                .onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: context.getString(R.string.wallpaper_import_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            importing = false
        }
    }
    val pickImage = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val gridState = rememberLazyStaggeredGridState()
    // Asks for the next page while about a screen of tiles is still ahead.
    val nearEnd by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, tab, state.items.size) {
        if (nearEnd && tab == TAB_BROWSE) viewModel.loadMore()
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(150.dp),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PAGE_GUTTER,
            end = PAGE_GUTTER,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
    ) {
        fullLine("header") {
            Column {
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.wallpaper_tab_browse),
                        stringResource(R.string.wallpaper_tab_favorites),
                    ),
                    selectedIndex = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                ImportCard(busy = importing, onClick = pickImage)
                Spacer(Modifier.height(12.dp))
                AnimatedVisibility(visible = tab == TAB_BROWSE, enter = fadeIn(), exit = fadeOut()) {
                    Column {
                        SearchField(
                            query = searchText,
                            onQueryChange = {
                                searchText = it
                                if (it.isBlank() && state.query.isNotEmpty()) viewModel.search("")
                            },
                            onSubmit = { viewModel.search(searchText) },
                            placeholder = stringResource(R.string.wallpaper_search_hint),
                        )
                        Spacer(Modifier.height(10.dp))
                        CategoryChips(
                            selected = state.category.takeIf { state.query.isEmpty() },
                            onSelect = {
                                searchText = ""
                                viewModel.selectCategory(it)
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        if (tab == TAB_FAVORITES) {
            if (favorites.isEmpty()) {
                fullLine("fav-empty") {
                    MessageState(message = stringResource(R.string.wallpaper_favorites_empty))
                }
            } else {
                items(favorites.size, key = { "fav:" + favorites[it].key }) { i ->
                    val wp = favorites[i]
                    WallpaperTile(
                        wallpaper = wp,
                        favorite = true,
                        onClick = { previewing = wp },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        } else {
            when {
                state.loading && state.items.isEmpty() -> {
                    items(10, key = { "skeleton:$it" }) { i ->
                        ShimmerBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(if (i % 3 == 0) 0.5f else 0.5625f),
                            shape = TILE_SHAPE,
                        )
                    }
                }
                state.error != null && state.items.isEmpty() -> fullLine("error") {
                    MessageState(
                        message = state.error.orEmpty(),
                        actionLabel = stringResource(R.string.try_again),
                        onAction = viewModel::retry,
                    )
                }
                state.items.isEmpty() -> fullLine("empty") {
                    MessageState(
                        message = if (state.query.isNotEmpty()) {
                            stringResource(R.string.wallpaper_no_results, state.query)
                        } else {
                            stringResource(R.string.wallpaper_empty)
                        },
                    )
                }
                else -> {
                    items(state.items.size, key = { "wp:" + state.items[it].key }) { i ->
                        val wp = state.items[i]
                        WallpaperTile(
                            wallpaper = wp,
                            favorite = favorites.any { it.key == wp.key },
                            onClick = { previewing = wp },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    fullLine("footer") {
                        BrowseFooter(
                            loadingMore = state.loadingMore,
                            error = state.moreError,
                            end = !state.hasMore,
                            onRetry = viewModel::retry,
                        )
                    }
                }
            }
        }
    }

    previewing?.let { wp ->
        WallpaperPreview(
            wallpaper = wp,
            onDismiss = { previewing = null },
        )
    }
}

private fun androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope.fullLine(
    key: String,
    content: @Composable () -> Unit,
) = item(key = key, span = StaggeredGridItemSpan.FullLine) { content() }

@Composable
private fun ImportCard(busy: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TILE_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Rounded.AddPhotoAlternate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.wallpaper_import_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.wallpaper_import_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CategoryChips(selected: WallpaperCategory?, onSelect: (WallpaperCategory) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(WallpaperCategory.entries, key = { it.name }) { category ->
            val active = category == selected
            val bg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                tween(200),
                label = "chip-bg",
            )
            val fg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
                tween(200),
                label = "chip-fg",
            )
            Text(
                text = stringResource(category.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(bg)
                    .clickable { onSelect(category) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun WallpaperTile(
    wallpaper: Wallpaper,
    favorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Real proportions, clamped so one panorama-tall picture cannot
            // take a whole column.
            .aspectRatio(wallpaper.aspect.coerceIn(0.4f, 1f))
            .clip(TILE_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = remember(wallpaper.key) {
                ImageRequest.Builder(context)
                    .data(wallpaper.thumbModel)
                    .crossfade(250)
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (favorite) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(5.dp),
            )
        }
        if (wallpaper.width > 0) {
            Text(
                text = "${wallpaper.width}×${wallpaper.height}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun BrowseFooter(loadingMore: Boolean, error: String?, end: Boolean, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
            }
            end -> Text(
                stringResource(R.string.wallpaper_end),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun WallpaperCategory.labelRes(): Int = when (this) {
    WallpaperCategory.FEATURED -> R.string.wallpaper_cat_featured
    WallpaperCategory.ANIME -> R.string.wallpaper_cat_anime
    WallpaperCategory.NATURE -> R.string.wallpaper_cat_nature
    WallpaperCategory.GAMING -> R.string.wallpaper_cat_gaming
    WallpaperCategory.DARK -> R.string.wallpaper_cat_dark
    WallpaperCategory.MINIMALIST -> R.string.wallpaper_cat_minimalist
    WallpaperCategory.ABSTRACT -> R.string.wallpaper_cat_abstract
}
