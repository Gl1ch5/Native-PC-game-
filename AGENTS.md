# AGENTS.md

Guidance for AI coding agents working in this repository.

## What this repo is

Android launchers that run Windows PC games as standalone phone apps. Each app is
[Winlator Cmod](https://github.com/coffincolors/winlator) (MIT, Bionic branch) plus a "game-pack"
launcher layer. The upstream source is **not vendored**: it is fetched at build time at the commit
pinned in `android/UPSTREAM.env`, then patched and overlaid.

## Layout

| Path | Purpose |
|---|---|
| `android/UPSTREAM.env` | Upstream repo and pinned commit. |
| `android/patches/*.patch` | Minimal edits to upstream files (manifest, `XServerDisplayActivity`, `ImageFsInstaller`, Gradle). |
| `android/overlay/` | New files copied over the upstream tree: `com.winlator.cmod.gamepack.*` Java classes and `gamepack_*` resources. |
| `android/defaults/` | Placeholder background and icon, used when a game has no `branding/`. |
| `android/prepare.sh` | Builds the project tree for one game: fetch, patch, overlay, game config, branding. |
| `android/fetch-system-assets.sh` | Takes `imagefs.txz` and `proton-9.0-*.txz` from an official Cmod release APK (upstream keeps them out of git). |
| `games/<id>/` | Per-game `gamepack.json`, touch/tilt control profiles (`.icp`), optional `branding/`, `NOTICE.md`. |
| `.github/workflows/build-apk.yml` | Builds every game in `matrix.game`; on the default branch it republishes the `nightly` prerelease. |

## Launcher flow (`android/overlay/.../gamepack`)

- `GameLauncherActivity`: the only LAUNCHER activity. Runs first-run setup (`GameSetupDialog`), starts the installer, auto-starts the game once installed.
- `GamePackInstallService`: foreground service. System image (`ImageFsInstaller.installFromAssetsBlocking`), container, download (`GamePackDownloader`: resume, Yandex.Disk public links), optional sha256, extraction (`GamePackExtractor`: RAR4/RAR5/7z/zip via 7-Zip-JBinding-4Android, tar.zst/xz via commons-compress). Progress goes to listeners and to the notification.
- `GamePackSetup`: creates the container, writes the `.desktop` shortcut from the current settings, builds the `XServerDisplayActivity` intent (launches the exe directly, no Wine desktop).
- `GameOverlay` + `TiltSteering`: created by the patched `XServerDisplayActivity` when the shortcut has `gamepack=<id>`. Back opens the pause menu instead of the Winlator drawer. Tilt steering writes the left-stick X of the virtual-gamepad profile.

## Rules

- Keep upstream edits in the patch minimal; put new logic in the overlay. When a patch stops applying, regenerate it against the pinned commit (`git diff` in a prepared tree, minus the overlay files).
- Upstream files mix CRLF and LF. Preserve each file's line endings when editing (Python `open(..., newline='')`), or the patch turns into a whole-file rewrite. `.gitattributes` keeps `*.patch` byte-exact.
- The application id must stay `com.winlator.cmod`: the system image and native libraries hard-code `/data/data/com.winlator.cmod`.
- Never commit game files, archives, or third-party logos/artwork. Games are downloaded at runtime from the URL in `gamepack.json`, and branding goes in `games/<id>/branding/` only when the rights holder allows it.
- UI strings are Russian (`gamepack_strings.xml`); the code and comments are English.

## Checking changes

```bash
UPSTREAM_REPO_OVERRIDE=/path/to/local/cmod-clone android/prepare.sh f1-2012 build/f1-2012   # override is optional
cd build/f1-2012 && echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:compileDebugJavaWithJavac -x externalNativeBuildDebug   # quick Java check
./gradlew assembleDebug                                                # full APK (needs fetch-system-assets.sh)
```

No device or emulator runs in CI. Behaviour on a phone (tilt direction, controller mapping, DXVK on a given Adreno) has to be checked by hand.
