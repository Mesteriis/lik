# Проверка каркаса Lik · 7 сентября 2026

## Task 8 · review round 1 · 8 сентября 2026

Убрано удаление внешнего destination после ошибки Save copy: CREATE_DOCUMENT может вернуть существующий документ и не доказывает владение Lik. Остаётся finally-cleanup только app snapshot; подготовка source завершается до открытия destination. Обычная запись провайдера может уже изменить внешний документ, поэтому безопасный rollback не заявляется.

- Real API37 RED: три новых теста separate-UID DocumentsProvider сначала читают существующий sentinel, получают тот же URI через createDocument и вызывают source-read, no-space и security failures. Старый код удалял документ: `expected:<1> but was:<0>` во всех трёх случаях (`/tmp/lik-task8-review1-red.log`). После исправления проверяются наличие документа, точные sentinel-байты, исходные байты и уборка app temp.
- Focused GREEN: **8 тестов**, 0 failures/skips, 13s (`/tmp/lik-task8-review1-focused-final.log`). Существующие проверки отмены, write failure и original preservation не ослаблены.
- `./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` — PASS, **64 JVM-теста**, 0 failures/errors/skips; 10s (`/tmp/lik-task8-review1-build-final.log`).
- `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest` — PASS, **83 теста**, XML totals 83/0/0/0; 2m 45s (`/tmp/lik-task8-review1-connected-final.log`). API37, Android17, AVD `lik_api37_qa`, 16KiB pages. Device-original GalleryTest прошёл; физическое устройство не использовалось.
- Первый полный прогон выявил один timeout новой grant fixture: NEW_TASK доставил intent верхней Activity (START_DELIVERED_TO_TOP), а helper обрабатывал только onCreate. Добавлен onNewIntent и пропуск уже выданных grants; default grant остаётся READ-only. Остальные 82 теста того прогона прошли; финальные 83 прошли полностью. Это исправление test fixture, не production-регрессия.
- Self-review и независимый read-only review не обнаружили оставшегося external-delete пути или регрессии обычного экспорта. Production manifest, permissions и зависимости не менялись; новый provider и WRITE grant находятся только в test APK. README, FEATURE_MATRIX и MEDIA_CATALOG отражают ограничение внешней очистки.

## Task 8 · экспорт и корзина · 8 сентября 2026

Проверен commit `c8c29ed80431cd450d74c19d4faaad5c38997168`, debug/release 0.1.0 (versionCode 1), JDK17 / Gradle9.4.1 / AGP9.2.1. Room v4 и миграции v1→v2→v3→v4 сохраняют организацию; удаление приватной копии теперь означает TRASHED на 30×24 часа. Durable PURGING claim, unlink, повтор после сбоя и restore сериализованы общим PhotoStore monitor. Удаление оригиналов не добавлено.

- `./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` — PASS, **64 JVM-теста**, 0 failures/errors/skips; строгий lint и release shrink прошли. Финальный лог `/tmp/lik-task8-build-final.log`, 14s.
- `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest` — PASS, **80 тестов**, 0 failures/errors/skips; `BUILD SUCCESSFUL in 2m 4s`. XML totals проверены отдельно. `/tmp/lik-task8-connected-final.log`. AVD `lik_api37_qa`, `sdk_gphone16k_arm64`, Android17/API37; `adb -s emulator-5580 shell getconf PAGE_SIZE` = 16384.
- RED: отсутствующая `trashedAt`, отсутствующий export-provider и неправильный MIME исторического PNG-импорта (`image/*` вместо `image/png`). Логи `/tmp/lik-task8-red.log`, `/tmp/lik-task8-export-red.log`, `/tmp/lik-task8-mime-red.log`. Семь focused trash/export тестов затем GREEN (`/tmp/lik-task8-export-trash-green.log`).
- Отдельный test APK UID проверяет READ выбранного prepared URI, отказ WRITE и отказ READ невыбранного URI. FileProvider отвергает private library, database и общий cache. JVM проверяет отмену, исходные байты, ошибки чтения, no-space/disconnect/security и уборку temp. Реальные тесты проверяют retention boundary, retry Worker, reopen после purge claim, v3 migration со связями, UI restore/purge-confirm/cancel и Share→trash→повторный Share с Restored=1. Галерея проверяет сохранность и читаемость device original после удаления только приватной копии.
- Первый full-run имел четыре ошибки старого тестового formatter: helper передавал три числа после добавления четвёртого Restored. Исправлен helper; production передавал все четыре. Во втором full-run один раз повторился известный precondition mixed-source теста из Task7: `positionForPhoto=-1` до удаления. Причина не установлена; добавлены diagnostic adapter anchors/catalog IDs. Focused GalleryTest (13), тот же порядок предшествующих классов (21) и финальный full (80) прошли. Production fix этого intermittent precondition не заявляется, проверка сохранности оригинала не ослаблена.
- Save copy поддерживает одно выбранное фото за раз через CREATE_DOCUMENT; multi-share реализован. При отозванном доступе или process death неполный внешний документ может остаться вне возможности очистки Lik; app-side temp очищаются, завершённые share snapshots истекают через 24 часа. WorkManager может выполнить удаление позже retention boundary из-за ограничений ОС. Нет отдельного визуального RU/200% sweep новых экранов, физического Fold/API36 или genuine Google cloud Share; AI не добавлен. Launcher PNG/aliases и `references/` не менялись, физическое устройство не использовалось.

## Как читать журнал

Записи ниже — исторические результаты конкретных команд, APK и устройств; они не являются свежим прогоном для последующих изменений. Они разделены по силе доказательства: код описывает реализацию, JVM/instrumentation на emulator дают автоматическое evidence, а визуальная проверка на Fold — отдельную физическую приёмку. Test-provider подтверждает только Share/URI-контракт и не заменяет настоящий Google cloud Share. Проверены внешний экран Fold и запуск Google Photos; физическое раскрытие внутреннего экрана, Share обозначенного cloud-only фото, офлайн-открытие после перезапуска и повторный импорт остаются открытыми.

Проверена итоговая конфигурация: `minSdk = 36`, `targetSdk = compileSdk = 37`, AGP 9.2.1, Gradle 9.4.1, JDK 17.

## Автоматические проверки

```bash
./gradlew lint assembleDebug assembleRelease assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5562 ./gradlew connectedDebugAndroidTest
```

- Debug и unsigned release APK собраны.
- Android Lint: 0 ошибок, 0 предупреждений.
- Два instrumentation-теста на Android 17: 0 ошибок, 0 пропусков.
- Проверены запуск и пересоздание главного экрана; все десять вариантов иконок; возврат к Классической; повторный выбор альтернативы; единственный активный launcher alias; восстановление выбранного пункта после пересоздания настроек; запуск через выбранный alias.
- Этот исторический APK требовал API 37 и не объявлял разрешений; позже `minSdk` был изменён на 36, а текущий manifest объявляет разрешения на фото.
- Все десять PNG побайтно совпадают с предоставленными пользователем файлами.

## Проверка интерфейса

На отдельном эмуляторе `lik_api37_qa` (Android 17, Pixel 7) проверены открытие настроек с главного экрана, выбор «Изумруда» нажатием, единственный активный alias и холодный запуск после `am force-stop`. Выбранная иконка сохранилась. Проверены русские подписи, превью и светлая/тёмная темы.

Локальные снимки экрана и диагностические дампы находятся в игнорируемом `app/build/qa/`. Отчёты Gradle — в `app/build/reports/`.

GitHub Actions настроен для сборки, lint и instrumentation на API 37. Сам workflow ещё не запускался на GitHub.

## Референсы и матрица · 7 сентября 2026

Добавлены семь подмодулей и исследовательская документация. Код приложения, ресурсы, зависимости и конфигурация сборки в этом изменении не менялись.

- Проверено совпадение HEAD каждого подмодуля с gitlink в индексе Lik; рабочие деревья референсов чистые.
- Проверены постоянные source-ссылки: commit соответствует закреплённому подмодулю, указанный файл существует. Проверены локальные ссылки и якоря документов.
- ID матрицы непрерывны: G01–G17, M01–M15, D01–D15.
- `./gradlew --offline projects` завершился успешно; в сборку включён только `:app`.
- `git diff --check` и проверка staged diff прошли.

APK референсов не собирались и не запускались. Тесты приложения для изменения только подмодулей/документации повторно не выполнялись; предыдущие результаты каркаса приведены выше. Нагрузка ML, качество русского поиска и UX референсов на Fold остаются непроверенными.

## Галерея MVP · 8 сентября 2026

Окружение: `lik_api37_qa`, Android 17 / API 37, 1080×2400 при density 420; JDK 17, AGP 9.2.1, Gradle 9.4.1. Выбран только `emulator-5580`; подключённое физическое устройство не использовалось.

```bash
./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest
```

- Обязательная сборка, release shrink, Android Lint и 13 JVM-тестов прошли.
- 18 instrumentation-тестов прошли без ошибок и пропусков. Они покрывают MediaStore без создания приватной копии, импорт через URI/Share, ошибки пакета, lifecycle, сетку, выбор, порядок, восстановление позиции, просмотр, EXIF 1–8, JPEG/PNG/WebP, большой и повреждённый файл, удаление Google-копий и все десять launcher aliases.
- HEIC проверен вручную на API 37: файл 1024×1024 открылся, сведения показали `HEIF · 1024 × 1024 · 0.1 MB`. Также вручную открыты JPEG и PNG; проверены следующее/предыдущее фото, double tap, pan и диалог удаления.
- Проверены EN/RU, светлая/тёмная темы, шрифт 200%, portrait/landscape и узкое окно. После смены сетки с 3 на 7 колонок сохранился тот же участок списка. Настройки эмулятора возвращены к светлой теме, масштабу 100% и автоповороту.
- На 2000 синтетических PNG холодный запуск Activity занял 615 мс; текст `Saved photos: 2000` появился в accessibility tree через 3,87 с. Одновременно были прикреплены 74 View; PSS — 83 451 КиБ, RSS — 216 496 КиБ, bitmap allocations — 8194 КиБ. Сетка прокручивается, ячейки имеют высоту 315 px, ANR/OOM и crash-записей нет.
- Общий системный Photo Picker удалён из импорта, чтобы не смешивать локальные и облачные источники. Реальный путь Google Photos cloud → Share → Lik → offline остаётся отдельной пользовательской приёмкой; локальный test-provider не выдаётся за эту проверку.
- Manifest добавляет только разрешения Android на все или выбранные изображения; network/account permissions, AI/ML-зависимостей и фоновых служб нет. Приватные Google-копии исключены из backup, launcher aliases и десять пользовательских PNG не изменялись.


## Целевой Galaxy Fold · 8 сентября 2026

После уточнения целевого устройства `minSdk` снижен с 37 до 36; `compileSdk` и `targetSdk` остались 37. Это поддерживает текущий Android 16 на Fold и Android 17 после обновления без отдельной ветки совместимости.

- `./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` — успешно после изменения SDK.
- Debug APK установлен через ADB на `SM-F966B` (`RFCY706SHEB`), Android 16 / API 36.
- Сохранённый launcher alias `Aurora` корректно определён PackageManager. Финальный холодный запуск `MainActivity` занял 298 мс.
- На чистом разрешении показан системный диалог с вариантами «Разрешить ограниченный доступ», «Разрешить ко всем» и «Запретить». После полного доступа сетка показала 232 фото.
- Число файлов в приватном `files/imported_photos` до и после чтения MediaStore осталось равным 7: локальная медиатека не копировалась. Локальное фото открылось в viewer без кнопки удаления.
- Кнопка «Открыть Google Фото» запустила `com.google.android.apps.photos/.home.HomeActivity`. Русский главный экран отрисован в 1080×2520, процесс работает, crash buffer пуст.
- Запуск Google Photos не равен проверке реального cloud-only Share. Такой Share, офлайн-открытие после перезапуска и повторный импорт остаются пользовательскими шагами на открытом Fold.

## Хронологическая лента и дизайн Fold · 8 сентября 2026

Плоская сетка заменена на виртуализированную ленту с уровнями «Фото», «Дни», «Недели», «Месяцы», «Годы». Группировка использует дату съёмки и дату добавления как резерв. Верхняя панель показывает выбранную эмблему без подложки; общий adaptive background десяти launcher-иконок заменён с оранжевого на графитовый, сами PNG не изменены.

- `./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` — успешно; 24 JVM-теста, 0 ошибок.
- `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest` — 24 теста на Android 17 / API 37, 0 ошибок и пропусков.
- На эмуляторе проверена компоновка 1968×2184 при density 420, затем размер и density возвращены к исходным 1080×2400 / 420.
- Финальный debug APK установлен на `SM-F966B`, Android 16 / API 36. На внешнем экране доступны все пять равных сегментов масштаба, 234 локальных фото появляются в ленте, launcher aliases сохраняют выбранный вариант.
- В настройках Fold просмотрены десять adaptive icons с новым графитовым фоном. Верхняя эмблема использует исходный прозрачный PNG. `git diff --name-only -- app/src/main/res/drawable-nodpi` пуст: пользовательские PNG не менялись.
- Физическая проверка внутреннего экрана отложена до раскрытия устройства, а проверка реального обновлённого Fold — до появления Android 17 на устройстве. API 37 покрыт эмулятором и тем же APK-кодом без отдельной ветки совместимости; это не принятие внутреннего экрана.
- По снятому на Fold референсу Google Photos уровень «Фото» изменён на три квадратные `centerCrop`-плитки с зазором 1 dp. Строка «Фотографии / Фото: N» удалена, поэтому после верхней панели сразу расположен переключатель масштаба.
- После изменения успешно прошли 25 JVM-тестов и 25 instrumentation-тестов на Android 17. Финальный APK установлен и визуально проверен на внешнем экране Fold с реальными локальными фото.

## Постоянный каталог · Task 5 · 8 сентября 2026

Room 2.8.4 хранит метаданные и доступность; приватные файлы остаются в PhotoStore. Проверены сохранение SHA-ID, разделение device identity по volume/version/row/generation, повтор миграции после ошибки SQLite, неизвестные метаданные, DST overlap, восстановление доступности и повторное открытие базы. [Контракт каталога](MEDIA_CATALOG.md) отделяет эту реализацию от будущего incremental scan/Paging и полного EXIF cache.

- `./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` — успешно на финальном коде; 55 JVM-тестов, 0 ошибок/пропусков, строгий lint и release shrink прошли.
- `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest` — 41 тест на AVD `lik_api37_qa`, Android 17 / API 37, 0 ошибок/пропусков. Полный набор повторён после ужесточения проверки файлового inventory и снова прошёл.
- Включены пять новых тестов на настоящем Room/SQLite; существующие Share, MediaStore, viewer, access transition, lifecycle и launcher tests прошли с подключённым каталогом.
- `git diff --check` чист. Физические устройства на этом этапе не использовались.

### Исправление по review: ошибка inventory импортов

Ошибка чтения каталога приватных файлов теперь откатывает транзакцию и возвращает ранее доступные записи с отдельным сообщением EN/RU. Сканирование завершается; после успешного обновления сообщение исчезает. Regression test сначала воспроизвёл исключение `IOException`, затем оба новых API 37 теста прошли, включая пересоздание Activity и восстановление чтения.

- Полный `lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` прошёл: 55 JVM-тестов, 0 ошибок/пропусков.
- `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest` прошёл: 43 теста на Android 17 / API 37, 0 ошибок/пропусков.

## Инкрементальный каталог и Paging · Task 6 · 8 сентября 2026

Проверен код commit `59bc0d5b99c193d660591516f25b23f4844164f1`, сборки `0.1.0` (`versionCode 1`) debug/release. Room v2 хранит checkpoint томов, period keys и EXIF revision cache; миграция v1→v2 сохраняет метаданные. Удаление из полного стабильного inventory отделено от ограниченного/отозванного доступа. Изменение generation во время MediaProvider scan откатывает транзакцию и повторяет её максимум три раза; cancellation и permission change не повторяются.

- `./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` — успешно, **58 JVM-тестов**, 0 ошибок/пропусков; строгий lint, debug/release APK и release shrink прошли. Лог: `/tmp/lik-task6-matrix-verified.log`.
- `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest` — успешно, **59 тестов**, 0 ошибок/пропусков; `BUILD SUCCESSFUL in 2m 33s`. AVD `lik_api37_qa`, Android 17 / API 37. Лог: `/tmp/lik-task6-connected-verified.log`.
- Настоящие Room/SQLite и Paging проверены на 2 000 / 20 000 / 100 000 metadata-only записей без файлов изображений: страницы по 60, SQL counts, максимум три обложки, окно viewer до трёх записей. Отдельный AsyncPagingDataDiffer-тест проверяет начало по далёкому ID и удаление старых страниц из памяти при прокрутке.
- Проверены insert/update/remove, limited access, revoked source/URI, version reset, отмена и постоянный generation churn без частичного commit, EXIF по revision обоих источников, UTC offsets/DST и сохранение EXIF при повторной ограниченной выборке. Новые регрессии сначала воспроизведены: полный viewer list, устаревшая file revision, scan token вместо epoch time, anchor внутри периода, однократный generation churn и потеря EXIF при повторном scan.
- Существующие Share, gallery access, выбор, жесты, resize/large fonts, viewer navigation и lifecycle/launcher tests прошли. `git diff --check` чист; `references/`, manifest permissions и пользовательские PNG не менялись.
- Это функциональная проверка bounded metadata workloads, а не замеры задержек/памяти на Fold. На данном этапе не было установки на физическое устройство; физический API 36/Fold и реальный Google cloud Share остаются отдельной приёмкой. EXIF enrichment ленивый; полный EXIF UI не заявляется.

### Исправление по review: period backfill и lifecycle observer

Проверен commit `25a38d4f314ed150ae40b84eb72e569312d6b9a9`. Миграция v1→v2 заполняет все четыре period keys в транзакции открытия Room с сохранённым часовым поясом библиотеки. Чтение идёт пакетами до 256 записей; Paging начинает работу только после commit. Поздние ContentObserver callbacks после stop больше не запускают сканирование, а отменённый запрос не публикует результат.

- Наблюдались два RED: датированная v1-запись оставалась `undated`; queued callback после stop запускал третий scan вместо двух. После исправления три focused API 37 теста прошли. Логи: `/tmp/lik-task6-review-migration-red.log`, `/tmp/lik-task6-review-observer-red.log`, `/tmp/lik-task6-review-focused-green.log`.
- Реальная SQLite-миграция проверена на 273 записях: граница пакета, inaccessible/missing, неизвестные даты, смена года и DST в Europe/Madrid при системном UTC. Ошибка SQL после частичного backfill откатывает schema/user_version до v1; повторное открытие успешно завершает миграцию.
- Lifecycle-тест проверяет coalescing 25 уведомлений, queued callbacks после stop, регистрацию после resume и отсутствие публикации отменённого scan.
- `./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` — успешно: **58 JVM-тестов**, 0 ошибок/пропусков. Лог `/tmp/lik-task6-review-matrix.log`.
- `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest` — успешно: **61 тест**, 0 ошибок/пропусков, `BUILD SUCCESSFUL in 2m 40s`; AVD `lik_api37_qa`, Android 17 / API 37. XML totals подтверждены отдельно. Лог `/tmp/lik-task6-review-connected.log`.
- `git diff --check` чист. Физические устройства не использовались.

## Альбомы, избранное, метки и обычный поиск · Task 7 · 8 сентября 2026

Проверен commit `48c226a7e4a9c6cf6a9bb21302c1c3066beac012`, debug/release `0.1.0` (`versionCode 1`), JDK 17.0.20.1 / Gradle 9.4.1. Room v3 добавляет связи альбомов, избранного и меток; миграции v1→v2→v3 сохраняют метаданные. Поиск использует параметризованные SQL-запросы, Unicode NFC/Locale.ROOT и доступность `AVAILABLE`; даты без значения не подменяются. Изменение организации не копирует и не перемещает фото.

- `./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` — успешно; **61 JVM-тест**, 0 ошибок/пропусков, строгий lint и release shrink прошли. Лог `/tmp/lik-task7-matrix-verified.log`.
- `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest` — успешно; **68 тестов**, 0 ошибок/пропусков, `BUILD SUCCESSFUL in 2m 29s`. XML totals проверены отдельно. AVD `lik_api37_qa`, `sdk_gphone16k_arm64`, Android 17 / API 37; `getconf PAGE_SIZE` = **16384**. Лог `/tmp/lik-task7-connected-verified-final.log`.
- RED: отсутствовали таблицы организации (`/tmp/lik-task7-red.log`); удержание device-фото оставляло Selected: 0 (`/tmp/lik-task7-ui-red.log`); refresh после перехода из Альбомов перерисовывал Места (`/tmp/lik-task7-navigation-red.log`). После исправлений focused Room/migration/UI набор из 8 тестов и отдельные 3 UI-теста прошли (`/tmp/lik-task7-focused-green.log`, `/tmp/lik-task7-navigation-green.log`).
- Реальная SQLite-миграция v2→v3 проверена на 270 строках с кириллицей, канонически эквивалентным Ё и неизвестным именем; искусственный сбой после первой пачки откатывает schema/user_version, повторное открытие успешно. Сохраняются idempotent membership, избранное и метки при INACCESSIBLE, закрытии/открытии базы и восстановлении доступа. Одинаковые имена альбомов/фото не объединяют ID; папки различаются по volume/bucket/path; `%`, `_` и SQL-подобный ввод ищутся буквально.
- UI проверяет реальные Альбомы, Search, Trash/AI routes с явными будущими статусами, сохранение кириллицы и дат при recreation. Полный набор сохраняет существующие проверки resize/large font, Share, lifecycle, viewer и десяти aliases. Смешанный выбор проходит настоящий диалог удаления: приватная копия исчезает, байты device-original читаются, device-фото остаётся выбранным с отключённым Delete. Этот тест фиксирует уровень «Фото», чтобы оба проверяемых элемента были видимы независимо от мозаики периода.
- `git diff --check` чист. Manifest permissions, `references/`, пользовательские PNG и зависимости не менялись. На физическое устройство ничего не устанавливалось; Fold/API 36 и настоящий Google cloud Share остаются отдельной приёмкой. Для новых экранов нет отдельного визуального RU/200% sweep. Результаты показываются текстовыми строками с источником/датой/метками; thumbnails и ограничение соседей viewer текущим результатом поиска не заявляются. Неизвестные исходные имена старых private imports не восстанавливаются. Корзина/экспорт — Task 8; AI runtime и модели — последующие задачи.

## HF model presets and CPU evidence · Task 9 · 8 September 2026

Task 9 implements immutable downloadable model metadata and verification tools. All debug/release/distribution APKs contain **zero model weights and tokenizer payloads**, even with a complete external cache. Settings downloads, gallery inference/indexing and profile activation remain Task 10. Balanced is selected in metadata but no fresh installation has a ready profile. Sensitive quarantine/biometric reveal remain Task 13; classifier preparation does not implement hiding.

The final [catalog](../models/catalog-v1.json) contains 29 deduplicated runtime files / 12 ONNX graphs with immutable HF URLs and measured hashes: **3,663,983,902 bytes** total. Compact requires 390,318,893 bytes, Balanced 863,924,753, Extended 2,592,306,219; shared bytes overlap these profile totals. All were downloaded and verified outside Git. Original publisher sources (57 files, 6,415,938,581 bytes) and earlier reproducible custom reference exports (40 files, 2,955,000,651 bytes) remain external. The earlier multi-GB bundled-APK experiment was superseded by the user's HF Settings-download requirement and is not distribution acceptance.

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest assembleDistribution` — PASS, **64 JVM tests**, zero failures/skips; strict lint, release shrink and both ordinary per-variant payload gates passed. Log: `~/Library/Caches/Lik/model-artifacts/research/hf-delivery/final-build-fp32-image.log`.
- `LIK_MODEL_CACHE=/tmp/lik-nonexistent-model-cache ./gradlew assembleDistribution` — PASS without model cache. `artifacts.py verify` separately fails for missing runtime files; size-preserving corruption, partial caches, unsafe paths/symlinks, floating HF revisions and APK model/tokenizer payloads are negative tests. Builds never download model data or generate trusted hashes.
- Final debug APK: **158,782,188 bytes**, 158,698,387 compressed payload bytes / 158,927,171 uncompressed entry bytes. Unsigned release: **147,440,685 bytes**, 147,360,414 compressed / 147,584,920 uncompressed. Both report 0 ONNX and 0 tokenizer payloads. The 250-MiB gate also rejects stale unreferenced ZIP data. Debug and test APKs installed successfully on the explicitly selected emulator; unsigned release was built, not installed.
- `venv/bin/python -m unittest discover -s scripts/models/tests -v` — **21 tests, zero failures/skips** in the locked export environment. Standalone downloaded tokenizers passed **128 fixed publisher vectors**, including every RU/EN query, case/whitespace/punctuation and truncation. A reproduction check caught AutoTokenizer's missing model config in the standalone multilingual directory; the runner now uses the actual DistilBertTokenizerFast/GemmaTokenizerFast implementations explicitly.
- `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest` — PASS in **2m 44s**, XML reports **85 tests, 0 failures/errors, 1 expected skip** (external model provisioning is opt-in). AVD `lik_api37_qa`, API 37, arm64, 4,062,880 KiB MemTotal, 16,384-byte pages. The final run includes gallery/lifecycle/launcher/Share/Room/export/trash coverage. Log: `.../hf-delivery/full-connected-final.log`.
- Separate direct Android instrumentation with `externalModels=true` and `requireModels=true` — **OK (2 tests)** in 15.484 seconds. `provision_android_probe.py --serial emulator-5580` copied verified QA graphs/reference outputs into debug private files; no model entered either APK. All **12 graphs** loaded and inferred with stock ORT Android 1.29.0 CPU, checked input/output shapes and finite complete outputs, and passed the predeclared comparison gates. Exact catalog/probe/runtime-library hashes and results are retained in [android-cpu-v1.json](../models/evidence/android-cpu-v1.json).

The Android probe uses two intra-op threads, one inter-op thread and PSS sampling every 100 ms. Each row is one session load plus one synthetic inference and includes sampling overhead; it is **not warm latency or physical Fold performance**. PSS is sampled process memory, not an exact allocation peak or a guaranteed ceiling. Detectors use reduced 96×160 smoke tensors; product-size OCR benchmarking remains outstanding.

| Graph | Load + one inference, ms | Sampled process PSS, KiB |
| --- | ---: | ---: |
| CLIP image | 737 | 464,750 |
| Multilingual text | 429 | 260,427 |
| SigLIP 2 Base image | 1,153 | 311,931 |
| SigLIP 2 Base text | 844 | 652,591 |
| SigLIP 2 Large FP32 image | 4,144 | 1,366,070 |
| SigLIP 2 Large FP16 text | 2,513 | 1,704,590 |
| OCR mobile detector | 107 | 93,276 |
| OCR server detector | 423 | 260,046 |
| Cyrillic recognizer | 211 | 119,118 |
| YuNet | 210 | 131,438 |
| SFace | 107 | 118,220 |
| Shared Marqo classifier | 313 | 138,670 |

RED/GREEN: HF Large image FP16 passed host publisher parity but Android auxiliary `last_hidden_state` cosine was **0.9401959606**, below the fixed 0.98 gate; pooled output was 0.9974879621. Disabling graph optimizations produced the same discrepancy. The selected runtime artifact was changed to the published FP32 image graph, retaining the passing FP16 text graph. Final Android image cosines are 0.999999999952 (hidden states) and 0.999999999999 (pooled). No tolerance was relaxed; rejected bytes/hash and provenance remain in the evidence file. Host publisher comparisons use three synthetic image tensors and 30 RU + 30 EN queries; those are conversion checks, not task-accuracy measurements.

The first full gallery run reproduced the Task 7/8 mixed-source selection test's existing snapshot race (`positionForPhoto=-1` after the row had reached Room/state). Its setup now waits until both rows reach the timeline before long-click freezes selection. The focused case and final full suite pass; product gallery code was not changed for this test precondition.

No physical device was installed or tested. Real photo retrieval, OCR/face accuracy, sensitive false-negative/positive rates and threshold calibration, complete Android preprocessing/tokenization/postprocessing parity, Fold warm latency/memory/thermal behavior and NNAPI/NPU partition/fallback evidence remain **not measured**. The [external fixture contract](../models/evaluation/DATASET_CONTRACT.md), [Samsung policy](SAMSUNG_BACKENDS.md), [AiGate Task 10 contract](AIGATE_INTEGRATION.md) and [Task 13 sensitive privacy contract](SENSITIVE_MEDIA.md) define that remaining work. No Galaxy AI system-model access, router transfer or implemented biometric hiding is claimed.

## APK payload boundary review fix · Task 9

Review found that renamed bytes outside `assets/` bypassed the first gate, and androidTest APKs had no gate. The fixed verifier applies a fail-closed boundary to every ZIP entry: exact repository metadata/Room schemas, 66 pinned runtime/classpath/license entries, 36 approved PNG digests, bounded compiled Android resources/DEX, and catalog artifact fingerprints independent of filename. Unknown raw resources and arbitrary blobs fail. ZIP prefix/suffix/comment/extra-field payloads, unreferenced nonzero data and hidden deflate bytes also fail; only empty alignment/deleted-entry padding and bounded APK signing records are allowed outside entries. No policy is auto-generated by the build. Policy SHA-256: `8c44f84e7c4cdf795e33c1b86e68cfb517aa2791f4a0813ada95ef3289ce0a72`.

Android Components registers the checks for every app and device-test variant, all APK outputs, assembly, direct packaging and pre-installation. The distribution aggregate now includes the test APK. Production Activity/manifest/model bytes and launcher PNGs were unchanged.

- RED: the former gate returned success for real `multilingual-text-v1/tokenizer.json` (1,961,847 bytes, SHA `5b4e1a8171c81dfd666ae40265b9530c6e0b3d53923fe8ac493dcc84229adf81`) at `res/raw/tokenizer.json`, and real YuNet (232,589 bytes, SHA `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`) at `res/raw/face_model.bin`. Nine Python tests produced 28 failing subcases. The former `assembleDebugAndroidTest` also succeeded with real YuNet in `res/raw/lik_review_model.bin`; the fixed assemble and direct package commands both fail in `verifyDebugAndroidTestModelPayloads`. The temporary source fixture was removed and the final build began with `clean`; no model entered Git or a tested installation. Separate RED/GREEN cases cover ZIP-envelope payloads. Logs: `.../research/hf-delivery/review1-apk-red.log`, `review1-test-apk-bypass-red.log`, `review1-test-apk-rejection-green.log`, `review1-direct-package-rejection.log`, `review1-zip-envelope-red.log`.
- `LIK_MODEL_CACHE=/Users/avm/Library/Caches/Lik/model-artifacts /Users/avm/Library/Caches/Lik/model-artifacts/venv/bin/python -m unittest discover -s scripts/models/tests -v` — **33 passed, no failures/skips**, including all real downloaded-byte regressions. `review1-python-validators.log`. Missing explicitly configured regression files fail; without configuration these real-byte cases may skip on machines without the developer cache.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew clean lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest assembleDistribution` — **PASS in 16s**, 64 JVM tests / zero failures/skips; all three APK gates, lint and release shrink passed. `review1-full-matrix.log`.
- `LIK_MODEL_CACHE=/tmp/lik-nonexistent-model-cache ./gradlew assembleDistribution` — **PASS in 1s**, all three APKs verified without a model cache. `review1-missing-cache-build.log`.
- Focused `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.mesteriis.lik.ModelArtifactSmokeTest` — **PASS in 3s**, 2 tests / 1 expected external-probe skip; both APK gates ran before installation/test execution. `review1-focused-android.log`.

Full `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest` also passed in **2m 50s**: final XML **85 tests, 0 failures/errors, 1 expected external-probe skip**. Both gates ran before installation. `review1-full-android.log`. No physical device was installed or tested.

Every entry in each actual final APK passed the gate (`review1-final-apks.json`):

| APK | Physical bytes | Compressed entry bytes | Uncompressed entry bytes | Inspected entries |
| --- | ---: | ---: | ---: | ---: |
| Debug | 158,781,425 | 158,698,387 | 158,927,171 | 161 |
| Unsigned release | 147,440,685 | 147,360,414 | 147,584,920 | 121 |
| Debug androidTest | 1,255,061 | 1,242,907 | 1,307,541 | 50 |

All report zero model/tokenizer payloads. Existing all-12 CPU artifact evidence remains the earlier recorded run; this fix changes build/verification boundaries, not models or inference. No new model-quality, calibrated-sensitive-threshold or physical Fold measurement is claimed.

Final self-review also checked AGP 9.2.1 `ExtractVersionControlInfoTask`/`RepositoryInfo` bytecode: ordinary Git checkouts produce fixed Git fields instead of the linked-worktree error receipt. A RED/GREEN regression now accepts only that exact field layout with a 40-digit revision, rejecting extra payload fields; commit changes do not require allowlisting new opaque data. `review1-git-metadata-red.log`. The full build matrix and missing-cache distribution were repeated successfully after this host-verifier refinement (`review1-final-matrix.log` / `review1-missing-cache-build.log`). Model/Android application bytes and build wiring were unchanged by it.


## Compiled-container and signing review fix 2 · Task 9

The second review reproduced two further bypasses: real model/tokenizer bytes inside superficial XML/ARSC/DEX wrappers, and a duplicate v2 signing record ignored by `apksigner`. These replace the structural-header claims in the previous review section. The [current APK policy](APK_PAYLOAD_POLICY.md) now requires a complete, source-bound per-variant set of reviewed compiler receipts, AAPT2 resource/XML parsing, full ART dexdump verification, unique completely consumed signing records and cryptographic verification. No normal build updates either policy.

Compiler receipts cover debug **83 resource entries / 8 DEX**, release **47 resource entries / 1 DEX / 2 baseline profiles**, and debugAndroidTest **38 resource entries / 4 DEX**. They were prepared only from compiler intermediates and independently matched to actual APKs. A candidate after clean uncached compilation is byte-identical to the checked-in policy: SHA-256 `3dad1e6ecfca4291fa1f80c4b49120d76eeac34b043689c6e7aeedf9b5ade8c2`; source fingerprint `d9231204b1b199f50af536dd9ff0f4c1a4da1dcb0867aa164d38feeaa987963b`. The helper refuses to overwrite the trusted policy and takes no APK input. macOS SDK parser receipts and the exact official Linux SDK distribution are pinned; Linux binaries were hash-verified, not executed in this macOS review.

- **RED → GREEN:** 36 real-byte XML/ARSC/DEX wrapper cases across debug/release/androidTest and STORE/DEFLATE, two duplicate-v2 actual APKs, four opaque/trailing signing records, plus seven nested signing-field carriers. The previous gate accepted all **49** cases; the final gate rejects them. Payloads are the existing external YuNet (232,589 bytes), multilingual tokenizer (1,961,847 bytes), and tokenizer config (371 bytes; SHA-256 `8f66d0ad85be46afc77e0bbf48312cff326ddb9e7290926df96a8228d69a814e`). Nothing was copied into Git. `apksigner` independently returns exit 0 for both duplicate-v2 APKs; Lik rejects them for duplicate ID. Logs: `review2-containers-red.log`, `review2-nested-signing-red.log`, `review2-duplicate-apksigner.log`.
- `LIK_MODEL_CACHE=... venv/bin/python -m unittest discover -s scripts/models/tests -v` — **40 tests, zero failures/skips**. Covers the real payloads, legitimate PNGs, immutable receipts, parser-distribution corruption and refusal to overwrite the trust anchor. Existing PyTorch legacy-export deprecation warnings remain toolchain warnings. `review2-python-suite.log`.
- `JAVA_HOME=... ./gradlew clean lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest assembleDistribution --no-build-cache` — **PASS, 24s**, 64 JVM tests / zero failures/skips; all three complete APK inventories passed. Final repeated matrix with the Linux parser policy also **PASS, 4s**. `review2-gradle-uncached-matrix.log`, `review2-final-matrix.log`.
- `LIK_MODEL_CACHE=/tmp/lik-nonexistent-model-cache JAVA_HOME=... ./gradlew assembleDistribution` — **PASS, 4s**, every app/test variant inspected without model cache. `review2-missing-cache-build.log`.
- `ANDROID_SERIAL=emulator-5580 JAVA_HOME=... ./gradlew connectedDebugAndroidTest` — **PASS, 1m52s** on explicitly selected `lik_api37_qa`, API37. Final XML: **85 tests, zero failures/errors, one expected external-model-probe skip**, 109.416 seconds. Gradle's progress footer says 86, but the XML and individual test cases agree on 85. `review2-full-android.log`, preserved `review2-full-android.xml`.
- `python3 scripts/models/artifacts.py verify --cache ...` — **all 3 profiles, 29 files, 3,663,983,902 bytes verified**. These files remain external; this is fresh integrity verification, not another inference/quality measurement. `review2-artifacts-verified.log`.
- The documented compiler-only refresh command completed successfully without a package task; the subsequent candidate matched the reviewed policy. `review2-compiler-only-workflow.log`, `review2-after-clean-candidate.json`.

Every final APK entry and physical envelope passed (`review2-final-apks.json`), with the final compiler-policy SHA above:

| APK | Physical bytes | Compressed entry bytes | Uncompressed entry bytes | Entries |
|---|---:|---:|---:|---:|
| Debug | 158,781,425 | 158,698,387 | 158,927,171 | 161 |
| Release unsigned | 147,440,685 | 147,360,414 | 147,584,920 | 121 |
| Debug androidTest | 1,255,061 | 1,242,907 | 1,307,541 | 50 |

All contain zero weights/tokenizer payloads. Debug/test signatures and emulator installation passed; unsigned release was inspected and built, not installed. Current builds use v2 signatures; source stamps, v3/rotation, signing attributes, alternate algorithms and extra certificates fail closed until explicitly supported. Production Kotlin/manifest/model bytes, launcher PNGs/aliases and physical devices were not changed by this fix. Previous all-12 Android CPU evidence remains its original run; no new Fold, quality or sensitive-calibration result is claimed. All logs named above are under `~/Library/Caches/Lik/model-artifacts/research/hf-delivery/`.

The final focused `ModelArtifactSmokeTest` run also passed after the parser-policy refinement: 2 tests / 1 expected external-probe skip, 4s Gradle command, with both final app/test payload gates before installation. `review2-focused-android.log`. Self-review checked complete variant inventories, source/tool hashes, signing field consumption and duplicate rejection, exact positive APK/PNG acceptance, the compiler-only workflow and staged content. No downloaded weights, SDK binaries, APKs, signing material or build outputs enter the commit.


## Certificate payload review fix 3 · Task 9

The third review demonstrated that a valid APK signing certificate's custom extension could contain the exact 371-byte HF `multilingual-text-v1/tokenizer_config.json` (SHA-256 `8f66d0ad85be46afc77e0bbf48312cff326ddb9e7290926df96a8228d69a814e`). Both the previous gate and SDK `apksigner` accepted the reviewer's Gradle-signed APK. The new [signing policy](../scripts/models/apk-signing-policy-v1.json) fully interprets a narrow set of X.509 fields and extensions; unknown/duplicate extensions and opaque standard-extension contents fail. An independent every-offset size/SHA scan covers the complete bounded signing block, including certificates, SPKI, signature bytes and field boundaries, using only the checked-in HF receipts. Remaining APK bytes retain exact entry/compiler receipts and strict physical ZIP accounting. No cache or signing identity is enrolled by a build. Policy SHA-256: `319af5760e903e555bc666f519f108112c33bd46309baeba7abf813b1a04bbde`.

- **RED → GREEN:** real HF bytes in OID `1.2.3.4` on debug, androidTest and signed release; separately, the same three paths with an unknown extension containing no known HF bytes. Each constructed APK passes `apksigner` before Lik rejects it. The original reviewer APK is now rejected too. Whole-envelope fingerprint tests place the real file at the beginning, unaligned positions and the final fitting byte offset of a 64-KiB block. Logs: `review3-cert-red.log`, `review3-policy-red.log`, `review3-cert-green.log`, `review3-reviewer-apk.log`.
- Ordinary AGP debug/test signatures and re-signing all three APK variants with standard RSA-2048 certificates pass. EC P-256 release signing and the documented JDK `keytool` RSA-4096 PKCS12 release route also pass. The latter uses a disposable external key, removed after verification. Standalone `apksigner` zero alignment is accepted only below 4096 bytes with every byte zero. `review3-keytool-release.log`; production signing instructions and exact supported fields are in [APK_PAYLOAD_POLICY.md](APK_PAYLOAD_POLICY.md).
- `JAVA_HOME=... LIK_MODEL_CACHE=... venv/bin/python -m unittest discover -s scripts/models/tests -v` — **45 tests, zero failures/skips**, 14.785s. This includes all earlier real-byte wrapper, duplicate-record and positive PNG/compiled-resource cases. `review3-final-python-suite.log`.
- `JAVA_HOME=... ./gradlew clean lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest assembleDistribution --no-build-cache` — **PASS, 20s**, 64 JVM tests / zero failures/errors/skips; all three APK gates, lint and release shrink passed. `review3-final-gradle-matrix.log`.
- `JAVA_HOME=... LIK_MODEL_CACHE=/tmp/lik-nonexistent-model-cache ./gradlew assembleDistribution` — **PASS, 4s**, including app and androidTest gates with no model cache. `review3-final-missing-cache-build.log`.
- `ANDROID_SERIAL=emulator-5580 JAVA_HOME=... ./gradlew connectedDebugAndroidTest` — final **PASS, 1m51s**; XML **85 tests, zero failures/errors, one expected external-model skip**, 108.720s. Explicit target: `lik_api37_qa`, API37. `review3-full-clean-android.log` / `.xml`.

Two earlier full runs each failed only `GalleryTest.mixedSourceSelectionDeletesOnlyThePrivateCopyAndKeepsDeviceSelected` at its existing 5-second wait for both adapter rows (85 tests, 1 failure, 1 expected skip; XML times 111.729s and 114.340s). The focused test passed (1 test, 1.529s). These remain a test-isolation/reproducibility issue; this fix does not claim to solve the gallery race. The authorized final clean-state repeat passed. `pm clear` returned `Failed` because the preceding connected task had already uninstalled Lik; the next run installed it afresh, with matching current `firstInstallTime` and `lastUpdateTime`, recorded in `review3-clean-install-dumpsys.txt`. Failure logs/XML are preserved as `review3-full-first-android.*` and `review3-full-second-android.*`; focused evidence is `review3-gallery-focused.*`. No gallery/test timing or production Android source was changed in this round.

Final inspection of debug (158,781,425 bytes), unsigned release (147,440,685 bytes), androidTest (1,255,061 bytes) and the disposable signed release succeeds, with zero model/tokenizer payloads (`review3-final-apks.json`). Debug/release/test compressed entry bytes remain 158,698,387 / 147,360,414 / 1,242,907; uncompressed bytes 158,927,171 / 147,584,920 / 1,307,541. Compiler receipts and source fingerprint remain unchanged from fix 2. The external cache was freshly verified: all 3 profiles, 29 files, 3,663,983,902 bytes (`review3-artifacts-verified.log`). This is integrity verification, not new inference, model quality, calibration or physical Fold evidence. No physical device was installed or tested.

Self-review checked exact extension consumption, SPKI/key identifier binding, the whole signing-envelope scan, positive production signing, bounded zero padding, unchanged build/compiler receipts and staged content. All logs above are under `~/Library/Caches/Lik/model-artifacts/research/hf-delivery/`. No downloaded model/tokenizer bytes, APKs, SDK files, certificates or private keys enter the commit.


## RSA-3072 compatibility review fix 4 · Task 9

Review confirmed that the certificate-payload bypass is closed, but a normal RSA-3072 signed release failed the allowlist. The fix adds only the corresponding **384-byte signature** and **3072-bit modulus** sizes, plus a real signed-release regression. It preserves the certificate extension semantics, cryptographic verification, complete signing-envelope HF scan and all APK entry/compiler receipts. The signing policy SHA-256 is now `69edb5f6abf754fb1b30caa0f88e23ac0dedde8ed60898fe1b1d9958dc4a5fe0`.

- **RED → GREEN:** the new regression signs the actual release APK with an external temporary RSA-3072 key and first requires SDK `apksigner verify` success. Before the fix Lik rejects it with `Invalid RSA signature length`; adding only the signature size reveals the second rejection, `Unreviewed RSA key size/exponent`. Adding the matching modulus size makes the release pass both signature and payload verification. `review4-rsa3072-red.log`, `review4-rsa3072-policy-red.log`, `review4-signing-green.log`.
- Focused signing suite: **6 tests passed, zero skips**, 13.455s. Full `LIK_MODEL_CACHE=... venv/bin/python -m unittest discover -s scripts/models/tests -v`: **46 tests passed, zero skips**, 16.541s. Existing real HF extension/wrapper/signing-carrier regressions remain green. `review4-python-suite.log`.
- `JAVA_HOME=... ./gradlew clean lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest assembleDistribution --no-build-cache`: **PASS, 25s**, including **64 JVM tests, zero failures/errors/skips**, lint and all three APK gates. `review4-gradle-matrix.log`.
- `JAVA_HOME=... LIK_MODEL_CACHE=/tmp/lik-nonexistent-model-cache ./gradlew assembleDistribution`: **PASS, 4s**. Final independent inspection of debug, unsigned release and androidTest reports the new signing policy hash and zero model/tokenizer payloads. APK sizes and compiled/source receipts remain unchanged. `review4-missing-cache-build.log`, `review4-final-apks.json`.

This narrow host-verifier compatibility change does not modify Android code, resources, dependencies, manifests, build wiring or compiler receipts. Android instrumentation was not rerun; its latest full result remains the fix-3 API37 run (85 tests, zero failures/errors, one expected skip), with both earlier intermittent gallery failures preserved above. No new model/runtime/device measurements are claimed. Self-review confirmed the two-value policy change and real SDK-verified regression; no key, certificate, APK or model payload enters Git. Logs are under `~/Library/Caches/Lik/model-artifacts/research/hf-delivery/`.

## Downloadable profiles, runtime, indexing and AiGate · Task 10 · 8 September 2026

Task 10 implements explicit Settings downloads for the three immutable Task 9 profiles. `ModelCatalog` is the sole fsynced profile state; one process/file-lock coordinator serializes downloads, and a durable ledger reserves only the remaining bytes while assigning shared digest ownership across profiles. Downloads use shared digest staging, validated HTTP ranges, declared size/SHA-256, durable journals, corruption quarantine, atomic publication and recovery of an already verified staging file without another request. The foreground data-sync worker propagates stop/pause/cancel to its active connection and hashing loop. Pause, resume, cancel, retry and inactive-profile cleanup are visible in stable EN/RU Settings views. The old profile remains active until the selected profile has passed its real runtime self-test and enabled generations can be committed together. OCR and People controls remain visibly unavailable until their Task 11 product/index paths exist; they cannot leave a profile permanently preparing.

The private isolated service runs stock ONNX Runtime Android 1.29.0 CPU with one heavy task at a time. A process-lifetime binding reuses size-budgeted sessions across indexing/search batches, invalidates leases on binder death and rebinds for the next request. Cache eviction happens before opening another graph, and profile validation releases serving sessions first; this prevented overlapping the two multi-GB Extended graphs on the 4-GiB emulator. API 37 acceptance executed a decoded image, a Russian query, OCR detector/recognizer, YuNet, SFace and sensitive-classifier graph through each applicable real profile: CLIP + multilingual projection (512 dimensions), SigLIP 2 Base (768) and SigLIP 2 Large (1024). Tokenizers and preprocessing came from the downloaded, hash-verified profile files. This is functional evidence only; it is not a measured quality, warm-latency, memory, battery or thermal result.

Search generations and checkpoints are stored in Room v5 with media revision, access epoch and pipeline fingerprint. A per-pipeline/generation coordinator serializes periodic, one-shot and manual workers; cancellation is rechecked after inference and before transactional Room/native publication. Failed, stale or inaccessible rows remain retryable and cannot advance truthful coverage or activation. Production search reads the persisted USearch generation, bounds candidates to 512, fetches them in one joined Room query and exact-reranks only that set; the bounded exact path is a fallback/oracle, not the ordinary full-catalog response. USearch 2.26.0 is built from pinned source behind a narrow JNI boundary; update/delete/search/save/load and sampled exact/approximate validation were exercised on API 37. Generation deletion uses a durable retirement journal and removes catalog pointers before Room/files, so restart or reselection cannot revive stale generations. Background work requires user opt-in and charging/storage/battery constraints; interactive work receives the next heavy-task lease. Sensitive raw logits are scheduled and stored before ordinary indexing, but Task 13 quarantine, calibration and biometric visibility remain unimplemented and are not treated as safe on failure.

AiGate is a separate opt-in loopback client for `127.0.0.1`, with configured/discovered port, required `health.running`, a successful `/v1/models` probe before chat, one overall request deadline, bounded response/error sizes, retained cancellation on lifecycle/opt-out, no redirect broadening and no provider secret in Lik. The photo encoder samples the source during decode, applies orientation, limits the long side to 1600, re-encodes JPEG below 4 MiB and removes EXIF metadata. Each request consumes a one-use send consent. The viewer boundary remains closed pending Task 13 reveal integration, and no real AiGate/cloud-provider transfer was run.

- `./gradlew lint testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` — PASS; **99 JVM tests**, zero failures/errors/skips, lint and release shrink passed. `./gradlew assembleDistribution` also passed. All three APK payload gates reported zero ONNX and tokenizer payloads.
- Clean compiler-only `--no-build-cache` rebuild and `compiled_apk.py` candidate — PASS; the final candidate reproduces byte-for-byte from the compiler outputs. Reviewed policy SHA-256 `c42ba02a515a78f177353752799368dbfc8452708a2ec0ae60de6438e6d8d57a`, source fingerprint `62d237842528ebdb71297a7864031f54b5e5966f4bf06a215ffffb259f64ff34`.
- External private-model acceptance on `emulator-5580` — **14 tests**, zero failures, 27.862 seconds. It covered all three real image/Russian-text pipelines and component smoke graphs, shared download reservation/corrupt quarantine, persistent runtime reuse/death/rebind, the Room same-generation revision race, generation/native USearch, a real immutable-HF ranged resume, verified-staging recovery without network, stable Settings recreation, catalog reopen and AiGate privacy/transport paths. The 29 files / 3,663,983,902 bytes were provisioned only into the emulator app-private digest store and never into an APK.
- `ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest` — PASS; final XML **100 tests, zero failures/errors, 6 expected external-model/network skips**. Gradle's footer includes setup accounting and displayed 106. AVD `lik_api37_qa`, API 37, arm64, 16-KiB page configuration.
- `PYTHONPATH=scripts/models python3 -m unittest discover -s scripts/models/tests -p 'test_*.py'` — **46 tests, zero failures, 5 expected external-cache skips**.

Final inspected APKs contain zero weights/tokenizers:

| APK | Physical bytes | SHA-256 | Compressed entry bytes | Uncompressed entry bytes | Entries |
| --- | ---: | --- | ---: | ---: | ---: |
| Debug | 61,095,219 | `a09136cd5cadc95e3d0df778ef9b440ca81bcb6f17d8c0ea8c918a97656631c3` | 60,466,247 | 60,696,423 | 158 |
| Release unsigned | 48,293,420 | `8d836870f0ea268bd1b1cf170a1e90cbaaf575a31bcb335742bbc893f3d13230` | 48,250,877 | 48,476,777 | 117 |
| Debug androidTest | 1,302,578 | `325a60cdcc0fe1429255ebc7e93121af7dfa001c56b27277ae8fd1843dd42754` | 1,290,229 | 1,375,485 | 51 |

The native debug/release `liblik_usearch.so` files have `0x4000` alignment for every `LOAD` segment. One earlier full Task 10 connected run hit the already documented Task 9 gallery wait timeout; its focused rerun and the subsequent full final run passed. No physical Fold/API 36 run, Samsung accelerator validation, RU/200% visual sweep, real AiGate provider run, calibrated NSFW threshold, or 2k/20k/100k AI performance test is claimed.
