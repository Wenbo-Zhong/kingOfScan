package com.kingscan.app.feature.editor

import com.kingscan.app.core.ui.ScanTopBar
import com.kingscan.app.core.platform.LocalContentLocked
import com.kingscan.app.core.platform.rememberGallerySaver
import com.kingscan.app.core.platform.rememberPrintLauncher
import com.kingscan.app.core.platform.rememberShareLauncher
import com.kingscan.app.core.ui.BusyOverlay
import com.kingscan.app.core.ui.PageImage
import com.kingscan.app.navigation.Navigator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.kingscan.app.core.ui.ScanDialog as AlertDialog
import com.kingscan.app.core.ui.ScanTheme as MaterialTheme
import com.kingscan.app.core.ui.ScanTextField as OutlinedTextField
import androidx.compose.material3.Text
import com.kingscan.app.core.ui.ScanTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.core.document.ScanDocument
import com.kingscan.app.navigation.Screen
import com.kingscan.app.core.document.label
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
    var document by rememberEditorDocument(documentId)
    var renaming by remember { mutableStateOf(false) }
    var titleDraft by remember(document?.title) { mutableStateOf(document?.title.orEmpty()) }
    var confirmDeleteDoc by remember { mutableStateOf(false) }
    var confirmDeletePage by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf(false) }
    var tagsDraft by remember { mutableStateOf("") }
    var notesDraft by remember { mutableStateOf("") }
    var sourcePdfDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val share = rememberShareLauncher(onMessage)
    val saveGallery = rememberGallerySaver(onMessage)
    val print = rememberPrintLauncher(onMessage)
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
            onBack = { navigator.scope.launch { AppGraph.session.cancel(); navigator.pop() } },
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
            if (!LocalContentLocked.current) PageImage(
                path = if (showOriginal) repository.originalPath(current.id, item) ?: repository.pagePath(current.id, item)
                    else repository.pagePath(current.id, item),
                modifier = Modifier.fillMaxSize(),
                contentDescription = "第 ${index + 1} 页",
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val actions = listOfNotNull(
                DetailAction(if (current.composition == null) "拼版与拼接" else "重新拼版") {
                    navigator.push(Screen.ComposePages(current.id))
                },
                if (current.composition != null) DetailAction("提取拼版原页") {
                    scope.launch {
                        busy = true
                        val result = withContext(Dispatchers.Default) { runCatching { repository.extractCompositionSources(current.id) } }
                        busy = false
                        result.fold({ navigator.showDetail(it.id) }, { onMessage(it.message ?: "无法提取") })
                    }
                } else null,
                DetailAction("识别文字") { navigator.push(Screen.Ocr(current.id, page.id)) },
                DetailAction("批注签名") { navigator.push(Screen.Annotate(current.id, page.id)) },
                DetailAction("擦除与修复") { navigator.push(Screen.Retouch(current.id, page.id)) },
                DetailAction("页面管理") { navigator.push(Screen.Pages(current.id)) },
                DetailAction("原始 PDF") {
                    if (current.originalPdfs.isEmpty()) onMessage("此文档没有导入的原始 PDF") else sourcePdfDialog = true
                },
                DetailAction(if (showOriginal) "查看结果" else "查看原图") {
                    if (repository.originalPath(current.id, page) == null) onMessage("这张旧页面未保留原图")
                    else showOriginal = !showOriginal
                },
                DetailAction("重新裁切") {
                    scope.launch {
                        busy = true
                        val result = runCatching { AppGraph.session.beginEdit(current.id, page) }
                        busy = false
                        result.fold({ navigator.push(Screen.Crop) }, { onMessage(it.message ?: "无法读取原图") })
                    }
                },
                DetailAction(if (current.starred) "取消收藏" else "收藏") {
                    refresh(repository.organize(current.id, starred = !current.starred))
                },
                DetailAction("标签与备注") {
                    tagsDraft = current.tags.joinToString("、")
                    notesDraft = current.notes
                    organizing = true
                },
                DetailAction("复制文档") {
                    scope.launch {
                        busy = true
                        val result = withContext(Dispatchers.Default) { runCatching { repository.duplicate(current.id) } }
                        busy = false
                        result.fold({ navigator.showDetail(it.id) }, { onMessage(it.message ?: "复制失败") })
                    }
                },
                DetailAction("提取本页") {
                    scope.launch {
                        busy = true
                        val result = withContext(Dispatchers.Default) {
                            runCatching { repository.extractPages(current.id, listOf(page.id), "${current.title} 第${pagerState.currentPage + 1}页") }
                        }
                        busy = false
                        result.fold({ navigator.showDetail(it.id) }, { onMessage(it.message ?: "提取失败") })
                    }
                },
                DetailAction("加页") {
                    navigator.scope.launch {
                        AppGraph.session.beginAppend(current.id)
                        navigator.push(Screen.Capture)
                    }
                },
                DetailAction("旋转") {
                    scope.launch {
                        busy = true
                        val updated = withContext(Dispatchers.Default) {
                            runCatching { AppGraph.pageOperations.rotate(current.id, page) }.getOrNull()
                        }
                        busy = false
                        if (updated == null) onMessage("旋转失败") else refresh(updated)
                    }
                },
                DetailAction("前移") { refresh(repository.movePage(current.id, page.id, -1)) },
                DetailAction("后移") { refresh(repository.movePage(current.id, page.id, 1)) },
                DetailAction("删除页") { confirmDeletePage = true },
                DetailAction("分享 PDF") {
                    navigator.push(Screen.Export(current.id))
                },
                DetailAction("分享图片") {
                    share(repository.pagePath(current.id, page), "image/jpeg", current.title)
                },
                DetailAction("保存本页到相册") { saveGallery(listOf(repository.pagePath(current.id, page))) },
                DetailAction("全部保存到相册") { saveGallery(current.pages.map { repository.pagePath(current.id, it) }) },
                DetailAction("系统打印") {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            runCatching { repository.exportPdf(current.id, repository.settings().export.copy(password = "", searchable = false)) }
                        }
                        busy = false
                        result.fold({ print(it, current.title) }, { onMessage(it.message ?: "打印文件生成失败") })
                    }
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

    if (sourcePdfDialog) {
        AlertDialog(onDismissRequest = { sourcePdfDialog = false }, title = { Text("原始 PDF") },
            text = { Column {
                current.originalPdfs.forEach { pdf ->
                    TextButton(onClick = { share(repository.originalPdfPath(current.id, pdf), "application/pdf", pdf.name) }) { Text(pdf.name) }
                }
            } }, confirmButton = { TextButton(onClick = { sourcePdfDialog = false }) { Text("关闭") } })
    }

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
            text = { Text("文档将移入回收站，可以恢复。") },
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
    if (organizing) {
        AlertDialog(
            onDismissRequest = { organizing = false },
            title = { Text("标签与备注") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(tagsDraft, { tagsDraft = it }, label = { Text("标签（逗号或顿号分隔）") })
                    OutlinedTextField(notesDraft, { notesDraft = it }, label = { Text("备注") }, minLines = 3)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    refresh(repository.organize(current.id, tags = tagsDraft.split(Regex("[,，、]")), notes = notesDraft))
                    organizing = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { organizing = false }) { Text("取消") } },
        )
    }
}
