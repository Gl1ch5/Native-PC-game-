package com.winlator.cmod.gamepack;

import android.content.Context;
import android.content.Intent;

import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.winhandler.WinHandler;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/** Creates the hidden Wine container for the game and launches the game directly, without the Wine desktop. */
public class GamePackSetup {
    /** Ids of the bundled layouts; kept far away from user-created profile ids. */
    public static final int TOUCH_PROFILE_ID = 1001;
    public static final int TILT_PROFILE_ID = 1002;
    private static final String TOUCH_PROFILE_ASSET = "gamepack/controls_touch.icp";
    private static final String TILT_PROFILE_ASSET = "gamepack/controls_tilt.icp";

    private final Context context;
    private final GamePack pack;
    private final GameSettings settings;

    public GamePackSetup(Context context, GamePack pack, GameSettings settings) {
        this.context = context.getApplicationContext();
        this.pack = pack;
        this.settings = settings;
    }

    public Container findContainer(ContainerManager manager) {
        int id = settings.getContainerId();
        return id > 0 ? manager.getContainerById(id) : null;
    }

    /** Must be called on a thread with a Looper (the callback is posted back to it). */
    public void createContainer(ContainerManager manager, Callback<Container> callback) {
        try {
            JSONObject data = new JSONObject();
            data.put("name", pack.title);
            data.put("screenSize", settings.getQuality().screenSize);
            data.put("envVars", pack.envVars.isEmpty() ? Container.DEFAULT_ENV_VARS : pack.envVars);
            if (!pack.cpuList.isEmpty()) data.put("cpuList", pack.cpuList);
            data.put("dxwrapper", "dxvk");
            data.put("dxwrapperConfig", buildDxwrapperConfig(settings.getQuality().frameRate));
            data.put("showFPS", settings.isShowFps());
            data.put("fullscreenStretched", true);
            data.put("wineVersion", pack.wineVersion);
            if (!pack.box64Preset.isEmpty()) data.put("box64Preset", pack.box64Preset);

            ContentsManager contentsManager = new ContentsManager(context);
            contentsManager.syncContents();
            manager.createContainerAsync(data, contentsManager, container -> {
                if (container != null) settings.setContainerId(container.id);
                callback.call(container);
            });
        }
        catch (JSONException e) {
            callback.call(null);
        }
    }

    public String buildDxwrapperConfig(int frameRate) {
        String dxvkVersion = pack.dxvkVersion.isEmpty() ? com.winlator.cmod.core.DefaultVersion.DXVK : pack.dxvkVersion;
        String async = pack.dxvkAsync ? "1" : "0";
        return "version=" + dxvkVersion + ",framerate=" + frameRate + ",maxDeviceMemory=0,async=" + async
                + ",asyncCache=" + async + ",vkd3dVersion=" + com.winlator.cmod.core.DefaultVersion.VKD3D + ",vkd3dLevel=12_1";
    }

    public static File getDriveC(Container container) {
        return new File(container.getRootDir(), ".wine/drive_c");
    }

    public File getInstallDir(Container container) {
        return new File(getDriveC(container), pack.getInstallDirUnix());
    }

    /** Finds the game executable (case-insensitive, any depth) and returns its Windows path, or null. */
    public String locateExecutable(Container container) {
        File driveC = getDriveC(container);
        File exe = findFile(getInstallDir(container), pack.exe.toLowerCase(Locale.ENGLISH), 6);
        if (exe == null) return null;
        String relative = exe.getAbsolutePath().substring(driveC.getAbsolutePath().length());
        return "C:" + relative.replace('/', '\\');
    }

    private static File findFile(File dir, String lowerName, int depth) {
        File[] files = dir.listFiles();
        if (files == null || depth < 0) return null;
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase(Locale.ENGLISH).equals(lowerName)) return file;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                File found = findFile(file, lowerName, depth - 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Copies the bundled layouts into Winlator's profile folder (refreshed on every launch). */
    public static boolean installControlProfiles(Context context) {
        new InputControlsManager(context).loadProfiles(false); // seeds the default profiles on first run
        return installProfile(context, TOUCH_PROFILE_ASSET, TOUCH_PROFILE_ID) & installProfile(context, TILT_PROFILE_ASSET, TILT_PROFILE_ID);
    }

    private static boolean installProfile(Context context, String asset, int id) {
        try {
            String json = FileUtils.readString(context, asset);
            if (json == null) return false;
            JSONObject profile = new JSONObject(json);
            profile.put("id", id);
            return FileUtils.writeString(ControlsProfile.getProfileFile(context, id), profile.toString());
        }
        catch (JSONException e) {
            return false;
        }
    }

    public static int getProfileId(GameSettings.Controls controls) {
        if (controls == GameSettings.Controls.TOUCH) return TOUCH_PROFILE_ID;
        if (controls == GameSettings.Controls.TILT) return TILT_PROFILE_ID;
        return 0;
    }

    /** Writes the .desktop shortcut from the current settings, so changes on the settings screen apply on the next start. */
    public File writeShortcut(Container container, String windowsExePath) {
        GameSettings.Quality quality = settings.getQuality();
        GameSettings.Controls controls = settings.getControls(pack);

        int profileId = getProfileId(controls);
        String controlsProfile = profileId != 0 && installControlProfiles(context) ? String.valueOf(profileId) : "";

        // Desktop entry escaping: every backslash is written as four (see StringUtils.unescape).
        String escapedExe = windowsExePath.replace("\\", "\\\\\\\\");
        String content = "[Desktop Entry]\n" +
                "Name=" + pack.title + "\n" +
                "Exec=env WINEPREFIX=\"/home/xuser/.wine\" wine " + escapedExe + "\n" +
                "Type=Application\n" +
                "StartupNotify=true\n" +
                "Icon=\n" +
                "StartupWMClass=" + FileUtils.getName(windowsExePath.replace('\\', '/')) + "\n\n" +
                "[Extra Data]\n" +
                "gamepack=" + pack.id + "\n" +
                "container_id=" + container.id + "\n" +
                "screenSize=" + quality.screenSize + "\n" +
                "dxwrapper=dxvk\n" +
                "dxwrapperConfig=" + buildDxwrapperConfig(quality.frameRate) + "\n" +
                "fullscreenStretched=1\n" +
                "inputType=" + WinHandler.DEFAULT_INPUT_TYPE + "\n" +
                (controlsProfile.isEmpty() ? "" : "controlsProfile=" + controlsProfile + "\n") +
                (controls == GameSettings.Controls.KEYBOARD ? "relativeMouseMovement=0\n" : "") +
                (pack.execArgs.isEmpty() ? "" : "execArgs=" + pack.execArgs + "\n") +
                (pack.cpuList.isEmpty() ? "" : "cpuList=" + pack.cpuList + "\n") +
                (pack.box64Preset.isEmpty() ? "" : "box64Preset=" + pack.box64Preset + "\n") +
                (isCustomDriverInstalled() ? "wrapperGraphicsDriverVersion=" + settings.getGpuDriver() + "\n" : "");

        container.setShowFPS(settings.isShowFps());
        container.saveData();

        File desktopDir = container.getDesktopDir();
        desktopDir.mkdirs();
        File shortcut = new File(desktopDir, pack.id + ".desktop");
        FileUtils.writeString(shortcut, content);
        return shortcut;
    }

    /** True when a custom driver is selected and still present (falls back to the system driver otherwise). */
    private boolean isCustomDriverInstalled() {
        String driver = settings.getGpuDriver();
        if (driver.equals(GameSettings.SYSTEM_DRIVER)) return false;
        if (new AdrenotoolsManager(context).enumarateInstalledDrivers().contains(driver)) return true;
        settings.setGpuDriver(GameSettings.SYSTEM_DRIVER);
        return false;
    }

    public Intent buildLaunchIntent(Container container, File shortcut) {
        Intent intent = new Intent(context, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("shortcut_path", shortcut.getPath());
        intent.putExtra("shortcut_name", pack.title);
        intent.putExtra("disableXinput", "0");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
