# Lik

## Scope

Lik (Лик) is an Android AI gallery. The repository contains a gallery that reads permitted device photos directly from MediaStore, accepts explicit Google Photos → Share → Lik imports as private copies, and provides an app-icon picker in Settings. Local originals stay in MediaStore; only explicitly shared imports are copied. The generic system Photo Picker is historical and must not be restored as Google integration. Do not add broad media access, AI providers, model downloads or background services as incidental setup.

## Conventions

- Kotlin and native Android Views, following the neighboring Rune Keyboard project.
- One `:app` module; package and application ID: `io.github.mesteriis.lik`.
- JDK 17, AGP 9.2.1, Gradle 9.4.1; min SDK 36, compile/target SDK 37. Support Android 16 on the target Galaxy Fold and Android 17 after its update; do not add compatibility branches for older releases.
- Keep dependency versions in `gradle/libs.versions.toml`; use the checked-in Wrapper.
- User-facing strings belong in English and Russian resources. Support system light/dark themes, large fonts and resizable windows.
- Keep Android lifecycle code small; add packages and dependencies only when functionality needs them.
- Never commit local SDK paths, signing material, build outputs or downloaded models.
- `references/` contains pinned upstream Git submodules for research, not Gradle modules or app assets. Do not edit or build them as part of routine Lik work. Upstream tracked assets remain in their own repositories.
- Keep `docs/FEATURE_MATRIX.md` and the README provenance table current as features are selected or implemented. Record source commit/files and distinguish ideas from copied code, plans from implementation, and repository licenses from model licenses.
- Model work must support multiple installed models and coherent per-feature model sets; follow `docs/MODEL_ARCHITECTURE.md` as the current proposal. Do not copy Rune's global single-active-model cleanup into a multi-model store.
- The planned offline profiles are Compact, Balanced (the default), and Extended. They are immutable versioned search/OCR/people sets; user-supplied profiles are unsupported. Distribution builds must package verified artifacts from an external cache, but no model artifacts are currently bundled or measured.
- All ten launcher variants use the user-supplied PNGs in `app/src/main/res/drawable-nodpi/lik_emblem*.png`. Preserve their alpha channels; the adaptive background is a separate color resource.
- Launcher alias names are persistent identities. Keep them stable across releases. Exactly one alias must be enabled; use the atomic PackageManager batch API and derive the selected icon from component state.

## Verification

Run `./gradlew lint assembleDebug assembleRelease assembleDebugAndroidTest` for changes to the scaffold. Add JVM tests for real business rules as they appear. Run the existing launch/lifecycle smoke test with `ANDROID_SERIAL=<emulator-serial> ./gradlew connectedDebugAndroidTest` when changing the Activity or manifest. Explicitly select an emulator; do not install onto a physical device unless requested.
