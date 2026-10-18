package com.music.bitchord.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * A picture of the user's choosing painted behind every page.
 *
 * The picked image is copied byte for byte into the app's private storage.
 * Nothing is re-encoded on the way in, so a 4K or 50 MP photo keeps every
 * pixel it arrived with, and the wallpaper keeps working after the original is
 * moved, deleted or its picker grant expires. Each import gets a fresh file
 * name so Coil's memory cache never serves the previous picture under the new
 * setting.
 */
object AppWallpaper {
    private const val DIR = "wallpaper"

    /** Larger than any phone photo; past this it is not a picture worth holding. */
    private const val MAX_BYTES = 64L * 1024 * 1024

    /**
     * Copies [source] into private storage and makes it the wallpaper.
     * Returns the stored file, or the reason the picture was refused.
     */
    suspend fun importFrom(context: Context, source: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val target = File(dir, "wallpaper_${System.currentTimeMillis()}")
            val input = context.contentResolver.openInputStream(source)
                ?: throw IOException("The picture could not be opened")
            input.use { inp ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    var total = 0L
                    while (true) {
                        val read = inp.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            out.close()
                            target.delete()
                            throw IOException("The picture is larger than 64 MB")
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            // Bounds only: proves the file is an image this phone can decode
            // without paying for a full decode here.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                target.delete()
                throw IOException("That file is not a supported image")
            }
            dir.listFiles()?.forEach { if (it != target) it.delete() }
            AppSettings.setAppWallpaperPath(target.absolutePath)
            target
        }
    }

    /** Back to the plain theme background, and the stored picture deleted. */
    fun clear(context: Context) {
        AppSettings.setAppWallpaperPath("")
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
    }
}

/**
 * The wallpaper at [path] decoded for this screen, or null when there is none.
 *
 * Decoded so its shorter side covers the screen's longer side. That is native
 * resolution in either orientation, so turning the phone never shows an
 * upscaled picture, while a photo far larger than the display is subsampled
 * on decode rather than held whole in memory. A picture smaller than the
 * screen is left at its own size rather than inflated.
 */
@Composable
fun rememberAppWallpaperPainter(path: String): Painter? {
    if (path.isEmpty()) return null
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val longSidePx = remember(configuration) {
        val metrics = context.resources.displayMetrics
        max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
    }
    val request = remember(path, longSidePx) {
        ImageRequest.Builder(context)
            .data(File(path))
            .size(longSidePx, longSidePx)
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .build()
    }
    return rememberAsyncImagePainter(request)
}

/**
 * Paints [painter] centre-cropped behind the content, with [veil] over it.
 *
 * [area] is the window the crop is fitted to, drawn from this node's top-left
 * corner. Passing the window size from every node that paints the wallpaper
 * makes their copies land on exactly the same pixels, so a page shorter than
 * the window never shows a seam against the copy painted beneath it.
 * Unspecified means this node's own bounds.
 *
 * Placed last in a modifier chain on purpose: draw modifiers earlier in the
 * chain (the glass backdrop layer, the haze source) record whatever the ones
 * after them draw, so the bars blur the wallpaper rather than a blank floor.
 */
fun Modifier.appWallpaper(
    painter: Painter?,
    veil: Color,
    area: Size = Size.Unspecified,
): Modifier {
    if (painter == null) return this
    return drawWithContent {
        val target = if (area.isSpecified) area else size
        val intrinsic = painter.intrinsicSize
        if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
            val scale = max(target.width / intrinsic.width, target.height / intrinsic.height)
            val drawn = Size(intrinsic.width * scale, intrinsic.height * scale)
            translate(
                left = (target.width - drawn.width) / 2f,
                top = (target.height - drawn.height) / 2f,
            ) {
                with(painter) { draw(drawn) }
            }
        } else {
            // Still loading, or a painter with no size of its own: fill the area.
            with(painter) { draw(target) }
        }
        if (veil.alpha > 0f) drawRect(veil, size = target)
        drawContent()
    }
}
