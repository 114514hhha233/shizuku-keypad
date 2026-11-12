package com.example.shizukukeypad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/**
 * 摇杆的圆盘外观：外圈 + 可移动的摇杆头。
 * 触摸逻辑不在这里，仍在 KeypadOverlay 里统一处理（键值 / 触摸两种输出）。
 */
class JoystickPad(
    context: Context,
    private val diameter: Int,
    private val density: Float
) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xC7181818.toInt()
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x99FFFFFF.toInt()
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x44FFFFFF
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE6505050.toInt()
    }
    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0xCCFFFFFF.toInt()
    }

    private val knobR = (diameter * 0.22f).coerceIn(9f * density, 24f * density)

    private var knobDx = 0f
    private var knobDy = 0f
    private var active = false

    /** 摇杆头相对圆心的偏移（像素，已夹在可移动范围内） */
    fun setVector(dx: Float, dy: Float) {
        knobDx = dx
        knobDy = dy
        invalidate()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        invalidate()
    }

    fun resetKnob() {
        knobDx = 0f
        knobDy = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 2f * density
        if (r <= 0f) return
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)
        // 内圈提示可拖动范围
        canvas.drawCircle(cx, cy, (r - knobR).coerceAtLeast(1f), innerPaint)
        val kx = cx + knobDx
        val ky = cy + knobDy
        canvas.drawCircle(kx, ky, knobR, knobPaint)
        canvas.drawCircle(kx, ky, knobR, knobRingPaint)
    }
}
