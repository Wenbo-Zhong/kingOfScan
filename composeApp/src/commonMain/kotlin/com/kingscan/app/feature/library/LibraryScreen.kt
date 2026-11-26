package com.kingscan.app.feature.library

import com.kingscan.app.core.ui.ScanLibraryHeader
import com.kingscan.app.core.ui.ScanSegmentedControl
import com.kingscan.app.core.ui.ScanLibraryActions
import com.kingscan.app.core.platform.BiometricAvailability
import com.kingscan.app.core.platform.LocalDeviceAuthenticator
import com.kingscan.app.core.platform.rememberFileImporter
import com.kingscan.app.core.platform.rememberImagesImporter
import com.kingscan.app.core.platform.rememberSecureAuthentication
import com.kingscan.app.core.ui.BusyOverlay
import com.kingscan.app.core.ui.ScanSymbol
import com.kingscan.app.feature.scan.importFiles
import com.kingscan.app.navigation.Navigator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.kingscan.app.core.ui.ScanIcon as Icon
import com.kingscan.app.core.ui.ScanTheme as MaterialTheme
import com.kingscan.app.core.ui.ScanTextField as OutlinedTextField
import androidx.compose.material3.Text
import com.kingscan.app.core.ui.ScanTextButton as TextButton
import com.kingscan.app.core.ui.ScanChoiceChip as FilterChip
import com.kingscan.app.core.ui.ScanMenu as DropdownMenu
import com.kingscan.app.core.ui.ScanMenuItem as DropdownMenuItem
import com.kingscan.app.core.ui.ScanIconButton as IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    navigator: Navigator,
    onMessage: (String) -> Unit,
) {
    val repository = AppGraph.repository
    val state = remember(repository) { LibraryState(repository) }
    with(state) {
    val authenticator = LocalDeviceAuthenticator.current
    val authenticate = rememberSecureAuthentication(onMessage)
    val securityRevision = AppGraph.security.revision
    val folders = remember(revision, securityRevision) { repository.folders() }
    val documents = remember(query, revision, securityRevision, folderId, starredOnly, trash, sort) {
        state.documents()
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(securityRevision) {
        state.securityChanged()
    }
    fun accessFolder(id: String, action: () -> Unit) {
        if (repository.canAccessFolder(id)) action()
        else authenticate("解锁文件夹以查看或移动文档") {
            AppGraph.unlockFolder(id)
            action()
        }
    }
    fun batch(action: (List<String>) -> Unit) {
        scope.launch { state.batch(action, onMessage) }
    }
    val importImage = rememberImagesImporter(
        onImages = { files ->
            navigator.scope.launch {
                AppGraph.session.beginNew()
                importFiles(files, { importing = it }, navigator, onMessage)
            }
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
            navigator.scope.launch {
                AppGraph.session.beginNew()
                importFiles(files, { importing = it }, navigator, onMessage)
            }
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
                    FilterChip(folderId == folder.id, {
                        accessFolder(folder.id) { folderId = folder.id; trash = false; selected = emptyList() }
                    }, { Text((if (AppGraph.security.isFolderLocked(folder.id)) "🔒 " else "") + folder.name) },
                        enabled = !authenticator.busy)
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
            }
            folders.firstOrNull { it.id == folderId }?.let { folder ->
                val protected = AppGraph.security.isFolderLocked(folder.id)
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    item {
                        TextButton(onClick = {
                            authenticate(if (protected) "关闭文件夹锁" else "启用文件夹锁") {
                                AppGraph.configureFolderLock(folder.id, !protected)
                                if (!protected) folderId = null
                                refresh()
                            }
                        }, enabled = !authenticator.busy && AppGraph.security.supported &&
                            (protected || authenticator.availability == BiometricAvailability.Ready)) {
                            Text(if (protected) "关闭文件夹锁" else "文件夹加锁")
                        }
                    }
                    if (protected) item {
                        TextButton(onClick = {
                            AppGraph.security.lockFolder(folder.id)
                            authenticator.cancel()
                            folderId = null
                            refresh()
                        }) { Text("立即锁定") }
                    }
                    item {
                        TextButton(onClick = {
                            folderId = null
                            batch { repository.deleteFolder(folder.id) }
                        }, enabled = !protected) { Text("移除文件夹") }
                    }
                }
                if (authenticator.availability != BiometricAvailability.Ready)
                    Text(authenticator.availability.message, Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall)
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
                        LibraryDocumentCard(document, repository, selecting, selected) {
                            if (selecting || trash) {
                                selecting = true
                                selected = if (document.id in selected) selected - document.id else selected + document.id
                            } else navigator.push(Screen.Detail(document.id))
                        }
                    }
                }
            }
        }
        ScanLibraryActions(
            onImport = importImage,
            onScan = { navigator.scope.launch { AppGraph.session.beginNew(); navigator.push(Screen.Capture) } },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
        BusyOverlay(importing, "正在处理")
    }

    LibraryDialogs(state, repository, authenticator, folders, ::accessFolder, ::batch, onMessage)
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
