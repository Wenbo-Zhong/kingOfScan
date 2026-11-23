package com.kingscan.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kingscan.app.ui.ScanDialog as AlertDialog
import com.kingscan.app.ui.ScanCard as Card
import com.kingscan.app.ui.ScanIcon as Icon
import com.kingscan.app.ui.ScanTheme as MaterialTheme
import com.kingscan.app.ui.ScanTextField as OutlinedTextField
import androidx.compose.material3.Text
import com.kingscan.app.ui.ScanTextButton as TextButton
import com.kingscan.app.ui.ScanChoiceChip as FilterChip
import com.kingscan.app.ui.ScanCheckbox as Checkbox
import com.kingscan.app.ui.ScanMenu as DropdownMenu
import com.kingscan.app.ui.ScanMenuItem as DropdownMenuItem
import com.kingscan.app.ui.ScanIconButton as IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.data.formatScanTime
import com.kingscan.app.model.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    navigator: Navigator,
    onMessage: (String) -> Unit,
) {
    val repository = AppGraph.repository
    var query by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var folderId by remember { mutableStateOf<String?>(null) }
    var starredOnly by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    var folderDraft by remember { mutableStateOf("") }
    var folderDialog by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var purgeDialog by remember { mutableStateOf(false) }
    var sort by remember { mutableIntStateOf(0) }
    val folders = remember(revision) { repository.folders() }
    val documents = remember(query, revision, folderId, starredOnly, trash, sort) {
        repository.search(query, includeTrash = trash).filter {
            (it.deletedAt != null) == trash && (folderId == null || it.folderId == folderId) && (!starredOnly || it.starred)
        }.let { list -> when (sort) { 1 -> list.sortedBy { it.title }; 2 -> list.sortedByDescending { it.pages.size }; else -> list } }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun refresh() { revision++; selected = emptyList(); selecting = false }
    fun batch(action: (List<String>) -> Unit) {
        val snapshot = selected.toList()
        scope.launch {
            importing = true
            val result = withContext(Dispatchers.Default) { runCatching { action(snapshot) } }
            importing = false
            result.fold({ refresh() }, { onMessage(it.message ?: "操作失败") })
        }
    }
    val importImage = rememberImagesImporter(
        onImages = { files ->
            AppGraph.session.beginNew()
            importFiles(files, { importing = it }, navigator, onMessage)
        },
        onError = { message ->
            importing = false
            onMessage(message)
        },
        onStart = { importing = true },
        onCancel = { importing = false },
    )
    val importFile = rememberFileImporter(
        onFiles = { files ->
            AppGraph.session.beginNew()
            importFiles(files, { importing = it }, navigator, onMessage)
        },
        onError = { importing = false; onMessage(it) },
        onStart = { importing = true },
        onCancel = { importing = false },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScanLibraryHeader {
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(ScanSymbol.More, "更多") }
                    DropdownMenu(showMenu, { showMenu = false }) {
                        DropdownMenuItem({ Text("导入文件") }, { showMenu = false; importFile() })
                        DropdownMenuItem({ Text(if (selecting) "取消选择" else "选择文档") }, {
                            showMenu = false; selecting = !selecting; selected = emptyList()
                        })
                        DropdownMenuItem({ Text("二维码与条码") }, { showMenu = false; navigator.push(Screen.Codes) })
                        DropdownMenuItem({ Text("设置") }, { showMenu = false; navigator.push(Screen.Settings) })
                        DropdownMenuItem({ Text("关于") }, { showMenu = false; showAbout = true })
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                placeholder = { Text("搜索标题、标签、备注和识别文字") },
            )
            ScanSegmentedControl(
                labels = listOf("全部", "收藏", "回收站"),
                selectedIndex = if (trash) 2 else if (starredOnly) 1 else 0,
                onSelect = { index -> folderId = null; trash = index == 2; starredOnly = index == 1; selected = emptyList() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(folders, key = { it.id }) { folder ->
                    FilterChip(folderId == folder.id, { folderId = folder.id; trash = false; selected = emptyList() }, { Text(folder.name) })
                }
                item { TextButton(onClick = { folderDraft = ""; folderDialog = true }) { Text("新建文件夹") } }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selecting) "已选择 ${selected.size} 份" else "${documents.size} 份文档", Modifier.weight(1f))
                if (selecting) {
                    TextButton(onClick = { selected = if (selected.size == documents.size) emptyList() else documents.map { it.id } }) { Text("全选") }
                } else {
                    TextButton(onClick = { sort = (sort + 1) % 3 }) { Text(listOf("最近更新", "名称排序", "页数排序")[sort]) }
                }
                if (folderId != null) {
                    TextButton(onClick = {
                        val deleting = folderId!!
                        folderId = null
                        batch { repository.deleteFolder(deleting) }
                    }) { Text("移除文件夹") }
                }
            }
            if (selecting) {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    if (trash) {
                        item { TextButton(onClick = { batch { ids -> ids.forEach { repository.restore(it) } } }, enabled = selected.isNotEmpty()) { Text("恢复") } }
                        item { TextButton(onClick = { purgeDialog = true }, enabled = selected.isNotEmpty()) { Text("彻底删除") } }
                    } else {
                        item { TextButton(onClick = {
                            val snapshot = selected.toList()
                            scope.launch {
                                importing = true
                                val result = withContext(Dispatchers.Default) { runCatching { repository.merge(snapshot, "合并文档") } }
                                importing = false
                                result.fold({ navigator.showDetail(it.id) }, { onMessage(it.message ?: "合并失败") })
                            }
                        }, enabled = selected.size >= 2) { Text("按选择顺序合并") } }
                        item { TextButton(onClick = { moveDialog = true }, enabled = selected.isNotEmpty()) { Text("移动") } }
                        item { TextButton(onClick = { batch { ids -> ids.forEach { repository.organize(it, starred = true) } } }, enabled = selected.isNotEmpty()) { Text("收藏") } }
                        item { TextButton(onClick = { batch { ids -> ids.forEach { repository.delete(it) } } }, enabled = selected.isNotEmpty()) { Text("移入回收站") } }
                    }
                }
            }
            Text(
                text = "拍摄、裁边、滤镜和 PDF 都在本机完成",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (documents.isEmpty()) {
                EmptyLibrary(
                    hasQuery = query.isNotBlank(),
                    modifier = Modifier.fillMaxSize().padding(bottom = 96.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(156.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 112.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(documents, key = { it.id }) { document ->
                        val thumb = document.pages.firstOrNull()?.let { repository.pagePath(document.id, it) }
                        Card(shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.clickable {
                                if (selecting || trash) {
                                    selecting = true
                                    selected = if (document.id in selected) selected - document.id else selected + document.id
                                } else navigator.push(Screen.Detail(document.id))
                            }) {
                                if (thumb != null) {
                                    JpegThumb(
                                        path = thumb,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(0.78f),
                                        contentDescription = document.title,
                                    )
                                } else {
                                    Box(
                                        Modifier.fillMaxWidth().aspectRatio(0.78f),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("空文档") }
                                }
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    if (selecting) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(document.id in selected, onCheckedChange = null)
                                            val order = selected.indexOf(document.id)
                                            if (order >= 0) Text("第 ${order + 1} 份")
                                        }
                                    }
                                    Text(
                                        (if (document.starred) "★ " else "") + document.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (document.tags.isNotEmpty()) Text(document.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    Text(
                                        "${document.pages.size} 页 · ${formatScanTime(document.updatedAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        ScanLibraryActions(
            onImport = importImage,
            onScan = { AppGraph.session.beginNew(); navigator.push(Screen.Capture) },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
        BusyOverlay(importing, "正在处理")
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("离线扫描王") },
            text = {
                Text(
                    "版本 1.0.0\n\n用来扫描合同、票据、笔记和证件。" +
                        "边缘识别、透视校正、滤镜和 PDF 都在这台设备上完成。\n\n" +
                        "应用没有网络权限，文档不会上传。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("知道了") }
            },
        )
    }
    if (folderDialog) {
        AlertDialog(
            onDismissRequest = { folderDialog = false },
            title = { Text("新建文件夹") },
            text = { OutlinedTextField(folderDraft, { folderDraft = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                runCatching { repository.createFolder(folderDraft) }.fold(
                    { folderId = it.id; revision++; folderDialog = false },
                    { onMessage(it.message ?: "创建失败") },
                )
            }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { folderDialog = false }) { Text("取消") } },
        )
    }
    if (moveDialog) {
        AlertDialog(
            onDismissRequest = { moveDialog = false },
            title = { Text("移动到") },
            text = {
                Column {
                    TextButton(onClick = { moveDialog = false; batch { ids -> ids.forEach { repository.organize(it, folderId = null) } } }) { Text("文稿库根目录") }
                    folders.forEach { folder ->
                        TextButton(onClick = { moveDialog = false; batch { ids -> ids.forEach { repository.organize(it, folderId = folder.id) } } }) { Text(folder.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moveDialog = false }) { Text("取消") } },
        )
    }
    if (purgeDialog) {
        AlertDialog(
            onDismissRequest = { purgeDialog = false },
            title = { Text("彻底删除 ${selected.size} 份文档？") },
            text = { Text("原图、处理结果和识别文字都将从本机删除，无法恢复。") },
            confirmButton = { TextButton(onClick = { purgeDialog = false; batch { ids -> ids.forEach { repository.purge(it) } } }) { Text("彻底删除") } },
            dismissButton = { TextButton(onClick = { purgeDialog = false }) { Text("取消") } },
        )
    }
    }

@Composable
private fun EmptyLibrary(hasQuery: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (hasQuery) "没有匹配的文稿" else "还没有扫描件",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasQuery) "换个关键词试试" else "点击扫描开始拍摄，或从相册导入图片",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
