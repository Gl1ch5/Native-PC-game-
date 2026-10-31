# CLAUDE.md

@AGENTS.md

## Notes for Claude

- The user writes in Russian; reply in Russian.
- Build the APK in CI, not on the user's machine: push to the branch, open a PR, and the `Build APK` workflow attaches the APK as an artifact. Pushing to the default branch refreshes the `nightly` release.
- Before changing `android/patches/`, prepare a tree, edit the upstream file there, and regenerate the patch with `git diff` (excluding overlay files).
- The user's Godot path (`F:\godot.windows.editor.x86_64.exe`) is irrelevant here: this project is Android/Java, not Godot.
