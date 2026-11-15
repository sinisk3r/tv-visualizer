---
name: next-work
description: Pick up the next work item for the TV Visualizer. Use when the user says "next work", "what's next", "pick up where we left off", "continue the queue", or /next-work in this repo.
---

# Next work

The work queue is `docs/next.md`, top item first. Each item has a ready-to-paste prompt.

1. Read `CLAUDE.md`, then `docs/next.md`.
2. Take the first item that isn't in "Done" (or the one the user names). Say in one line which item and why.
3. Follow that item's prompt as the task. Its steps and "done when" are the plan; ask where it says to ask.
4. Before starting, check the setup it needs:
   - The TV: `adb devices`. If it's not there, use the Mac path in CLAUDE.md "Testing off the TV" and say so.
   - The emulator: `/opt/homebrew/share/android-commandlinetools/emulator/emulator -list-avds` should list `tv34`.
     If it's missing, install `emulator` and `system-images;android-34;google-tv;arm64-v8a` with sdkmanager
     (JAVA_HOME=/opt/homebrew/opt/openjdk@17), then
     `avdmanager create avd -n tv34 -k "system-images;android-34;google-tv;arm64-v8a" -d tv_1080p`.
5. When the item is done: move it to "Done" in `docs/next.md` with the date and commit hash, and add any new
   items it turned up (each with a prompt). Commit; push only when the user asks.
