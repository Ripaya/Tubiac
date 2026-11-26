# Tubiac (`tube.maniac`)

> Fast, minimal, open reading — your Shelf, your sources, your AniList.

**Status:** Alpha (`v0.1.0-alpha1`)

Tubiac is a minimal, fast, native Kotlin Android reader for manga, manhwa, webtoons, and comics built with **Jetpack Compose**, **Material 3 Expressive (1.5.0)**, **Tachiyomi/Mihon extension compatibility**, **SAF Local Source**, and **AniList GraphQL v2** with granular local-vs-synced privacy controls.

## Highlights

- **Material 3 Expressive UI**: Android 12+ Dynamic Color, signature *Tubiac Ink & Vermilion* fallback palette (Light `#FAF8F5`, Dark `#111216`, AMOLED `#000000`, Primary `#D9483B`), optional cover-derived detail accents, tabular numerals, `MotionScheme.expressive()` spring physics, edge-to-edge cutouts, and Android 12+ SplashScreen API.
- **Extension Engine & Local Source**: `tachiyomix` stub interfaces (`eu.kanade.tachiyomi.*`) with `PackageManager` + `PathClassLoader` loading, shared WebView/OkHttp `CookieJar`, custom User-Agent / `X-Requested-With` spoofing, Cloudflare WebView solver, native DNS-over-HTTPS (Cloudflare, Google, AdGuard), Repo Stores (`index.min.json`), and SAF Local Source (CBZ/CBR/ZIP/EPUB/folders + `ComicInfo.xml` + `.nomedia`).
- **AniList GraphQL v2 & Granular Sync Matrix**: OAuth2 login, rate-limit-aware client (`X-RateLimit-Remaining` & `Retry-After` handling both 90 req/min and 30 req/min degraded windows), offline mutation queueing, per-series AniList vs. Source metadata toggle, and field-level Local-Only vs. Synced policy toggles.
- **Specialized Reader**: Webtoon (gapless continuous strip + side padding + border crop), Continuous Vertical, LTR, RTL, Landscape/Tablet Double-Page Spread (auto-split/combine), Smart Auto-Detection, dynamic page-edge background matching, color/e-ink filters, Telephoto + Coil 3 tiled zoom, customizable tap zones, volume-key paging, and Download Ahead.
- **WorkManager Downloads & Selective Backups**: Concurrent page downloads (default 5), Wi-Fi-only toggle, CBZ or raw folder output, `.tachibk` (Gzip) & JSON+ZIP backups with selective restore checkboxes, and scheduled auto-backups.

## Modular Gradle Architecture

```
:app
├── :feature:home       (Home feeds & chronological History)
├── :feature:shelf      (Unified Library, Categories, Tags, AniList lists, Update Errors)
├── :feature:browse     (Sources, Global Search, Source Migration, Extensions, Repo Stores)
├── :feature:detail     (Series Detail, AniList Tracker sheet, Chapter list & batch actions)
├── :feature:reader     (Specialized Webtoon/Manga/Spread Reader engine)
├── :feature:downloads  (WorkManager Download Queue, Library Updater, Auto-Backup workers)
├── :feature:settings   (Material You Settings, Privacy/Security, Data & Sync Hub)
└── :core
    ├── :core:designsystem     (Material 3 Expressive theme, shapes, typography, shared UI)
    ├── :core:model            (Pure Kotlin domain models, enums, sync policies)
    ├── :core:database         (Room + FTS4, DataStore preferences, BackupManager)
    ├── :core:network          (OkHttp + DoH + shared WebView CookieJar + Cloudflare detector)
    ├── :core:extension-api    (Tachiyomi/Mihon compatibility stubs)
    ├── :core:extension-loader (APK loader, PackageInstaller, Repo index parser, LocalSource)
    └── :core:anilist          (Rate-limit-aware GraphQL v2 client, OAuth2, Sync Engine)
```

## CI/CD & Alpha Releases

Tubiac depends on GitHub Actions for continuous verification and Obtainium-ready alpha builds:

1. **Alpha Check & Compile** (`.github/workflows/ci.yml`): Runs unit tests (`gradle test testDebugUnitTest`) and Android Lint (`gradle :app:lintDebug`) first; only after checks pass does it compile `tube.maniac.debug` and `tube.maniac.alpha` APKs with SHA-256 checksums.
2. **Release Alpha APK** (`.github/workflows/release-alpha.yml`): Triggered on `v*-alpha*` tags or manual dispatch to verify, build, and publish GitHub Pre-Releases for **Obtainium**.

### Build Locally

Requires **JDK 17**, **Android SDK (API 37)**, and **Gradle 9.4.1**:

```bash
gradle test testDebugUnitTest :app:lintDebug
gradle :app:assembleDebug :app:assembleAlpha
```

## Legal Disclaimer

Tubiac is strictly an open-source reader application and AniList tracking client licensed under the GNU GPLv3. It does not host, distribute, or bundle any copyrighted media content or third-party extension code.
