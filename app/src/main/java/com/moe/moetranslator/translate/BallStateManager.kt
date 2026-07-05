package com.moe.moetranslator.translate

import android.content.Context
import android.graphics.BitmapFactory
import android.view.View
import android.animation.ValueAnimator
import android.widget.ImageView
import com.moe.moetranslator.R
import com.moe.moetranslator.utils.CustomPreference
import java.io.File

/**
 * 悬浮球状态机。
 *
 * - Idle 走用户偏好路径 (Icon_Game / Icon_Comic)，与 commit 1351774 一致
 * - Processing / Translating / Completed 直接 setImageResource 写死的 mipmap
 * - Error 复用 Processing 的图 + 红圈叠加 + 1100ms 脉冲
 *
 * 切换瞬时完成，无动画（红圈脉冲除外）。
 */
class BallStateManager(
    private val context: Context,
    private val floatingBallView: View,
    private val mode: Mode
) {
    enum class Mode { Game, Comic }
    enum class State { Idle, Processing, Translating, Completed, Error }

    private val prefs = CustomPreference.getInstance(context)
    private val iconView: ImageView =
        floatingBallView.findViewById(R.id.floating_ball_icon)
    private val errorRingView: View =
        floatingBallView.findViewById(R.id.floating_ball_error_ring)
    private var errorAnimator: ValueAnimator? = null

    @Volatile
    var currentState: State = State.Idle
        private set

    fun setState(state: State) {
        if (state == currentState) return
        currentState = state
        applyForState(state)
    }

    fun release() {
        stopErrorPulse()
        errorRingView.visibility = View.GONE
        errorAnimator = null
    }

    private fun applyForState(state: State) {
        stopErrorPulse()
        when (state) {
            State.Idle -> {
                loadIdleIcon()
                errorRingView.visibility = View.GONE
            }
            State.Processing -> {
                iconView.setImageResource(stateResource(State.Processing))
                errorRingView.visibility = View.GONE
            }
            State.Translating -> {
                iconView.setImageResource(stateResource(State.Translating))
                errorRingView.visibility = View.GONE
            }
            State.Completed -> {
                iconView.setImageResource(stateResource(State.Completed))
                errorRingView.visibility = View.GONE
            }
            State.Error -> {
                iconView.setImageResource(stateResource(State.Processing)) // 复用 state2
                errorRingView.visibility = View.VISIBLE
                startErrorPulse()
            }
        }
    }

    private fun loadIdleIcon() {
        val key = if (mode == Mode.Game) "Icon_Game" else "Icon_Comic"
        val defaultName = if (mode == Mode.Game)
            "game-1.进入游戏-启动游戏界面.png"
        else
            "comic-1.准备识别-打开漫画页面.png"
        val name = prefs.getString(key, defaultName)
        if (name.isEmpty()) {
            iconView.setImageResource(defaultMipmap())
            return
        }
        val file = File(context.getExternalFilesDir(null), "icon/$name")
        try {
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) iconView.setImageBitmap(bmp) else iconView.setImageResource(defaultMipmap())
            } else {
                iconView.setImageResource(defaultMipmap())
            }
        } catch (e: Exception) {
            iconView.setImageResource(defaultMipmap())
        }
    }

    private fun stateResource(state: State): Int = when (mode) {
        Mode.Game -> when (state) {
            State.Processing -> R.mipmap.icon_game_state2
            State.Translating -> R.mipmap.icon_game_state3
            State.Completed -> R.mipmap.icon_game_state4
            else -> R.mipmap.icon_game_default
        }
        Mode.Comic -> when (state) {
            State.Processing -> R.mipmap.icon_comic_state2
            State.Translating -> R.mipmap.icon_comic_state3
            State.Completed -> R.mipmap.icon_comic_state4
            else -> R.mipmap.icon_comic_default
        }
    }

    private fun defaultMipmap(): Int = when (mode) {
        Mode.Game -> R.mipmap.icon_game_default
        Mode.Comic -> R.mipmap.icon_comic_default
    }

    private fun startErrorPulse() {
        errorAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(0.6f, 1.0f, 0.6f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { errorRingView.alpha = it.animatedValue as Float }
        }
        errorAnimator = anim
        anim.start()
    }

    private fun stopErrorPulse() {
        errorAnimator?.cancel()
        errorAnimator = null
        errorRingView.alpha = 1.0f
    }
}
