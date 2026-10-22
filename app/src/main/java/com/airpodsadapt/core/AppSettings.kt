// SPDX-License-Identifier: GPL-3.0-or-later
package com.airpodsadapt.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 手机端（App 自己实现的）功能开关，持久化在 SharedPreferences。 */
data class AppPrefs(
    /** 摘下一只或两只耳机时暂停播放，戴回后恢复。 */
    val autoPause: Boolean = true,
    /** 对话感知触发时降低媒体音量。 */
    val conversationDucking: Boolean = true,
    /** 打开充电盒时弹出电量通知。 */
    val lidPopup: Boolean = true,
)

object AppSettings {
    private const val FILE = "app_prefs"
    private const val KEY_AUTO_PAUSE = "auto_pause"
    private const val KEY_DUCKING = "conversation_ducking"
    private const val KEY_LID_POPUP = "lid_popup"

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(AppPrefs())
    val state: StateFlow<AppPrefs> = _state.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _state.value = AppPrefs(
            autoPause = prefs.getBoolean(KEY_AUTO_PAUSE, true),
            conversationDucking = prefs.getBoolean(KEY_DUCKING, true),
            lidPopup = prefs.getBoolean(KEY_LID_POPUP, true),
        )
    }

    fun setAutoPause(v: Boolean) = save { copy(autoPause = v) }
    fun setConversationDucking(v: Boolean) = save { copy(conversationDucking = v) }
    fun setLidPopup(v: Boolean) = save { copy(lidPopup = v) }

    private fun save(change: AppPrefs.() -> AppPrefs) {
        val next = _state.value.change()
        _state.value = next
        prefs.edit {
            putBoolean(KEY_AUTO_PAUSE, next.autoPause)
            putBoolean(KEY_DUCKING, next.conversationDucking)
            putBoolean(KEY_LID_POPUP, next.lidPopup)
        }
    }
}
