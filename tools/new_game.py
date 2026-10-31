#!/usr/bin/env python3
"""Creates games/<id>/ for a new port from games/_template.

Example (works on Windows too: `py tools\\new_game.py ...`):
  python3 tools/new_game.py nfs-mw --title "Need for Speed: Most Wanted" \\
      --package com.gl1ch5.nfsmw --exe speed.exe --preset racing \\
      --url https://cloud.mail.ru/public/XXXX/YYYY/nfsmw.rar

Presets pick the on-screen layouts and control modes:
  racing   touch wheel + pedals, tilt steering (TOUCH, TILT, GAMEPAD, KEYBOARD)
  gamepad  full virtual gamepad, tilt optional (TOUCH, TILT, GAMEPAD, KEYBOARD)
  keyboard mouse/keyboard games: no on-screen layout (KEYBOARD, GAMEPAD)
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEMPLATE = os.path.join(ROOT, "games", "_template")
CONTROLS = os.path.join(ROOT, "android", "defaults", "controls")
PRESETS = {
    "racing": (["TOUCH", "TILT", "GAMEPAD", "KEYBOARD"], "TOUCH", "racing"),
    "gamepad": (["TOUCH", "TILT", "GAMEPAD", "KEYBOARD"], "TOUCH", "gamepad"),
    "keyboard": (["KEYBOARD", "GAMEPAD"], "KEYBOARD", None),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("id", help="folder / game id: lowercase latin, digits, '-' (e.g. nfs-mw)")
    parser.add_argument("--title", required=True, help="name under the icon and on the launch screen")
    parser.add_argument("--package", required=True, help="Android package id, max 17 chars (e.g. com.gl1ch5.nfsmw)")
    parser.add_argument("--exe", required=True, help="game executable name, found anywhere inside the archive")
    parser.add_argument("--preset", choices=sorted(PRESETS), default="gamepad")
    parser.add_argument("--url", default="DOWNLOAD_URL", help="public archive link (Cloud Mail.ru, Yandex.Disk or direct)")
    parser.add_argument("--archive", help="archive file name (default: last part of the URL, or <id>.rar)")
    args = parser.parse_args()

    if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", args.id):
        sys.exit("id: lowercase latin letters, digits and '-' only")
    if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", args.package) or len(args.package) > 17:
        sys.exit("--package: Android package name of at most 17 chars, e.g. com.gl1ch5.nfsmw")
    target = os.path.join(ROOT, "games", args.id)
    if os.path.exists(target):
        sys.exit(f"{target} already exists")

    archive = args.archive or (args.url.rstrip("/").rsplit("/", 1)[-1] if args.url.startswith("http") else f"{args.id}.rar")
    game_dir = re.sub(r"[^A-Za-z0-9]", "", args.title) or args.id.replace("-", "")
    modes, default, layout = PRESETS[args.preset]

    shutil.copytree(TEMPLATE, target)
    path = os.path.join(target, "gamepack.json")
    text = open(path, encoding="utf-8").read()
    for key, value in {
        "GAME_ID": args.id, "PACKAGE_ID": args.package, "DOWNLOAD_URL": args.url,
        "ARCHIVE_NAME": archive, "GAME_EXE": args.exe, "GAME_DIR": game_dir,
    }.items():
        text = text.replace(key, value)
    text = text.replace("GAME_TITLE", json.dumps(args.title, ensure_ascii=False)[1:-1])
    pack = json.loads(text)
    pack["controls"] = {"modes": modes, "default": default}
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(pack, ensure_ascii=False, indent=2) + "\n")

    notice = os.path.join(target, "NOTICE.md")
    with open(notice, encoding="utf-8") as f:
        notice_text = f.read().replace("GAME_TITLE", args.title)
    with open(notice, "w", encoding="utf-8") as f:
        f.write(notice_text)

    if layout:
        shutil.copy(os.path.join(CONTROLS, f"{layout}_touch.icp"), os.path.join(target, "controls_touch.icp"))
        shutil.copy(os.path.join(CONTROLS, f"{layout}_tilt.icp"), os.path.join(target, "controls_tilt.icp"))

    print(f"Created games/{args.id}. Next: fill NOTICE.md, add branding/ if allowed, then push.")
    sys.exit(subprocess.call([sys.executable, os.path.join(ROOT, "tools", "validate_games.py")]))


if __name__ == "__main__":
    main()
