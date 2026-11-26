package com.kingscan.app.feature.library

import com.kingscan.app.core.platform.LocalContentLocked
import com.kingscan.app.core.ui.JpegThumb

import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kingscan.app.core.storage.DocumentRepository
import com.kingscan.app.core.storage.formatScanTime
import com.kingscan.app.core.document.ScanDocument
import com.kingscan.app.core.ui.ScanCard as Card
import com.kingscan.app.core.ui.ScanCheckbox as Checkbox
import com.kingscan.app.core.ui.ScanTheme as MaterialTheme

@Composable
internal fun LibraryDocumentCard(
    document: ScanDocument, repository: DocumentRepository,
    selecting: Boolean, selected: List<String>, onClick: () -> Unit,
) {
    val thumb = if (LocalContentLocked.current || !repository.canAccessFolder(document.folderId)) null
        else document.pages.firstOrNull()?.let { repository.pagePath(document.id, it) }
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.clickable { onClick() }) {
            if (thumb != null) {
                JpegThumb(
                    path = thumb,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.78f),
                    contentDescription = document.title,
                )
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(0.78f), contentAlignment = Alignment.Center) {
                    Text("空文档")
                }
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
