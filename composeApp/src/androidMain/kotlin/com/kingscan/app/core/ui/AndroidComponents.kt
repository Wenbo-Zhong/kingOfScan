package com.kingscan.app.core.ui

import com.kingscan.app.core.platform.LocalContentLocked

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable internal actual fun platformAppearance(dark: Boolean): ScanAppearance {
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else brandColors(dark)
    return ScanAppearance(colors, Typography())
}
@Composable actual fun ScanButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, shape: Shape?, content: @Composable RowScope.() -> Unit) =
    Button(onClick, modifier, enabled, shape = shape ?: ButtonDefaults.shape, content = content)
@Composable actual fun ScanSecondaryButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, shape: Shape?, content: @Composable RowScope.() -> Unit) =
    OutlinedButton(onClick, modifier, enabled, shape = shape ?: ButtonDefaults.outlinedShape, content = content)
@Composable actual fun ScanTextButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, content: @Composable RowScope.() -> Unit) = TextButton(onClick, modifier, enabled, content = content)
@Composable actual fun ScanIconButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, content: @Composable () -> Unit) = IconButton(onClick, modifier, enabled, content = content)
@Composable actual fun ScanIcon(symbol: ScanSymbol, contentDescription: String?, modifier: Modifier, tint: Color) {
    val vector = when (symbol) {
        ScanSymbol.Back -> Icons.AutoMirrored.Filled.ArrowBack
        ScanSymbol.Close -> Icons.Default.Close
        ScanSymbol.More -> Icons.Default.MoreVert
        ScanSymbol.Add -> Icons.Default.Add
        ScanSymbol.PhotoLibrary -> Icons.Default.PhotoLibrary
        ScanSymbol.FlashOn -> Icons.Default.FlashOn
        ScanSymbol.FlashOff -> Icons.Default.FlashOff
        ScanSymbol.RotateRight -> Icons.AutoMirrored.Filled.RotateRight
        ScanSymbol.Undo -> Icons.AutoMirrored.Filled.Undo
        ScanSymbol.Redo -> Icons.AutoMirrored.Filled.Redo
        ScanSymbol.Scan -> Icons.Default.DocumentScanner
        ScanSymbol.Search -> Icons.Default.Search
    }
    Icon(vector, contentDescription, modifier, tint)
}
@Composable actual fun ScanChoiceChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier, enabled: Boolean) = FilterChip(selected, onClick, label, modifier, enabled)
@Composable actual fun ScanTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier, enabled: Boolean, readOnly: Boolean, label: (@Composable () -> Unit)?, placeholder: (@Composable () -> Unit)?, singleLine: Boolean, minLines: Int, maxLines: Int, visualTransformation: VisualTransformation) =
    OutlinedTextField(value, onValueChange, modifier, enabled, readOnly, label = label, placeholder = placeholder, singleLine = singleLine, minLines = minLines, maxLines = maxLines, visualTransformation = visualTransformation)
@Composable actual fun ScanSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier, enabled: Boolean, label: String?) = Switch(checked, onCheckedChange, modifier, enabled = enabled)
@Composable actual fun ScanSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier, enabled: Boolean, valueRange: ClosedFloatingPointRange<Float>, steps: Int, label: String?) = Slider(value, onValueChange, modifier, enabled, valueRange = valueRange, steps = steps)
@Composable actual fun ScanCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier, enabled: Boolean) = Checkbox(checked, onCheckedChange, modifier, enabled)
@Composable actual fun ScanCard(modifier: Modifier, shape: Shape?, content: @Composable ColumnScope.() -> Unit) = Card(modifier, shape = shape ?: CardDefaults.shape, content = content)
@Composable actual fun ScanDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier, dismissButton: (@Composable () -> Unit)?, icon: (@Composable () -> Unit)?, title: (@Composable () -> Unit)?, text: (@Composable () -> Unit)?) {
    if (!LocalContentLocked.current) AlertDialog(onDismissRequest, confirmButton, modifier, dismissButton, icon, title, text)
}
@Composable actual fun ScanMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) = DropdownMenu(expanded && !LocalContentLocked.current, onDismissRequest, content = content)
@Composable actual fun ScanMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, enabled: Boolean) = DropdownMenuItem(text, onClick, enabled = enabled)
@Composable actual fun ScanProgressIndicator(modifier: Modifier, color: Color) = CircularProgressIndicator(modifier, color)
@Composable actual fun ScanMessageHost(state: SnackbarHostState) = SnackbarHost(state)

@OptIn(ExperimentalMaterial3Api::class)
@Composable actual fun ScanTopBar(title: String, onBack: (() -> Unit)?, contentColor: Color, actions: @Composable RowScope.() -> Unit) {
    TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = {
        if (onBack != null) ScanIconButton(onBack) { ScanIcon(ScanSymbol.Back, "返回", tint = contentColor) }
    }, actions = actions, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = contentColor, actionIconContentColor = contentColor))
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable actual fun ScanLibraryHeader(actions: @Composable RowScope.() -> Unit) {
    MediumTopAppBar(title = { Text("离线扫描王") }, actions = actions,
        colors = TopAppBarDefaults.mediumTopAppBarColors(containerColor = Color.Transparent))
}
@Composable actual fun ScanLibraryActions(onImport: () -> Unit, onScan: () -> Unit, modifier: Modifier) {
    Column(modifier.navigationBarsPadding().padding(20.dp), horizontalAlignment = androidx.compose.ui.Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallFloatingActionButton(onImport, containerColor = ScanTheme.colorScheme.secondaryContainer) { ScanIcon(ScanSymbol.PhotoLibrary, "从相册导入", tint = ScanTheme.colorScheme.onSecondaryContainer) }
        ExtendedFloatingActionButton(onClick = onScan, icon = { ScanIcon(ScanSymbol.Scan, "扫描", tint = ScanTheme.colorScheme.onPrimaryContainer) }, text = { Text("扫描") })
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable actual fun ScanSegmentedControl(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(selected = selectedIndex == index, onClick = { onSelect(index) }, shape = SegmentedButtonDefaults.itemShape(index, labels.size)) { Text(label) }
        }
    }
}
@Composable actual fun ScanSection(title: String, footer: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = ScanTheme.typography.titleMedium, color = ScanTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
        Surface(shape = ScanTheme.shapes.large, color = ScanTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
        if (footer != null) Text(footer, style = ScanTheme.typography.bodySmall, color = ScanTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
    }
}
