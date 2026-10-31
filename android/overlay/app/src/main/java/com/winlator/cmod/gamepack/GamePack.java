package com.winlator.cmod.gamepack;

import android.content.Context;

import com.winlator.cmod.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Game description bundled into the APK as assets/gamepack/gamepack.json
 * (copied from games/<id>/gamepack.json by android/prepare.sh).
 */
public class GamePack {
    public static final String ASSET_PATH = "gamepack/gamepack.json";

    public static class Part {
        public final String url;
        public final String fileName;
        public final String sha256;

        Part(String url, String fileName, String sha256) {
            this.url = url;
            this.fileName = fileName;
            this.sha256 = sha256;
        }
    }

    public final String id;
    public final String title;
    public final String notice;
    public final int packageVersion;
    public final List<Part> parts = new ArrayList<>();
    public final String archivePassword;

    public final String installDir;
    public final String exe;
    public final String execArgs;
    public final long minFreeSpaceMb;

    public final String wineVersion;
    public final String screenSize;
    public final int frameRateLimit;
    public final String dxvkVersion;
    public final boolean dxvkAsync;
    public final String cpuList;
    public final String box64Preset;
    public final String envVars;

    private GamePack(JSONObject root) throws JSONException {
        id = root.getString("id");
        title = root.getString("title");
        notice = root.optString("notice", "");
        packageVersion = root.optInt("packageVersion", 1);

        JSONObject download = root.getJSONObject("download");
        JSONArray partsArray = download.getJSONArray("parts");
        for (int i = 0; i < partsArray.length(); i++) {
            JSONObject part = partsArray.getJSONObject(i);
            parts.add(new Part(part.getString("url"), part.getString("fileName"), part.optString("sha256", "")));
        }
        archivePassword = download.optString("archivePassword", "");

        JSONObject install = root.getJSONObject("install");
        installDir = install.getString("dir").replace('/', '\\');
        exe = install.getString("exe");
        execArgs = install.optString("execArgs", "");
        minFreeSpaceMb = install.optLong("minFreeSpaceMb", 0);

        JSONObject container = root.getJSONObject("container");
        wineVersion = container.getString("wineVersion");
        screenSize = container.optString("screenSize", "1280x720");
        frameRateLimit = container.optInt("frameRateLimit", 60);
        dxvkVersion = container.optString("dxvkVersion", "");
        dxvkAsync = container.optBoolean("dxvkAsync", false);
        cpuList = container.optString("cpuList", "");
        box64Preset = container.optString("box64Preset", "");
        envVars = container.optString("envVars", "");
    }

    public static GamePack load(Context context) throws JSONException {
        String json = FileUtils.readString(context, ASSET_PATH);
        if (json == null) throw new JSONException("Missing asset " + ASSET_PATH);
        return new GamePack(new JSONObject(json));
    }

    /** Path of the install directory relative to drive_c, with forward slashes. */
    public String getInstallDirUnix() {
        return installDir.replace('\\', '/');
    }
}
