package com.yidream.mdm

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], qualifiers = "w240dp-h320dp-mdpi")
class SwipeWorkspaceTest {
    private lateinit var pager: SwipeWorkspaceLayout
    private val directions = mutableListOf<Boolean>()

    @Before fun setUp() {
        pager = SwipeWorkspaceLayout(RuntimeEnvironment.getApplication())
        pager.layout(0, 0, 240, 320)
        pager.onPageSwipe = { directions += it }
    }

    private fun event(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, 20L, action, x, y, 0)
    private fun intercept(action: Int, x: Float, y: Float): Boolean =
        event(action, x, y).let { try { pager.onInterceptTouchEvent(it) } finally { it.recycle() } }
    private fun touch(action: Int, x: Float, y: Float) =
        event(action, x, y).let { try { pager.onTouchEvent(it) } finally { it.recycle() } }
    private fun add(child: View) {
        pager.addView(child, FrameLayout.LayoutParams(240, 320))
        child.layout(0, 0, 240, 320)
    }

    @Test fun horizontalSwipeChangesOnePageInLogicalReadingDirection() {
        intercept(MotionEvent.ACTION_DOWN, 220f, 160f)
        assertTrue(intercept(MotionEvent.ACTION_MOVE, 50f, 165f))
        touch(MotionEvent.ACTION_UP, 30f, 165f)
        assertEquals(listOf(true), directions)
        pager.layoutDirection = View.LAYOUT_DIRECTION_RTL
        intercept(MotionEvent.ACTION_DOWN, 20f, 160f)
        assertTrue(intercept(MotionEvent.ACTION_MOVE, 180f, 165f))
        touch(MotionEvent.ACTION_UP, 220f, 165f)
        assertEquals(listOf(true, true), directions)
    }

    @Test fun verticalScrollAndTapsRemainWithThePage() {
        intercept(MotionEvent.ACTION_DOWN, 120f, 200f)
        assertFalse(intercept(MotionEvent.ACTION_MOVE, 125f, 80f))
        assertFalse(intercept(MotionEvent.ACTION_MOVE, 20f, 80f))
        intercept(MotionEvent.ACTION_DOWN, 120f, 200f)
        assertFalse(intercept(MotionEvent.ACTION_MOVE, 124f, 200f))
        assertTrue(directions.isEmpty())
    }

    @Test fun editingTextNeverStartsAPageSwipe() {
        add(EditText(pager.context))
        intercept(MotionEvent.ACTION_DOWN, 220f, 100f)
        assertFalse(intercept(MotionEvent.ACTION_MOVE, 20f, 100f))
        assertTrue(directions.isEmpty())
    }

    @Test fun horizontalScrollableChildKeepsItsOwnGesture() {
        add(object : View(pager.context) { override fun canScrollHorizontally(direction: Int) = true })
        intercept(MotionEvent.ACTION_DOWN, 220f, 100f)
        assertFalse(intercept(MotionEvent.ACTION_MOVE, 20f, 100f))
        assertTrue(directions.isEmpty())
    }

    @Test fun cancelledGestureDoesNotNavigate() {
        intercept(MotionEvent.ACTION_DOWN, 220f, 160f)
        assertTrue(intercept(MotionEvent.ACTION_MOVE, 50f, 160f))
        touch(MotionEvent.ACTION_CANCEL, 30f, 160f)
        touch(MotionEvent.ACTION_UP, 30f, 160f)
        assertTrue(directions.isEmpty())
    }
}
