package com.kingscan.app.ui

import androidx.compose.runtime.mutableStateListOf
import com.kingscan.app.model.Screen

class Navigator {
    private val stack = mutableStateListOf<Screen>(Screen.Library)
    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun backToCapture() {
        while (current is Screen.Crop || current is Screen.Filter) pop()
        if (current !is Screen.Capture) push(Screen.Capture)
    }

    fun showDetail(documentId: String) {
        stack.clear()
        stack.add(Screen.Library)
        stack.add(Screen.Detail(documentId))
    }
}
