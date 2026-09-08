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
