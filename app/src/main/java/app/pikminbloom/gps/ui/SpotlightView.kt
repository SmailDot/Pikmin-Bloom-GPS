package app.pikminbloom.gps.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import app.pikminbloom.gps.R
import com.google.android.material.button.MaterialButton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The spotlight tour, drawn over the real main screen: a 70 % scrim with a hole over the step's control, a ring that
 * pulses round the hole, a card with the step's text, and, for a tap or long-press step, a hand that shows the gesture.
 * Every touch only moves the tour on; nothing underneath is ever pressed. Animations are skipped when the system has
 * them turned off. The geometry is pure, in [SpotlightLayout].
 */
class SpotlightView(context: Context) : FrameLayout(context) {

    private val pad = resources.getDimensionPixelSize(R.dimen.coach_padding)
    private val margin = resources.getDimensionPixelSize(R.dimen.coach_margin)
    private val pulseMax = resources.getDimensionPixelSize(R.dimen.coach_pulse)
    private val corner = resources.getDimensionPixelSize(R.dimen.coach_hole_corner).toFloat()
    private val handSize = resources.getDimensionPixelSize(R.dimen.coach_hand)
    private val circleRadius = resources.getDimensionPixelSize(R.dimen.coach_circle_radius)
    private val cardWidth = resources.getDimensionPixelSize(R.dimen.coach_card_width)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density

    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() } // 70 % black
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        color = 0xFFFFFFFF.toInt()
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = 0xFFFFFFFF.toInt()
    }
    private val holePath = Path()
    private val holeRect = RectF()

    private val card: View = LayoutInflater.from(context).inflate(R.layout.coach_card, this, false)
    private val titleView: TextView = card.findViewById(R.id.coachTitle)
    private val bodyView: TextView = card.findViewById(R.id.coachBody)
    private val nextButton: MaterialButton = card.findViewById(R.id.coachNext)
    private val demo: GoModesDemoView = card.findViewById(R.id.coachDemo)
    private val hand = ImageView(context).apply { setImageResource(R.drawable.ic_touch_hand) }

    private var steps: List<CoachStep> = emptyList()
    private var resolve: (TargetKey) -> Bounds? = { null }
    private var onFinished: () -> Unit = {}
    private var index = 0
    private var placement: Placement? = null
    private var pulseFraction = 0f
    private var animating = false
    private var animator: ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f

    init {
        isClickable = true // the whole tour consumes touches; see onTouchEvent
        setWillNotDraw(false)
        addView(card)
        addView(hand, LayoutParams(handSize, handSize))
        hand.visibility = GONE
        card.findViewById<MaterialButton>(R.id.coachSkip).setOnClickListener { skip() }
        nextButton.setOnClickListener { next() }
    }

    /** Starts the tour. [resolve] finds a step's spot in this view's pixels, which are the content root's; null means it is missing or hidden. */
    fun start(steps: List<CoachStep>, resolve: (TargetKey) -> Bounds?, onFinished: () -> Unit) {
        this.steps = steps
        this.resolve = resolve
        this.onFinished = onFinished
        index = 0
        post { showStep(0) }
    }

    /** Ends the tour now: 略過, Back, or after the last step. */
    fun skip() = finish()

    private fun next() {
        if (index >= steps.lastIndex) finish() else showStep(index + 1)
    }

    private fun finish() {
        animator?.cancel()
        animator = null
        demo.stop()
        (parent as? ViewGroup)?.removeView(this)
        onFinished()
    }

    private fun showStep(i: Int) {
        if (steps.isEmpty()) {
            finish()
            return
        }
        index = i
        val step = steps[i]
        titleView.setText(step.titleRes)
        nextButton.setText(if (i == steps.lastIndex) R.string.tour_done else R.string.tour_next)
        demo.visibility = if (step.demo) VISIBLE else GONE
        if (step.demo) demo.start() else demo.stop()
        // A spot that is missing or hidden turns the step into a centred card, with its fallback text if it has one.
        val spot = step.target?.let(resolve)
        bodyView.setText(if (spot == null) (step.fallbackBodyRes ?: step.bodyRes) else step.bodyRes)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val p = SpotlightLayout.place(
            Size(width, height),
            spot,
            step.shape,
            step.gesture,
            pad,
            circleRadius,
            handSize,
            margin,
            Size(card.measuredWidth, card.measuredHeight),
        )
        placement = p
        card.translationX = p.tooltipX.toFloat()
        card.translationY = p.tooltipY.toFloat()
        val tip = p.fingertip
        if (tip != null) {
            val origin = SpotlightLayout.handOrigin(tip, handSize, Size(width, height))
            hand.visibility = VISIBLE
            hand.translationX = origin.x.toFloat()
            hand.translationY = origin.y.toFloat()
        } else {
            hand.visibility = GONE
        }
        restartAnimation()
        invalidate()
    }

    /** The pulse and the press loop, 1200 ms, forever. With the system's animations off, everything stays still. */
    private fun restartAnimation() {
        animator?.cancel()
        animator = null
        pulseFraction = 0f
        hand.scaleX = 1f
        hand.scaleY = 1f
        animating = ValueAnimator.areAnimatorsEnabled()
        if (!animating) {
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                pulseFraction = anim.animatedValue as Float
                if (hand.visibility == VISIBLE) {
                    val press = 1f - 0.15f * sin(PI * pulseFraction).toFloat() // 1 to 0.85 and back
                    hand.scaleX = press
                    hand.scaleY = press
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val p = placement
        val hole = p?.hole
        val circle = p?.circle
        if (hole == null && circle == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        } else {
            holePath.reset()
            holePath.fillType = Path.FillType.EVEN_ODD
            holePath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (hole != null) {
                holeRect.set(hole.left.toFloat(), hole.top.toFloat(), hole.right.toFloat(), hole.bottom.toFloat())
                holePath.addRoundRect(holeRect, corner, corner, Path.Direction.CCW)
            }
            if (circle != null) holePath.addCircle(circle.cx.toFloat(), circle.cy.toFloat(), circle.radius.toFloat(), Path.Direction.CCW)
            canvas.drawPath(holePath, scrim)
        }
        val ring = p?.ring
        if (ring != null) {
            val pulse = SpotlightLayout.pulse(pulseFraction, pulseMax)
            ringPaint.alpha = pulse.alpha
            canvas.drawCircle(ring.cx.toFloat(), ring.cy.toFloat(), (ring.radius + pulse.growth).toFloat(), ringPaint)
        }
        val tip = p?.fingertip
        if (tip != null && animating) {
            // the tap or press ripple: it grows from the fingertip and fades
            ripplePaint.alpha = (255 * (1 - pulseFraction)).toInt()
            val radius = handSize * 0.3f + handSize * 0.7f * pulseFraction
            canvas.drawCircle(tip.x.toFloat(), tip.y.toFloat(), radius, ripplePaint)
        }
        super.onDraw(canvas)
    }

    /**
     * Every touch moves the tour on, wherever it lands, and none reaches the views underneath: this is a safety screen,
     * so a tap on 開始巡邏 or on the map must never really press them. A tap is a touch that did not move.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> if (abs(event.x - downX) < slop && abs(event.y - downY) < slop) next()
        }
        return true
    }

    private companion object {
        const val CYCLE_MS = 1200L
    }
}
