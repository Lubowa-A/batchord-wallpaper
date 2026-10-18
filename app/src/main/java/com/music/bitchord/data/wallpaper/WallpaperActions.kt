package com.music.bitchord.data.wallpaper

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.math.max

/** Where a wallpaper is applied. Lock screen needs API 24; minSdk is 26. */
enum class WallpaperTarget(val flags: Int) {
    HOME(WallpaperManager.FLAG_SYSTEM),
    LOCK(WallpaperManager.FLAG_LOCK),
    BOTH(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
}

/**
 * Everything that touches a wallpaper's bytes: fetching, applying, saving to
 * the gallery and importing from it. All of it runs on [Dispatchers.IO] and
 * reports failure as a [Result] carrying a message fit to show the user.
 */
object WallpaperActions {
    const val IMPORTS_DIR = "wallpaper_imports"
    private const val CACHE_DIR = "wallpaper_cache"

    /** Past this long side the picture is scaled down before the system gets it. */
    private const val MAX_APPLY_SIDE = 4096

    /** Larger than any real wallpaper; stops a hostile or broken response filling storage. */
    private const val MAX_BYTES = 80L * 1024 * 1024

    /** Whether this device and its policy let an app change the wallpaper at all. */
    fun canApply(context: Context): Boolean {
        val manager = WallpaperManager.getInstance(context)
        return manager.isWallpaperSupported && manager.isSetWallpaperAllowed
    }

    /**
     * The full-resolution file for [wallpaper]: the import itself, or a cached
     * download. A second apply or save of the same picture reuses the cache.
     */
    suspend fun fullFile(context: Context, wallpaper: Wallpaper): File = withContext(Dispatchers.IO) {
        wallpaper.localPath?.let { return@withContext File(it) }
        val url = wallpaper.fullUrl ?: throw IOException("This wallpaper has no image address")
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val name = wallpaper.key.replace(Regex("[^A-Za-z0-9_-]"), "_") + extensionFor(wallpaper.fileType)
        val target = File(dir, name)
        if (target.isFile && target.length() > 0) return@withContext target

        val partial = File(dir, "$name.part")
        try {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
                val body = response.body ?: throw IOException("Download returned nothing")
                body.byteStream().use { copyCapped(it, partial) }
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("Could not store the download")
        }
        trimCache(dir)
        target
    }

    /** Sets [wallpaper] on [target]. */
    suspend fun apply(context: Context, wallpaper: Wallpaper, target: WallpaperTarget): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!canApply(context)) {
                    throw IOException("This device does not allow apps to change the wallpaper")
                }
                val source = fullFile(context, wallpaper)
                val file = downscaledIfHuge(context, source)
                val manager = WallpaperManager.getInstance(context)
                file.inputStream().use { stream ->
                    // Returns 0 when the system refused the stream.
                    val id = manager.setStream(stream, null, true, target.flags)
                    if (id == 0) throw IOException("The system did not accept the picture")
                }
                if (file != source) file.delete()
            }
        }

    /**
     * Saves the original bytes to Pictures/BitChord/Wallpapers. No re-encoding.
     * From API 29 MediaStore needs no permission for rows the app creates;
     * below that the caller must hold WRITE_EXTERNAL_STORAGE.
     */
    suspend fun saveToGallery(context: Context, wallpaper: Wallpaper): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = fullFile(context, wallpaper)
                val mime = wallpaper.fileType.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                val name = "bitchord-" +
                    wallpaper.key.substringAfter(':').replace(Regex("[^A-Za-z0-9_-]"), "_") +
                    extensionFor(mime)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_PICTURES}/BitChord/Wallpapers",
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("The gallery refused a new picture")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    } ?: throw IOException("Could not write to the gallery")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        resolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                            null,
                            null,
                        )
                    }
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                uri
            }
        }

    /**
     * Copies a picture the user picked into private storage so it can be
     * previewed, applied and starred after the picker's grant has expired.
     * Earlier imports that were never starred are deleted.
     */
    suspend fun importFromGallery(context: Context, uri: Uri): Result<Wallpaper> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, IMPORTS_DIR).apply { mkdirs() }
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val target = File(dir, "import_${System.currentTimeMillis()}${extensionFor(mime)}")
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("The picture could not be opened")
                try {
                    input.use { copyCapped(it, target) }
                } catch (e: Exception) {
                    target.delete()
                    throw e
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(target.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    target.delete()
                    throw IOException("That file is not a supported image")
                }
                val starred = WallpaperFavorites.items.value.mapNotNull { it.localPath }.toSet()
                dir.listFiles()?.forEach {
                    if (it != target && it.absolutePath !in starred) it.delete()
                }
                Wallpaper(
                    key = "local:${target.name}",
                    localPath = target.absolutePath,
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                    fileSize = target.length(),
                    fileType = mime,
                )
            }
        }

    /**
     * The system decodes a wallpaper whole. An 8K picture is ~130 MB as a
     * bitmap, which some launchers fail on, so anything past [MAX_APPLY_SIDE]
     * is subsampled to the nearest power of two above the screen's long side
     * and re-encoded once at high quality. Smaller pictures go through as is.
     */
    private fun downscaledIfHuge(context: Context, source: File): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val longSide = max(bounds.outWidth, bounds.outHeight)
        if (longSide <= MAX_APPLY_SIDE) return source
        val metrics = context.resources.displayMetrics
        val screenLong = max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        var sample = 1
        while (longSide / (sample * 2) >= screenLong) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            source.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IOException("The picture could not be decoded")
        val out = File(context.cacheDir, "wallpaper_apply_${System.nanoTime()}.jpg")
        try {
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } finally {
            bitmap.recycle()
        }
        return out
    }

    /** Streams [input] into [target], refusing anything past [MAX_BYTES]. */
    private fun copyCapped(input: java.io.InputStream, target: File) {
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) throw IOException("The picture is larger than 80 MB")
                output.write(buffer, 0, read)
            }
        }
    }

    /** Keeps the download cache to the most recent dozen pictures. */
    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        files.sortedByDescending { it.lastModified() }.drop(12).forEach { it.delete() }
    }

    private fun extensionFor(mime: String): String = when (mime.lowercase()) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/heic", "image/heif" -> ".heic"
        "image/gif" -> ".gif"
        else -> ".jpg"
    }
}
