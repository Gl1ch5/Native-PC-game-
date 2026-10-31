---
name: new-game-port
description: Add a new PC game port (a games/<id>/ pack that CI turns into its own APK). Use when the user asks to port, add or set up another game.
---

# New game port

Follow `docs/NEW_GAME.md`. In short:

1. Collect from the user: title, public archive link (Cloud Mail.ru / Yandex.Disk / direct), exe name, genre, and confirmation that the rights holder allows the use. Do not download or commit game files or third-party artwork.
2. Pick a package id of at most 17 characters, unique among `games/*/gamepack.json` (convention: `com.gl1ch5.<short>`).
3. Run:
   ```bash
   python3 tools/new_game.py <id> --title "<Title>" --package <package> --exe <exe> --preset <racing|gamepad|keyboard> --url <link>
   ```
4. Adjust `games/<id>/gamepack.json` for the game: `install.minFreeSpaceMb`, `execArgs`, `dxvkVersion` (DX8 games: `1.10.3`), `controls.default`.
5. Leave `NOTICE.md` for the user to fill with the rights holder's terms; tell them.
6. `python3 tools/validate_games.py` must print `ok: <id>`.
7. Commit and push to the working branch. The `Build APK` workflow builds one APK per game and refreshes the `nightly` release on the default branch. Check the run and fix failures.

Never edit `android/overlay` or `android/patches` for a single game. If a game needs new launcher behaviour, add a generic `gamepack.json` option, read it in `GamePack`, and document it in `docs/NEW_GAME.md` and the validator.
