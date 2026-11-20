package com.example.mobileplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mobileplay.engine.ButtonShape
import com.example.mobileplay.engine.ControlButtonSpec
import com.example.mobileplay.engine.EditorTarget
import com.example.mobileplay.engine.LayoutEditorState
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.CharcoalSurfaceHigh
import com.example.ui.theme.MutedCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateMutedText
import com.example.ui.theme.SoftIceText
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.TacticalCoral
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Interactive visual touchscreen control layout editor.
 * Completely separated from gameplay input so dragging controls never triggers PS5 commands.
 */
@Composable
fun LayoutEditorScreen(
    editorState: LayoutEditorState,
    onUpdateEditor: ((LayoutEditorState) -> LayoutEditorState) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit
) {
    BackHandler(onBack = onDiscard)

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var dragStartSnapshot by remember { mutableStateOf(editorState.draft) }
    var activeDragTarget by remember { mutableStateOf<EditorTarget>(EditorTarget.None) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .testTag("layout_editor_screen")
    ) {
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val shortEdge = min(widthPx, heightPx)
        val draft = editorState.draft

        // Interactive Full-Screen Layout Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(draft.id, editorState.previewMode, editorState.snapToGrid) {
                    if (editorState.previewMode) return@pointerInput
                    detectTapGestures { offset ->
                        val hit = hitTestEditorTarget(offset, widthPx, heightPx, shortEdge, editorState)
                        onUpdateEditor { it.select(hit) }
                    }
                }
                .pointerInput(draft.id, editorState.previewMode, editorState.snapToGrid) {
                    if (editorState.previewMode) return@pointerInput
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            dragStartSnapshot = editorState.draft
                            val hit = hitTestEditorTarget(startOffset, widthPx, heightPx, shortEdge, editorState)
                            activeDragTarget = hit
                            onUpdateEditor { it.select(hit) }
                        },
                        onDragEnd = {
                            onUpdateEditor { it.commitCheckpoint(dragStartSnapshot) }
                            activeDragTarget = EditorTarget.None
                        },
                        onDragCancel = {
                            activeDragTarget = EditorTarget.None
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val dxNorm = dragAmount.x / widthPx
                            val dyNorm = dragAmount.y / heightPx
                            when (val target = activeDragTarget) {
                                is EditorTarget.Button -> onUpdateEditor { state ->
                                    val btn = state.draft.buttons.firstOrNull { it.id == target.buttonId } ?: return@onUpdateEditor state
                                    state.moveButton(
                                        buttonId = btn.id,
                                        newCenterX = btn.centerX + dxNorm,
                                        newCenterY = btn.centerY + dyNorm,
                                        recordUndo = false
                                    )
                                }
                                EditorTarget.Joystick -> onUpdateEditor { state ->
                                    state.moveJoystick(
                                        newCenterX = state.draft.joystickCenterX + dxNorm,
                                        newCenterY = state.draft.joystickCenterY + dyNorm,
                                        recordUndo = false
                                    )
                                }
                                EditorTarget.CameraRegion -> onUpdateEditor { state ->
                                    state.updateCameraRegion(
                                        startX = state.draft.cameraRegionStartX + dxNorm,
                                        recordUndo = false
                                    )
                                }
                                EditorTarget.None -> Unit
                            }
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val short = min(w, h)

            // 1. Safe-zone grid (hidden in Preview mode)
            if (!editorState.previewMode && editorState.showGrid) {
                val cols = 16
                val rows = 9
                for (c in 1 until cols) {
                    val x = w * (c / cols.toFloat())
                    drawLine(CharcoalBorder.copy(alpha = 0.32f), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                }
                for (r in 1 until rows) {
                    val y = h * (r / rows.toFloat())
                    drawLine(CharcoalBorder.copy(alpha = 0.32f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }
            }

            // 2. Display Cutout / Safe-Area Preview Border
            if (!editorState.previewMode && editorState.showSafeArea) {
                val padX = w * 0.045f
                val padY = h * 0.055f
                drawRect(
                    color = StatusAmber.copy(alpha = 0.45f),
                    topLeft = Offset(padX, padY),
                    size = Size(w - padX * 2f, h - padY * 2f),
                    style = Stroke(
                        width = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    )
                )
            }

            // 3. Camera Swipe Region Overlay
            val camLeft = w * draft.cameraRegionStartX
            val camTop = h * draft.cameraRegionTopY
            val camBottom = h * draft.cameraRegionBottomY
            val camSelected = !editorState.previewMode && editorState.selectedTarget == EditorTarget.CameraRegion
            drawRect(
                color = MutedCyan.copy(alpha = if (camSelected) 0.14f else if (editorState.previewMode) 0.02f else 0.05f),
                topLeft = Offset(camLeft, camTop),
                size = Size(w - camLeft, camBottom - camTop)
            )
            if (!editorState.previewMode) {
                drawRect(
                    color = if (camSelected) MutedCyan else CharcoalBorder,
                    topLeft = Offset(camLeft, camTop),
                    size = Size(w - camLeft, camBottom - camTop),
                    style = Stroke(width = if (camSelected) 3.5f else 1.5f)
                )
            }

            // 4. Joystick / Analog Steer Anchor
            val joyX = w * draft.joystickCenterX
            val joyY = h * draft.joystickCenterY
            val joyR = short * draft.joystickRadiusFraction
            val joySelected = !editorState.previewMode && editorState.selectedTarget == EditorTarget.Joystick
            val masterAlpha = draft.controlOpacity
            drawCircle(
                color = CharcoalSurface.copy(alpha = 0.40f * masterAlpha),
                radius = joyR,
                center = Offset(joyX, joyY)
            )
            drawCircle(
                color = if (joySelected) StatusEmerald else MutedCyan.copy(alpha = 0.7f * masterAlpha),
                radius = joyR,
                center = Offset(joyX, joyY),
                style = Stroke(width = if (joySelected) 4.5f else 2.5f)
            )
            drawCircle(
                color = MutedCyan.copy(alpha = 0.48f * masterAlpha),
                radius = joyR * 0.36f,
                center = Offset(joyX, joyY)
            )

            // 5. All Editable Buttons
            for (btn in draft.buttons) {
                val isSelected = !editorState.previewMode &&
                    (editorState.selectedTarget as? EditorTarget.Button)?.buttonId == btn.id
                drawEditorButton(
                    spec = btn,
                    w = w,
                    h = h,
                    short = short,
                    alpha = (masterAlpha * btn.opacity).coerceIn(0.18f, 1.0f),
                    selected = isSelected,
                    textMeasurer = textMeasurer
                )
            }
        }

        // Top Toolbar (minimal exit button in Preview mode, full controls in Edit mode)
        if (editorState.previewMode) {
            Button(
                onClick = { onUpdateEditor { it.copy(previewMode = false) } },
                colors = ButtonDefaults.buttonColors(containerColor = MutedCyan, contentColor = ObsidianBg),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("editor_exit_preview_button")
            ) {
                Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Exit Preview")
            }
        } else {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = CharcoalSurface.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, CharcoalBorder),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onDiscard,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("editor_discard_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel editing", tint = SoftIceText)
                    }
                    Text(
                        text = draft.name.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MutedCyan,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    IconButton(
                        onClick = { onUpdateEditor { it.undo() } },
                        enabled = editorState.canUndo,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("editor_undo_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            tint = if (editorState.canUndo) SoftIceText else SlateMutedText.copy(alpha = 0.4f)
                        )
                    }
                    IconButton(
                        onClick = { onUpdateEditor { it.redo() } },
                        enabled = editorState.canRedo,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("editor_redo_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo",
                            tint = if (editorState.canRedo) SoftIceText else SlateMutedText.copy(alpha = 0.4f)
                        )
                    }
                    IconButton(
                        onClick = { onUpdateEditor { it.copy(showGrid = !it.showGrid, snapToGrid = !it.snapToGrid) } },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("editor_grid_button")
                    ) {
                        Icon(
                            Icons.Default.GridOn,
                            contentDescription = "Toggle grid & snap",
                            tint = if (editorState.showGrid) MutedCyan else SlateMutedText
                        )
                    }
                    IconButton(
                        onClick = { onUpdateEditor { it.copy(previewMode = true) } },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("editor_preview_button")
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = "Preview layout", tint = SoftIceText)
                    }
                    IconButton(
                        onClick = { onUpdateEditor { it.resetLayoutToPresetDefault() } },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("editor_reset_button")
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Reset layout", tint = TacticalCoral)
                    }
                    Button(
                        onClick = onSave,
                        colors = ButtonDefaults.buttonColors(containerColor = MutedCyan, contentColor = ObsidianBg),
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("editor_save_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save")
                    }
                }
            }

            // Bottom Selected-Element Inspector Bar
            SelectedControlInspector(
                editorState = editorState,
                onUpdateEditor = onUpdateEditor,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun SelectedControlInspector(
    editorState: LayoutEditorState,
    onUpdateEditor: ((LayoutEditorState) -> LayoutEditorState) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CharcoalSurface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, CharcoalBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        when (editorState.selectedTarget) {
            EditorTarget.None -> {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tap or drag any button, joystick, or camera zone to customize. Safe-area dashed border marks display cutouts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateMutedText
                    )
                }
            }

            is EditorTarget.Button -> {
                val btn = editorState.selectedButton ?: return@Surface
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${btn.label} (${btn.owner.name})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MutedCyan
                        )
                        // Size slider
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Size", style = MaterialTheme.typography.labelSmall, color = SlateMutedText)
                            Slider(
                                value = btn.radiusShortFraction,
                                onValueChange = { v -> onUpdateEditor { it.resizeSelected(v, recordUndo = false) } },
                                valueRange = 0.045f..0.155f,
                                colors = SliderDefaults.colors(thumbColor = MutedCyan, activeTrackColor = MutedCyan),
                                modifier = Modifier.weight(1f).height(28.dp)
                            )
                        }
                        // Opacity slider
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Opacity", style = MaterialTheme.typography.labelSmall, color = SlateMutedText)
                            Slider(
                                value = btn.opacity,
                                onValueChange = { v -> onUpdateEditor { it.setSelectedOpacity(v, recordUndo = false) } },
                                valueRange = 0.25f..1.0f,
                                colors = SliderDefaults.colors(thumbColor = MutedCyan, activeTrackColor = MutedCyan),
                                modifier = Modifier.weight(1f).height(28.dp)
                            )
                        }
                    }

                    // Shape chips + DualSense Action Rebinding Strip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Shape:", style = MaterialTheme.typography.labelSmall, color = SlateMutedText)
                        ButtonShape.entries.forEach { shape ->
                            FilterChip(
                                selected = btn.effectiveShape == shape,
                                onClick = { onUpdateEditor { it.setSelectedShape(shape) } },
                                label = { Text(shape.label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MutedCyan.copy(alpha = 0.24f),
                                    selectedLabelColor = SoftIceText
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Bind:", style = MaterialTheme.typography.labelSmall, color = SlateMutedText)
                        ControlButtonSpec.reassignableOwners.forEach { owner ->
                            val (lbl, sub) = ControlButtonSpec.defaultLabelsFor(owner)
                            FilterChip(
                                selected = btn.owner == owner,
                                onClick = { onUpdateEditor { it.reassignSelectedAction(owner) } },
                                label = {
                                    Text(
                                        text = if (sub.isNotEmpty()) "$lbl ($sub)" else lbl,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = StatusEmerald.copy(alpha = 0.24f),
                                    selectedLabelColor = SoftIceText
                                )
                            )
                        }
                    }
                }
            }

            EditorTarget.Joystick -> {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "LEFT JOYSTICK  (${(editorState.draft.joystickCenterX * 100).roundToInt()}%, ${(editorState.draft.joystickCenterY * 100).roundToInt()}%)",
                        style = MaterialTheme.typography.labelLarge,
                        color = StatusEmerald
                    )
                    Text("Radius", style = MaterialTheme.typography.labelSmall, color = SlateMutedText)
                    Slider(
                        value = editorState.draft.joystickRadiusFraction,
                        onValueChange = { v -> onUpdateEditor { it.resizeSelected(v, recordUndo = false) } },
                        valueRange = 0.08f..0.24f,
                        colors = SliderDefaults.colors(thumbColor = StatusEmerald, activeTrackColor = StatusEmerald),
                        modifier = Modifier.weight(1f).height(30.dp)
                    )
                }
            }

            EditorTarget.CameraRegion -> {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "CAMERA SWIPE REGION",
                        style = MaterialTheme.typography.labelLarge,
                        color = MutedCyan
                    )
                    Text(
                        text = "Split ${(editorState.draft.cameraRegionStartX * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateMutedText
                    )
                    Slider(
                        value = editorState.draft.cameraRegionStartX,
                        onValueChange = { v -> onUpdateEditor { it.updateCameraRegion(startX = v, recordUndo = false) } },
                        valueRange = 0.30f..0.60f,
                        colors = SliderDefaults.colors(thumbColor = MutedCyan, activeTrackColor = MutedCyan),
                        modifier = Modifier.weight(1f).height(30.dp)
                    )
                    Text(
                        text = "Top ${(editorState.draft.cameraRegionTopY * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateMutedText
                    )
                    Slider(
                        value = editorState.draft.cameraRegionTopY,
                        onValueChange = { v -> onUpdateEditor { it.updateCameraRegion(topY = v, recordUndo = false) } },
                        valueRange = 0.04f..0.40f,
                        colors = SliderDefaults.colors(thumbColor = MutedCyan, activeTrackColor = MutedCyan),
                        modifier = Modifier.weight(0.8f).height(30.dp)
                    )
                }
            }
        }
    }
}

private fun hitTestEditorTarget(
    offset: Offset,
    w: Float,
    h: Float,
    short: Float,
    state: LayoutEditorState
): EditorTarget {
    // 1. Check buttons first
    for (btn in state.draft.buttons.asReversed()) {
        val cx = w * btn.centerX
        val cy = h * btn.centerY
        val radius = (short * btn.radiusShortFraction).coerceAtLeast(36f)
        val hit = if (btn.isRect && btn.halfWidthFraction > 0f && btn.halfHeightFraction > 0f) {
            abs(offset.x - cx) <= w * btn.halfWidthFraction && abs(offset.y - cy) <= h * btn.halfHeightFraction
        } else {
            hypot(offset.x - cx, offset.y - cy) <= radius * 1.15f
        }
        if (hit) return EditorTarget.Button(btn.id)
    }

    // 2. Check joystick
    val jx = w * state.draft.joystickCenterX
    val jy = h * state.draft.joystickCenterY
    val jr = short * state.draft.joystickRadiusFraction
    if (hypot(offset.x - jx, offset.y - jy) <= jr * 1.1f) {
        return EditorTarget.Joystick
    }

    // 3. Check camera region
    if (offset.x >= w * state.draft.cameraRegionStartX) {
        return EditorTarget.CameraRegion
    }

    return EditorTarget.None
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEditorButton(
    spec: ControlButtonSpec,
    w: Float,
    h: Float,
    short: Float,
    alpha: Float,
    selected: Boolean,
    textMeasurer: TextMeasurer
) {
    val cx = w * spec.centerX
    val cy = h * spec.centerY
    val radius = short * spec.radiusShortFraction
    val accent = if (spec.isAccent) TacticalCoral else MutedCyan
    val fill = CharcoalSurfaceHigh.copy(alpha = 0.65f * alpha)
    val stroke = if (selected) StatusEmerald else accent.copy(alpha = 0.85f * alpha)
    val strokeW = if (selected) 4.5f else 2.2f

    if (spec.isRect && spec.halfWidthFraction > 0f && spec.halfHeightFraction > 0f) {
        val hw = w * spec.halfWidthFraction
        val hh = h * spec.halfHeightFraction
        val corner = if (spec.effectiveShape == ButtonShape.PILL) hh else 14f
        drawRoundRect(fill, Offset(cx - hw, cy - hh), Size(hw * 2f, hh * 2f), CornerRadius(corner, corner))
        drawRoundRect(stroke, Offset(cx - hw, cy - hh), Size(hw * 2f, hh * 2f), CornerRadius(corner, corner), style = Stroke(strokeW))
    } else {
        when (spec.effectiveShape) {
            ButtonShape.CIRCLE -> {
                drawCircle(fill, radius, Offset(cx, cy))
                drawCircle(stroke, radius, Offset(cx, cy), style = Stroke(strokeW))
            }
            ButtonShape.ROUNDED_RECT -> {
                val c = radius * 0.34f
                drawRoundRect(fill, Offset(cx - radius, cy - radius), Size(radius * 2f, radius * 2f), CornerRadius(c, c))
                drawRoundRect(stroke, Offset(cx - radius, cy - radius), Size(radius * 2f, radius * 2f), CornerRadius(c, c), style = Stroke(strokeW))
            }
            ButtonShape.PILL -> {
                val hw = radius * 1.25f
                val hh = radius * 0.78f
                drawRoundRect(fill, Offset(cx - hw, cy - hh), Size(hw * 2f, hh * 2f), CornerRadius(hh, hh))
                drawRoundRect(stroke, Offset(cx - hw, cy - hh), Size(hw * 2f, hh * 2f), CornerRadius(hh, hh), style = Stroke(strokeW))
            }
            ButtonShape.OCTAGON -> {
                val path = Path()
                for (i in 0 until 8) {
                    val angle = Math.toRadians(22.5 + i * 45.0)
                    val px = cx + (cos(angle) * radius).toFloat()
                    val py = cy + (sin(angle) * radius).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                drawPath(path, fill)
                drawPath(path, stroke, style = Stroke(strokeW))
            }
        }
    }

    val measured = textMeasurer.measure(
        text = spec.label,
        style = androidx.compose.ui.text.TextStyle(
            color = SoftIceText.copy(alpha = alpha.coerceAtLeast(0.4f)),
            fontSize = 11.sp
        )
    )
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(cx - measured.size.width / 2f, cy - measured.size.height / 2f)
    )
}
