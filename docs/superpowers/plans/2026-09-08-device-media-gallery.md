# Device Media Gallery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Request photo access and show the existing Fold photo library on first launch while preserving private Google Photos imports.

**Architecture:** Add a MediaStore-backed catalog and a source-neutral gallery item. Merge device rows with private copies in the ViewModel/UI, and decode each source through `PhotoLibrary`.

**Tech Stack:** Kotlin, native Android Views, AndroidX Activity/ViewModel/LiveData, MediaStore, ImageDecoder, JUnit, Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-09-08-device-media-gallery-design.md`

## Global Constraints

- `minSdk = 36`, `compileSdk = targetSdk = 37`.
- Request `READ_MEDIA_IMAGES` plus Android selected-photo access; do not request video, audio, account, network, or legacy storage permissions.
- Device images are view-only; deletion remains limited to private Lik copies.
- Preserve Google Photos Share imports and remove generic Photo Picker import, aliases, icons, EN/RU, theme and resizable UI.

### Task 1: Source-neutral catalog

**Files:** create `gallery/GalleryPhoto.kt`, `gallery/PhotoCatalog.kt`; modify `imports/PhotoLibrary.kt`; test `PhotoCatalogTest.kt`.

- [ ] Write a failing catalog merge/order test.
- [x] Add MediaStore query and source-neutral decode.
- [ ] Run focused tests.

### Task 2: Permission and combined grid

**Files:** modify manifest, `MainActivity.kt`, `PhotoAdapter.kt`, layout and EN/RU strings; add instrumentation coverage.

- [ ] Write a failing test for a granted MediaStore image appearing in the grid.
- [x] Request full or selected image access on first launch and expose retry UI.
- [x] Merge MediaStore rows and private copies; keep device rows view-only.
- [ ] Run focused instrumentation on API 37.

### Task 3: Viewer and device validation

**Files:** modify viewer ViewModel/Activity and tests; update project docs.

- [x] Load viewer cursor and metadata from the combined catalog.
- [ ] Run the full Gradle and API 37 instrumentation suites.
- [ ] Install on `SM-F966B` API 36, launch, verify permission UI and real gallery.
- [ ] Record results and limitations.
