# Shizuku File Injector

A minimal Android app (Kotlin) that uses [Shizuku](https://shizuku.rikka.app/) to copy a
file you pick into an arbitrary filesystem path with elevated (shell/root) privilege —
useful for things like restoring save files, pushing config into another app's data dir,
or general file-injection tooling that a normal sandboxed app can't do on its own.

## How it works

- Your app runs normally, sandboxed, like any Android app.
- Shizuku (a separate app you install once, started via ADB or root) exposes a privileged
  binder that your app can request permission to use.
- This project defines a small **user service** (`FileInjectorService`, declared via
  `IFileInjectorService.aidl`) that Shizuku loads into its own privileged process. Only
  code running *inside that service* actually gets shell/root-level file access — your
  Activity just calls it over IPC.
- The flow in `MainActivity.kt`:
  1. Check/request Shizuku permission (`Shizuku.requestPermission`).
  2. Bind the privileged user service (`Shizuku.bindUserService`).
  3. Pick a source file with the system file picker, then copy it into
     `externalCacheDir` — a path the shell user can actually read (content:// Uris and
     your app's private internal storage are NOT visible to the shell process).
  4. Call `service.injectFile(stagedPath, destPath, chmod)`, which runs `cp` (and
     optional `chmod`) inside the privileged process.

## ⚠️ Privilege reality check

Shizuku gives you **adb-shell** privilege (or root, only if the user's device is rooted
*and* they chose "Grant root permission" in the Shizuku app). That means:

- Writing to world-writable paths, `/data/local/tmp`, external storage, etc. → works fine.
- Writing into **another app's** `/data/data/<pkg>/...` → only works if:
  - the device is rooted and Shizuku is running as root, **or**
  - the target app is `debuggable` (shell can use `run-as`-equivalent access), **or**
  - the specific path/file was deliberately left world-accessible by that app.
- On a stock, non-rooted device, you generally **cannot** inject into an arbitrary
  non-debuggable app's private data dir even with Shizuku — that's an OS security
  boundary Shizuku doesn't bypass by itself. If your real target is a specific app,
  tell me which one and I can help you check what's actually reachable (the
  "Run diagnostics" button in the app runs `id` and `ls -la` on your target dir so you
  can see permissions/ownership before you try to write).

## Project structure

```
ShizukuFileInjector/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── aidl/.../IFileInjectorService.aidl   # privileged-service interface
│       ├── java/.../MainActivity.kt             # UI + Shizuku permission/bind flow
│       ├── java/.../FileInjectorService.kt       # runs INSIDE the privileged process
│       └── res/layout/activity_main.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Building it

1. Install [Android Studio](https://developer.android.com/studio) (Koala/2024.1+ recommended).
2. **Open** this folder as a project (`File → Open`) — do NOT "import" it, just open the
   root folder directly. Android Studio will generate the missing `gradlew` /
   `gradle-wrapper.jar` binary automatically on first sync (this scaffold includes
   `gradle-wrapper.properties` pointing at Gradle 8.7, but not the jar itself, since it's
   a binary file — Android Studio regenerates it, or run `gradle wrapper` once if you
   have Gradle installed separately).
3. Let Gradle sync — it will pull the Shizuku API artifacts from Maven Central.
4. Run on a device/emulator that has **Shizuku installed and running**
   (via `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`,
   or the [Sui](https://github.com/RikkaApps/Sui) Magisk module if rooted).

## Pushing to GitHub

```bash
cd ShizukuFileInjector
git init
git add .
git commit -m "Initial Shizuku file injector scaffold"
git branch -M main
git remote add origin https://github.com/<your-username>/<your-repo>.git
git push -u origin main
```

## Next steps / things to customize

- `applicationId` / `namespace` in `app/build.gradle.kts` — currently
  `com.example.shizukufileinjector`, change to your own.
- The UI is intentionally bare-bones (pick file → type dest path → inject). Swap in
  proper Material components, a saved "recent targets" list, progress states, etc. once
  the core flow is confirmed working on your target device.
- If you need to inject into a **specific known app**, tell me which one — I can help
  tailor the diagnostics/permission-check flow to it (e.g. detecting whether it's
  debuggable, whether root is required, correct SELinux context, etc).
