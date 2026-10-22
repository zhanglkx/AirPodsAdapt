// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.service

import android.media.AudioManager
import android.view.KeyEvent
import com.airpodsadapt.core.AirPodsHub
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * iPhone 上由手机完成的两件事：摘下耳机暂停播放、对话感知时降低媒体音量。
 * AirPods 只负责上报事件，真正的动作需要手机端来做。
 */
class MediaAutomation(private val audio: AudioManager) {

    // ---- 入耳检测自动暂停 ----

    private var lastInEar = -1
    private var pausedByUs = false
    private var inEarAtPause = 0

    /** [inEar] 当前已佩戴数量；[allInCase] 两只都放回了盒中。 */
    fun onEarChanged(inEar: Int, allInCase: Boolean, enabled: Boolean) {
        val prev = lastInEar
        lastInEar = inEar
        if (allInCase) {
            // 收回盒子就不再自动恢复
            pausedByUs = false
            return
        }
        if (!enabled || prev < 0 || inEar == prev) return
        if (inEar < prev && audio.isMusicActive) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            pausedByUs = true
            inEarAtPause = prev
            AirPodsHub.log("摘下耳机（$prev → $inEar），已暂停播放")
        } else if (inEar > prev && pausedByUs && inEar >= inEarAtPause) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            pausedByUs = false
            AirPodsHub.log("重新戴上耳机，已恢复播放")
        }
    }

    fun resetEar() {
        lastInEar = -1
        pausedByUs = false
    }

    // ---- 对话感知降低音量 ----

    private var savedVolume: Int? = null

    /** 级别 1~2 开始说话，3~7 逐渐恢复，8 及以上恢复正常。 */
    fun onConversationLevel(level: Int, enabled: Boolean) {
        if (!enabled) {
            restoreVolume()
            return
        }
        val stream = AudioManager.STREAM_MUSIC
        val saved = savedVolume
        when {
            level <= 2 -> {
                if (saved == null && !audio.isMusicActive) return
                val original = saved ?: audio.getStreamVolume(stream).also { savedVolume = it }
                setVolume(reduced(original))
            }
            level in 3..7 && saved != null -> {
                val low = reduced(saved)
                val target = low + ((saved - low) * (level - 2) / 6f).roundToInt()
                setVolume(target)
            }
            level >= 8 -> restoreVolume()
        }
    }

    fun restoreVolume() {
        val saved = savedVolume ?: return
        savedVolume = null
        setVolume(saved)
        AirPodsHub.log("对话结束，音量恢复到 $saved")
    }

    private fun reduced(original: Int) = max(1, (original * DUCK_RATIO).roundToInt())

    private fun setVolume(v: Int) {
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != v) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        }
    }

    private fun sendMediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private companion object {
        const val DUCK_RATIO = 0.3f
    }
}
