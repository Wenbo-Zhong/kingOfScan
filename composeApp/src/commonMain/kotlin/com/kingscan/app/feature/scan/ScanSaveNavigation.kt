package com.kingscan.app.feature.scan

import com.kingscan.app.navigation.Navigator

import com.kingscan.app.AppGraph
import com.kingscan.app.core.document.CompositionKind
import com.kingscan.app.core.document.ScanMode
import com.kingscan.app.navigation.Screen

/** Common post-save routing for camera, filter, book and queued image imports. */
internal fun finishScanSave(
    result: SavedScanPages, mode: ScanMode, navigator: Navigator,
    setBusy: (Boolean) -> Unit, onMessage: (String) -> Unit,
    continueScan: Boolean = false, continuous: Boolean = false,
    onSaved: () -> Unit = {},
) {
    val session = AppGraph.session
    session.accept(result, mode)
    if (!continuous) session.clearImage()
    onSaved()
    val id = result.document.id
    when {
        !continuous && session.pendingImports.isNotEmpty() -> {
            navigator.finishEditor()
            processNextImport(setBusy, navigator, onMessage)
        }
        mode == ScanMode.IdCard && result.addedIds.isNotEmpty() && session.idCardPageIds.size >= 2 -> {
            navigator.showDetail(id)
            navigator.push(Screen.ComposePages(id, session.idCardPageIds.takeLast(2), CompositionKind.IdCard))
        }
        continuous -> Unit
        mode == ScanMode.IdCard && result.addedIds.isNotEmpty() || continueScan -> navigator.backToCapture()
        else -> navigator.showDetail(id)
    }
}
