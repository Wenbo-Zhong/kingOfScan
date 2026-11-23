package com.kingscan.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.VisualTransformation

enum class ScanSymbol { Back, Close, More, Add, PhotoLibrary, FlashOn, FlashOff, RotateRight, Undo, Redo, Scan, Search }

@Composable expect fun ScanButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape? = null, content: @Composable RowScope.() -> Unit)
@Composable expect fun ScanSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape? = null, content: @Composable RowScope.() -> Unit)
@Composable expect fun ScanTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit)
@Composable expect fun ScanIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit)
@Composable expect fun ScanIcon(symbol: ScanSymbol, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = ScanTheme.colorScheme.primary)
@Composable expect fun ScanChoiceChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true)
@Composable expect fun ScanTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, readOnly: Boolean = false, label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null, singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, visualTransformation: VisualTransformation = VisualTransformation.None)
@Composable expect fun ScanSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: String? = null)
@Composable expect fun ScanSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, label: String? = null)
@Composable expect fun ScanCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true)
@Composable expect fun ScanCard(modifier: Modifier = Modifier, shape: Shape? = null, content: @Composable ColumnScope.() -> Unit)
@Composable expect fun ScanDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null, icon: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null)
@Composable expect fun ScanMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit)
@Composable expect fun ScanMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, enabled: Boolean = true)
@Composable expect fun ScanProgressIndicator(modifier: Modifier = Modifier, color: Color = ScanTheme.colorScheme.primary)
@Composable expect fun ScanMessageHost(state: SnackbarHostState)
@Composable expect fun ScanTopBar(title: String, onBack: (() -> Unit)? = null, contentColor: Color = ScanTheme.colorScheme.onSurface, actions: @Composable RowScope.() -> Unit = {})
@Composable expect fun ScanLibraryHeader(actions: @Composable RowScope.() -> Unit)
@Composable expect fun ScanLibraryActions(onImport: () -> Unit, onScan: () -> Unit, modifier: Modifier = Modifier)
@Composable expect fun ScanSegmentedControl(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier)
@Composable expect fun ScanSection(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit)
