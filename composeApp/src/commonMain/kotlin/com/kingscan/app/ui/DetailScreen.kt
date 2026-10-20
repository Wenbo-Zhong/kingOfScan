package com.kingscan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.imaging.ScanLimits
import com.kingscan.app.model.EncodedPage
import com.kingscan.app.model.ScanDocument
import com.kingscan.app.model.Screen
import com.kingscan.app.model.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DetailAction(val label: String, val onClick: () -> Unit)

@Composable
fun DetailScreen(
    documentId: String,
    navigator: Navigator,
    onMessage: (String) -> Unit,
) {
    val repository = AppGraph.repository
    var document by remember(documentId) { mutableStateOf(repository.load(documentId)) }
    var renaming by remember { mutableStateOf(false) }
    var titleDraft by remember(document?.title) { mutableStateOf(document?.title.orEmpty()) }
    var confirmDeleteDoc by remember { mutableStateOf(false) }
    var confirmDeletePage by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val share = rememberShareLauncher()
    val current = document

    if (current == null || current.pages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TextButton(onClick = { navigator.pop() }) { Text("返回文稿库") }
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { current.pages.size })
    val page = current.pages[pagerState.currentPage.coerceIn(0, current.pages.lastIndex)]

    fun refresh(updated: ScanDocument?) {
        document = updated
        if (updated == null) navigator.pop()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ScanTopBar(
            title = current.title,
            onBack = { AppGraph.session.cancel(); navigator.pop() },
        ) {
            TextButton(onClick = {
                titleDraft = current.title
                renaming = true
            }) { Text("重命名") }
        }
        Text(
            "${pagerState.currentPage + 1} / ${current.pages.size} · ${page.filter.label()}",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
        ) { index ->
            val item = current.pages[index]
            PageImage(
                path = repository.pagePath(current.id, item),
                modifier = Modifier.fillMaxSize(),
                contentDescription = "第 ${index + 1} 页",
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val actions = listOf(
                DetailAction("加页") {
                    AppGraph.session.beginAppend(current.id)
                    navigator.push(Screen.Capture)
                },
                DetailAction("旋转") {
                    scope.launch {
                        busy = true
                        val updated = withContext(Dispatchers.Default) {
                            runCatching {
                                val bytes = repository.readBytes(repository.pagePath(current.id, page))
                                val rotated = AppGraph.codec.decode(bytes, ScanLimits.MAX_EDGE).rotate90CW()
                                repository.replacePage(
                                    current.id,
                                    page.id,
                                    EncodedPage(
                                        jpeg = AppGraph.codec.encodeJpeg(rotated, ScanLimits.JPEG_QUALITY),
                                        width = rotated.width,
                                        height = rotated.height,
                                        filter = page.filter,
                                    ),
                                )
                            }.getOrNull()
                        }
                        busy = false
                        if (updated == null) onMessage("旋转失败") else refresh(updated)
                    }
                },
                DetailAction("前移") { refresh(repository.movePage(current.id, page.id, -1)) },
                DetailAction("后移") { refresh(repository.movePage(current.id, page.id, 1)) },
                DetailAction("删除页") { confirmDeletePage = true },
                DetailAction("分享 PDF") {
                    scope.launch {
                        busy = true
                        val path = withContext(Dispatchers.Default) {
                            runCatching { repository.exportPdf(current.id) }.getOrNull()
                        }
                        busy = false
                        if (path == null) onMessage("生成 PDF 失败") else share(path, "application/pdf", current.title)
                    }
                },
                DetailAction("分享图片") {
                    share(repository.pagePath(current.id, page), "image/jpeg", current.title)
                },
                DetailAction("删除文档") { confirmDeleteDoc = true },
            )
            items(actions) { action ->
                TextButton(onClick = action.onClick, enabled = !busy) {
                    Text(action.label)
                }
            }
        }
    }
    BusyOverlay(busy, "正在处理")

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    refresh(repository.rename(current.id, titleDraft))
                    renaming = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } },
        )
    }
    if (confirmDeletePage) {
        AlertDialog(
            onDismissRequest = { confirmDeletePage = false },
            title = { Text("删除这一页？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    refresh(repository.deletePage(current.id, page.id))
                    confirmDeletePage = false
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDeletePage = false }) { Text("取消") } },
        )
    }
    if (confirmDeleteDoc) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDoc = false },
            title = { Text("删除整份文档？") },
            text = { Text("所有页面都会从本机移除。") },
            confirmButton = {
                TextButton(onClick = {
                    repository.delete(current.id)
                    confirmDeleteDoc = false
                    navigator.pop()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteDoc = false }) { Text("取消") } },
        )
    }
}
