package app.pikminbloom.gps.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import app.pikminbloom.gps.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Three rows, one per flower menu action: a dot (the avatar) walks along a track and a flower stands where the walk
 * reaches it. Each row is labelled. The timing is [GoModesDemo.frame]; the loop runs every [CYCLE_MS], and with the
 * system's animations off the view stays on the final frame.
 */
class GoModesDemoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val rowHeight = 56 * density
    private val pad = 12 * density
    private val dotRadius = 6 * density
    private val flowerRadius = 7 * density
    private val modes = listOf(GoMode.GO_NOW, GoMode.GO_AND_STAY, GoMode.TELEPORT)
    private val labels = listOf(R.string.demo_go_now, R.string.demo_go_and_stay, R.string.demo_teleport)
        .map { context.getString(it) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66808080; strokeWidth = 2 * density }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E88E5.toInt() }
    private val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt() }
    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val pausePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF424242.toInt() }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = 0xFFFFC107.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF424242.toInt()
        textSize = 13 * resources.displayMetrics.scaledDensity
    }
    private var progress = 1f
    private var animator: ValueAnimator? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            (rowHeight * modes.size).toInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val usable = width - 2 * pad
        fun x(fraction: Float) = pad + fraction * usable
        modes.forEachIndexed { row, mode ->
            val top = row * rowHeight
            val y = top + 40 * density
            canvas.drawText(labels[row], pad, top + 16 * density, labelPaint)
            canvas.drawLine(x(GoModesDemo.START), y, x(GoModesDemo.FAR), y, trackPaint)
            canvas.drawFlower(x(GoModesDemo.FLOWER), y)
            if (mode == GoMode.GO_NOW) canvas.drawFlower(x(GoModesDemo.FAR), y)
            val frame = GoModesDemo.frame(mode, progress)
            val cx = x(frame.dotX)
            if (frame.flash) canvas.drawCircle(cx, y, dotRadius * 2.2f, flashPaint)
            if (frame.visible) canvas.drawCircle(cx, y, dotRadius, dotPaint)
            if (frame.paused) {
                val bar = 2.5f * density
                val left = cx + dotRadius * 1.6f
                canvas.drawRect(left, y - dotRadius, left + bar, y + dotRadius, pausePaint)
                canvas.drawRect(left + bar * 2, y - dotRadius, left + bar * 3, y + dotRadius, pausePaint)
            }
        }
    }

    /** A flower: five petals round a white centre. */
    private fun Canvas.drawFlower(cx: Float, cy: Float) {
        for (i in 0 until 5) {
            val angle = -PI / 2 + i * 2 * PI / 5
            drawCircle(
                cx + (flowerRadius * 0.55 * cos(angle)).toFloat(),
                cy + (flowerRadius * 0.55 * sin(angle)).toFloat(),
                flowerRadius * 0.5f,
                petalPaint,
            )
        }
        drawCircle(cx, cy, flowerRadius * 0.35f, centrePaint)
    }

    /** Starts the loop. With the system's animations off, it shows the final frame and does not move. */
    fun start() {
        animator?.cancel()
        animator = null
        if (!ValueAnimator.areAnimatorsEnabled()) {
            progress = 1f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val CYCLE_MS = 3000L
    }
}
