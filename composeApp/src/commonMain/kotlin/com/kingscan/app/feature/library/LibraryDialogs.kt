package com.kingscan.app.feature.library

import com.kingscan.app.core.platform.DeviceAuthenticator

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import com.kingscan.app.AppGraph
import com.kingscan.app.core.storage.DocumentRepository
import com.kingscan.app.core.document.DocumentFolder
import com.kingscan.app.core.ui.ScanDialog as AlertDialog
import com.kingscan.app.core.ui.ScanTextButton as TextButton
import com.kingscan.app.core.ui.ScanTextField as OutlinedTextField

@Composable
internal fun LibraryDialogs(
    state: LibraryState, repository: DocumentRepository, authenticator: DeviceAuthenticator,
    folders: List<DocumentFolder>,
    accessFolder: (String, () -> Unit) -> Unit,
    batch: ((List<String>) -> Unit) -> Unit, onMessage: (String) -> Unit,
) = with(state) {
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
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("知道了") } },
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
                        TextButton(onClick = {
                            moveDialog = false
                            accessFolder(folder.id) { batch { ids -> ids.forEach { repository.organize(it, folderId = folder.id) } } }
                        }, enabled = !authenticator.busy) {
                            Text((if (AppGraph.security.isFolderLocked(folder.id)) "🔒 " else "") + folder.name)
                        }
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
