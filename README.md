# FetchFlow for Android

FetchFlow is a privacy-focused Android download manager with a custom, resumable HTTP(S) transfer engine. Version 0.9.0 hardens transfer lifecycle handling and adds actionable progress notifications, Android edge-to-edge layout, multi-link sharing, and server-provided filenames.

## Project setup

- Android Studio Meerkat (2024.3.1 Patch 1) or newer with Android SDK Platform 36 and Build Tools
- Android Gradle Plugin 8.10.0 and Gradle 8.11.1 or newer in the supported 8.x line
- JDK 17 or newer
- Open this project folder in Android Studio and allow Gradle sync
- Build using **Build → Build App Bundle(s) / APK(s) → Build APK(s)**

This workspace has no Android SDK, Gradle installation, emulator, or connected Android device. The source has not been compiled here, and no installable APK is included. See **Verification status** below.

## Included

- Android 10+ support; target SDK 36.
- HTTP and HTTPS links entered directly, opened from other apps, or shared as one or multiple links.
- Batch intake of up to 50 links, with whole-batch validation and duplicate removal.
- Parallel byte-range transfers (1, 4, 8, or 16 connections), with fewer connections selected for smaller files.
- Completed-segment checkpoints validated against ETag or Last-Modified. Stale segments are discarded when validators change or are unavailable.
- Three attempts for failed segments and a single-connection fallback if a server stops honoring ranges.
- Server-provided filename support when the user has not entered a custom name.
- Optional SHA-256 verification before a finished file is published to Downloads.
- Shared speed limits of 256 KB/s, 512 KB/s, 1 MB/s, 2 MB/s, 5 MB/s, or unlimited.
- 1–4 concurrent whole-file downloads (default 2), with a FIFO queue for additional transfers.
- Pause, resume, retry, cancel, searchable download list, status details, and completion history.
- Progress notifications with pause/cancel actions, retry/resume actions, and a settings shortcut for Android notification permission.
- Android 14+ user-initiated data-transfer jobs for user-started downloads; foreground-service transfers on Android 10–13.
- Scheduled individual downloads and batches through WorkManager, with network constraints.
- Saves completed files to `Downloads/FetchFlow` via MediaStore; supports opening and sharing files.
- ZIP extraction to `Downloads/FetchFlow/Extracted/<archive-name>`. Rejects traversal and absolute paths; rolls back partial output on errors; caps archives at 500 entries, 256 MB per file, and 512 MB total.
- On-device queue metadata and transfer checkpoints. No FetchFlow account, advertisements, analytics, or FetchFlow-owned service/API.

## Transfer and scheduling limits

- Parallel resume keeps fully completed segments. A segment interrupted mid-write is restarted from its beginning.
- When a server provides no ETag or Last-Modified validator, the app discards earlier segment checkpoints rather than risk joining bytes from different file revisions.
- Servers without byte-range support use one connection; an interrupted single-connection transfer restarts from byte zero.
- Scheduled work is best-effort: Android may defer the start to satisfy network, battery, and system limits. Long-running WorkManager transfers are subject to Android's background execution policy, especially on Android 16+.
- Download URLs and history are kept on device. This version does not offer browser-cookie capture or custom HTTP authentication headers, so sign-in-gated and some expiring links will not work.
- HTTP remains supported for compatibility, but is unencrypted. FetchFlow warns before adding HTTP links; use HTTPS whenever the server offers it.

## Not included in 0.9.0

7z/RAR extraction, torrent and Magnet transfers, video extraction, browser extensions, cloud/debrid services, a local API/MCP server, and a built-in browser remain out of scope for this build. These need separate protocol, dependency, permissions, and device-testing work; they are not represented as implemented features.

## Verification status

Project configuration and source were reviewed, and the Android manifest was checked for well-formed XML. A full Gradle compile, APK install, Android 10–16 device matrix, and lifecycle/network testing have not been possible in this workspace because the Android SDK, Gradle tooling, emulator, and device are unavailable. Treat this as a development build until those checks are completed.
