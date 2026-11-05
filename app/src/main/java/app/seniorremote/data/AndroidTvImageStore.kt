package app.seniorremote.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.seniorremote.domain.TvImageStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps a small private copy of a picture chosen with the system photo picker, so no storage
 * permission is needed and the picture survives the original being deleted.
 */
class AndroidTvImageStore(private val context: Context) : TvImageStore {
    private val dir = File(context.filesDir, "tv_images")

    /** Copies and shrinks the picture; returns its file name, or null if it cannot be read. */
    suspend fun import(tvId: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE_PX) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@runCatching null
            dir.mkdirs()
            val name = "${tvId.filter(Char::isLetterOrDigit)}_${System.currentTimeMillis()}.jpg"
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            name
        }.getOrNull()
    }

    suspend fun load(fileName: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(File(dir, safe(fileName)).path) }.getOrNull()
    }

    override suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        File(dir, safe(fileName)).delete()
        Unit
    }

    private fun safe(fileName: String) = File(fileName).name

    private companion object {
        const val MAX_SIZE_PX = 256
    }
}
