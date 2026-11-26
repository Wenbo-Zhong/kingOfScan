package com.kingscan.app.feature.editor

import com.kingscan.app.core.ui.ScanTopBar
import com.kingscan.app.core.platform.LocalContentLocked
import com.kingscan.app.core.ui.BusyOverlay
import com.kingscan.app.core.ui.JpegThumb
import com.kingscan.app.navigation.Navigator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import com.kingscan.app.core.ui.ScanCard as Card
import com.kingscan.app.core.ui.ScanTheme as MaterialTheme
import com.kingscan.app.core.ui.ScanTextButton as TextButton
import com.kingscan.app.core.ui.ScanCheckbox as Checkbox
import com.kingscan.app.core.ui.ScanDialog as AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.core.document.FilterType
import com.kingscan.app.core.document.ScanDocument
import com.kingscan.app.navigation.Screen
import com.kingscan.app.core.document.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PageManagementScreen(documentId: String, navigator: Navigator, onMessage: (String) -> Unit) {
    val repository = AppGraph.repository
    var document by rememberEditorDocument(documentId)
    val current = document ?: return
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var filterDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun action(block: (List<String>) -> ScanDocument?) {
        val snapshot = selected.toList()
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.Default) { runCatching { block(snapshot) } }
            busy = false
            result.fold({
                if (it == null) navigator.showDetail(documentId) else { document = it; selected = emptyList() }
            }, { onMessage(it.message ?: "操作失败"); document = repository.load(documentId) })
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScanTopBar("页面管理 · ${current.pages.size} 页", onBack = { if (!busy) navigator.pop() }) {
                TextButton(onClick = { selected = if (selected.size == current.pages.size) emptyList() else current.pages.map { it.id } }) { Text("全选") }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                item { TextButton(onClick = { navigator.push(Screen.ComposePages(documentId, selected.toList())) },
                    enabled = selected.isNotEmpty() && !busy) { Text("拼版与拼接") } }
                item { TextButton(onClick = { action { ids ->
                    val extracted = repository.extractPages(documentId, ids, "${current.title} 提取")
                    // Keep the source view while the new document remains in the library.
                    repository.load(documentId).also { onMessage("已提取 ${ids.size} 页：${extracted.title}") }
                } }, enabled = selected.isNotEmpty() && !busy) { Text("提取") } }
                item { TextButton(onClick = { action { ids ->
                    ids.forEach { id ->
                        repository.load(documentId)?.pages?.firstOrNull { it.id == id }?.let { AppGraph.pageOperations.rotate(documentId, it) }
                    }; repository.load(documentId)
                } }, enabled = selected.isNotEmpty() && !busy) { Text("旋转") } }
                item { TextButton(onClick = { filterDialog = true }, enabled = selected.isNotEmpty() && !busy) { Text("批量滤镜") } }
                item { TextButton(onClick = { deleteDialog = true }, enabled = selected.isNotEmpty() && !busy) { Text("删除") } }
                item { TextButton(onClick = { action { repository.reorderPages(documentId, current.pages.map { it.id }.reversed()) } },
                    enabled = !busy) { Text("倒序") } }
            }
            Text("按选择顺序提取；用箭头调整页序", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(current.pages, key = { it.id }) { page ->
                    val index = current.pages.indexOf(page)
                    Card {
                        Row(Modifier.fillMaxWidth().clickable {
                            selected = if (page.id in selected) selected - page.id else selected + page.id
                        }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(page.id in selected, null)
                            if (!LocalContentLocked.current)
                                JpegThumb(repository.pagePath(documentId, page), Modifier.size(64.dp, 88.dp), "第 ${index + 1} 页")
                            Column(Modifier.weight(1f).padding(12.dp)) {
                                Text("第 ${index + 1} 页")
                                Text(page.filter.label(), style = MaterialTheme.typography.bodySmall)
                                if (page.id in selected) Text("选择顺序 ${selected.indexOf(page.id) + 1}")
                            }
                            TextButton(onClick = { action { repository.movePage(documentId, page.id, -1) } }, enabled = index > 0 && !busy) { Text("↑") }
                            TextButton(onClick = { action { repository.movePage(documentId, page.id, 1) } }, enabled = index < current.pages.lastIndex && !busy) { Text("↓") }
                        }
                    }
                }
            }
        }
        BusyOverlay(busy, "正在处理页面")
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("删除 ${selected.size} 页？") },
        text = { Text(if (selected.size == current.pages.size) "整份文档会移入回收站。" else "选中的页面将被删除，无法恢复。") },
        confirmButton = { TextButton(onClick = { deleteDialog = false; action { repository.deletePages(documentId, it) } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
    if (filterDialog) AlertDialog(onDismissRequest = { filterDialog = false }, title = { Text("批量滤镜") },
        text = { Column {
            FilterType.entries.forEach { filter ->
                TextButton(onClick = {
                    filterDialog = false
                    action { ids ->
                        ids.forEach { id -> repository.load(documentId)?.pages?.firstOrNull { it.id == id }?.let {
                            AppGraph.pageOperations.filter(documentId, it, filter)
                        } }; repository.load(documentId)
                    }
                }) { Text(filter.label()) }
            }
        } }, confirmButton = { TextButton(onClick = { filterDialog = false }) { Text("取消") } })
}
