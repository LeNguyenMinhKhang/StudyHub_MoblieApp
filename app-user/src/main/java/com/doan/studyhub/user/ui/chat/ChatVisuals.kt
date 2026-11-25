package com.doan.studyhub.user.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import com.doan.studyhub.user.R
import kotlin.math.abs
import kotlin.math.min

/** Up to two upper-case initials of [name] ("Giải tích 1" -> "GT"); "?" when blank. */
fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    val letters = words.mapNotNull { word -> word.codePoints().toArray().firstOrNull { Character.isLetterOrDigit(it) } }
    if (letters.isEmpty()) return "?"
    val picked = if (letters.size == 1) letters.take(1) else listOf(letters.first(), letters.last())
    return picked.joinToString("") { String(Character.toChars(it)).uppercase() }
}

/** Circle with initials, used for groups (and people) that have no picture. */
class InitialsAvatarDrawable(name: String) : Drawable() {
    private val initials = initialsOf(name)
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PALETTE[abs(name.hashCode()) % PALETTE.size]
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun draw(canvas: Canvas) {
        val box = bounds
        val radius = min(box.width(), box.height()) / 2f
        canvas.drawCircle(box.exactCenterX(), box.exactCenterY(), radius, circle)
        text.textSize = radius * if (initials.length > 1) 0.8f else 1.0f
        val baseline = box.exactCenterY() - (text.descent() + text.ascent()) / 2f
        canvas.drawText(initials, box.exactCenterX(), baseline, text)
    }

    override fun setAlpha(alpha: Int) { circle.alpha = alpha; text.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { circle.colorFilter = colorFilter; text.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        val PALETTE = intArrayOf(0xFF1F4D43.toInt(), 0xFF2E7D6B.toInt(), 0xFF3F6FA8.toInt(), 0xFF7B5EA7.toInt(), 0xFFB5651D.toInt(), 0xFFA23B5A.toInt())
    }
}

/** Rounded bubble whose corners next to a neighbouring bubble of the same run are tightened. */
object BubbleShapes {
    private const val ROUND_DP = 18f
    private const val TIGHT_DP = 4f

    fun background(context: Context, mine: Boolean, position: RunPosition): Drawable {
        val density = context.resources.displayMetrics.density
        val round = ROUND_DP * density
        val tight = TIGHT_DP * density
        val joinsAbove = position == RunPosition.MIDDLE || position == RunPosition.LAST
        val joinsBelow = position == RunPosition.FIRST || position == RunPosition.MIDDLE
        val top = if (joinsAbove) tight else round
        val bottom = if (joinsBelow) tight else round
        // Corners are TL, TR, BR, BL; the tightened side faces the avatar column / screen edge.
        val radii = if (mine) floatArrayOf(round, round, top, top, bottom, bottom, round, round)
        else floatArrayOf(top, top, round, round, round, round, bottom, bottom)
        return GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, if (mine) R.color.primary else R.color.surface_variant))
            cornerRadii = radii
        }
    }
}
