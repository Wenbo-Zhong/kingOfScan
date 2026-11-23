@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.kingscan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import platform.Foundation.NSSelectorFromString
import platform.UIKit.*
import platform.darwin.NSObject
import com.kingscan.app.imaging.toByteArray
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin

private fun Color.native() = UIColor(red = red.toDouble(), green = green.toDouble(), blue = blue.toDouble(), alpha = alpha.toDouble())
private fun UIColor.compose(traits: UITraitCollection): Color = memScoped {
    val r = alloc<DoubleVar>(); val g = alloc<DoubleVar>(); val b = alloc<DoubleVar>(); val a = alloc<DoubleVar>()
    resolvedColorWithTraitCollection(traits).getRed(r.ptr, g.ptr, b.ptr, a.ptr)
    Color(r.value.toFloat(), g.value.toFloat(), b.value.toFloat(), a.value.toFloat())
}

@Composable internal actual fun platformAppearance(dark: Boolean): ScanAppearance {
    val traits = UITraitCollection.traitCollectionWithUserInterfaceStyle(if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight)
    val blue = UIColor.systemBlueColor.compose(traits)
    val label = UIColor.labelColor.compose(traits)
    val secondary = UIColor.secondaryLabelColor.compose(traits)
    val background = UIColor.systemGroupedBackgroundColor.compose(traits)
    val surface = UIColor.secondarySystemGroupedBackgroundColor.compose(traits)
    val fill = UIColor.tertiarySystemFillColor.compose(traits)
    val separator = UIColor.separatorColor.compose(traits)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return ScanAppearance(base.copy(
        primary = blue, onPrimary = Color.White, primaryContainer = blue.copy(alpha = .12f), onPrimaryContainer = blue,
        secondary = blue, onSecondary = Color.White, secondaryContainer = fill, onSecondaryContainer = label,
        tertiary = UIColor.systemGreenColor.compose(traits), onTertiary = Color.White,
        tertiaryContainer = fill, onTertiaryContainer = label,
        background = background, onBackground = label, surface = surface, onSurface = label,
        surfaceVariant = fill, onSurfaceVariant = secondary, outline = separator, outlineVariant = separator,
        surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = fill, surfaceContainerHighest = fill, surfaceDim = background, surfaceBright = surface,
        error = UIColor.systemRedColor.compose(traits), onError = Color.White,
    ), Typography(
        headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
        headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
        titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 13.sp),
    ), Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp)))
}

@Composable private fun AppleButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, shape: Shape?, filled: Int, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val blue = ScanTheme.colorScheme.primary
    val background = when (filled) { 2 -> blue; 1 -> blue.copy(alpha = .12f); else -> Color.Transparent }
    Row(modifier.defaultMinSize(minHeight = 44.dp).alpha(if (!enabled) .35f else if (pressed) .55f else 1f)
        .clip(shape ?: RoundedCornerShape(10.dp)).background(background)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = if (filled == 0) 8.dp else 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides if (filled == 2) Color.White else blue) {
            ProvideTextStyle(ScanTheme.typography.labelLarge) { content() }
        }
    }
}
@Composable actual fun ScanButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, shape: Shape?, content: @Composable RowScope.() -> Unit) = AppleButton(onClick, modifier, enabled, shape, 2, content)
@Composable actual fun ScanSecondaryButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, shape: Shape?, content: @Composable RowScope.() -> Unit) = AppleButton(onClick, modifier, enabled, shape, 1, content)
@Composable actual fun ScanTextButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, content: @Composable RowScope.() -> Unit) = AppleButton(onClick, modifier, enabled, null, 0, content)
@Composable actual fun ScanIconButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).alpha(if (!enabled) .3f else if (pressed) .5f else 1f)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center, content = { content() })
}
@Composable actual fun ScanIcon(symbol: ScanSymbol, contentDescription: String?, modifier: Modifier, tint: Color) {
    val name = when (symbol) {
        ScanSymbol.Back -> "chevron.backward"; ScanSymbol.Close -> "xmark"; ScanSymbol.More -> "ellipsis.circle"
        ScanSymbol.Add -> "plus"; ScanSymbol.PhotoLibrary -> "photo.on.rectangle"; ScanSymbol.FlashOn -> "bolt.fill"
        ScanSymbol.FlashOff -> "bolt.slash.fill"; ScanSymbol.RotateRight -> "rotate.right"
        ScanSymbol.Undo -> "arrow.uturn.backward"; ScanSymbol.Redo -> "arrow.uturn.forward"
        ScanSymbol.Scan -> "doc.viewfinder"; ScanSymbol.Search -> "magnifyingglass"
    }
    // Rasterize the system symbol once. Transparent UIKitView interop clears a rectangular
    // hole in Compose 1.8; drawing the symbol here preserves backgrounds, clipping and alpha.
    val bitmap = remember(name) {
        val symbol = requireNotNull(UIImage.systemImageNamed(name))
        val data = requireNotNull(UIImagePNGRepresentation(symbol))
        SkiaImage.makeFromEncoded(data.toByteArray()).toComposeImageBitmap()
    }
    Image(bitmap, contentDescription, modifier.size(22.dp), colorFilter = ColorFilter.tint(tint))
}
@Composable actual fun ScanChoiceChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier, enabled: Boolean) {
    Box(modifier.defaultMinSize(minHeight = 44.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp))
        .background(if (selected) ScanTheme.colorScheme.primary.copy(alpha = .14f) else ScanTheme.colorScheme.surfaceVariant)
        .selectable(selected, interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .alpha(if (enabled) 1f else .35f).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides if (selected) ScanTheme.colorScheme.primary else ScanTheme.colorScheme.onSurface) {
            ProvideTextStyle(ScanTheme.typography.bodyMedium) { label() }
        }
    }
}
@Composable actual fun ScanTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier, enabled: Boolean, readOnly: Boolean, label: (@Composable () -> Unit)?, placeholder: (@Composable () -> Unit)?, singleLine: Boolean, minLines: Int, maxLines: Int, visualTransformation: VisualTransformation) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) CompositionLocalProvider(LocalContentColor provides ScanTheme.colorScheme.onSurfaceVariant) {
            ProvideTextStyle(ScanTheme.typography.bodySmall) { label() }
        }
        BasicTextField(value, onValueChange, Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(10.dp)).background(ScanTheme.colorScheme.surfaceVariant)
            .alpha(if (enabled) 1f else .4f).padding(horizontal = 12.dp, vertical = 11.dp),
            enabled = enabled, readOnly = readOnly, singleLine = singleLine, minLines = minLines, maxLines = maxLines,
            textStyle = ScanTheme.typography.bodyLarge.copy(color = ScanTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(ScanTheme.colorScheme.primary), visualTransformation = visualTransformation,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder != null) CompositionLocalProvider(LocalContentColor provides ScanTheme.colorScheme.onSurfaceVariant) {
                        ProvideTextStyle(ScanTheme.typography.bodyMedium) { placeholder() }
                    }
                    inner()
                }
            })
    }
}

// UIControl keeps weak targets; Compose owns these targets for the lifetime of the native view.
private class SwitchTarget(var changed: (Boolean) -> Unit) : NSObject() {
    @ObjCAction fun valueChanged(sender: UISwitch) { changed(sender.on) }
}
private class SliderTarget(var changed: (Float) -> Unit) : NSObject() {
    @ObjCAction fun valueChanged(sender: UISlider) { changed(sender.value) }
}
@Composable actual fun ScanSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier, enabled: Boolean, label: String?) {
    val callback by rememberUpdatedState(onCheckedChange)
    val target = remember { SwitchTarget { callback(it) } }
    var nativeSize by remember { mutableStateOf(DpSize(64.dp, 31.dp)) }
    val background = LocalScanControlBackground.current
    Box(modifier.sizeIn(minWidth = nativeSize.width, minHeight = 44.dp).semantics {
        role = Role.Switch; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        if (label != null) contentDescription = label
        if (!enabled) disabled() else onClick { callback(!checked); true }
    }, contentAlignment = Alignment.Center) {
        UIKitView(factory = { UISwitch().apply {
            intrinsicContentSize.useContents { nativeSize = DpSize(width.toFloat().dp, height.toFloat().dp) }
            addTarget(target, NSSelectorFromString("valueChanged:"), UIControlEventValueChanged)
            isAccessibilityElement = false
        } }, modifier = Modifier.size(nativeSize), update = {
            it.backgroundColor = background.native()
            it.enabled = enabled; if (it.on != checked) it.setOn(checked, animated = true)
        }, properties = UIKitInteropProperties(isNativeAccessibilityEnabled = false))
    }
}
@Composable actual fun ScanSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier, enabled: Boolean, valueRange: ClosedFloatingPointRange<Float>, steps: Int, label: String?) {
    require(steps >= 0)
    fun snap(input: Float): Float {
        val clamped = input.coerceIn(valueRange)
        val length = valueRange.endInclusive - valueRange.start
        return if (steps == 0 || length <= 0f) clamped else valueRange.start + ((clamped - valueRange.start) / length * (steps + 1)).roundToInt() * length / (steps + 1)
    }
    val callback by rememberUpdatedState<(Float) -> Unit> { onValueChange(snap(it)) }
    val target = remember { SliderTarget { callback(it) } }
    val blue = ScanTheme.colorScheme.primary
    val background = LocalScanControlBackground.current
    UIKitView(factory = { UISlider().apply {
        addTarget(target, NSSelectorFromString("valueChanged:"), UIControlEventValueChanged)
        isAccessibilityElement = false
    } }, modifier = modifier.fillMaxWidth().height(44.dp).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange, steps)
        if (label != null) contentDescription = label
        if (!enabled) disabled() else setProgress { callback(it); true }
    }, update = {
        it.minimumValue = valueRange.start; it.maximumValue = valueRange.endInclusive; it.enabled = enabled
        it.minimumTrackTintColor = blue.native()
        it.backgroundColor = background.native()
        if (kotlin.math.abs(it.value - value) > .0001f) it.setValue(value.coerceIn(valueRange), animated = false)
    }, properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative, isNativeAccessibilityEnabled = false))
}
@Composable actual fun ScanCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier, enabled: Boolean) {
    val interaction = if (onCheckedChange == null) Modifier else Modifier.toggleable(checked, remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Checkbox) { onCheckedChange(it) }
    Box(modifier.size(44.dp).then(interaction).semantics { role = Role.Checkbox; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (checked) ScanTheme.colorScheme.primary else Color.Transparent)
            .border(1.dp, if (checked) ScanTheme.colorScheme.primary else ScanTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
            if (checked) Text("✓", color = Color.White, fontSize = 17.sp)
        }
    }
}
@Composable actual fun ScanCard(modifier: Modifier, shape: Shape?, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(shape ?: RoundedCornerShape(12.dp)).background(ScanTheme.colorScheme.surface), content = content)
}
@Composable actual fun ScanDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier, dismissButton: (@Composable () -> Unit)?, icon: (@Composable () -> Unit)?, title: (@Composable () -> Unit)?, text: (@Composable () -> Unit)?) {
    Dialog(onDismissRequest) {
        Column(modifier.widthIn(max = 340.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ScanTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                icon?.invoke()
                ProvideTextStyle(ScanTheme.typography.titleMedium.copy(textAlign = TextAlign.Center)) { title?.invoke() }
                ProvideTextStyle(ScanTheme.typography.bodyMedium) { text?.invoke() }
            }
            AppleSeparator()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (dismissButton != null) { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { dismissButton() }; Box(Modifier.width(.5.dp).height(44.dp).background(ScanTheme.colorScheme.outline)) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { confirmButton() }
            }
        }
    }
}
@Composable private fun AppleSeparator() { Box(Modifier.fillMaxWidth().height(.5.dp).background(ScanTheme.colorScheme.outline)) }
@Composable actual fun ScanMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (expanded) Popup(alignment = Alignment.TopEnd, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        Column(Modifier.padding(top = 44.dp, end = 12.dp).width(240.dp).clip(RoundedCornerShape(14.dp))
            .background(ScanTheme.colorScheme.surface).border(.5.dp, ScanTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .verticalScroll(rememberScrollState()), content = content)
    }
}
@Composable actual fun ScanMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, enabled: Boolean) {
    Column {
        Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)) { ProvideTextStyle(ScanTheme.typography.bodyLarge) { text() } }
        AppleSeparator()
    }
}
@Composable actual fun ScanProgressIndicator(modifier: Modifier, color: Color) {
    val transition = rememberInfiniteTransition()
    val phase by transition.animateFloat(0f, 12f, infiniteRepeatable(tween(900, easing = LinearEasing)))
    Canvas(modifier.size(28.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val radius = size.minDimension / 2
        repeat(12) { index ->
            val angle = index * kotlin.math.PI / 6
            val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            val age = (index - phase.toInt() + 12) % 12
            drawLine(color.copy(alpha = color.alpha * (.15f + .85f * age / 11f)),
                center + direction * (radius * .5f), center + direction * (radius * .85f),
                strokeWidth = radius * .16f, cap = StrokeCap.Round)
        }
    }
}
@Composable actual fun ScanMessageHost(state: SnackbarHostState) {
    val accessibility = LocalAccessibilityManager.current
    state.currentSnackbarData?.let { data ->
        LaunchedEffect(data) {
            if (data.visuals.duration != SnackbarDuration.Indefinite) {
                val duration = if (data.visuals.duration == SnackbarDuration.Long) 10_000L else 4_000L
                val timeout = accessibility?.calculateRecommendedTimeoutMillis(duration, containsIcons = false, containsText = true, containsControls = data.visuals.actionLabel != null) ?: duration
                delay(timeout)
                data.dismiss()
            }
        }
        Box(Modifier.navigationBarsPadding().padding(16.dp).clip(RoundedCornerShape(14.dp))
            .background(ScanTheme.colorScheme.onSurface.copy(alpha = .92f)).padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(data.visuals.message, Modifier.weight(1f), color = ScanTheme.colorScheme.surface, style = ScanTheme.typography.bodyMedium)
                data.visuals.actionLabel?.let { label ->
                    ScanTextButton(onClick = data::performAction) { Text(label, color = ScanTheme.colorScheme.surface) }
                }
            }
        }
    }
}
@Composable actual fun ScanTopBar(title: String, onBack: (() -> Unit)?, contentColor: Color, actions: @Composable RowScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().height(44.dp)) {
        // Equal side reserves keep the title truly centered even when actions have different widths.
        Text(title, modifier = Modifier.align(Alignment.Center).padding(horizontal = 100.dp), color = contentColor,
            style = ScanTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.align(Alignment.CenterStart)) {
            if (onBack != null) ScanIconButton(onBack) { ScanIcon(ScanSymbol.Back, "返回", tint = if (contentColor == ScanTheme.colorScheme.onSurface) ScanTheme.colorScheme.primary else contentColor) }
        }
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}
@Composable actual fun ScanLibraryHeader(actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = actions)
        Text("离线扫描王", style = ScanTheme.typography.headlineLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
    }
}
@Composable actual fun ScanLibraryActions(onImport: () -> Unit, onScan: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().background(ScanTheme.colorScheme.surface)) {
        AppleSeparator()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ScanTextButton(onImport) { ScanIcon(ScanSymbol.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("导入") }
            ScanTextButton(onScan) { ScanIcon(ScanSymbol.Scan, null); Spacer(Modifier.width(8.dp)); Text("扫描文稿", fontWeight = FontWeight.SemiBold) }
        }
    }
}
@Composable actual fun ScanSegmentedControl(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val dark = isSystemInDarkTheme()
    val traits = UITraitCollection.traitCollectionWithUserInterfaceStyle(
        if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
    )
    // Grouped surface and translucent fill resolve to nearly the same dark gray.
    // Use the system gray selected segment so selection stays visible in dark mode.
    val selectedBackground = if (dark) UIColor.systemGray2Color.compose(traits) else ScanTheme.colorScheme.surface
    Row(modifier.height(36.dp).clip(RoundedCornerShape(9.dp)).background(ScanTheme.colorScheme.surfaceVariant).padding(2.dp)) {
        labels.forEachIndexed { index, label ->
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                .background(if (selectedIndex == index) selectedBackground else Color.Transparent)
                .selectable(selectedIndex == index, remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center) { Text(label, style = ScanTheme.typography.bodySmall, fontWeight = if (selectedIndex == index) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}
@Composable actual fun ScanSection(title: String, footer: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, modifier = Modifier.padding(horizontal = 16.dp), style = ScanTheme.typography.bodySmall, color = ScanTheme.colorScheme.onSurfaceVariant)
        CompositionLocalProvider(LocalScanControlBackground provides ScanTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ScanTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
        if (footer != null) Text(footer, modifier = Modifier.padding(horizontal = 16.dp), style = ScanTheme.typography.bodySmall, color = ScanTheme.colorScheme.onSurfaceVariant)
    }
}
