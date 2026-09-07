# Lik scaffold implementation plan

**Goal:** Подготовить запускаемый Android-каркас Lik по соглашениям Rune Keyboard.

**Architecture:** Один модуль `:app`; системные Activity и XML-ресурсы. Стартовый экран и выбор одной из десяти иконок через launcher aliases. Без логики галереи и runtime-библиотек помимо Kotlin stdlib.

**Tech Stack:** Kotlin, AGP 9.2.1, Gradle 9.4.1, JDK 17, Android 17 (API 37).

**Spec:** `docs/superpowers/specs/2026-09-07-lik-scaffold-design.md`.

## Этапы

- [x] Проверить пустой remote, изучить `rune-keyboard`, настроить Git и Wrapper.
- [x] Создать `app/build.gradle.kts`, manifest, `ui/MainActivity.kt`, layout, локализации и темы.
- [x] Подключить предоставленную PNG и adaptive icon.
- [x] Добавить `LaunchSmokeTest`, GitHub Actions, README и описание структуры.
- [x] По дополнительному запросу добавить настройки, все 10 PNG и атомарное переключение aliases; воспроизвести отсутствие экрана настроек в instrumentation перед реализацией.
- [x] Выполнить `./gradlew lint assembleDebug assembleRelease assembleDebugAndroidTest`.
- [x] Выполнить `connectedDebugAndroidTest` на эмуляторе API 37; проверить интерфейс.
- [x] Проверить Git diff и итоговые APK перед локальным начальным коммитом.
