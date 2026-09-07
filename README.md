# Cat

Android video/audio downloader, forked from Seal.

> Note: this fork is still under active rework and is not stable yet.

## What makes Cat different from Seal

- **Cat branding** — new name, launcher icons, notification icons, and store listings.
- **Reworked download engine** — queue with per-task pause / resume / retry, concurrent-download limit, and smarter handling of network loss. Manual pauses are never auto-touched.
- **Auto-resume on reconnect** — downloads paused by a network drop resume by themselves once connectivity is back. Toggle in Settings > Network (on by default).
- **More network controls** — network-type restriction (Any / Wi-Fi only / Mobile only), pause-delay before a loss counts as a drop, and configurable aria2c connections.
- **Shizuku file moves (opt-in)** — finished files on SD cards / OTG / restricted folders can be moved via Shizuku instead of slow system copy. Any failure falls back to the normal copy automatically. Off by default, toggle in Download directory settings.
- **Format picking tweaks** — optional list view and an MP4-only filter (falls back to all formats when a site has no MP4).
- **Save info file** — optional `.txt` alongside each download with title, description, and tags.
- **Smarter notifications** — separate success / error sounds plus vibration and LED toggles.
- **Updater defaults changed** — stable yt-dlp channel with daily checks and auto-update on, instead of nightly / weekly.
- **Appearance defaults changed** — dark theme on by default.
- **History & library extras** — saved videos / comment sets in the database, richer history tracking, and video-info export.
- **Nags you can't silence by accident** — battery-optimization reminder re-appears on every launch until it's actually disabled, since background downloads depend on it.

Some patches are taken from https://github.com/MaheshTechnicals/Sealplus, plus my own patches.

## To-do

- [x] Implement Shizuku for more flexible file storing other than Internal (e.g External SSD or OTG USB)
- [x] Auto-resume on reconnect + Shizuku moves (needs on-device check)
- [ ] Bulk resume / retry + continue after reboot
- [ ] Scheduling + duplicate check
- [ ] History search + queue ETA + format memory
- [ ] Bumping Youtubedl-android to latest yt-dlp binary including latest ffmpeg (Problematic to do)

## License

See [LICENSE](./LICENSE).
