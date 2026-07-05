package com.moe.moetranslator

import com.moe.moetranslator.translate.BallStateManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BallStateManagerTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun frame(): android.widget.FrameLayout {
        val fl = android.widget.FrameLayout(ctx)
        fl.addView(android.widget.ImageView(ctx).apply {
            id = com.moe.moetranslator.R.id.floating_ball_icon
        })
        fl.addView(android.view.View(ctx).apply {
            id = com.moe.moetranslator.R.id.floating_ball_error_ring
        })
        return fl
    }

    @Test fun initialStateIsIdle() {
        val mgr = BallStateManager(ctx, frame(), BallStateManager.Mode.Game)
        assertEquals(BallStateManager.State.Idle, mgr.currentState)
    }

    @Test fun setTranslatingChangesCurrentState() {
        val mgr = BallStateManager(ctx, frame(), BallStateManager.Mode.Game)
        mgr.setState(BallStateManager.State.Translating)
        assertEquals(BallStateManager.State.Translating, mgr.currentState)
    }

    @Test fun setCompletedChangesCurrentState() {
        val mgr = BallStateManager(ctx, frame(), BallStateManager.Mode.Game)
        mgr.setState(BallStateManager.State.Completed)
        assertEquals(BallStateManager.State.Completed, mgr.currentState)
    }

    @Test fun setProcessingChangesCurrentState() {
        val mgr = BallStateManager(ctx, frame(), BallStateManager.Mode.Game)
        mgr.setState(BallStateManager.State.Processing)
        assertEquals(BallStateManager.State.Processing, mgr.currentState)
    }

    @Test fun setErrorShowsRing() {
        val fl = frame()
        val mgr = BallStateManager(ctx, fl, BallStateManager.Mode.Game)
        mgr.setState(BallStateManager.State.Error)
        val ring = fl.findViewById<android.view.View>(com.moe.moetranslator.R.id.floating_ball_error_ring)
        assertEquals(android.view.View.VISIBLE, ring.visibility)
    }

    @Test fun errorThenIdleHidesRing() {
        val fl = frame()
        val mgr = BallStateManager(ctx, fl, BallStateManager.Mode.Game)
        mgr.setState(BallStateManager.State.Error)
        mgr.setState(BallStateManager.State.Idle)
        val ring = fl.findViewById<android.view.View>(com.moe.moetranslator.R.id.floating_ball_error_ring)
        assertEquals(android.view.View.GONE, ring.visibility)
    }

    @Test fun setSameStateIsNoop() {
        val mgr = BallStateManager(ctx, frame(), BallStateManager.Mode.Game)
        mgr.setState(BallStateManager.State.Completed)
        mgr.setState(BallStateManager.State.Completed) // second call: must not throw or change anything
        assertEquals(BallStateManager.State.Completed, mgr.currentState)
    }
}
