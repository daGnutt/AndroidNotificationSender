package se.gnutt.notificationsender

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream

fun getAppName(packageName: String, pm: PackageManager): String =
    try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

/** Renders [drawable] into a [size]×[size] bitmap and returns a Base64-encoded PNG. */
fun drawableToBase64(drawable: Drawable, size: Int): String {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
        drawable.background?.apply { setBounds(0, 0, size, size); draw(canvas) }
        drawable.foreground?.apply { setBounds(0, 0, size, size); draw(canvas) }
    } else {
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
    }
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    bitmap.recycle()
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}
