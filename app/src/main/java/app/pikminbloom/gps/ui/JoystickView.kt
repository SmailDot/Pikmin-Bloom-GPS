package app.pikminbloom.gps.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import app.pikminbloom.gps.R
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * A round virtual joystick: a base disc with a knob that follows the finger. Up is north, right is
 * east (Pikmin Bloom's map is north-up unless the user rotates it). Reports a compass bearing and a
 * 0..1 magnitude through [onSteer] on every move, and (0 magnitude) when the finger lifts.
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** bearingDeg in [0, 360), magnitude in [0, 1]. */
    var onSteer: ((bearingDeg: Double, magnitude: Double) -> Unit)? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.joystick_base)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.joystick_ring)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.joystick_knob)
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.joystick_ring)
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    private var knobDx = 0f
    private var knobDy = 0f
    private var active = false

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = baseRadius()
        canvas.drawCircle(cx, cy, r, basePaint)
        canvas.drawCircle(cx, cy, r, ringPaint)
        // North tick, so the user knows which way "up" points on the map.
        canvas.drawLine(cx, cy - r, cx, cy - r + r * 0.18f, tickPaint)
        canvas.drawCircle(cx + knobDx, cy + knobDy, knobRadius(), knobPaint)
    }

    private fun baseRadius(): Float = min(width, height) / 2f - 2f * resources.displayMetrics.density
    private fun knobRadius(): Float = baseRadius() * 0.36f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                active = true
                val cx = width / 2f
                val cy = height / 2f
                val travel = baseRadius() - knobRadius()
                var dx = event.x - cx
                var dy = event.y - cy
                val dist = hypot(dx, dy)
                if (dist > travel) {
                    dx *= travel / dist
                    dy *= travel / dist
                }
                knobDx = dx
                knobDy = dy
                val magnitude = (min(dist, travel) / travel).toDouble()
                // Screen y grows downwards; a compass bearing is clockwise from up.
                val bearing = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).let { if (it < 0) it + 360.0 else it }
                if (magnitude < DEAD_ZONE) onSteer?.invoke(bearing, 0.0) else onSteer?.invoke(bearing, magnitude)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false
                knobDx = 0f
                knobDy = 0f
                onSteer?.invoke(0.0, 0.0)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        /** Below this fraction of full deflection the knob counts as centred (standing still). */
        private const val DEAD_ZONE = 0.12
    }
}
