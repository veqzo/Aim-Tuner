package dev.veqzo.aimtune.app.accel

/** Whether the app judges a Raw Accel setting from the stored rounds or only explains it. */
enum class SettingKind { ANALYSIS, INFO }

/**
 * The settings of Raw Accel that the hub lists under "Other settings" (docs/RAWACCEL-HUB.md, "Settings audit"). [id] builds the
 * text keys `hub.set.<id>.*`. Only the three [passive] ones can go into the patched copy of the player's file.
 */
enum class RawAccelSetting(val id: String, val kind: SettingKind, val passive: Boolean = false) {
    ROTATION("rotation", SettingKind.ANALYSIS, passive = true),
    Y_X("yx", SettingKind.ANALYSIS, passive = true),
    L_R("lr", SettingKind.ANALYSIS, passive = true),
    U_D("ud", SettingKind.INFO),
    SNAP("snap", SettingKind.INFO),
    SPEED_CAP("speed_cap", SettingKind.INFO),
    SMOOTHING("smoothing", SettingKind.INFO),
    STRETCH("stretch", SettingKind.INFO),
    COMPONENT("component", SettingKind.INFO),
    POLLING("polling", SettingKind.INFO),
}
