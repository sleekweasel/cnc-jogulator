package com.example.cncjogger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnJoystickMoveListener {
        fun onValueChanged(angle: Float, power: Float, direction: Int)
        fun onJog(axis: String, step: Float)
    }

    private var onJoystickMoveListener: OnJoystickMoveListener? = null
    
    // Dimensions
    private var centerX = 0f
    private var centerY = 0f
    private var outerRadius = 0f
    private var innerRadius = 0f
    private var joystickRadius = 0f
    private var hatRadius = 0f
    
    // State
    private enum class Mode { NONE, JOYSTICK, JOG_DIAL }
    private var currentMode = Mode.NONE
    
    // Joystick State
    private var hatX = 0f
    private var hatY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    
    // Jog Dial State
    private var activeAxis = ""
    private var lastAngle = 0.0
    private var angleAccumulator = 0.0
    private var lastEventTime = 0L
    
    // Flash State
    private var flashAxis = ""
    private val flashDuration = 100L
    
    private val JOG_STEP_THRESHOLD = 15.0
    private val FAST_SPEED_THRESHOLD = 0.5

    // Paints
    private val axisPaint = Paint().apply {
        color = Color.LTGRAY
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val hatPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val ringPaint = Paint().apply {
        color = Color.parseColor("#222222")
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val dividerPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    private val highlightPaint = Paint().apply {
        color = Color.parseColor("#444400") // Dim yellow highlight
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val flashPaint = Paint().apply {
        color = Color.parseColor("#004444") // Dim cyan flash
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    init {
        isHapticFeedbackEnabled = true
        isClickable = true
    }

    fun setOnJoystickMoveListener(listener: OnJoystickMoveListener) {
        onJoystickMoveListener = listener
    }
    
    fun flash(axis: String) {
        flashAxis = axis
        invalidate()
        postDelayed({
            flashAxis = ""
            invalidate()
        }, flashDuration)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        
        val d = min(w, h).toFloat()
        outerRadius = d / 2f - 10f
        innerRadius = outerRadius * 0.6f
        joystickRadius = innerRadius * 0.8f
        hatRadius = joystickRadius / 4f
        
        hatX = centerX
        hatY = centerY
        
        ringPaint.strokeWidth = outerRadius - innerRadius
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val ringCenterRadius = (innerRadius + outerRadius) / 2f
        canvas.drawCircle(centerX, centerY, ringCenterRadius, ringPaint)
        
        // Draw Highlights
        val axisToDraw = if (flashAxis.isNotEmpty()) flashAxis else if (currentMode == Mode.JOG_DIAL) activeAxis else ""
        
        if (axisToDraw.isNotEmpty()) {
            val startAngle = when(axisToDraw) {
                "X" -> -45f
                "S" -> 45f
                "Z" -> 135f
                "Y" -> 225f
                else -> 0f
            }
            
            val paint = if (flashAxis == axisToDraw) flashPaint else highlightPaint
            
            val arcPaint = Paint(paint)
            arcPaint.style = Paint.Style.STROKE
            arcPaint.strokeWidth = outerRadius - innerRadius
            canvas.drawArc(RectF(centerX - ringCenterRadius, centerY - ringCenterRadius, centerX + ringCenterRadius, centerY + ringCenterRadius), 
                startAngle, 90f, false, arcPaint)
        }
        
        // Dividers
        for (i in 0..3) {
            val angleRad = Math.toRadians(-45.0 + (i * 90)).toFloat()
            val x1 = centerX + innerRadius * kotlin.math.cos(angleRad)
            val y1 = centerY + innerRadius * kotlin.math.sin(angleRad)
            val x2 = centerX + outerRadius * kotlin.math.cos(angleRad)
            val y2 = centerY + outerRadius * kotlin.math.sin(angleRad)
            canvas.drawLine(x1, y1, x2, y2, dividerPaint)
        }
        
        drawLabel(canvas, "Y", -90f)
        drawLabel(canvas, "X", 0f)
        drawLabel(canvas, "S", 90f)
        drawLabel(canvas, "Z", 180f)
        
        canvas.drawCircle(centerX, centerY, innerRadius, dividerPaint)
        canvas.drawLine(centerX, centerY - joystickRadius, centerX, centerY + joystickRadius, axisPaint)
        canvas.drawLine(centerX - joystickRadius, centerY, centerX + joystickRadius, centerY, axisPaint)
        canvas.drawCircle(hatX, hatY, hatRadius, hatPaint)
    }
    
    private fun drawLabel(canvas: Canvas, text: String, angleDeg: Float) {
        val angleRad = Math.toRadians(angleDeg.toDouble())
        val radius = (innerRadius + outerRadius) / 2f
        val x = centerX + radius * kotlin.math.cos(angleRad)
        val y = centerY + radius * kotlin.math.sin(angleRad)
        
        val bounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)
        val yOff = bounds.height() / 2f
        
        canvas.drawText(text, x.toFloat(), y.toFloat() + yOff, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val dist = sqrt(((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)).toDouble()).toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (dist < innerRadius) {
                    currentMode = Mode.JOYSTICK
                    lastTouchX = x
                    lastTouchY = y
                } else if (dist < outerRadius) {
                    currentMode = Mode.JOG_DIAL
                    val angle = Math.toDegrees(atan2((y - centerY).toDouble(), (x - centerX).toDouble()))
                    if (angle > -45 && angle <= 45) activeAxis = "X"
                    else if (angle > 45 && angle <= 135) activeAxis = "S"
                    else if (angle > -135 && angle <= -45) activeAxis = "Y"
                    else activeAxis = "Z"
                    
                    lastAngle = angle
                    angleAccumulator = 0.0
                    lastEventTime = System.currentTimeMillis()
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentMode == Mode.JOYSTICK) {
                    handleJoystickMove(x, y)
                } else if (currentMode == Mode.JOG_DIAL) {
                    handleJogDialMove(x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (currentMode == Mode.JOYSTICK) {
                    hatX = centerX
                    hatY = centerY
                    onJoystickMoveListener?.onValueChanged(0f, 0f, 0)
                }
                currentMode = Mode.NONE
                activeAxis = ""
                invalidate()
            }
        }
        return true
    }
    
    private fun handleJoystickMove(x: Float, y: Float) {
        val dx = x - lastTouchX
        val dy = y - lastTouchY
        var nextX = hatX + dx
        var nextY = hatY + dy
        val dispX = nextX - centerX
        val dispY = nextY - centerY
        val distance = sqrt((dispX * dispX + dispY * dispY).toDouble()).toFloat()
        
        if (distance > joystickRadius) {
            val ratio = joystickRadius / distance
            nextX = centerX + (dispX * ratio)
            nextY = centerY + (dispY * ratio)
        }
        
        hatX = nextX
        hatY = nextY
        lastTouchX = x
        lastTouchY = y
        
        val angle = Math.toDegrees(atan2((centerY - hatY).toDouble(), (hatX - centerX).toDouble())).toFloat()
        val finalDispX = hatX - centerX
        val finalDispY = hatY - centerY
        val finalDist = sqrt((finalDispX * finalDispX + finalDispY * finalDispY).toDouble()).toFloat()
        val power = min(finalDist / joystickRadius, 1f)
        
        onJoystickMoveListener?.onValueChanged(angle, power, 0)
        invalidate()
    }
    
    private fun handleJogDialMove(x: Float, y: Float) {
        val currentAngle = Math.toDegrees(atan2((y - centerY).toDouble(), (x - centerX).toDouble()))
        var delta = currentAngle - lastAngle
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        angleAccumulator += delta
        
        val now = System.currentTimeMillis()
        val dt = now - lastEventTime
        val speed = abs(delta) / dt.toDouble()
        
        var stepSize = if (activeAxis == "S") 10f else 0.1f
        if (activeAxis == "S" && speed > FAST_SPEED_THRESHOLD) stepSize = 100f
        else if (activeAxis != "S" && speed > FAST_SPEED_THRESHOLD) stepSize = 1.0f
        
        if (abs(angleAccumulator) > JOG_STEP_THRESHOLD) {
            val steps = (abs(angleAccumulator) / JOG_STEP_THRESHOLD).toInt()
            val sign = if (angleAccumulator > 0) 1 else -1
            val totalMove = steps * stepSize * sign
            
            onJoystickMoveListener?.onJog(activeAxis, totalMove)
            angleAccumulator -= (steps * JOG_STEP_THRESHOLD * sign)
        }
        lastAngle = currentAngle
        lastEventTime = now
    }
}
