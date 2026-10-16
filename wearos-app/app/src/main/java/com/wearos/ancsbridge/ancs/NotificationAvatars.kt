package com.wearos.ancsbridge.ancs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import androidx.core.graphics.drawable.IconCompat

/**
 * Round avatars for notification senders: initials on a colored disc, like the
 * contact avatars of Android's Messages app. ANCS sends no pictures, so this
 * is what tells senders and apps apart at a glance.
 */
object NotificationAvatars {

    private const val SIZE_PX = 128

    private val cache = object : LruCache<String, Bitmap>(48) {}

    /** An avatar for [name] on [color], as an icon for a MessagingStyle Person. */
    fun icon(name: String, color: Int): IconCompat = IconCompat.createWithBitmap(bitmap(name, color))

    fun bitmap(name: String, color: Int): Bitmap {
        val initials = NotificationLook.initials(name)
        val key = "$color|$initials"
        cache.get(key)?.let { return it }
        return draw(initials, color).also { cache.put(key, it) }
    }

    private fun draw(initials: String, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val size = SIZE_PX.toFloat()
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawCircle(size / 2, size / 2, size / 2, disc)

        val foreground = NotificationLook.textColorOn(color)
        if (initials.isEmpty()) {
            drawPersonOutline(canvas, size, foreground)
            return bitmap
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = foreground
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = size * if (initials.codePointCount(0, initials.length) > 1) 0.40f else 0.48f
        }
        // Center the glyphs vertically on the disc.
        val baseline = size / 2 - (text.descent() + text.ascent()) / 2
        canvas.drawText(initials, size / 2, baseline, text)
        return bitmap
    }

    /** Head and shoulders, for senders known only by their phone number. */
    private fun drawPersonOutline(canvas: Canvas, size: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.save()
        val clip = Path().apply { addCircle(size / 2, size / 2, size / 2, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawCircle(size / 2, size * 0.40f, size * 0.17f, paint)
        canvas.drawOval(RectF(size * 0.20f, size * 0.64f, size * 0.80f, size * 1.10f), paint)
        canvas.restore()
    }
}
