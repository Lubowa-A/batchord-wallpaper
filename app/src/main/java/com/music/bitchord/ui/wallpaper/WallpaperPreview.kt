package com.music.bitchord.ui.wallpaper

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.music.bitchord.R
import com.music.bitchord.data.wallpaper.Wallpaper
import com.music.bitchord.data.wallpaper.WallpaperActions
import com.music.bitchord.data.wallpaper.WallpaperFavorites
import com.music.bitchord.data.wallpaper.WallpaperTarget
import com.music.bitchord.ui.components.AppWallpaper
import kotlinx.coroutines.launch
import kotlin.math.max

/** What the preview is busy doing, so the right control shows a spinner. */
private enum class Busy { APPLY, DOWNLOAD, APP_BACKGROUND }

/**
 * A wallpaper full screen, over everything including the app's own bars.
 *
 * The grid's thumbnail is shown at once and the full picture fades in over it
 * when it arrives, so opening never shows a blank screen. Pinch to zoom, drag
 * to pan, double tap to reset; a single tap hides the controls to see the
 * picture unobstructed.
 */
@Composable
fun WallpaperPreview(wallpaper: Wallpaper, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favorites by WallpaperFavorites.items.collectAsStateWithLifecycle()
    val isFavorite = favorites.any { it.key == wallpaper.key }
    val canApply = remember { WallpaperActions.canApply(context) }

    var busy by remember { mutableStateOf<Busy?>(null) }
    var choosingTarget by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var fullLoaded by remember { mutableStateOf(false) }
    var fullFailed by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun download() {
        busy = Busy.DOWNLOAD
        scope.launch {
            WallpaperActions.saveToGallery(context, wallpaper)
                .onSuccess { toast(context.getString(R.string.wallpaper_saved)) }
                .onFailure {
                    toast(context.getString(R.string.wallpaper_save_failed, it.message ?: ""))
                }
            busy = null
        }
    }

    // Only Android 8–9 need a permission to add to the gallery; from 10 the
    // MediaStore row this app creates is its own without asking.
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) download() else toast(context.getString(R.string.wallpaper_permission_denied))
    }
    val onDownload = {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            download()
        }
    }

    fun applyTo(target: WallpaperTarget) {
        choosingTarget = false
        busy = Busy.APPLY
        scope.launch {
            WallpaperActions.apply(context, wallpaper, target)
                .onSuccess {
                    toast(
                        context.getString(
                            when (target) {
                                WallpaperTarget.HOME -> R.string.wallpaper_applied_home
                                WallpaperTarget.LOCK -> R.string.wallpaper_applied_lock
                                WallpaperTarget.BOTH -> R.string.wallpaper_applied_both
                            },
                        ),
                    )
                }
                .onFailure {
                    toast(context.getString(R.string.wallpaper_apply_failed, it.message ?: ""))
                }
            busy = null
        }
    }

    fun useAsAppBackground() {
        busy = Busy.APP_BACKGROUND
        scope.launch {
            runCatching { WallpaperActions.fullFile(context, wallpaper) }
                .mapCatching { file -> AppWallpaper.importFrom(context, Uri.fromFile(file)).getOrThrow() }
                .onSuccess { toast(context.getString(R.string.wallpaper_app_background_set)) }
                .onFailure {
                    toast(context.getString(R.string.wallpaper_apply_failed, it.message ?: ""))
                }
            busy = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val enter = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = enter,
            enter = fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.94f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                val imageModifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            },
                        )
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }

                // The thumbnail first, from the grid's cache; the full picture on top.
                AsyncImage(
                    model = wallpaper.thumbModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = imageModifier,
                )
                val fullAlpha by animateFloatAsState(if (fullLoaded) 1f else 0f, tween(300), label = "full")
                val longSide = remember {
                    val m = context.resources.displayMetrics
                    max(m.widthPixels, m.heightPixels)
                }
                AsyncImage(
                    // Twice the screen so a zoomed-in look is still sharp,
                    // never more than the file itself holds.
                    model = remember(wallpaper.key) {
                        ImageRequest.Builder(context)
                            .data(wallpaper.fullModel)
                            .size(longSide * 2, longSide * 2)
                            .precision(Precision.INEXACT)
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = { s ->
                        fullLoaded = s is AsyncImagePainter.State.Success
                        fullFailed = s is AsyncImagePainter.State.Error
                    },
                    modifier = imageModifier.graphicsLayer { alpha = fullAlpha },
                )

                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    TopInfo(
                        wallpaper = wallpaper,
                        loading = !fullLoaded && !fullFailed,
                        failed = fullFailed,
                        onClose = onDismiss,
                    )
                }

                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                    ) {
                        AnimatedContent(
                            targetState = choosingTarget,
                            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(160)) },
                            label = "actions",
                        ) { choosing ->
                            if (choosing) {
                                TargetRow(onPick = ::applyTo, onCancel = { choosingTarget = false })
                            } else {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    ActionButton(
                                        icon = Icons.Rounded.PhoneAndroid,
                                        label = stringResource(R.string.wallpaper_action_apply),
                                        busy = busy == Busy.APPLY,
                                        enabled = canApply && busy == null,
                                        onClick = { choosingTarget = true },
                                    )
                                    // An import is already on the phone; saving it again would duplicate it.
                                    if (!wallpaper.isLocal) {
                                        ActionButton(
                                            icon = Icons.Rounded.Download,
                                            label = stringResource(R.string.wallpaper_action_download),
                                            busy = busy == Busy.DOWNLOAD,
                                            enabled = busy == null,
                                            onClick = onDownload,
                                        )
                                    }
                                    ActionButton(
                                        icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        label = stringResource(
                                            if (isFavorite) R.string.wallpaper_action_unfavorite
                                            else R.string.wallpaper_action_favorite,
                                        ),
                                        busy = false,
                                        enabled = true,
                                        onClick = { WallpaperFavorites.toggle(context, wallpaper) },
                                    )
                                    ActionButton(
                                        icon = Icons.Rounded.Wallpaper,
                                        label = stringResource(R.string.wallpaper_action_app_background),
                                        busy = busy == Busy.APP_BACKGROUND,
                                        enabled = busy == null,
                                        onClick = ::useAsAppBackground,
                                    )
                                }
                            }
                        }
                    }
                }
                if (!canApply && controlsVisible) {
                    Text(
                        stringResource(R.string.wallpaper_apply_unsupported),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 104.dp, start = 24.dp, end = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopInfo(wallpaper: Wallpaper, loading: Boolean, failed: Boolean, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.35f))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Close,
            contentDescription = stringResource(R.string.close),
            tint = Color.White,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(8.dp),
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            val size = if (wallpaper.width > 0) "${wallpaper.width}×${wallpaper.height}" else ""
            val mb = if (wallpaper.fileSize > 0) {
                " · " + String.format(java.util.Locale.ROOT, "%.1f MB", wallpaper.fileSize / 1_048_576.0)
            } else {
                ""
            }
            Text(size + mb, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                when {
                    failed -> stringResource(R.string.wallpaper_full_failed)
                    loading -> stringResource(R.string.wallpaper_loading_full)
                    wallpaper.isLocal -> stringResource(R.string.wallpaper_source_import)
                    else -> stringResource(R.string.wallpaper_source_wallhaven)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
        if (loading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TargetRow(onPick: (WallpaperTarget) -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.wallpaper_set_on),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TargetChip(stringResource(R.string.wallpaper_target_home)) { onPick(WallpaperTarget.HOME) }
            TargetChip(stringResource(R.string.wallpaper_target_lock)) { onPick(WallpaperTarget.LOCK) }
            TargetChip(stringResource(R.string.wallpaper_target_both)) { onPick(WallpaperTarget.BOTH) }
            TargetChip(stringResource(R.string.cancel), muted = true, onClick = onCancel)
        }
    }
}

@Composable
private fun TargetChip(label: String, muted: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (muted) Color.White.copy(alpha = 0.7f) else Color.Black,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (muted) Color.White.copy(alpha = 0.12f) else Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .graphicsLayer { alpha = if (enabled || busy) 1f else 0.4f }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            } else {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
