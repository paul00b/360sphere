package care.primary.sphere360.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import care.primary.sphere360.util.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Superposition de guidage : réticule central, points cibles projetés dans l'image caméra,
 * flèche vers la prochaine cible hors champ, et mini-carte de couverture en bas.
 */
class GuidanceOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    init {
        isClickable = true
        isFocusable = false
    }

    var plan: CapturePlan? = null
    var dirs: Array<Vec3> = emptyArray()
    var captured: BooleanArray = BooleanArray(0)
    var rotation: FloatArray? = null
    var nextIndex = -1
    var armed = false
    var capturing = false
    var active = false
    var yaw0 = 0.0
    var bottomInset = 0
    val previewRect = RectF()
    private var fx = 1f
    private var fy = 1f

    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; alpha = 160
        pathEffect = DashPathEffect(floatArrayOf(context.dp(6f), context.dp(6f)), 0f)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val path = Path()

    private val colorDone = 0xFF4CD964.toInt()
    private val colorNext = 0xFFFFD60A.toInt()

    fun setFov(hfovDeg: Double, vfovDeg: Double) {
        if (previewRect.width() <= 0) return
        fx = (previewRect.width() / 2 / tan(Math.toRadians(hfovDeg / 2))).toFloat()
        fy = (previewRect.height() / 2 / tan(Math.toRadians(vfovDeg / 2))).toFloat()
        invalidate()
    }

    fun minimapHeight(): Float = minimapWidth() / 2.4f
    private fun minimapWidth(): Float = min(width - context.dp(32f), context.dp(360f))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (previewRect.width() <= 0) return
        val cx = previewRect.centerX()
        val cy = previewRect.centerY()
        val r = rotation
        val plan = plan

        if (active && plan != null && r != null && dirs.size == plan.size) {
            drawTargets(canvas, r, cx, cy)
            drawMinimap(canvas, r, plan)
        }
        drawReticle(canvas, cx, cy)
    }

    private fun drawReticle(canvas: Canvas, cx: Float, cy: Float) {
        val radius = context.dp(30f)
        stroke.strokeWidth = context.dp(2.5f)
        stroke.color = if (armed || capturing) colorNext else Color.WHITE
        stroke.alpha = if (active) 235 else 140
        canvas.drawCircle(cx, cy, radius + context.dp(1f), shadow.apply { style = Paint.Style.STROKE; strokeWidth = context.dp(5f) })
        canvas.drawCircle(cx, cy, radius, stroke)
        if (capturing) {
            fill.color = colorNext; fill.alpha = 110
            canvas.drawCircle(cx, cy, radius, fill)
        }
        fill.color = if (armed || capturing) colorNext else Color.WHITE; fill.alpha = 230
        canvas.drawCircle(cx, cy, context.dp(3f), fill)
    }

    private fun drawTargets(canvas: Canvas, r: FloatArray, cx: Float, cy: Float) {
        val dotR = context.dp(9f)
        val margin = context.dp(24f)
        var nextVisible = false
        var nextX = 0f
        var nextY = 0f
        var nextInFront = false

        for (i in dirs.indices) {
            val p = SphereMath.projectToCamera(r, dirs[i])
            val isNext = i == nextIndex
            if (p == null) { if (isNext) nextInFront = false; continue }
            if (kotlin.math.abs(p[0]) > 1.6 || kotlin.math.abs(p[1]) > 2.2) { if (isNext) nextInFront = true; continue }
            val px = cx + (p[0] * fx).toFloat()
            val py = cy + (p[1] * fy).toFloat()
            if (isNext) { nextInFront = true; nextX = px; nextY = py }
            val inside = px > previewRect.left - margin && px < previewRect.right + margin &&
                py > previewRect.top - margin && py < previewRect.bottom + margin
            if (!inside) { continue }
            if (isNext) nextVisible = true
            when {
                captured[i] -> {
                    fill.color = colorDone; fill.alpha = 170
                    canvas.drawCircle(px, py, dotR * 0.6f, fill)
                }
                isNext -> {
                    stroke.color = colorNext; stroke.alpha = 255; stroke.strokeWidth = context.dp(2.5f)
                    canvas.drawCircle(px, py, dotR * 1.7f, shadow.apply { style = Paint.Style.STROKE; strokeWidth = context.dp(5f) })
                    canvas.drawCircle(px, py, dotR * 1.7f, stroke)
                    fill.color = colorNext; fill.alpha = 255
                    canvas.drawCircle(px, py, dotR * 0.8f, fill)
                }
                else -> {
                    canvas.drawCircle(px, py, dotR + context.dp(1.5f), shadow.apply { style = Paint.Style.FILL })
                    fill.color = Color.WHITE; fill.alpha = 215
                    canvas.drawCircle(px, py, dotR, fill)
                }
            }
        }

        if (nextIndex >= 0) {
            if (nextVisible) {
                // ligne pointillée du réticule vers la cible
                dash.strokeWidth = context.dp(2f)
                path.reset(); path.moveTo(cx, cy); path.lineTo(nextX, nextY)
                canvas.drawPath(path, dash)
            } else {
                drawEdgeArrow(canvas, r, cx, cy, nextInFront, nextX, nextY)
            }
        }
    }

    private fun drawEdgeArrow(canvas: Canvas, r: FloatArray, cx: Float, cy: Float, inFront: Boolean, tx: Float, ty: Float) {
        val dirX: Float
        val dirY: Float
        if (inFront) {
            val dx = tx - cx; val dy = ty - cy
            val l = hypot(dx, dy).coerceAtLeast(1f)
            dirX = dx / l; dirY = dy / l
        } else {
            val f = SphereMath.cameraForward(r)
            val t = dirs[nextIndex]
            val dYaw = SphereMath.normalizeAngle(SphereMath.yawOf(t) - SphereMath.yawOf(f))
            val dPitch = SphereMath.pitchOf(t) - SphereMath.pitchOf(f)
            val l = hypot(dYaw, dPitch).coerceAtLeast(1e-3)
            dirX = (dYaw / l).toFloat(); dirY = (-dPitch / l).toFloat()
        }
        val halfW = previewRect.width() / 2 - context.dp(34f)
        val halfH = previewRect.height() / 2 - context.dp(34f)
        // intersection du rayon avec le rectangle
        val sx = if (dirX != 0f) halfW / kotlin.math.abs(dirX) else Float.MAX_VALUE
        val sy = if (dirY != 0f) halfH / kotlin.math.abs(dirY) else Float.MAX_VALUE
        val s = min(sx, sy)
        val ax = cx + dirX * s
        val ay = cy + dirY * s
        val angle = atan2(dirY, dirX)
        val size = context.dp(16f)
        path.reset()
        path.moveTo(ax + cos(angle) * size, ay + sin(angle) * size)
        path.lineTo(ax + cos(angle + 2.4f) * size, ay + sin(angle + 2.4f) * size)
        path.lineTo(ax + cos(angle - 2.4f) * size, ay + sin(angle - 2.4f) * size)
        path.close()
        canvas.drawPath(path, shadow.apply { style = Paint.Style.STROKE; strokeWidth = context.dp(4f) })
        fill.color = colorNext; fill.alpha = 255
        canvas.drawPath(path, fill)
    }

    private fun drawMinimap(canvas: Canvas, r: FloatArray, plan: CapturePlan) {
        val w = minimapWidth()
        val h = minimapHeight()
        val left = (width - w) / 2
        val bottom = height - bottomInset - context.dp(16f)
        val top = bottom - h
        val rect = RectF(left, top, left + w, bottom)
        fill.color = Color.BLACK; fill.alpha = 150
        canvas.drawRoundRect(rect, context.dp(14f), context.dp(14f), fill)

        val pitchRange = 150.0 // -75° .. +75°
        fun xOf(yawDeg: Double) = left + ((SphereMath.normalizeDeg(yawDeg) + 180.0) / 360.0 * w).toFloat()
        fun yOf(pitchDeg: Double) = top + (((75.0 - pitchDeg) / pitchRange) * h).toFloat()

        // horizon
        stroke.color = Color.WHITE; stroke.alpha = 60; stroke.strokeWidth = context.dp(1f)
        canvas.drawLine(left + context.dp(8f), yOf(0.0), left + w - context.dp(8f), yOf(0.0), stroke)

        // cadre de la vue courante
        val f = SphereMath.cameraForward(r)
        val camYaw = SphereMath.normalizeDeg(Math.toDegrees(SphereMath.yawOf(f) - yaw0))
        val camPitch = Math.toDegrees(SphereMath.pitchOf(f))
        val vw = (plan.hfovDeg / 360.0 * w).toFloat()
        val vh = (plan.vfovDeg / pitchRange * h).toFloat()
        val vx = xOf(camYaw)
        val vy = yOf(camPitch).coerceIn(top + vh / 2, bottom - vh / 2)
        stroke.color = Color.WHITE; stroke.alpha = 200; stroke.strokeWidth = context.dp(1.5f)
        canvas.save()
        canvas.clipRect(rect)
        for (shift in intArrayOf(-1, 0, 1)) {
            val x = vx + shift * w
            canvas.drawRoundRect(RectF(x - vw / 2, vy - vh / 2, x + vw / 2, vy + vh / 2), context.dp(3f), context.dp(3f), stroke)
        }
        canvas.restore()

        // cibles
        val dr = context.dp(3.2f)
        for (t in plan.targets) {
            val x = xOf(t.yawDeg); val y = yOf(t.pitchDeg)
            when {
                captured.getOrElse(t.index) { false } -> { fill.color = colorDone; fill.alpha = 230 }
                t.index == nextIndex -> { fill.color = colorNext; fill.alpha = 255 }
                else -> { fill.color = Color.WHITE; fill.alpha = 120 }
            }
            canvas.drawCircle(x, y, if (t.index == nextIndex) dr * 1.5f else dr, fill)
        }
    }
}
