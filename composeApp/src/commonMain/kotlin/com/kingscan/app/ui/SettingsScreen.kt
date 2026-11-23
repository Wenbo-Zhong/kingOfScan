package com.kingscan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.kingscan.app.ui.ScanChoiceChip as FilterChip
import com.kingscan.app.ui.ScanTheme as MaterialTheme
import com.kingscan.app.ui.ScanTextField as OutlinedTextField
import com.kingscan.app.ui.ScanSlider as Slider
import com.kingscan.app.ui.ScanSwitch as Switch
import androidx.compose.material3.Text
import com.kingscan.app.ui.ScanTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kingscan.app.AppGraph
import com.kingscan.app.model.ExportOptions
import com.kingscan.app.model.FilterType
import com.kingscan.app.model.PdfPaper
import com.kingscan.app.model.ScanMode
import com.kingscan.app.model.label

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SettingsScreen(navigator: Navigator, onMessage: (String) -> Unit) {
    var settings by remember { mutableStateOf(AppGraph.repository.settings()) }
    var authenticating by remember { mutableStateOf(false) }
    val authenticate = rememberDeviceAuthenticator { success, error ->
        authenticating = false
        if (success) {
            val enabled = !settings.deviceLock
            AppGraph.repository.saveSettings(AppGraph.repository.settings().copy(deviceLock = enabled))
            AppGraph.configureDeviceLock(enabled)
            settings = settings.copy(deviceLock = enabled)
        } else onMessage(error)
    }
    Column(Modifier.fillMaxSize()) {
        ScanTopBar("扫描设置", onBack = { navigator.pop() }) {
            TextButton(onClick = {
                AppGraph.repository.saveSettings(settings.copy(export = settings.export.copy(password = "")))
                AppGraph.configureDeviceLock(settings.deviceLock)
                onMessage("设置已保存")
                navigator.pop()
            }) { Text("保存") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            ScanSection("默认滤镜") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterType.entries.forEach { filter ->
                        FilterChip(settings.defaultFilter == filter, { settings = settings.copy(defaultFilter = filter) },
                            label = { Text(filter.label()) })
                    }
                }
            }
            ScanSection("默认拍摄模式") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScanMode.entries.forEach { mode ->
                        FilterChip(settings.scanMode == mode, { settings = settings.copy(scanMode = mode) }, { Text(mode.label()) })
                    }
                }
            }
            ScanSection("拍摄选项") {
                SettingSwitch("稳定后自动拍摄", settings.autoCapture) { settings = settings.copy(autoCapture = it) }
                SettingSwitch("连续拍摄", settings.continuousCapture) { settings = settings.copy(continuousCapture = it) }
                SettingSwitch("书籍从右向左阅读", settings.bookRightToLeft) { settings = settings.copy(bookRightToLeft = it) }
                SettingSwitch("扫描后保存到系统相册", settings.autoSaveToGallery) { settings = settings.copy(autoSaveToGallery = it) }
            }
            ScanSection("隐私保护", footer = "开启后，启动或从后台返回需系统认证。请先设置设备密码。导出和相册副本需单独保护。") {
                SettingSwitch("设备锁保护", settings.deviceLock, enabled = !authenticating) {
                    authenticating = true
                    authenticate()
                }
            }
            ScanSection("默认 PDF 导出") {
                ExportOptionsEditor(settings.export, { settings = settings.copy(export = it) }, showPassword = false)
            }
        }
    }
}

@Composable
internal fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onCheckedChange = onChange, enabled = enabled, label = label)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ExportOptionsEditor(options: ExportOptions, onChange: (ExportOptions) -> Unit,
    showPassword: Boolean = true, fixedIdPaper: Boolean = false) {
    if (fixedIdPaper) Text("证件复印件使用 A4、无边距，保持证件实际尺寸")
    else {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PdfPaper.entries.forEach { paper ->
            FilterChip(options.paper == paper, { onChange(options.copy(paper = paper)) },
                { Text(when (paper) { PdfPaper.FitImage -> "适合图片"; PdfPaper.A4 -> "A4"; PdfPaper.Letter -> "Letter" }) })
        }
    }
    SettingSwitch("横向纸张", options.landscape) { onChange(options.copy(landscape = it)) }
    Text("留白：${options.marginPoints.toInt()} pt")
    Slider(options.marginPoints, { onChange(options.copy(marginPoints = it)) }, valueRange = 0f..72f, steps = 11, label = "留白")
    }
    Text("图像质量：${options.jpegQuality}%")
    Slider(options.jpegQuality.toFloat(), { onChange(options.copy(jpegQuality = it.toInt())) }, valueRange = 50f..100f, steps = 9, label = "图像质量")
    SettingSwitch("页码", options.pageNumbers) { onChange(options.copy(pageNumbers = it)) }
    SettingSwitch("可搜索文字（离线识别）", options.searchable) { onChange(options.copy(searchable = it)) }
    if (showPassword) {
        OutlinedTextField(options.password, { onChange(options.copy(password = it)) },
            label = { Text("打开密码（留空则不加密）") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    }
}
