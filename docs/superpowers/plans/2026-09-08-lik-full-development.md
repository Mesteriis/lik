# Lik Full Development Implementation Plan

> **For agentic workers:** Use test-driven development for behavior changes. Work only in the assigned task, run focused tests red/green, then the full required verification before committing.

**Goal:** Complete T00-T13 for a photo-only Android gallery with stable local/Google Share flows, a persistent catalog, organization, export and trash, three built-in offline AI profiles, and biometric protection for sensitive photos.

**Architecture:** Keep one native Android Views `:app` module. Room owns persistent metadata and user relationships while `PhotoStore` owns private imported files and MediaStore owns device originals. AI profiles are immutable, versioned sets of search, OCR, and face pipelines; profile and compatible index generations switch atomically.

**Tech Stack:** Kotlin, Android Views, JDK 17, AGP 9.2.1, Gradle 9.4.1, min SDK 36, compile/target SDK 37, Room, Paging 3, WorkManager, ONNX Runtime Android, USearch JNI.

## Global Constraints

- Package/application ID remains `io.github.mesteriis.lik`; one `:app` module; no compatibility branches below API 36.
- Local photos remain MediaStore-owned and read-only. Google Photos remains explicit Share import into private copies. Only photos are in scope.
- User-facing text is English and Russian; support light/dark themes, large fonts, resizable windows, and Fold state restoration.
- Preserve all ten user PNGs and stable launcher alias component names; exactly one launcher alias remains enabled.
- Never commit downloaded weights, model exports, SDK paths, signing material, or build outputs. Commit pinned model manifests, hashes, licenses, and reproducible preparation scripts.
- Three profiles are visible and selectable: Compact, Balanced (default), Extended. Each is a coherent set for semantic search, OCR, and people; all weights/tokenizers are downloaded explicitly from immutable Hugging Face URLs in Settings and must never be packaged in debug/release/distribution APKs. Balanced selection alone is not runtime readiness.
- Models are offline and user-supplied profiles are unsupported. Switching keeps the old profile active until all enabled feature indexes for the new profile are ready, then switches profile plus index generations atomically.
- Private-import trash retention is 30 days. MediaStore and Google originals are never deleted.
- Keep README provenance, `docs/FEATURE_MATRIX.md`, `docs/MODEL_ARCHITECTURE.md`, and `docs/VERIFICATION.md` current.

### Task 0: Documentation contract

Reconcile AGENTS, README, feature matrix, Google import, architecture/model architecture, and verification docs. Mark obsolete Photo Picker and old grid decisions historical. Distinguish implementation, automated evidence, physical Fold acceptance, and genuine Google cloud Share acceptance. Record the three AI profiles and HF Settings-download/no-bundled-weights constraint without claiming models were measured or bundled when they were not.

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

Define immutable Compact/Balanced/Extended profile manifests and reproducible preparation/verification scripts. Compact uses CLIP ViT-B/32 plus aligned multilingual text, PP-OCRv5 mobile detector/Cyrillic recognizer, YuNet/SFace. Balanced uses SigLIP 2 Base 224 with the same OCR/face set. Extended uses SigLIP 2 Large 256, PP-OCRv5 server detector/Cyrillic recognizer, YuNet/SFace. Pin exact upstream revisions, filenames, sizes, hashes, licenses, tokenizer/preprocessing/output contracts, and ONNX exports. Keep actual source/reference exports and verified HF conversions in an external developer cache. All APKs contain metadata/licenses only; distribution verification rejects bundled model/tokenizer payloads. Every runtime dependency has an immutable HF URL; community conversions are allowed only with explicit converter/license provenance and independent publisher parity. Provide an RU/EN evaluation harness (minimum 30 queries each) and record measurements only after real runs.

User extension: include one deduplicated shared local NSFW screening classifier with primary publisher provenance, exact source and artifact pins, CPU export parity, preprocessing, ordered labels and explicit uncalibrated threshold state. Add an RU/EN sensitive-content evaluation slice. `docs/SENSITIVE_MEDIA.md` defines the selected classifier and Task 13 privacy contract; artifact validation alone must not be described as implemented privacy protection.

### Task 10: AI profile settings, runtime, indexing, and semantic search

Add Settings > AI with three profile cards, default Balanced, component names, verified sizes/measurements, per-feature toggles, active/preparing status, progress, pause/resume/cancel, and inactive-index cleanup. ModelCatalog is the sole persisted selection source. Implement explicit Hugging Face Settings downloads with INTERNET permission, resumable durable Range requests, private per-file staging and operation journals, low-space reservations, progress/network/cancel/retry controls, exact size/SHA verification, atomic publication, shared-artifact dedup and safe cleanup. No arbitrary URLs or imported model files. Selected Balanced stays unready until download and self-test complete. Build enabled-feature index generations, keep the old profile serving, then atomically activate profile+indexes. Use a private ONNX Runtime process, one heavy inference task at a time, and USearch behind a narrow JNI interface with exact-search reference tests. WorkManager indexing runs after opt-in while charging/storage/battery/thermal constraints permit; manual foreground indexing and interactive search preempt background work. Check source access/revision before committing results.

User clarification during Task 9: keep CPU as required reference/fallback and consider Samsung/NNAPI/vendor NPU only as an experimental backend, following `models/backend-policy-v1.json` and `docs/SAMSUNG_BACKENDS.md`. No Galaxy AI foundation-model access is assumed. Actual Fold provider assignments, unsupported operators, complete pipeline parity, cold/warm latency, memory and sustained thermal evidence must precede accelerator opt-in; backend availability is not a profile-completion dependency.

Also implement the separate optional AiGate consumer contract in `docs/AIGATE_INTEGRATION.md`: explicit EN/RU opt-in and loopback port settings, health/model discovery, Open AiGate action, no provider secrets in Lik, and a deliberate per-photo action before sending a resized metadata-stripped image. Use only `127.0.0.1` with narrow cleartext handling, cancellation/timeouts and a visible local-versus-router distinction. Do not infer vision capability from the current `/v1/models` response or silently write router output into local indexes. Built-in profiles remain offline/default and independent. Task 9 records this contract only; Task 10 implements and tests the connector.

Schedule shared sensitive screening ahead of other indexing after import/content change. Preserve the future visibility boundary: hidden/sensitive media requires an authenticated reveal and a separate send action before AiGate receives it. Screening errors never imply safe content.

### Task 11: OCR and people

Run RU/EN OCR with the active profile, persist text by media revision/pipeline generation, display/copy it, and include it in local search. Detect/align/embed faces with YuNet/SFace and expose people groups with rename, merge, split, and false-match exclusion. Store manual identities/corrections separately from computed clusters so profile switches and reindexing preserve them. Test Cyrillic/Latin OCR, corrupt/revoked media, resumed indexing, profile generation isolation, and manual people corrections.

### Task 12: Exact duplicates and similar photos

Persist exact content SHA-256 and a separate perceptual fingerprint relation without changing media identity. Add comparison UI showing source, dimensions, size, and allowed actions. Never auto-delete or merge. Imported-photo deletion uses trash; device originals remain read-only. Test exact duplicates across sources, visually similar edits, false positives, inaccessible items, and user-confirmed action routing.

### Task 13: Sensitive-photo quarantine and biometric reveal

Implement `docs/SENSITIVE_MEDIA.md`: quarantine new/revised/unclassified photos; hide sensitive content by default across every feed, thumbnail, search, viewer, export and AI/router boundary; persist manual overrides separately from model results. Expose top-bar reveal using BIOMETRIC_STRONG only, no credential fallback, with an in-memory lease that relocks on background/screen lock/process restart. Failed/unavailable biometrics preserve hiding. Freeze a calibrated classifier threshold only after real disjoint evaluation; missing calibration keeps automatic safe decisions disabled. Test cross-surface access, async races, cancellation, content revision changes, all authentication failure paths and router's separate send authorization.

## Required Verification

Run `./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` and `ANDROID_SERIAL=<explicit-emulator> ./gradlew connectedDebugAndroidTest`. Validate API 36/37, 16 KiB page compatibility, EN/RU, light/dark, large fonts, resize, and physical Fold/real Google Share separately. Every verification entry records commit, build, device/API, exact command, result, and open physical acceptance.

### Final whole-branch review fix wave — 9 September 2026

One integrated wave based on `6b6241f777fc0c2495e75da9e9533b1c111eb835` addresses all nine Important and both Minor findings. The commit containing this entry retains pipeline lock identities, unifies sorted multi-key acquisition, normalizes ACTIVE profile cards, adds durable opt-in-aware catalog scheduling, cancels every ORT graph path, bounds People clustering outside global publication locks with indexed seek/update SQL, decodes actual OCR posteriors, and rechecks organization/trash plus non-screening inference/publication privacy. Room v16 retains a general catalog/privacy revision separate from Similarity's SAFE-only revision and durable budgets. Packaging/current-scope documentation is consistent. No reviewer or subagent was dispatched for this wave.

Observed RED/GREEN evidence is recorded finding-by-finding in `.superpowers/sdd/2026-09-08-lik-full-development/final-fix-report.md`: 199 same-key overlaps in 1.2M calls; conflicting maintenance lock order; duplicate ACTIVE cards including persisted state; absent catalog screening/successor work; interrupted/preempted inference and pending-ID cleanup; unbounded/interruption-ignoring 8k-face clustering and actual DAO query plans requiring full-generation scan/sort; incorrect/malformed OCR posterior confidence; stale final organization render/retained purge after relock; revoked SAFE admission and transactional OCR/face commit. The corrected focused suites pass, including 11/11 final persistence/migration tests and real-model image/text cancellation followed by interactive queries. Test-only fixture corrections replace lifecycle/Room assumptions and a one-second provider sleep with actual-state synchronization; unsuccessful full attempts are retained in the report.

- Clean compiler preparation: `./gradlew clean lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest --no-build-cache -x :app:verifyDebugModelPayloads -x :app:verifyReleaseModelPayloads -x :app:verifyDebugAndroidTestModelPayloads` — **PASS, 42s, 139 tasks**. `python3 scripts/models/compiled_apk.py --output /tmp/lik-final-fix-compiled-final-candidate.json` generated compiler-only receipts, reviewed before `apply_patch` enrollment and `cmp`; no APK-derived trust enrollment.
- Required final matrix: `./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` — **PASS, 7s, 139 tasks; 167 JVM tests, zero failures/errors/skips**. All APK checks enabled; debug/release/androidTest **61,670,187 / 48,630,885 / 1,694,588 bytes**, metadata-only, zero ONNX/tokenizer payloads.
- `ANDROID_SERIAL=emulator-5582 ./gradlew connectedDebugAndroidTest` — **PASS, 4m17s**, fresh `lik_api36_qa`, API 36 / 4096-byte pages; XML **196 cases, 188 passed, eight expected external-probe skips, zero failures/errors**, 253.034s. `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest` — **PASS, 4m29s**, fresh `lik_api37_qa`, API 37 / 16,384-byte pages; XML **196 cases, 188 passed, eight expected external-probe skips, zero failures/errors**, 263.544s. Gradle reports 204 progress completions per suite because it counts skip notifications again. Final XML copies: `/tmp/lik-final-fix-api36-final-acceptance.xml`, `/tmp/lik-final-fix-api37-final-acceptance.xml`.
- Python: `LIK_MODEL_CACHE=/Users/avm/Library/Caches/Lik/model-artifacts /Users/avm/Library/Caches/Lik/model-artifacts/venv/bin/python -m unittest discover -s scripts/models/tests -p 'test_*.py' -v` — **PASS, 46/46, 25.274s, zero skips**.
- External artifacts/runtime: `scripts/models/artifacts.py verify --cache /Users/avm/Library/Caches/Lik/model-artifacts` verified **29 unique pinned files / 3,663,983,902 bytes** using the cache's Python environment. Explicit emulator-5580 provisioning with `provision_lik_runtime.py` and `provision_android_probe.py` preceded the eight-test real-model command recorded in the report: **PASS, 8/8, 49.748s, zero skips** on final production APK bytes. All three profiles, twelve graph/output references, Russian image/text search, native index publication, real HF Range resume, staging recovery, runtime reuse/rebind and cancellation were exercised. The final later edits affect test fixtures only; production APK hashes remain identical to that run.
- Build identity: Android source fingerprint `a0d7fe085c7999c09b2561c7d68490725ae90bd55ecc4aef2e1ff22d805fc5f6`; compiler policy SHA-256 `3404a1d43bae94e1148971459e94d323718a98ad81ad5843263e3cb845f97856`. APK hashes and full device results are in `docs/VERIFICATION.md` and the final-fix report.

Physical Fold strong biometric/OEM lock/user-switch behavior, genuine Google Photos Share, broad EN/RU theme/maximum-font/resize visual acceptance, Samsung accelerator parity, sustained physical-device performance and labeled model-quality/calibration measurements remain separate acceptance. Automatic SAFE is still disabled (`releaseThreshold=null`); 128-face bounded automatic groups may require manual merging across pages. No physical install, original-source mutation, model/tokenizer bundling or reference-submodule edit/build occurred.

### Final I4 reconciliation addendum — 9 September 2026

The final independent re-review identified and then cleared one residual T10/T13 integration edge. WorkManager can recursively fail an already-appended catalog successor when its still-running predecessor later returns permanent failure. `AiCatalogScheduler` now observes the latest screening root and replays the current durable catalog/model plan only when that root inherited failure without ever running. It does not replay downstream permanent failures or roots with a real attempt, preserving WorkManager retry/backoff semantics. RED/GREEN instrumentation covers the exact RUNNING predecessor → BLOCKED successor → inherited failure → complete replacement sequence; a JVM policy test excludes the retry-loop variants. The final scoped re-review reports no Critical/Important findings.

Final acceptance on this addendum: required Gradle matrix **PASS with 168 JVM tests**; API 36 and API 37 full XML each **197 cases, 189 passed + eight expected external-model skips, zero failures/errors**; Python **46/46**; final-Dex externally provisioned real-model/runtime checks **8/8**; 29 pinned external files / 3,663,983,902 bytes verified. Source fingerprint is `806fd7add23a40915bf4df0708ddec30bca8356e246d19287fae7824faa1cdba`; compiler policy SHA-256 is `91cc9abb40d2c888bf4ab731ddf51a93568628e5aca01cea2b8f152061d228a5`. All APKs remain metadata-only with zero model/tokenizer payloads. The previously recorded physical/OEM/Google Photos/visual/performance/quality/calibration boundaries remain open and are not part of automated plan completion.
