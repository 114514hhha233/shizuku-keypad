package com.example.shizukukeypad

import android.inputmethodservice.InputMethodService
import android.view.View

/**
 * 空输入法：设置页开启“拦截系统输入法”后，用 Shizuku 把它临时设为默认输入法。
 * 它不提供任何输入界面，所以被唤起时不会盖住屏幕，也不会把字母转成中文。
 * 关闭悬浮按键或隐藏时，ImeGuard 会把原输入法换回来。
 */
class NoopImeService : InputMethodService() {

    override fun onCreateInputView(): View? = null

    /** 不显示任何输入区域 */
    override fun onEvaluateInputViewShown(): Boolean = false
}
