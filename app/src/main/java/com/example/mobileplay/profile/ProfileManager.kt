package com.example.mobileplay.profile

import android.content.Context
import com.example.mobileplay.engine.AimTuning
import com.example.mobileplay.engine.ButtonShape
import com.example.mobileplay.engine.ControlButtonSpec
import com.example.mobileplay.engine.ControlMode
import com.example.mobileplay.engine.ControlProfile
import com.example.mobileplay.engine.Owner
import com.example.mobileplay.video.StreamCodec
import com.example.mobileplay.video.StreamFrameRate
import com.example.mobileplay.video.StreamQualitySettings
import com.example.mobileplay.video.StreamResolution
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages JSON-serialized gameplay control profiles (4 built-in presets + unlimited custom game profiles)
 * and streaming quality settings.
 */
class ProfileManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadAllProfiles(): List<ControlProfile> {
        val rawArray = prefs.getString(KEY_PROFILES_LIST, null)
        val storedById = LinkedHashMap<String, ControlProfile>()
        if (rawArray != null) {
            runCatching {
                val arr = JSONArray(rawArray)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optString("id")
                    val fallback = builtInById(id) ?: ControlProfile.onFootShooterPreset()
                    val parsed = fromJsonObject(obj, fallback)
                    storedById[parsed.id] = parsed
                }
            }
        }
        // Guarantee all 4 built-in presets exist in order, followed by custom user profiles.
        val result = ArrayList<ControlProfile>()
        for (builtIn in ControlProfile.allBuiltInPresets()) {
            result.add(storedById.remove(builtIn.id) ?: builtIn)
        }
        result.addAll(storedById.values)
        return result
    }

    fun loadActiveProfile(): ControlProfile {
        val all = loadAllProfiles()
        val lastId = prefs.getString(KEY_LAST_PROFILE_ID, null)
        return all.firstOrNull { it.id == lastId } ?: all.first()
    }

    fun loadProfile(mode: ControlMode): ControlProfile {
        val targetId = if (mode == ControlMode.DRIVING) ControlProfile.ID_DRIVING else ControlProfile.ID_ON_FOOT
        return loadAllProfiles().firstOrNull { it.id == targetId }
            ?: loadAllProfiles().firstOrNull { it.mode == mode }
            ?: defaultFor(mode)
    }

    fun saveProfile(profile: ControlProfile) {
        val all = loadAllProfiles().toMutableList()
        val idx = all.indexOfFirst { it.id == profile.id }
        if (idx >= 0) all[idx] = profile else all.add(profile)
        persistList(all)
        prefs.edit()
            .putString(KEY_LAST_PROFILE_ID, profile.id)
            .putString(KEY_LAST_MODE, profile.mode.name)
            .apply()
    }

    fun duplicateProfile(source: ControlProfile, newName: String): ControlProfile {
        val cleanName = newName.trim().ifBlank { "${source.name} Copy" }
        val customId = "custom_${System.currentTimeMillis()}"
        val copy = source.copy(
            id = customId,
            name = cleanName,
            isBuiltIn = false
        )
        saveProfile(copy)
        return copy
    }

    fun deleteCustomProfile(id: String): List<ControlProfile> {
        val all = loadAllProfiles().filterNot { it.id == id && !it.isBuiltIn }
        persistList(all)
        return all
    }

    fun resetProfile(profileId: String): ControlProfile {
        val fallback = builtInById(profileId) ?: ControlProfile.onFootShooterPreset()
        saveProfile(fallback)
        return fallback
    }

    fun resetProfile(mode: ControlMode): ControlProfile {
        val defaults = defaultFor(mode)
        saveProfile(defaults)
        return defaults
    }

    fun loadLastMode(): ControlMode {
        val name = prefs.getString(KEY_LAST_MODE, ControlMode.ON_FOOT.name)
        return runCatching { ControlMode.valueOf(name ?: ControlMode.ON_FOOT.name) }
            .getOrDefault(ControlMode.ON_FOOT)
    }

    fun exportPrettyJson(profile: ControlProfile): String = toJsonObject(profile).toString(2)

    fun importProfileFromJson(rawJson: String): Result<ControlProfile> = runCatching {
        val root = JSONObject(rawJson.trim())
        val baseMode = runCatching {
            ControlMode.valueOf(root.optString("mode", ControlMode.ON_FOOT.name))
        }.getOrDefault(ControlMode.ON_FOOT)
        val imported = fromJsonObject(root, defaultFor(baseMode)).copy(
            id = "imported_${System.currentTimeMillis()}",
            isBuiltIn = false
        )
        saveProfile(imported)
        imported
    }

    fun loadStreamQuality(): StreamQualitySettings {
        val raw = prefs.getString(KEY_STREAM_QUALITY, null) ?: return StreamQualitySettings()
        return runCatching {
            val obj = JSONObject(raw)
            StreamQualitySettings(
                resolution = runCatching {
                    StreamResolution.valueOf(obj.optString("resolution", StreamResolution.P720.name))
                }.getOrDefault(StreamResolution.P720),
                frameRate = runCatching {
                    StreamFrameRate.valueOf(obj.optString("frameRate", StreamFrameRate.FPS_60.name))
                }.getOrDefault(StreamFrameRate.FPS_60),
                codec = runCatching {
                    StreamCodec.valueOf(obj.optString("codec", StreamCodec.H264.name))
                }.getOrDefault(StreamCodec.H264),
                autoQuality = obj.optBoolean("autoQuality", true),
                manualBitrateKbps = obj.optInt("manualBitrateKbps", 12_000).coerceIn(2_000, 30_000),
                hardwareDecodingEnabled = obj.optBoolean("hardwareDecodingEnabled", true),
                audioVolume = obj.optDouble("audioVolume", 0.85).toFloat().coerceIn(0f, 1f)
            )
        }.getOrDefault(StreamQualitySettings())
    }

    fun saveStreamQuality(settings: StreamQualitySettings) {
        val json = JSONObject().apply {
            put("resolution", settings.resolution.name)
            put("frameRate", settings.frameRate.name)
            put("codec", settings.codec.name)
            put("autoQuality", settings.autoQuality)
            put("manualBitrateKbps", settings.manualBitrateKbps)
            put("hardwareDecodingEnabled", settings.hardwareDecodingEnabled)
            put("audioVolume", settings.audioVolume.toDouble())
        }.toString()
        prefs.edit().putString(KEY_STREAM_QUALITY, json).apply()
    }

    private fun persistList(profiles: List<ControlProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(toJsonObject(it)) }
        prefs.edit().putString(KEY_PROFILES_LIST, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "mobileplay_profiles_v2"
        private const val KEY_PROFILES_LIST = "profiles_json_list"
        private const val KEY_LAST_PROFILE_ID = "last_profile_id"
        private const val KEY_LAST_MODE = "last_control_mode"
        private const val KEY_STREAM_QUALITY = "stream_quality_v1"

        private fun builtInById(id: String): ControlProfile? =
            ControlProfile.allBuiltInPresets().firstOrNull { it.id == id }

        private fun defaultFor(mode: ControlMode): ControlProfile = when (mode) {
            ControlMode.ON_FOOT -> ControlProfile.onFootShooterPreset()
            ControlMode.DRIVING -> ControlProfile.drivingPreset()
        }

        fun toJson(profile: ControlProfile): String = toJsonObject(profile).toString()

        fun toJsonObject(profile: ControlProfile): JSONObject = JSONObject().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("mode", profile.mode.name)
            put("isBuiltIn", profile.isBuiltIn)
            put("controlOpacity", profile.controlOpacity.toDouble())
            put("movementSplitFraction", profile.movementSplitFraction.toDouble())
            put("joystickCenterX", profile.joystickCenterX.toDouble())
            put("joystickCenterY", profile.joystickCenterY.toDouble())
            put("joystickRadiusFraction", profile.joystickRadiusFraction.toDouble())
            put("cameraRegionStartX", profile.cameraRegionStartX.toDouble())
            put("cameraRegionTopY", profile.cameraRegionTopY.toDouble())
            put("cameraRegionBottomY", profile.cameraRegionBottomY.toDouble())
            put("showDiagnosticsHud", profile.showDiagnosticsHud)
            put("useLegacyOverlay", profile.useLegacyOverlay)
            put(
                "tuning",
                JSONObject().apply {
                    put("speedForFullStickMmPerSec", profile.tuning.speedForFullStickMmPerSec.toDouble())
                    put("horizontalSensitivity", profile.tuning.horizontalSensitivity.toDouble())
                    put("verticalSensitivity", profile.tuning.verticalSensitivity.toDouble())
                    put("adsSensitivityMultiplier", profile.tuning.adsSensitivityMultiplier.toDouble())
                    put("antiDeadzone", profile.tuning.antiDeadzone.toDouble())
                    put("curve", profile.tuning.curve.toDouble())
                    put("smoothingTimeMs", profile.tuning.smoothingTimeMs.toDouble())
                    put("stopAfterMs", profile.tuning.stopAfterMs)
                    put("invertX", profile.tuning.invertX)
                    put("invertY", profile.tuning.invertY)
                    put("fireDragAimEnabled", profile.tuning.fireDragAimEnabled)
                    put("adsToggleMode", profile.tuning.adsToggleMode)
                    put("sprintToggleMode", profile.tuning.sprintToggleMode)
                    put("crouchToggleMode", profile.tuning.crouchToggleMode)
                }
            )
            put(
                "buttons",
                JSONArray().apply {
                    profile.buttons.forEach { btn ->
                        put(
                            JSONObject().apply {
                                put("id", btn.id)
                                put("owner", btn.owner.name)
                                put("centerX", btn.centerX.toDouble())
                                put("centerY", btn.centerY.toDouble())
                                put("radiusShortFraction", btn.radiusShortFraction.toDouble())
                                put("label", btn.label)
                                put("subLabel", btn.subLabel)
                                put("isAccent", btn.isAccent)
                                put("shape", btn.shape.name)
                                put("opacity", btn.opacity.toDouble())
                                put("isRect", btn.isRect)
                                put("halfWidthFraction", btn.halfWidthFraction.toDouble())
                                put("halfHeightFraction", btn.halfHeightFraction.toDouble())
                            }
                        )
                    }
                }
            )
        }

        fun fromJson(raw: String, fallback: ControlProfile): ControlProfile =
            fromJsonObject(JSONObject(raw), fallback)

        fun fromJsonObject(root: JSONObject, fallback: ControlProfile): ControlProfile {
            val t = root.optJSONObject("tuning")
            val ft = fallback.tuning
            val mode = runCatching {
                ControlMode.valueOf(root.optString("mode", fallback.mode.name))
            }.getOrDefault(fallback.mode)

            val tuning = AimTuning(
                speedForFullStickMmPerSec = (t?.optDouble("speedForFullStickMmPerSec", ft.speedForFullStickMmPerSec.toDouble())
                    ?: ft.speedForFullStickMmPerSec.toDouble()).toFloat().coerceIn(50f, 1500f),
                horizontalSensitivity = (t?.optDouble("horizontalSensitivity", ft.horizontalSensitivity.toDouble())
                    ?: ft.horizontalSensitivity.toDouble()).toFloat().coerceIn(0.1f, 4.0f),
                verticalSensitivity = (t?.optDouble("verticalSensitivity", ft.verticalSensitivity.toDouble())
                    ?: ft.verticalSensitivity.toDouble()).toFloat().coerceIn(0.1f, 4.0f),
                adsSensitivityMultiplier = (t?.optDouble("adsSensitivityMultiplier", ft.adsSensitivityMultiplier.toDouble())
                    ?: ft.adsSensitivityMultiplier.toDouble()).toFloat().coerceIn(0.1f, 1.5f),
                antiDeadzone = (t?.optDouble("antiDeadzone", ft.antiDeadzone.toDouble())
                    ?: ft.antiDeadzone.toDouble()).toFloat().coerceIn(0f, 0.5f),
                curve = (t?.optDouble("curve", ft.curve.toDouble())
                    ?: ft.curve.toDouble()).toFloat().coerceIn(0.25f, 4.0f),
                smoothingTimeMs = (t?.optDouble("smoothingTimeMs", ft.smoothingTimeMs.toDouble())
                    ?: ft.smoothingTimeMs.toDouble()).toFloat().coerceIn(0f, 100f),
                stopAfterMs = (t?.optLong("stopAfterMs", ft.stopAfterMs) ?: ft.stopAfterMs).coerceIn(1L, 1000L),
                invertX = t?.optBoolean("invertX", ft.invertX) ?: ft.invertX,
                invertY = t?.optBoolean("invertY", ft.invertY) ?: ft.invertY,
                fireDragAimEnabled = t?.optBoolean("fireDragAimEnabled", ft.fireDragAimEnabled) ?: ft.fireDragAimEnabled,
                adsToggleMode = t?.optBoolean("adsToggleMode", ft.adsToggleMode) ?: ft.adsToggleMode,
                sprintToggleMode = t?.optBoolean("sprintToggleMode", ft.sprintToggleMode) ?: ft.sprintToggleMode,
                crouchToggleMode = t?.optBoolean("crouchToggleMode", ft.crouchToggleMode) ?: ft.crouchToggleMode
            )

            val buttonsArr = root.optJSONArray("buttons")
            val buttons = if (buttonsArr != null && buttonsArr.length() > 0) {
                buildList {
                    for (i in 0 until buttonsArr.length()) {
                        val b = buttonsArr.optJSONObject(i) ?: continue
                        val owner = runCatching { Owner.valueOf(b.optString("owner")) }.getOrNull() ?: continue
                        val shape = runCatching {
                            ButtonShape.valueOf(b.optString("shape", ButtonShape.CIRCLE.name))
                        }.getOrDefault(ButtonShape.CIRCLE)
                        add(
                            ControlButtonSpec(
                                id = b.optString("id", "btn_$i"),
                                owner = owner,
                                centerX = b.optDouble("centerX", 0.5).toFloat().coerceIn(0.04f, 0.96f),
                                centerY = b.optDouble("centerY", 0.5).toFloat().coerceIn(0.05f, 0.95f),
                                radiusShortFraction = b.optDouble("radiusShortFraction", 0.08).toFloat().coerceIn(0.04f, 0.18f),
                                label = b.optString("label", owner.name),
                                subLabel = b.optString("subLabel", ""),
                                isAccent = b.optBoolean("isAccent", false),
                                shape = shape,
                                opacity = b.optDouble("opacity", 1.0).toFloat().coerceIn(0.20f, 1.0f),
                                isRect = b.optBoolean("isRect", false),
                                halfWidthFraction = b.optDouble("halfWidthFraction", 0.0).toFloat().coerceIn(0f, 0.25f),
                                halfHeightFraction = b.optDouble("halfHeightFraction", 0.0).toFloat().coerceIn(0f, 0.20f)
                            )
                        )
                    }
                }.ifEmpty { fallback.buttons }
            } else {
                fallback.buttons
            }

            return ControlProfile(
                id = root.optString("id", fallback.id),
                name = root.optString("name", fallback.name),
                mode = mode,
                tuning = tuning,
                controlOpacity = root.optDouble("controlOpacity", fallback.controlOpacity.toDouble())
                    .toFloat().coerceIn(0.15f, 1.0f),
                movementSplitFraction = root.optDouble("movementSplitFraction", fallback.movementSplitFraction.toDouble())
                    .toFloat().coerceIn(0.30f, 0.60f),
                joystickCenterX = root.optDouble("joystickCenterX", fallback.joystickCenterX.toDouble())
                    .toFloat().coerceIn(0.08f, 0.45f),
                joystickCenterY = root.optDouble("joystickCenterY", fallback.joystickCenterY.toDouble())
                    .toFloat().coerceIn(0.20f, 0.88f),
                joystickRadiusFraction = root.optDouble("joystickRadiusFraction", fallback.joystickRadiusFraction.toDouble())
                    .toFloat().coerceIn(0.08f, 0.24f),
                cameraRegionStartX = root.optDouble("cameraRegionStartX", fallback.cameraRegionStartX.toDouble())
                    .toFloat().coerceIn(0.30f, 0.60f),
                cameraRegionTopY = root.optDouble("cameraRegionTopY", fallback.cameraRegionTopY.toDouble())
                    .toFloat().coerceIn(0.0f, 0.50f),
                cameraRegionBottomY = root.optDouble("cameraRegionBottomY", fallback.cameraRegionBottomY.toDouble())
                    .toFloat().coerceIn(0.55f, 1.0f),
                buttons = buttons,
                isBuiltIn = root.optBoolean("isBuiltIn", fallback.isBuiltIn),
                showDiagnosticsHud = root.optBoolean("showDiagnosticsHud", fallback.showDiagnosticsHud),
                useLegacyOverlay = root.optBoolean("useLegacyOverlay", fallback.useLegacyOverlay)
            )
        }
    }
}
