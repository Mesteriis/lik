# Gallery Timeline and Fold Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat photo grid with the approved five-scale chronological gallery, Fold-responsive layout, persistent navigation, and date-aware viewer return.

**Architecture:** Extend `GalleryPhoto` with capture-time metadata and feed it into a pure `GalleryTimeline` model. Render model rows with a virtualized RecyclerView whose grid spans change with the timeline level and available width. Keep permission/import/delete orchestration in `MainActivity`, while a small saved-state model owns scale, section, and anchor.

**Tech Stack:** Kotlin, Android Views, AndroidX Activity/Lifecycle/RecyclerView, JUnit 4, Android instrumentation tests, API 36–37.

**Spec:** `docs/superpowers/specs/2026-09-08-gallery-zoom-fold-design.md`

## Global Constraints

- Package and application ID remain `io.github.mesteriis.lik`; one `:app` module.
- JDK 17, AGP 9.2.1, Gradle 9.4.1; min SDK 36, compile/target SDK 37.
- Local MediaStore photos stay in place; only explicit Google Photos shares create private copies.
- ML/AI, face scanning, maps, model downloads, and background services remain out of scope.
- User-facing text is present in English and Russian; layouts support light/dark themes, large fonts, and resizable windows.
- Existing launcher aliases and supplied emblem PNGs remain unchanged.

---

### Task 1: Date-aware timeline model

**Files:**
- Create: `app/src/main/java/io/github/mesteriis/lik/gallery/GalleryTimeline.kt`
- Create: `app/src/test/java/io/github/mesteriis/lik/gallery/GalleryTimelineTest.kt`
- Modify: `app/src/main/java/io/github/mesteriis/lik/gallery/GalleryPhoto.kt`

**Interfaces:**
- Produces: `enum class TimelineLevel`, `sealed interface TimelineEntry`, and `GalleryTimeline.build(photos, level, zoneId, locale): List<TimelineEntry>`.
- Produces: `GalleryPhoto.takenAt: Long?` and `GalleryPhoto.timelineAt`, with `addedAt` fallback.

- [x] **Step 1: Write failing tests** for capture-date precedence, added-date fallback, undated ordering, day/week/month/year grouping, cross-year locale weeks, deterministic covers, and total counts using literal UTC fixtures.
- [x] **Step 2: Run** `./gradlew testDebugUnitTest --tests io.github.mesteriis.lik.gallery.GalleryTimelineTest` and confirm compilation/failure because the model is absent.
- [x] **Step 3: Implement** immutable timeline entries and calendar grouping with `java.time`; make every photo occur exactly once at each level and keep newest-first ordering stable by ID.
- [x] **Step 4: Run** the focused JVM test and confirm all timeline cases pass.
- [x] **Step 5: Commit** the model and its tests.

### Task 2: Capture-date extraction

**Files:**
- Modify: `app/src/main/java/io/github/mesteriis/lik/gallery/GalleryPhoto.kt`
- Modify: `app/src/test/java/io/github/mesteriis/lik/gallery/GalleryTimelineTest.kt`
- Modify: `app/src/androidTest/java/io/github/mesteriis/lik/GalleryTest.kt`

**Interfaces:**
- Consumes: `GalleryPhoto.takenAt` from Task 1.
- Produces: MediaStore `DATE_TAKEN` values for device photos and EXIF `DateTimeOriginal` values for private imports, falling back to `addedAt` only when absent.

- [x] **Step 1: Add a failing instrumentation assertion** that a MediaStore item exposes its explicit `DATE_TAKEN` independently of `DATE_ADDED`.
- [x] **Step 2: Run** the focused `GalleryTest` on the selected API 37 emulator and observe the missing value.
- [x] **Step 3: Query `DATE_TAKEN`** and parse imported EXIF date/time plus offset without changing either source file.
- [x] **Step 4: Run** focused JVM and instrumentation tests and confirm capture date controls grouping.
- [x] **Step 5: Commit** date extraction.

### Task 3: Virtualized timeline renderer

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/io/github/mesteriis/lik/ui/TimelineAdapter.kt`
- Create: `app/src/main/res/layout/item_timeline_header.xml`
- Create: `app/src/main/res/layout/item_timeline_photo.xml`
- Create: `app/src/main/res/layout/item_timeline_period.xml`
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values-night/colors.xml`

**Interfaces:**
- Consumes: `List<TimelineEntry>` and `TimelineLevel`.
- Produces: stable RecyclerView items, full-span headers, asymmetric day tiles, overview cards, bounded thumbnail decoding, retry, click and long-click callbacks.

- [x] **Step 1: Add a failing UI test** asserting that only visible timeline cells are attached for a 120-photo library and that period rows expose their counts.
- [x] **Step 2: Run** the focused UI test and confirm the old GridView contract fails.
- [x] **Step 3: Add RecyclerView** through the version catalog and implement the adapter with stable IDs, span sizes, source aspect ratios in Photo mode, and a bounded bitmap cache.
- [x] **Step 4: Run** unit tests and assemble debug to confirm the renderer compiles.
- [x] **Step 5: Commit** the renderer.

### Task 4: Approved gallery shell and interactions

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/io/github/mesteriis/lik/ui/MainActivity.kt`
- Create: `app/src/main/java/io/github/mesteriis/lik/ui/GalleryUiState.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Create: `app/src/main/res/drawable/lik_nav_selected.xml`

**Interfaces:**
- Consumes: `GalleryTimeline`, `TimelineAdapter`, and current `ImportViewModel` state.
- Produces: the selected header, five direct scale controls, pinch level changes, anchored relayout, `Лента · Альбомы · Места · Люди · Ещё`, import instructions, permission states, and saved scale/section/anchor.

- [x] **Step 1: Add failing UI tests** for default Days, direct Years selection, section placeholder/return, local-photo selection protection, and retained level after recreation.
- [x] **Step 2: Run** the focused UI tests and observe missing controls and old local selection behavior.
- [x] **Step 3: Replace the main layout and Activity binding**, preserve import/delete logic, show an honest placeholder for future sections, and switch timeline level by controls or one-step pinch while retaining the centered photo anchor.
- [x] **Step 4: Run** UI tests on the selected emulator and inspect both outer-sized and inner-sized resizable windows.
- [x] **Step 5: Commit** the gallery shell.

### Task 5: Viewer return anchor and refined viewer chrome

**Files:**
- Modify: `app/src/main/java/io/github/mesteriis/lik/gallery/PhotoViewerActivity.kt`
- Modify: `app/src/main/res/layout/activity_photo_viewer.xml`
- Modify: `app/src/androidTest/java/io/github/mesteriis/lik/PhotoViewerTest.kt`
- Modify: `app/src/main/java/io/github/mesteriis/lik/ui/MainActivity.kt`

**Interfaces:**
- Produces: `PhotoViewerActivity.EXTRA_RESULT_PHOTO_ID`; the gallery restores around the last swiped photo without changing its timeline level.

- [x] **Step 1: Add a failing instrumentation test** that swipes/moves to another photo, closes the viewer, and receives that photo ID.
- [x] **Step 2: Run** the focused viewer test and confirm the result is absent.
- [x] **Step 3: Return the current ID** on back/finish, launch the viewer for result, and simplify viewer controls over the dark photo surface while preserving delete-copy behavior.
- [x] **Step 4: Run** viewer and gallery instrumentation tests.
- [x] **Step 5: Commit** viewer return behavior.

### Task 6: Documentation and acceptance

**Files:**
- Modify: `README.md`
- Modify: `docs/FEATURE_MATRIX.md`
- Modify: `docs/VERIFICATION.md`

**Interfaces:**
- Consumes: final implementation and verification evidence from Tasks 1–5.

- [x] **Step 1: Update provenance and feature status** from selected design to implemented behavior, retaining the distinction between original code, design assets, and external references.
- [x] **Step 2: Run** `./gradlew lint assembleDebug assembleRelease assembleDebugAndroidTest`.
- [x] **Step 3: Run** `ANDROID_SERIAL=<api-37-emulator> ./gradlew connectedDebugAndroidTest` on the explicitly selected emulator.
- [ ] **Step 4: Install and inspect** the debug build on the requested Fold, confirm existing MediaStore photos appear after permission, then verify the same build after the device Android update if that update is available.
- [x] **Step 5: Review `git diff`** against every specification section, confirm no launcher PNG changed, and commit the final documentation/acceptance record.
