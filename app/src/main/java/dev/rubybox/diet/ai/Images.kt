package dev.rubybox.diet.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max

class PreparedImage(val thumb: ImageBitmap, val dataUrl: String)

object Images {
    /** Decodes, fixes EXIF rotation, downsizes to [maxSide] px and encodes as a JPEG data URL. Blocking. */
    fun prepare(context: Context, uri: Uri, maxSide: Int = 1024): PreparedImage {
        val cr = context.contentResolver
        fun open() = cr.openInputStream(uri) ?: throw IOException("Cannot open image")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported image")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = open().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Cannot decode image")

        val rotation = runCatching { open().use { ExifInterface(it).rotationDegrees } }.getOrDefault(0)
        val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
        val m = Matrix()
        if (scale < 1f) m.postScale(scale, scale)
        if (rotation != 0) m.postRotate(rotation.toFloat())
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return PreparedImage(bmp.asImageBitmap(), "data:image/jpeg;base64,$b64")
    }
}
