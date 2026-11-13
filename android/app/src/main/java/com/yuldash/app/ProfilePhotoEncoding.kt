package com.yuldash.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
// Платформенный конструктор от InputStream доступен с API 24.
import android.media.ExifInterface
import android.net.Uri
import android.os.Build

/**
 * Декодирует выбранное фото и перекодирует в компактный JPEG: нормализует формат
 * (png/webp/heic → jpg — сервер иначе отвергал не-jpeg, фото «не сохранялось») и уменьшает
 * тяжёлое фото до размера аватара (экономит трафик и квоту). null при ошибке. Тяжёлое —
 * звать на IO-потоке. Даунсэмпл через inSampleSize, чтобы не поймать OOM на больших снимках.
 *
 * ⚠️ Ориентация. `BitmapFactory` игнорирует EXIF-тег поворота, а перекодированный JPEG его уже
 * не несёт — селфи с камеры (портрет) сохранялся аватаром «на боку». Поэтому на API 28+ идём
 * через `ImageDecoder`: он применяет EXIF сам. На старых версиях — читаем тег `ExifInterface`
 * и доворачиваем матрицей вручную (аудит 2026-07-26).
 */
internal fun decodeToJpeg(
    context: Context,
    uri: Uri,
    maxSize: Int = 1024,
    quality: Int = 88,
): ByteArray? = runCatching {
    val decoded: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder сам применяет EXIF-ориентацию и умеет HEIC; ограничиваем размер в колбэке.
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.isMutableRequired = false
            val longest = maxOf(info.size.width, info.size.height, 1)
            if (longest > maxSize) {
                val k = maxSize.toFloat() / longest
                dec.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1),
                                  (info.size.height * k).toInt().coerceAtLeast(1))
            }
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSize * 2 || bounds.outHeight / sample > maxSize * 2) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        // До API 28 EXIF применяем руками — иначе портретное фото уедет на бок.
        val degrees = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees != 0f) {
            val m = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        } else raw
    }
    val scale = maxSize.toFloat() / maxOf(decoded.width, decoded.height, 1)
    val bmp = if (scale < 1f) {
        Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
    } else decoded
    java.io.ByteArrayOutputStream().use { bos ->
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        bos.toByteArray()
    }
}.getOrNull()
