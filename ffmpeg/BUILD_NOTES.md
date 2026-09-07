# ffmpeg module — locally built binaries

This module replaces the `io.github.junkfood02.youtubedl-android:ffmpeg` artifact. Upstream
bundles ffmpeg 7.x built from Termux packages; the goal of this module is to ship a current
ffmpeg (9.x) with the CVE backlog that implies.

## How to refresh the binaries

1. Push this repo to GitHub and run the **build-ffmpeg** workflow (Actions tab →
   build-ffmpeg → Run workflow). It builds ffmpeg from `termux-packages` (pinned commit,
   bump `TERMUX_PACKAGES_PIN` in the workflow for newer versions) inside the runner's
   preinstalled Docker, for all four ABIs in parallel.
2. Download the **ffmpeg-jniLibs** artifact from the finished run.
3. Unzip it and copy the contents into this module so the layout ends up as:

```
ffmpeg/src/main/jniLibs/
├── arm64-v8a/   libffmpeg.so  libffprobe.so  libffmpeg.zip.so
├── armeabi-v7a/ libffmpeg.so  libffprobe.so  libffmpeg.zip.so
├── x86/         libffmpeg.so  libffprobe.so  libffmpeg.zip.so
└── x86_64/      libffmpeg.so  libffprobe.so  libffmpeg.zip.so
```

4. Commit. No code changes are needed anywhere: `App` calls `FFmpeg.init()` exactly as before
   and `FFmpeg.kt` here is verbatim upstream. `FFmpeg.init()` versions the extracted runtime
   by binary **size**, so a swapped `libffmpeg.zip.so` re-extracts itself on first launch
   after update — stale 7.x files never survive an install.

## Why the custom prefix matters

The workflow builds with `TERMUX_PREFIX=/data/youtubedl-android/usr` (upstream's own recipe).
Stock Termux binaries hard-bake `/data/data/com.termux/...` into their rpath and cannot locate
their own `libavcodec.so` etc. from another app's data directory. Do not swap in Termux's
published `.deb`s — they will not run here.

## Verifying on device

After install, trigger any merge/post-process (e.g. download with "merge formats") and check
logcat for the yt-dlp invocation; or add a temporary debug call printing
`ffmpeg -version` output from the extracted `packages/ffmpeg` dir.
