package com.example.mobileplay.ui

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.mobileplay.engine.AimTuning
import com.example.mobileplay.engine.ControlProfile
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
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun ControlsScreen(
    uiState: MobilePlayUiState,
    onSelectProfile: (String) -> Unit,
    onOpenLayoutEditor: () -> Unit,
    onDuplicateProfile: (String) -> Unit,
    onRenameProfile: (String) -> Unit,
    onDeleteCustomProfile: (String) -> Unit,
    onUpdateProfile: ((ControlProfile) -> ControlProfile) -> Unit,
    onUpdateTuning: ((AimTuning) -> AimTuning) -> Unit,
    onResetProfile: () -> Unit,
    onExportJson: () -> String,
    onImportJson: (String) -> Unit
) {
    var showJsonDialog by remember { mutableStateOf(false) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var duplicateName by rememberSaveable { mutableStateOf("") }
    var jsonText by remember { mutableStateOf("") }
    val context = LocalContext.current

    val profile = uiState.activeProfile
    val tuning = profile.tuning

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + Primary Visual Layout Editor CTA
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Controls",
                    style = MaterialTheme.typography.titleMedium,
                    color = SoftIceText
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenLayoutEditor,
                    colors = ButtonDefaults.buttonColors(containerColor = MutedCyan, contentColor = ObsidianBg),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("controls_open_layout_editor")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Edit Layout")
                }

                OutlinedButton(
                    onClick = {
                        jsonText = onExportJson()
                        showJsonDialog = true
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("controls_json_button")
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = MutedCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import / Export")
                }

                OutlinedButton(
                    onClick = onResetProfile,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("controls_reset_button")
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, tint = TacticalCoral, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset")
                }
            }
        }

        uiState.profileBannerMessage?.let { banner ->
            Text(text = banner, style = MaterialTheme.typography.labelMedium, color = StatusEmerald)
        }

        // Two-Column Landscape Layout
        Row(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Left Column: Game Profiles (4 Presets + Custom) + Response Curve + Invert/Hold/Toggle
            Column(
                modifier = Modifier
                    .weight(0.98f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, CharcoalBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("GAME PROFILES (${uiState.profiles.size})", style = MaterialTheme.typography.labelMedium, color = SlateMutedText)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = {
                                        duplicateName = "${profile.name} Custom"
                                        renaming = false
                                        showDuplicateDialog = true
                                    },
                                    modifier = Modifier.minimumInteractiveComponentSize().testTag("duplicate_profile_button")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate profile", tint = MutedCyan, modifier = Modifier.size(18.dp))
                                }
                                if (!profile.isBuiltIn) {
                                    IconButton(onClick = {
                                        duplicateName = profile.name
                                        renaming = true
                                        showDuplicateDialog = true
                                    }) {
                                        Icon(Icons.Default.DriveFileRenameOutline, "Rename profile")
                                    }
                                    IconButton(
                                        onClick = { confirmDelete = true },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete custom profile", tint = TacticalCoral, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.profiles.forEach { item ->
                                FilterChip(
                                    selected = item.id == profile.id,
                                    onClick = { onSelectProfile(item.id) },
                                    label = { Text(item.name) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MutedCyan.copy(alpha = 0.22f),
                                        selectedLabelColor = SoftIceText
                                    ),
                                    modifier = Modifier.testTag("profile_chip_${item.id}")
                                )
                            }
                        }
                    }
                }

                // Live Touch Velocity -> Virtual Stick Output Graph
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, CharcoalBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("TOUCH VELOCITY → STICK OUTPUT", style = MaterialTheme.typography.labelMedium, color = SlateMutedText)
                            Text("Cyan: Hip  •  Amber: ADS", style = MaterialTheme.typography.labelSmall, color = MutedCyan)
                        }
                        AimCurveGraph(
                            tuning = tuning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(126.dp)
                        )
                    }
                }

                // Behaviors: Invert X/Y, Fire-Drag Aim, Hold/Toggle
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, CharcoalBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("AIM & TRIGGER BEHAVIORS", style = MaterialTheme.typography.labelMedium, color = SlateMutedText)
                        BehaviorToggleRow(
                            title = "Fire-Button Drag Aiming",
                            subtitle = "Drag while firing to track targets and counter recoil",
                            checked = tuning.fireDragAimEnabled,
                            testTag = "toggle_fire_drag_aim",
                            onCheckedChange = { onUpdateTuning { t -> t.copy(fireDragAimEnabled = it) } }
                        )
                        BehaviorToggleRow(
                            title = "Invert Horizontal Look (X)",
                            subtitle = "Reverses virtual right-stick horizontal direction",
                            checked = tuning.invertX,
                            testTag = "toggle_invert_x",
                            onCheckedChange = { onUpdateTuning { t -> t.copy(invertX = it) } }
                        )
                        BehaviorToggleRow(
                            title = "Invert Vertical Look (Y)",
                            subtitle = "Reverses virtual right-stick vertical pitch",
                            checked = tuning.invertY,
                            testTag = "toggle_invert_y",
                            onCheckedChange = { onUpdateTuning { t -> t.copy(invertY = it) } }
                        )
                        BehaviorToggleRow(
                            title = "ADS Toggle Mode (L2)",
                            subtitle = if (tuning.adsToggleMode) "Tap to toggle ADS" else "Hold to ADS",
                            checked = tuning.adsToggleMode,
                            testTag = "toggle_ads_mode",
                            onCheckedChange = { onUpdateTuning { t -> t.copy(adsToggleMode = it) } }
                        )
                        BehaviorToggleRow(
                            title = "Sprint Toggle Mode (L3)",
                            subtitle = if (tuning.sprintToggleMode) "Tap to latch Sprint" else "Hold to Sprint",
                            checked = tuning.sprintToggleMode,
                            testTag = "toggle_sprint_mode",
                            onCheckedChange = { onUpdateTuning { t -> t.copy(sprintToggleMode = it) } }
                        )
                        BehaviorToggleRow(
                            title = "Crouch Toggle Mode (○)",
                            subtitle = if (tuning.crouchToggleMode) "Tap to latch Crouch" else "Hold to Crouch",
                            checked = tuning.crouchToggleMode,
                            testTag = "toggle_crouch_mode",
                            onCheckedChange = { onUpdateTuning { t -> t.copy(crouchToggleMode = it) } }
                        )
                    }
                }
            }

            // Right Column: Precision Aiming & Region Sliders
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CharcoalSurface,
                border = BorderStroke(1.dp, CharcoalBorder),
                modifier = Modifier
                    .weight(1.02f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CalibrationSlider(
                        label = "Master Control Opacity",
                        valueText = "${(profile.controlOpacity * 100f).roundToInt()}%",
                        value = profile.controlOpacity,
                        range = 0.20f..1.0f,
                        onValueChange = { v -> onUpdateProfile { it.copy(controlOpacity = v) } }
                    )
                    CalibrationSlider(
                        label = "Camera Swipe Region Width",
                        valueText = "${((1f - profile.cameraRegionStartX) * 100f).roundToInt()}% of screen",
                        value = 1f - profile.cameraRegionStartX,
                        range = 0.40f..0.70f,
                        onValueChange = { widthFrac ->
                            val startX = (1f - widthFrac).coerceIn(0.30f, 0.60f)
                            onUpdateProfile { it.copy(cameraRegionStartX = startX, movementSplitFraction = startX) }
                        }
                    )
                    CalibrationSlider(
                        label = "Horizontal Sensitivity",
                        valueText = "%.2fx".format(tuning.horizontalSensitivity),
                        value = tuning.horizontalSensitivity,
                        range = 0.25f..3.0f,
                        onValueChange = { v -> onUpdateTuning { it.copy(horizontalSensitivity = v) } }
                    )
                    CalibrationSlider(
                        label = "Vertical Sensitivity",
                        valueText = "%.2fx".format(tuning.verticalSensitivity),
                        value = tuning.verticalSensitivity,
                        range = 0.25f..3.0f,
                        onValueChange = { v -> onUpdateTuning { it.copy(verticalSensitivity = v) } }
                    )
                    CalibrationSlider(
                        label = "ADS Horizontal",
                        valueText = "%.2fx".format(tuning.adsSensitivityMultiplier),
                        value = tuning.adsSensitivityMultiplier,
                        range = 0.20f..1.25f,
                        onValueChange = { v -> onUpdateTuning { it.copy(adsSensitivityMultiplier = v) } }
                    )
                    CalibrationSlider(
                        label = "ADS Vertical",
                        valueText = "%.2fx".format(tuning.verticalAdsMultiplier),
                        value = tuning.verticalAdsMultiplier,
                        range = 0.1f..1.5f,
                        onValueChange = { v -> onUpdateTuning { it.copy(adsVerticalSensitivityMultiplier = v) } }
                    )
                    CalibrationSlider(
                        label = "Swipe Boost",
                        valueText = "%.1fx".format(tuning.swipeBoost),
                        value = tuning.swipeBoost,
                        range = 1f..8f,
                        onValueChange = { v -> onUpdateTuning { it.copy(swipeBoost = v) } }
                    )
                    CalibrationSlider(
                        label = "Anti-Deadzone",
                        valueText = "%.2f".format(tuning.antiDeadzone),
                        value = tuning.antiDeadzone,
                        range = 0f..0.40f,
                        onValueChange = { v -> onUpdateTuning { it.copy(antiDeadzone = v) } }
                    )
                    CalibrationSlider(
                        label = "Response Curve Exponent",
                        valueText = "%.2f".format(tuning.curve),
                        value = tuning.curve,
                        range = 0.40f..3.0f,
                        onValueChange = { v -> onUpdateTuning { it.copy(curve = v) } }
                    )
                    CalibrationSlider(
                        label = "Smoothing Strength (0 = Off)",
                        valueText = "${tuning.smoothingTimeMs.roundToInt()} ms",
                        value = tuning.smoothingTimeMs,
                        range = 0f..45f,
                        onValueChange = { v -> onUpdateTuning { it.copy(smoothingTimeMs = v) } }
                    )
                    CalibrationSlider(
                        label = "Full-Stick Swipe Speed",
                        valueText = "${(tuning.speedForFullStickMmPerSec / tuning.swipeBoost).roundToInt()} mm/s at 1× axis sensitivity",
                        value = tuning.speedForFullStickMmPerSec,
                        range = 120f..750f,
                        onValueChange = { v -> onUpdateTuning { it.copy(speedForFullStickMmPerSec = v) } }
                    )
                    CalibrationSlider(
                        label = "Zero-Drift Stop Watchdog",
                        valueText = "${tuning.stopAfterMs} ms",
                        value = tuning.stopAfterMs.toFloat(),
                        range = 10f..120f,
                        onValueChange = { v -> onUpdateTuning { it.copy(stopAfterMs = v.roundToLong().coerceIn(10L, 120L)) } }
                    )
                }
            }
        }
    }

    if (showDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { showDuplicateDialog = false },
            containerColor = CharcoalSurface,
            title = { Text(if (renaming) "Rename Profile" else "Duplicate Profile", color = SoftIceText) },
            text = {
                OutlinedTextField(
                    value = duplicateName,
                    onValueChange = { duplicateName = it.take(60) },
                    label = { Text("Profile name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renaming) onRenameProfile(duplicateName) else onDuplicateProfile(duplicateName)
                        showDuplicateDialog = false
                    },
                    enabled = duplicateName.isNotBlank()
                ) {
                    Text(if (renaming) "Rename" else "Create", color = MutedCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDuplicateDialog = false }) {
                    Text("Cancel", color = SlateMutedText)
                }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${profile.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteCustomProfile(profile.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    if (showJsonDialog) {
        AlertDialog(
            onDismissRequest = { showJsonDialog = false },
            containerColor = CharcoalSurface,
            title = { Text("Export / Import Layout JSON", color = MutedCyan) },
            text = {
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = { jsonText = it },
                    textStyle = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onImportJson(jsonText)
                        showJsonDialog = false
                    }
                ) {
                    Text("Import as New Profile", color = MutedCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, onExportJson())
                    context.startActivity(Intent.createChooser(share, "Share MobilePlay profile"))
                }) { Text("Share Profile") }
            }
        )
    }
}

@Composable
private fun AimCurveGraph(
    tuning: AimTuning,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .background(CharcoalSurfaceHigh, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        for (i in 0..4) {
            val y = h * (i / 4f)
            val x = w * (i / 4f)
            drawLine(CharcoalBorder, Offset(0f, y), Offset(w, y), strokeWidth = 1.5f)
            drawLine(CharcoalBorder, Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f)
        }

        val dzY = h * (1f - tuning.antiDeadzone)
        drawLine(TacticalCoral.copy(alpha = 0.55f), Offset(0f, dzY), Offset(w, dzY), strokeWidth = 2f)

        val hipPath = Path()
        val adsPath = Path()
        val steps = 48
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val hipOut = tuning.evaluateMagnitude(t, isAds = false)
            val adsOut = tuning.evaluateMagnitude(t, isAds = true)
            val x = t * w
            val hipY = h * (1f - hipOut)
            val adsY = h * (1f - adsOut)
            if (i == 0) {
                hipPath.moveTo(x, hipY)
                adsPath.moveTo(x, adsY)
            } else {
                hipPath.lineTo(x, hipY)
                adsPath.lineTo(x, adsY)
            }
        }

        drawPath(adsPath, color = StatusAmber, style = Stroke(width = 3.5f, cap = StrokeCap.Round))
        drawPath(hipPath, color = MutedCyan, style = Stroke(width = 4.5f, cap = StrokeCap.Round))
    }
}

@Composable
private fun CalibrationSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = SoftIceText)
            Text(text = valueText, style = MaterialTheme.typography.labelMedium, color = MutedCyan)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MutedCyan,
                activeTrackColor = MutedCyan,
                inactiveTrackColor = CharcoalBorder
            )
        )
    }
}

@Composable
private fun BehaviorToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodySmall, color = SoftIceText)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = SlateMutedText)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
