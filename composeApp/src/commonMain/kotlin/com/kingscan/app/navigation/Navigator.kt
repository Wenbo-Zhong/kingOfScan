package com.kingscan.app.navigation

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope

class Navigator(val scope: CoroutineScope) {
    private val stack = mutableStateListOf<Screen>(Screen.Library)
    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun backToCapture() {
        finishEditor()
        if (current !is Screen.Capture) push(Screen.Capture)
    }

    fun finishEditor() {
        while (current is Screen.Crop || current is Screen.Filter || current is Screen.Book || current is Screen.PdfUnlock) pop()
    }

    fun showDetail(documentId: String) {
        stack.clear()
        stack.add(Screen.Library)
        stack.add(Screen.Detail(documentId))
    }

    fun showLibrary() {
        stack.clear()
        stack.add(Screen.Library)
    }
}
