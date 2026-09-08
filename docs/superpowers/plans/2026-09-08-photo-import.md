# Photo import implementation plan

> **Superseded:** the generic Photo Picker import described here was replaced on 2026-09-08 by direct MediaStore viewing plus Google Photos → Share → Lik. See `../specs/2026-09-08-device-media-gallery-design.md`.
**Goal:** Import user-selected photos into Lik through Photo Picker and Android Share.

**Architecture:** A pure Kotlin atomic file store, an Android ViewModel for URI ingestion, and native Views for progress and saved images. One isolated exported Activity accepts shares; launcher aliases remain stable.

**Tech stack:** Kotlin, Android 16–17 / API 36–37, AndroidX Activity/ViewModel/LiveData, JUnit and Android instrumentation.

**Spec:** [Import design](../../GOOGLE_PHOTOS_IMPORT.md). User approved implementation in the current task. Execute inline.

## Constraints

min SDK 36, compile/target SDK 37; existing aliases and user icons preserved; no broad media/network/account permissions; EN/RU; no personal data in logs; no OEM code copied.

## Steps

- [x] Write PhotoStore tests for exact byte preservation, duplicate input, interrupted/invalid/oversized reads and restart cleanup; run `testDebugUnitTest` to observe missing behavior.
- [x] Implement `PhotoStore.importPhoto(InputStream)` and `photos()` with injected image validation, streaming SHA-256, bounded copy and atomic rename; run those tests.
- [x] Add Android tests that import real test-provider images through Share and exercise picker cancel and Activity recreation.
- [x] Implement URI intake/ViewModel, picker and share entry points, grid/preview, progress and EN/RU errors. Keep thumbnail decoding off the main thread and bounded.
- [x] Run `lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest`, then all instrumentation tests on the explicitly selected API 37 emulator. Inspect rendered screen.
- [ ] Install on the user-requested Fold (API 36 or 37) when connected and perform user-selected cloud import; record any remaining device constraint without claiming cloud success from a local fixture.
- [x] Update README provenance, feature matrix and architecture with actual behavior and verification.

Physical-device cloud verification uses the user-requested Galaxy Fold; the app supports its current API 36 firmware and API 37 after update.
