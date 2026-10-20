package com.kingscan.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@Composable
fun LibraryScreen(
    navigator: Navigator,
    onMessage: (String) -> Unit,
) {
    val repository = AppGraph.repository
    var query by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val documents = remember(query, revision) {
        repository.list().filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val importImage = rememberImageImporter(
        onImage = { bytes ->
            AppGraph.session.beginNew()
            openEditor(bytes, scope, { importing = it }, navigator, onMessage)
        },
        onError = { message ->
            importing = false
            onMessage(message)
        },
        onStart = { importing = true },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScanTopBar(title = "离线扫描王") {
                TextButton(onClick = { showAbout = true }) { Text("关于") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                placeholder = { Text("搜索文稿") },
            )
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
                            Column(Modifier.clickable { navigator.push(Screen.Detail(document.id)) }) {
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
                                    Text(
                                        document.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${document.pages.size} 页 · ${formatScanTime(document.updatedAt)}",
                                        style = MaterialTheme.typography.bodyMedium,
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
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FloatingActionButton(
                onClick = { importImage() },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = "从相册导入")
            }
            FloatingActionButton(
                onClick = {
                    AppGraph.session.beginNew()
                    navigator.push(Screen.Capture)
                },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "扫描")
            }
        }
        BusyOverlay(importing, "正在读取图片")
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
            if (hasQuery) "换个关键词试试" else "点右下角开始拍摄，或从相册导入一张图片",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
