package com.winlator.cmod.gamepack;

import android.content.Context;
import android.content.SharedPreferences;

/** Player choices made on the first-run screen; stored per game pack. */
public class GameSettings {
    public enum Controls { GAMEPAD, TOUCH, TILT, KEYBOARD }

    public enum Quality {
        ECO(45, "1280x720"),
        BALANCED(60, "1280x720"),
        HIGH(60, "1920x1080");

        public final int frameRate;
        public final String screenSize;

        Quality(int frameRate, String screenSize) {
            this.frameRate = frameRate;
            this.screenSize = screenSize;
        }
    }

    public static final String SYSTEM_DRIVER = com.winlator.cmod.core.DefaultVersion.WRAPPER;
    private static final String PREFS = "gamepack_settings";
    private final SharedPreferences prefs;

    public GameSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isConfigured() {
        return prefs.getBoolean("configured", false);
    }

    public Controls getControls() {
        return Controls.valueOf(prefs.getString("controls", Controls.GAMEPAD.name()));
    }

    /** Current mode, or the game's default before the first-run screen was completed. */
    public Controls getControls(GamePack pack) {
        String fallback = pack.allowsControls(Controls.valueOf(pack.defaultControls)) ? pack.defaultControls : pack.controlModes.get(0);
        Controls controls = Controls.valueOf(prefs.getString("controls", fallback));
        return pack.allowsControls(controls) ? controls : Controls.valueOf(fallback);
    }

    public Quality getQuality() {
        return Quality.valueOf(prefs.getString("quality", Quality.BALANCED.name()));
    }

    public boolean isShowFps() {
        return prefs.getBoolean("show_fps", false);
    }

    public void setControls(Controls controls) {
        prefs.edit().putString("controls", controls.name()).apply();
    }

    public void setShowFps(boolean showFps) {
        prefs.edit().putBoolean("show_fps", showFps).apply();
    }

    /** Phone roll (degrees) that turns the wheel fully; smaller is more sensitive. */
    public float getTiltMaxAngle() {
        return prefs.getFloat("tilt_max_angle", 30f);
    }

    public void setTiltMaxAngle(float degrees) {
        prefs.edit().putFloat("tilt_max_angle", degrees).apply();
    }

    /** Roll angle (degrees) treated as "wheel centred". */
    public float getTiltCenter() {
        return prefs.getFloat("tilt_center", 0f);
    }

    public void setTiltCenter(float degrees) {
        prefs.edit().putFloat("tilt_center", degrees).apply();
    }

    public void save(Controls controls, Quality quality, boolean showFps) {
        prefs.edit()
                .putBoolean("configured", true)
                .putString("controls", controls.name())
                .putString("quality", quality.name())
                .putBoolean("show_fps", showFps)
                .apply();
    }

    /** Adrenotools driver id from an imported archive, or "System" for the phone's own Vulkan driver. */
    public String getGpuDriver() {
        return prefs.getString("gpu_driver", SYSTEM_DRIVER);
    }

    public void setGpuDriver(String driverId) {
        prefs.edit().putString("gpu_driver", driverId).apply();
    }

    public int getContainerId() {
        return prefs.getInt("container_id", 0);
    }

    public void setContainerId(int id) {
        prefs.edit().putInt("container_id", id).apply();
    }

    /** Windows path of the installed executable, or empty when the game is not installed. */
    public String getInstalledExe() {
        return prefs.getString("installed_exe", "");
    }

    public int getInstalledVersion() {
        return prefs.getInt("installed_version", 0);
    }

    public void setInstalled(String windowsExePath, int packageVersion) {
        prefs.edit().putString("installed_exe", windowsExePath).putInt("installed_version", packageVersion).apply();
    }

    public void clearInstalled() {
        prefs.edit().remove("installed_exe").remove("installed_version").apply();
    }
}
