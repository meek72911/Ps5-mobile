package com.example.mobileplay.engine

import kotlin.math.roundToInt

sealed interface EditorTarget {
    data object None : EditorTarget
    data class Button(val buttonId: String) : EditorTarget
    data object Joystick : EditorTarget
    data object CameraRegion : EditorTarget
}

/**
 * Pure Kotlin immutable state reducer for the visual touchscreen control layout editor.
 * Keeps editing mode 100% separate from gameplay TouchInputEngine and enables deterministic undo/redo tests.
 */
data class LayoutEditorState(
    val draft: ControlProfile,
    val selectedTarget: EditorTarget = EditorTarget.None,
    val undoStack: List<ControlProfile> = emptyList(),
    val redoStack: List<ControlProfile> = emptyList(),
    val showGrid: Boolean = true,
    val snapToGrid: Boolean = false,
    val showSafeArea: Boolean = true,
    val previewMode: Boolean = false
) {
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val selectedButton: ControlButtonSpec?
        get() = (selectedTarget as? EditorTarget.Button)?.let { target ->
            draft.buttons.firstOrNull { it.id == target.buttonId }
        }

    fun select(target: EditorTarget): LayoutEditorState = copy(selectedTarget = target)

    fun commitCheckpoint(previousDraft: ControlProfile): LayoutEditorState {
        if (previousDraft == draft) return this
        val nextUndo = (undoStack + previousDraft).takeLast(40)
        return copy(undoStack = nextUndo, redoStack = emptyList())
    }

    fun applyMutation(
        recordUndo: Boolean = true,
        transform: (ControlProfile) -> ControlProfile
    ): LayoutEditorState {
        val updated = transform(draft)
        if (updated == draft) return this
        return if (recordUndo) {
            copy(
                draft = updated,
                undoStack = (undoStack + draft).takeLast(40),
                redoStack = emptyList()
            )
        } else {
            copy(draft = updated)
        }
    }

    fun moveButton(
        buttonId: String,
        newCenterX: Float,
        newCenterY: Float,
        recordUndo: Boolean = false
    ): LayoutEditorState {
        val x = maybeSnap(newCenterX.coerceIn(0.05f, 0.95f))
        val y = maybeSnap(newCenterY.coerceIn(0.08f, 0.94f))
        return applyMutation(recordUndo = recordUndo) { profile ->
            profile.copy(
                buttons = profile.buttons.map { btn ->
                    if (btn.id == buttonId) btn.copy(centerX = x, centerY = y) else btn
                }
            )
        }
    }

    fun moveJoystick(
        newCenterX: Float,
        newCenterY: Float,
        recordUndo: Boolean = false
    ): LayoutEditorState {
        val x = maybeSnap(newCenterX.coerceIn(0.08f, 0.45f))
        val y = maybeSnap(newCenterY.coerceIn(0.22f, 0.86f))
        return applyMutation(recordUndo = recordUndo) { profile ->
            profile.copy(joystickCenterX = x, joystickCenterY = y)
        }
    }

    fun resizeSelected(fraction: Float, recordUndo: Boolean = true): LayoutEditorState =
        when (val target = selectedTarget) {
            is EditorTarget.Button -> applyMutation(recordUndo) { profile ->
                val clamped = fraction.coerceIn(0.045f, 0.16f)
                profile.copy(
                    buttons = profile.buttons.map { btn ->
                        if (btn.id == target.buttonId) {
                            if (btn.isRect) {
                                btn.copy(
                                    radiusShortFraction = clamped,
                                    halfWidthFraction = (clamped * 1.35f).coerceIn(0.03f, 0.16f),
                                    halfHeightFraction = (clamped * 0.75f).coerceIn(0.03f, 0.10f)
                                )
                            } else {
                                btn.copy(radiusShortFraction = clamped)
                            }
                        } else {
                            btn
                        }
                    }
                )
            }
            EditorTarget.Joystick -> applyMutation(recordUndo) { profile ->
                profile.copy(joystickRadiusFraction = fraction.coerceIn(0.08f, 0.24f))
            }
            else -> this
        }

    fun setSelectedOpacity(opacity: Float, recordUndo: Boolean = true): LayoutEditorState {
        val target = selectedTarget as? EditorTarget.Button ?: return this
        val clamped = opacity.coerceIn(0.20f, 1.0f)
        return applyMutation(recordUndo) { profile ->
            profile.copy(
                buttons = profile.buttons.map { btn ->
                    if (btn.id == target.buttonId) btn.copy(opacity = clamped) else btn
                }
            )
        }
    }

    fun setSelectedShape(shape: ButtonShape): LayoutEditorState {
        val target = selectedTarget as? EditorTarget.Button ?: return this
        return applyMutation(recordUndo = true) { profile ->
            profile.copy(
                buttons = profile.buttons.map { btn ->
                    if (btn.id == target.buttonId) {
                        btn.copy(shape = shape, isRect = false, halfWidthFraction = 0f, halfHeightFraction = 0f)
                    } else {
                        btn
                    }
                }
            )
        }
    }

    fun reassignSelectedAction(owner: Owner): LayoutEditorState {
        val target = selectedTarget as? EditorTarget.Button ?: return this
        val (label, subLabel) = ControlButtonSpec.defaultLabelsFor(owner)
        val isAccent = owner == Owner.FIRE || owner == Owner.ACCELERATE
        return applyMutation(recordUndo = true) { profile ->
            profile.copy(
                buttons = profile.buttons.map { btn ->
                    if (btn.id == target.buttonId) {
                        btn.copy(owner = owner, label = label, subLabel = subLabel, isAccent = isAccent)
                    } else {
                        btn
                    }
                }
            )
        }
    }

    fun updateCameraRegion(
        startX: Float = draft.cameraRegionStartX,
        topY: Float = draft.cameraRegionTopY,
        bottomY: Float = draft.cameraRegionBottomY,
        recordUndo: Boolean = true
    ): LayoutEditorState = applyMutation(recordUndo) { profile ->
        val safeStart = startX.coerceIn(0.30f, 0.60f)
        val safeTop = topY.coerceIn(0.04f, 0.45f)
        val safeBottom = bottomY.coerceIn(maxOf(safeTop + 0.25f, 0.55f), 0.98f)
        profile.copy(
            movementSplitFraction = safeStart,
            cameraRegionStartX = safeStart,
            cameraRegionTopY = safeTop,
            cameraRegionBottomY = safeBottom
        )
    }

    fun resetLayoutToPresetDefault(): LayoutEditorState {
        val baseline = when (draft.id) {
            ControlProfile.ID_DRIVING -> ControlProfile.drivingPreset()
            ControlProfile.ID_GENERIC_SHOOTER -> ControlProfile.genericShooterPreset()
            ControlProfile.ID_STANDARD_CONTROLLER -> ControlProfile.standardControllerPreset()
            else -> if (draft.mode == ControlMode.DRIVING) {
                ControlProfile.drivingPreset()
            } else {
                ControlProfile.onFootShooterPreset()
            }
        }
        return applyMutation(recordUndo = true) { profile ->
            profile.copy(
                buttons = baseline.buttons,
                joystickCenterX = baseline.joystickCenterX,
                joystickCenterY = baseline.joystickCenterY,
                joystickRadiusFraction = baseline.joystickRadiusFraction,
                movementSplitFraction = baseline.movementSplitFraction,
                cameraRegionStartX = baseline.cameraRegionStartX,
                cameraRegionTopY = baseline.cameraRegionTopY,
                cameraRegionBottomY = baseline.cameraRegionBottomY
            )
        }
    }

    fun undo(): LayoutEditorState {
        if (undoStack.isEmpty()) return this
        val previous = undoStack.last()
        return copy(
            draft = previous,
            undoStack = undoStack.dropLast(1),
            redoStack = redoStack + draft
        )
    }

    fun redo(): LayoutEditorState {
        if (redoStack.isEmpty()) return this
        val restored = redoStack.last()
        return copy(
            draft = restored,
            undoStack = undoStack + draft,
            redoStack = redoStack.dropLast(1)
        )
    }

    private fun maybeSnap(v: Float): Float =
        if (snapToGrid) (v * 50f).roundToInt() / 50f else v
}
