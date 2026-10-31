#!/usr/bin/env python3
"""Checks every games/<id>/ pack before a build.

Usage:
  python3 tools/validate_games.py              # human-readable report, exit 1 on errors
  python3 tools/validate_games.py --list-json  # also print the game ids as a JSON array (for the CI matrix)
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GAMES = os.path.join(ROOT, "games")
MAX_PACKAGE_ID = len("com.winlator.cmod")
CONTROL_MODES = {"GAMEPAD", "TOUCH", "TILT", "KEYBOARD"}
PROFILE_FOR_MODE = {"TOUCH": "controls_touch.icp", "TILT": "controls_tilt.icp"}
PLACEHOLDERS = ("GAME_ID", "GAME_TITLE", "PACKAGE_ID", "DOWNLOAD_URL", "ARCHIVE_NAME", "GAME_EXE", "GAME_DIR", "REPLACE_ME")
URL_RE = re.compile(r"https?://\S+$")
ID_RE = re.compile(r"[a-z0-9][a-z0-9-]*$")
PACKAGE_RE = re.compile(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")


def game_dirs():
    for name in sorted(os.listdir(GAMES)):
        path = os.path.join(GAMES, name)
        if os.path.isdir(path) and not name.startswith(("_", ".")):
            yield name, path


def check_profile(path, errors):
    try:
        data = json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError) as e:
        errors.append(f"{os.path.basename(path)}: {e}")
        return
    if list(data)[:3] != ["id", "name", "cursorSpeed"]:
        errors.append(f"{os.path.basename(path)}: keys must start with id, name, cursorSpeed (Winlator reads them in order)")
    if not data.get("elements"):
        errors.append(f"{os.path.basename(path)}: no elements")


def check_game(name, path, package_ids):
    errors, warnings = [], []
    try:
        pack = json.load(open(os.path.join(path, "gamepack.json"), encoding="utf-8"))
    except (OSError, ValueError) as e:
        return [f"gamepack.json: {e}"], warnings

    text = json.dumps(pack, ensure_ascii=False)
    for placeholder in PLACEHOLDERS:
        if placeholder in text:
            (warnings if placeholder in ("DOWNLOAD_URL", "REPLACE_ME") else errors).append(f"placeholder {placeholder} is still in gamepack.json")

    if not ID_RE.match(name):
        errors.append("folder name must be lowercase latin letters, digits and '-'")
    if pack.get("id") != name:
        errors.append(f"id '{pack.get('id')}' must equal the folder name '{name}'")
    if not pack.get("title"):
        errors.append("title is empty")

    package_id = pack.get("packageId", "")
    if not PACKAGE_RE.match(package_id):
        errors.append(f"packageId '{package_id}' is not a valid Android package name")
    elif len(package_id) > MAX_PACKAGE_ID:
        errors.append(f"packageId '{package_id}' is {len(package_id)} chars, max {MAX_PACKAGE_ID}")
    elif package_id == "com.winlator.cmod":
        errors.append("packageId must differ from Winlator Cmod's")
    elif package_id in package_ids:
        errors.append(f"packageId '{package_id}' is already used by {package_ids[package_id]}")
    else:
        package_ids[package_id] = name

    parts = pack.get("download", {}).get("parts", [])
    if not parts:
        errors.append("download.parts is empty")
    for part in parts:
        if not URL_RE.match(part.get("url", "")) and "DOWNLOAD_URL" not in part.get("url", ""):
            errors.append(f"bad url: {part.get('url')}")
        if not part.get("fileName") or "/" in part.get("fileName", ""):
            errors.append(f"bad fileName: {part.get('fileName')}")
        sha = part.get("sha256", "")
        if sha and not re.fullmatch(r"[0-9a-fA-F]{64}", sha):
            errors.append(f"sha256 of {part.get('fileName')} is not 64 hex chars")

    install = pack.get("install", {})
    if not install.get("dir") or not install.get("exe", "").lower().endswith((".exe", ".bat", ".lnk")):
        errors.append("install.dir and install.exe (.exe/.bat/.lnk) are required")

    controls = pack.get("controls", {})
    modes = controls.get("modes", sorted(CONTROL_MODES))
    unknown = set(modes) - CONTROL_MODES
    if unknown or not modes:
        errors.append(f"controls.modes must be a non-empty subset of {sorted(CONTROL_MODES)}")
    if controls.get("default", modes[0] if modes else "") not in modes:
        errors.append("controls.default must be one of controls.modes")
    for mode, profile in PROFILE_FOR_MODE.items():
        profile_path = os.path.join(path, profile)
        if mode in modes:
            if os.path.isfile(profile_path):
                check_profile(profile_path, errors)
            else:
                errors.append(f"mode {mode} needs {profile}")

    container = pack.get("container", {})
    if not container.get("wineVersion"):
        errors.append("container.wineVersion is required")
    if container.get("frameRateLimit", 60) not in range(0, 241):
        errors.append("container.frameRateLimit must be 0..240")

    if not os.path.isfile(os.path.join(path, "NOTICE.md")):
        warnings.append("NOTICE.md is missing (rights holder's terms)")
    return errors, warnings


def main():
    list_json = "--list-json" in sys.argv
    package_ids, ok_games, failed = {}, [], False
    out = sys.stderr if list_json else sys.stdout
    for name, path in game_dirs():
        errors, warnings = check_game(name, path, package_ids)
        for w in warnings:
            print(f"warning: {name}: {w}", file=out)
        for e in errors:
            print(f"error: {name}: {e}", file=out)
        if errors:
            failed = True
        else:
            ok_games.append(name)
            print(f"ok: {name}", file=out)
    if list_json:
        print(json.dumps(ok_games))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
