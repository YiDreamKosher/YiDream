package com.yidream.mdm

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import kotlin.math.abs

/** Horizontal page swipes leave native vertical scrolling and text selection to their children. */
class SwipeWorkspaceLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    var onPageSwipe: ((forward: Boolean) -> Unit)? = null
    private val threshold = maxOf(ViewConfiguration.get(context).scaledTouchSlop * 3f, 48 * resources.displayMetrics.density)
    private var startX = 0f
    private var startY = 0f
    private var allowGesture = false
    private var dragging = false
    private var target: View? = null

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                dragging = false
                target = targetAt(this, event.x, event.y)
                allowGesture = true
                var source = target
                while (source != null && source !== this) {
                    if (source is EditText || source is WebView) allowGesture = false
                    source = source.parent as? View
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> allowGesture = false
            MotionEvent.ACTION_MOVE -> {
                if (!allowGesture || event.pointerCount != 1) return false
                val dx = event.x - startX
                val dy = event.y - startY
                if (abs(dy) > threshold / 2 && abs(dy) >= abs(dx)) allowGesture = false
                val direction = if (dx > 0) -1 else 1
                if (abs(dx) > threshold && abs(dx) > abs(dy) * 2 && !childScrolls(direction)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.x - startX
            if (abs(dx) > threshold) onPageSwipe?.invoke(if (layoutDirection == LAYOUT_DIRECTION_RTL) dx > 0 else dx < 0)
            dragging = false
            parent?.requestDisallowInterceptTouchEvent(false)
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            dragging = false
            allowGesture = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun childScrolls(direction: Int): Boolean {
        var view = target
        while (view != null && view !== this) {
            if (view.canScrollHorizontally(direction)) return true
            view = view.parent as? View
        }
        return false
    }

    private fun targetAt(parent: ViewGroup, x: Float, y: Float): View {
        for (index in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(index)
            if (child.visibility != VISIBLE) continue
            val localX = x + parent.scrollX - child.left
            val localY = y + parent.scrollY - child.top
            if (localX !in 0f..child.width.toFloat() || localY !in 0f..child.height.toFloat()) continue
            return if (child is ViewGroup) targetAt(child, localX, localY) else child
        }
        return parent
    }
}
