package com.kingscan.app.imaging

/** Snapshot history drops a redo branch when the user makes a new edit. */
class EditHistory<T>(initial: T) {
    private val snapshots = mutableListOf(initial)
    private var position = 0
    val current: T get() = snapshots[position]
    val canUndo: Boolean get() = position > 0
    val canRedo: Boolean get() = position < snapshots.lastIndex
    fun push(value: T) {
        if (value == current) return
        while (snapshots.lastIndex > position) snapshots.removeAt(snapshots.lastIndex)
        snapshots += value
        position++
    }
    fun undo(): T { if (canUndo) position--; return current }
    fun redo(): T { if (canRedo) position++; return current }
}
