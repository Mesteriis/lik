# Lik Full Development Implementation Plan

> **For agentic workers:** Use test-driven development for behavior changes. Work only in the assigned task, run focused tests red/green, then the full required verification before committing.

**Goal:** Complete T00-T12 for a photo-only Android gallery with stable local/Google Share flows, a persistent catalog, organization, export and trash, and three built-in offline AI profiles.

**Architecture:** Keep one native Android Views `:app` module. Room owns persistent metadata and user relationships while `PhotoStore` owns private imported files and MediaStore owns device originals. AI profiles are immutable, versioned sets of search, OCR, and face pipelines; profile and compatible index generations switch atomically.

**Tech Stack:** Kotlin, Android Views, JDK 17, AGP 9.2.1, Gradle 9.4.1, min SDK 36, compile/target SDK 37, Room, Paging 3, WorkManager, ONNX Runtime Android, USearch JNI.

## Global Constraints

- Package/application ID remains `io.github.mesteriis.lik`; one `:app` module; no compatibility branches below API 36.
- Local photos remain MediaStore-owned and read-only. Google Photos remains explicit Share import into private copies. Only photos are in scope.
- User-facing text is English and Russian; support light/dark themes, large fonts, resizable windows, and Fold state restoration.
- Preserve all ten user PNGs and stable launcher alias component names; exactly one launcher alias remains enabled.
- Never commit downloaded weights, model exports, SDK paths, signing material, or build outputs. Commit pinned model manifests, hashes, licenses, and reproducible preparation scripts.
- Three profiles are visible and selectable: Compact, Balanced (default), Extended. Each is a coherent set for semantic search, OCR, and people; all weights are packaged from an external verified cache into ordinary debug/release APKs for distribution builds.
- Models are offline and user-supplied profiles are unsupported. Switching keeps the old profile active until all enabled feature indexes for the new profile are ready, then switches profile plus index generations atomically.
- Private-import trash retention is 30 days. MediaStore and Google originals are never deleted.
- Keep README provenance, `docs/FEATURE_MATRIX.md`, `docs/MODEL_ARCHITECTURE.md`, and `docs/VERIFICATION.md` current.

### Task 0: Documentation contract

Reconcile AGENTS, README, feature matrix, Google import, architecture/model architecture, and verification docs. Mark obsolete Photo Picker and old grid decisions historical. Distinguish implementation, automated evidence, physical Fold acceptance, and genuine Google cloud Share acceptance. Record the three AI profiles and external-cache packaging constraint without claiming models were measured or bundled when they were not.

### Task 1: Import summary and operation identity

Add a one-shot, recreation-safe import result with added/duplicate/failed counts and typed failure reasons. Separate malformed batch rejection from per-photo failure and distinguish a busy operation from invalid input. Preserve limits of 50 photos and 200 MiB each, provider ownership checks, published files, and startup cleanup of temporary files. Add focused JVM/instrumentation tests and EN/RU strings.

### Task 2: Timeline identity, pinch, and viewer consistency

Disable hash-derived RecyclerView stable IDs while retaining full stable-key DiffUtil identity. Route a captured two-pointer stream through completion/cancel and change at most one zoom level per completed gesture. Clear stale viewer bitmap/details on ID change, reject stale loads by request ID/content revision, and disable destructive actions during loading/error/deleting. Cover collision, cancel, A-to-broken-B, and rapid navigation regressions.

### Task 3: Timeline and thumbnail scheduling

Move timeline construction and diff computation off the main thread and publish only the latest immutable revision. Do not rebuild timeline for import-progress-only state changes. Extract a two-worker thumbnail loader with bounded queue, coalescing, cancellation for recycled views, visible priority, and cache keys containing ID/source revision/target size/access epoch. Never decode on the caller/UI thread when saturated. Test superseded revisions and bounded work.

### Task 4: Access state, screen state, and Fold anchors

Model scanning, empty, partial, denied, permanently denied, and source error states. Recheck access on resume, invalidate device thumbnails and in-flight results when access changes, and leave private imports available. Offer app settings after permanent denial. Capture the photo nearest pinch focus and relative offset; preserve anchors on resize; fall back to nearest chronological photo if the ID disappears. Add API 36/37 instrumentation for permission changes, two-pointer gestures, resize, recreation, large fonts, and selection.

### Task 5: Persistent media catalog

Add Room with records for internal media ID, source, source key, volume/version identity, URI or private file ID, display name, MIME, dimensions, byte size, taken/added/modified timestamps with date source/offset, bucket/folder, content revision, availability, and last-seen time. Preserve imported SHA-256 IDs and files. Make imported-file migration idempotent and crash-safe. Never merge cross-source objects by name/date heuristics. Test identity reuse, unknown metadata, DST boundaries, and migration retries.

### Task 6: Incremental scanning and paging

Add cancellable batched MediaStore scanning, ContentObserver event coalescing, on-resume reconciliation, version/generation handling, and explicit deletion reconciliation. Loss of permission or limited-selection visibility marks items inaccessible and preserves metadata/collections. Use Room/Paging 3 for feed pages and database aggregates for timeline counts/covers. Viewer reads current/previous/next by media ID and sort order without full catalog loads. Cache EXIF by content revision. Test insert/update/delete, version reset, cancellation, revoked URI, and 2k/20k/100k metadata loads.

### Task 7: Albums, favorites, tags, and conventional search

Implement device folders, Lik virtual albums, Lik favorites, and manual tags as catalog relationships without copying/moving media. Search by display name, date range, source, and tag with Cyrillic preserved. Allow selection of either source, but compute operations per item so device originals cannot be deleted. Add real Albums and Search UI, and route More to trash and AI settings. Preserve relationships while media is inaccessible. Test idempotent membership, revoked access, duplicate names, missing dates, and action capabilities.

### Task 8: Share, export, and 30-day trash

Share selected media via narrow temporary grants and save copies via the system document destination. FileProvider may expose only prepared export/private paths. Cancellation/failure preserves sources and cleans incomplete output. Deleting an imported copy atomically marks it trashed, excludes it from normal/AI results, and retains its file/relationships. Restore, purge-now confirmation, and re-import restore are supported. WorkManager plus app-start reconciliation purges items after 30x24 hours, serialized against restore. Test grant isolation, cancellation, no-space/revoked destination, retention boundary, race safety, and original preservation.

### Task 9: Built-in profile artifacts and evidence harness

Define immutable Compact/Balanced/Extended profile manifests and reproducible preparation/verification scripts. Compact uses CLIP ViT-B/32 plus aligned multilingual text, PP-OCRv5 mobile detector/Cyrillic recognizer, YuNet/SFace. Balanced uses SigLIP 2 Base 224 with the same OCR/face set. Extended uses SigLIP 2 Large 256, PP-OCRv5 server detector/Cyrillic recognizer, YuNet/SFace. Pin exact upstream revisions, filenames, sizes, hashes, licenses, tokenizer/preprocessing/output contracts, and ONNX exports. Build assets from an external cache; distribution build verification fails if any required artifact is missing or mismatched. Provide an RU/EN evaluation harness (minimum 30 queries each) and record measurements only after real runs.

### Task 10: AI profile settings, runtime, indexing, and semantic search

Add Settings > AI with three profile cards, default Balanced, component names, verified sizes/measurements, per-feature toggles, active/preparing status, progress, pause/resume/cancel, and inactive-index cleanup. ModelCatalog is the sole persisted selection source. Verify bundled artifacts/self-test, build enabled-feature index generations, keep the old profile serving, then atomically activate profile+indexes. Use a private ONNX Runtime process, one heavy inference task at a time, and USearch behind a narrow JNI interface with exact-search reference tests. WorkManager indexing runs after opt-in while charging/storage/battery/thermal constraints permit; manual foreground indexing and interactive search preempt background work. Check source access/revision before committing results.

### Task 11: OCR and people

Run RU/EN OCR with the active profile, persist text by media revision/pipeline generation, display/copy it, and include it in local search. Detect/align/embed faces with YuNet/SFace and expose people groups with rename, merge, split, and false-match exclusion. Store manual identities/corrections separately from computed clusters so profile switches and reindexing preserve them. Test Cyrillic/Latin OCR, corrupt/revoked media, resumed indexing, profile generation isolation, and manual people corrections.

### Task 12: Exact duplicates and similar photos

Persist exact content SHA-256 and a separate perceptual fingerprint relation without changing media identity. Add comparison UI showing source, dimensions, size, and allowed actions. Never auto-delete or merge. Imported-photo deletion uses trash; device originals remain read-only. Test exact duplicates across sources, visually similar edits, false positives, inaccessible items, and user-confirmed action routing.

## Required Verification

Run `./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` and `ANDROID_SERIAL=<explicit-emulator> ./gradlew connectedDebugAndroidTest`. Validate API 36/37, 16 KiB page compatibility, EN/RU, light/dark, large fonts, resize, and physical Fold/real Google Share separately. Every verification entry records commit, build, device/API, exact command, result, and open physical acceptance.
