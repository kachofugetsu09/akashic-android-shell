package com.akashic.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

enum class PipMode(val id: String) {
    HIDDEN("hidden"),
    RUNNING("running"),
    NEEDS_YOU("needs_you"),
    DONE("done"),
}

// 右下角原生画中画悬浮小窗：显示后台虚拟屏实时预览帧（10fps），绿光运行 / 橙色呼吸光晕待用户接管，1:1 跟手拖拽吸附四角。
@SuppressLint("ViewConstructor")
class AgentPipView(
    context: Context,
    private val onTakeoverTap: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private var mode: PipMode = PipMode.HIDDEN
    private var expanded: Boolean = false
    private var frame: Bitmap? = null
    private var statusText: String = "后台操作中"
    private var breathAlpha: Float = 1f
    private var breathDirection: Float = -0.05f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(232, 24, 24, 22)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(236, 235, 228)
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 20, 20, 19)
        style = Paint.Style.FILL
    }

    private val animator = Handler(Looper.getMainLooper())
    private val breatheTick = object : Runnable {
        override fun run() {
            if (mode == PipMode.NEEDS_YOU && visibility == VISIBLE) {
                breathAlpha += breathDirection
                if (breathAlpha <= 0.4f) {
                    breathAlpha = 0.4f
                    breathDirection = 0.05f
                } else if (breathAlpha >= 1f) {
                    breathAlpha = 1f
                    breathDirection = -0.05f
                }
                invalidate()
                animator.postDelayed(this, 45L)
            }
        }
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startTranslationX = 0f
    private var startTranslationY = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        visibility = GONE
        elevation = dp(10f)
    }

    fun defaultLayoutParams(): FrameLayout.LayoutParams {
        val w = dp(if (expanded) 210f else 96f).toInt()
        val h = (w * 2.05f).toInt()
        return FrameLayout.LayoutParams(w, h).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(14f).toInt()
            bottomMargin = dp(76f).toInt()
        }
    }

    fun updateState(readState: () -> Triple<PipMode, String, ByteArray?>) {
        post {
            // UI 队列执行时再读当前状态，不能重绘排队期间已退役的屏幕。
            val (newMode, label, pngBytes) = readState()
            val previousFrame = frame
            frame = pngBytes?.takeIf { it.isNotEmpty() }?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }
            if (previousFrame !== frame) previousFrame?.recycle()
            mode = newMode
            statusText = label
            if (newMode == PipMode.HIDDEN) {
                animator.removeCallbacks(breatheTick)
                visibility = GONE
                return@post
            }
            if (newMode == PipMode.NEEDS_YOU) {
                expanded = true
                applySize()
                animator.removeCallbacks(breatheTick)
                animator.post(breatheTick)
            } else {
                animator.removeCallbacks(breatheTick)
                breathAlpha = 1f
            }
            visibility = VISIBLE
            invalidate()
        }
    }

    private fun applySize() {
        val lp = (layoutParams as? FrameLayout.LayoutParams) ?: defaultLayoutParams()
        val w = dp(if (expanded) 210f else 96f).toInt()
        val aspect = frame?.let { if (it.width > 0) it.height.toFloat() / it.width else 2.05f } ?: 2.05f
        lp.width = w
        lp.height = (w * aspect.coerceIn(0.6f, 2.3f)).toInt()
        layoutParams = lp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = dp(if (expanded) 22f else 16f)
        val pad = dp(3f)
        val rect = RectF(pad, pad, width - pad, height - pad)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        val bmp = frame
        if (bmp != null && !bmp.isRecycled) {
            val inner = Rect(
                (pad * 2).toInt(),
                (pad * 2).toInt(),
                (width - pad * 2).toInt(),
                (height - pad * 2).toInt(),
            )
            canvas.drawBitmap(bmp, null, inner, bitmapPaint)
        } else {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawText(if (expanded) statusText else "Phone", cx, cy, textPaint)
        }

        val pillRect = RectF(dp(8f), height - dp(28f), width - dp(8f), height - dp(8f))
        canvas.drawRoundRect(pillRect, dp(10f), dp(10f), badgePaint)
        val shortLabel = if (expanded) statusText.take(14) else if (mode == PipMode.NEEDS_YOU) "点此接管" else "后台屏"
        canvas.drawText(shortLabel, width / 2f, height - dp(14f), textPaint)

        borderPaint.color = when (mode) {
            PipMode.RUNNING -> Color.rgb(0x4C, 0xAF, 0x50)
            PipMode.NEEDS_YOU -> Color.argb((255 * breathAlpha).toInt(), 0xF5, 0x9E, 0x0B)
            PipMode.DONE -> Color.argb(140, 160, 160, 160)
            PipMode.HIDDEN -> Color.TRANSPARENT
        }
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startTranslationX = translationX
                startTranslationY = translationY
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    translationX = startTranslationX + dx
                    translationY = startTranslationY + dy
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    if (mode == PipMode.NEEDS_YOU) {
                        onTakeoverTap()
                    } else {
                        expanded = !expanded
                        applySize()
                    }
                } else {
                    animate().translationX(0f).setDuration(220L).start()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
