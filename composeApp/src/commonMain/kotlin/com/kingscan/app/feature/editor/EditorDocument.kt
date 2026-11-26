package com.kingscan.app.feature.editor

import com.kingscan.app.core.platform.LocalContentLocked

import androidx.compose.runtime.*
import com.kingscan.app.AppGraph
import com.kingscan.app.core.document.ScanDocument

/** Preserve an open editor; retry an initial blocked load after authentication. */
@Composable
internal fun rememberEditorDocument(id: String): MutableState<ScanDocument?> {
    val document = remember(id) { mutableStateOf(AppGraph.repository.load(id)) }
    val locked = LocalContentLocked.current
    val revision = AppGraph.security.revision
    LaunchedEffect(id, locked, revision) {
        if (!locked && document.value == null) document.value = AppGraph.repository.load(id)
    }
    return document
}
