# Лик · Lik

Android-галерея с искусственным интеллектом. Реализованы прямой просмотр разрешённой локальной медиатеки через MediaStore, ручной импорт «Google Photos → Поделиться → Лик» и настройка иконки. ИИ пока не подключён.

Основа следует соседнему проекту Rune Keyboard: Kotlin, системные Android Views, минимум зависимостей и строгий Android Lint.

Реализован MVP галереи; все ML/AI-функции пока вне реализации. Локальные оригиналы читаются на месте, а только явно переданные из Google Photos изображения сохраняются как приватные копии. Прежние решения с generic Photo Picker и GridView сохранены в [историческом плане](docs/superpowers/plans/2026-09-08-gallery-mvp.md); текущая лента использует уровни «Фото · Дни · Недели · Месяцы · Годы». Сквозная проверка обозначенного cloud-only фото из авторизованного Google Photos остаётся отдельной приёмкой на настроенном устройстве.

## Статус контракта

| Область | Статус и граница доказательства |
| --- | --- |
| Реализация | Разрешённые фото MediaStore показываются без копирования; явный Share из Google Photos создаёт приватную копию. Экран явно различает сканирование, пустую библиотеку, частичный, отклонённый и окончательно отклонённый доступ, а также ошибку источника; при окончательном отказе открываются настройки приложения. Смена доступа отменяет только миниатюры и результаты MediaStore, не затрагивая приватные импорты. На уровне «Фото» показаны три квадратные `centerCrop`-плитки в ряд; отдельной строки «Фотографии / Фото: N» нет. Построение ленты и DiffUtil выполняются вне UI-потока; миниатюры проходят через два worker-потока с ограниченной очередью. |
| Автоматические проверки | JVM и instrumentation с отдельным test-provider проверяют хранилище, Share, ленту, просмотр, lifecycle и aliases. Test-provider доказывает контракт URI, но не Google Photos cloud. |
| Физический Fold | Debug APK визуально проверен на внешнем экране SM-F966B с Android 16 и реальными локальными фото. Внутренний экран при физическом раскрытии ещё не принят. |
| Google cloud Share | Запуск Google Photos на Fold подтверждён, но настоящий Share обозначенного cloud-only фото, офлайн-открытие после перезапуска и повторный импорт ещё не приняты. |

Подробные команды, исторические результаты и открытая физическая приёмка приведены в [журнале проверки](docs/VERIFICATION.md).

## Функциональность и референсы

**[Матрица функций для обсуждения](docs/FEATURE_MATRIX.md)** — возможности шести галерей и Rune Keyboard, локальный/серверный способ работы, подтверждение кодом или документацией и варианты выбора D01–D15. Неизвестные и незавершённые функции отмечены отдельно.

Все семь проектов склонированы в [references](references/README.md) как Git-подмодули, закреплённые на конкретных commit. [Разбор каждого проекта](docs/REFERENCE_REVIEW.md) содержит ссылки на просмотренные исходники и ограничения. Референсы не участвуют в сборке Lik.

### Что и откуда берём

| Источник | Решение для Lik | Как используется сейчас |
| --- | --- | --- |
| [Дизайн ленты и Fold](docs/superpowers/specs/2026-09-08-gallery-zoom-fold-design.md) | Мозаика дней, пять уровней масштаба времени и нижняя навигация | Реализовано собственным кодом: уровень «Фото» — три квадратные плитки в ряд, остальные уровни перестраиваются по дате съёмки; ревизии ленты и миниатюры планируются вне UI-потока; сторонний код не переносился |
| [MediaStore и Google Photos Share](docs/GOOGLE_PHOTOS_IMPORT.md) | Локальные фото читаются на месте; явно отправленные из Google Photos сохраняются как приватные копии с итогом операции | Собственный код `gallery/`, `imports/`, `ui/`; доступ повторно сверяется при возврате в Activity, а привязка масштаба хранит ближайшее к focus фото и относительное смещение; без OEM SDK |
| [Постоянный каталог](docs/MEDIA_CATALOG.md), [Room 2.8.4](https://developer.android.com/jetpack/androidx/releases/room#2.8.4), [Paging 3.5.1](https://developer.android.com/jetpack/androidx/releases/paging#3.5.1) | Room хранит метаданные и доступность; PhotoStore владеет приватными файлами | Собственная реализация `catalog/`, `gallery/DeviceMediaQuery.kt`, `ui/TimelineAdapter.kt`; upstream-код не копировался. Room, Paging и KSP — зависимости Apache-2.0, не модели. SHA-ID импортов сохранены; device identity учитывает volume/version/row/generation. Схема v4 и миграции v1→v2→v3→v4, отменяемые пакеты, checkpoint поколений, ContentObserver, Room Paging, агрегаты периодов, соседние ID viewer и revision EXIF cache реализованы |
| [План галереи MVP](docs/superpowers/plans/2026-09-08-gallery-mvp.md) | Исторический план Photo Picker/GridView, просмотра, выбора и удаления копий | Реализовано собственным кодом на Android API; текущий контракт использует MediaStore + Google Share и пятиуровневую ленту |
| [Организация и обычный поиск](docs/MEDIA_CATALOG.md#organization-and-conventional-search-task-7), [Task 7](docs/superpowers/plans/2026-09-08-lik-full-development.md) | Папки устройства, виртуальные альбомы, избранное, ручные метки, поиск по имени/дате/источнику/метке | Собственный код `catalog/Organization.kt`, `ui/OrganizationPanel.kt`, миграция v2→v3 поверх `ad08472`; upstream-код не копировался. Связи сохраняются при потере доступа и в корзине; UI EN/RU хранит фильтры и выбор при recreation. AI settings пока обозначен как будущая функция; модельные артефакты отдельно подготовлены в Task 9 |
| [Экспорт и корзина](docs/MEDIA_CATALOG.md#share-export-and-trash-task-8), [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider), [WorkManager 2.11.2](https://developer.android.com/jetpack/androidx/releases/work#2.11.2) | Share выбранных фото; Save copy одного фото через системное место назначения; приватная корзина на 30×24 часа | Собственные `exports/`, `catalog/TrashRepository.kt`, `TrashMaintenance.kt`, `res/xml/export_paths.xml` поверх `73f5f49`; схема v4, миграции v1→v2→v3→v4. Только подготовленные cache-копии получают READ grants. Restore сохраняет альбомы/избранное/метки, повторный SHA-импорт восстанавливает запись. Durable purge и повторы после сбоя; оригиналы не удаляются. WorkManager — Apache-2.0 зависимость, не модель; upstream-код не копировался |
| [Model artifacts Task 9](docs/MODEL_PROVENANCE.md) | Pinned OpenAI/Google/sentence-transformers/PaddlePaddle/OpenCV Zoo/Marqo weights; shared immutable profiles | Собственные `scripts/models/`, HF manifests, проверка всех записей app/androidTest APK по допустимому содержимому и внешний Android CPU acceptance test; исходный runtime-код не копировался. Переименование, XML/ARSC/DEX-обёртки, дубли signing records и payloads в X.509-расширениях отклоняются; [правила APK](docs/APK_PAYLOAD_POLICY.md) закрепляют полные compiler receipts и SDK parsing. Модельные лицензии MIT/Apache-2.0 закреплены отдельно; source commits/files и HF URLs/SHA-256 в `models/catalog-v1.json`. ORT Android 1.29.0 — MIT runtime dependency, не модель. Sensitive-калибровка и biometric hiding не реализованы |
| [Rune Keyboard](docs/REFERENCE_REVIEW.md#rune) | Kotlin/Views, инструменты сборки, Wrapper и подход CI; проверяемая доставка, self-test, активация и откат моделей | Основа каркаса применена; работа с моделями описана в проекте архитектуры |
| [ReFra](docs/REFERENCE_REVIEW.md#refra) | Возможности источников, наборы моделей по функции, этапы индексации, text/image поиск | Кандидаты D05–D07, D10, D13; runtime-код не перенесён |
| [Lavender Photos](docs/REFERENCE_REVIEW.md#lavender) | Paging, SAF, теги, группы альбомов, прогресс файловых операций и Immich | Кандидаты D01–D04, D10–D13 |
| [Aves](docs/REFERENCE_REVIEW.md#aves) | Метаданные, ориентация, motion/multitrack и сложные форматы, каталогизация | Кандидаты D03, D14; источник тестовых сценариев |
| [Fossify Gallery](docs/REFERENCE_REVIEW.md#fossify) | Локальная галерея на Views, корзина/восстановление, конфликты имён, EXIF | Кандидаты D01–D03, D13 |
| [Ente Photos](docs/REFERENCE_REVIEW.md#ente) | Локальный ML, версии индекса, несколько assets, пауза по состоянию устройства, OCR | Кандидаты D05–D09; E2EE sync обсуждается отдельно |
| [Immich](docs/REFERENCE_REVIEW.md#immich) | Опциональный backend, фильтры поиска, зависимости ML, разбор дубликатов | Кандидаты D06, D08–D11; сервер не является условием локальной галереи |

Для Lik подготовлены три immutable набора: **Compact, Balanced (default), Extended**. [Task 9](models/README.md) фиксирует реальные source/artifact SHA-256, 12 ONNX-графов и общие OCR/people/sensitive зависимости. Веса и токенизаторы скачиваются из Hugging Face через Settings (Task 10) и никогда не включаются в APK. Все сборки содержат только manifests/licenses; distribution gate отклоняет model payloads. Выбранный Balanced не означает готовность: нужны download, hash/self-test и готовые индексы; старый active profile сохраняется до атомарного переключения. Галерейный runtime, индексы, качество RU/EN и Fold-приёмка остаются отдельными задачами. См. [provenance и проверки](docs/MODEL_PROVENANCE.md), [Samsung backend policy](docs/SAMSUNG_BACKENDS.md), [план AiGate](docs/AIGATE_INTEGRATION.md) и [Task 13 sensitive privacy](docs/SENSITIVE_MEDIA.md).

В текущем CLIP-пути ReFra фильтр удаляет кириллицу, а локальный OCR остаётся заглушкой. Поэтому наличие этих названий в проекте не считается готовой реализацией для Lik. Подробности и исходники — в разборе.

При переносе каждой выбранной функции обновляем эту таблицу: указываем commit/файл источника, идею или перенесённый код, путь реализации в Lik и проверку. Лицензии исходников и конкретных весов учитываются отдельно; копирование runtime-кода сторонних галерей на этом этапе не выполнялось.

## Быстрый старт

Требуются JDK 17, Android SDK Platform 37 и Build Tools 36.0.0. Gradle 9.4.1 загружается через включённый в репозиторий Wrapper; отдельно устанавливать Gradle не нужно. AGP — 9.2.1 со встроенной поддержкой Kotlin.

```bash
git clone git@github.com:Mesteriis/lik.git
cd lik
```

Откройте корень проекта в Android Studio с поддержкой AGP 9.2 и дождитесь Gradle Sync. Для Gradle JDK выберите JDK 17, затем запустите конфигурацию `app` на устройстве с Android 16 или 17. Целевой Galaxy Fold поддерживается на API 36 и продолжит работать после обновления до API 37: `minSdk = 36`, `targetSdk = compileSdk = 37`.

Для сборки из терминала задайте `JAVA_HOME` и `ANDROID_HOME` под свою машину. Вместо `ANDROID_HOME` можно создать локальный, игнорируемый Git файл `local.properties`:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

При необходимости установите SDK:

```bash
sdkmanager "platforms;android-37.0" "build-tools;36.0.0"
```

Сборка и статические проверки:

```bash
./gradlew lint assembleDebug assembleRelease assembleDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell monkey -p io.github.mesteriis.lik -c android.intent.category.LAUNCHER 1
```

## Проверки

`lint` считает предупреждения ошибками. GitHub Actions собирает debug/release APK, проверяет lint и запускает instrumentation-тесты на Android 17; целевой Fold на Android 16 также проверяется перед выпуском.

Для проверки запуска, пересоздания Activity и переключения всех десяти иконок выберите **эмулятор API 37** или целевой Fold API 36 из `adb devices`:

```bash
ANDROID_SERIAL=<emulator-serial> ./gradlew connectedDebugAndroidTest
```

Без `ANDROID_SERIAL` Gradle может запустить тесты на всех подключённых устройствах. `testDebugUnitTest` проверяет атомарное сохранение, точные дубликаты, ошибки потока и уборку временных файлов. Instrumentation проверяет приём настоящих content URI от отдельного тестового provider, частичные ошибки пакета, сетку, восстановление позиции и выделения, просмотр, форматы, EXIF-ориентацию, удаление, пересоздание Activity, выбор иконки и запуск. Тестовые компоненты не входят в APK приложения.

## Структура

```text
app/
  src/main/
    java/io/github/mesteriis/lik/ui/        # галерея, превью и системные отступы
    java/io/github/mesteriis/lik/imports/   # URI, атомарные копии, Share и операции
    java/io/github/mesteriis/lik/gallery/   # навигация, просмотр и zoom/pan
    java/io/github/mesteriis/lik/settings/  # выбор иконки и PackageManager
    res/layout/             # стартовый экран
    res/values*/            # EN/RU, светлая и тёмная темы
    res/drawable-nodpi/      # исходная иконка пользователя
    res/mipmap-anydpi/       # adaptive launcher icon
  src/androidTest/          # запуск, lifecycle и переключение launcher aliases
gradle/                     # Wrapper и каталог версий
.github/workflows/ci.yml    # сборка и lint
docs/ARCHITECTURE.md         # границы каркаса и точки расширения
docs/FEATURE_MATRIX.md       # функции и выбор для обсуждения
docs/REFERENCE_REVIEW.md     # разбор исходников семи проектов
docs/MODEL_ARCHITECTURE.md   # несколько моделей и совместимость индекса
references/                 # закреплённые Git-подмодули, вне сборки
```

Идентификатор приложения: `io.github.mesteriis.lik`; версия: `0.1.0`. На русском устройстве название — «Лик», на остальных — «Lik». Тема следует системе.

## Фото из Google Photos

При первом запуске разрешите доступ к фотографиям. Лик показывает доступные локальные фото напрямую из системного MediaStore и не создаёт для них копии. Чтобы импортировать фото из Google Photos, нажмите **Открыть Google Фото**, выберите фото и выполните **Поделиться → Импорт в Лик**. Проверка владельца URI подтверждает Google Photos provider, но не устанавливает, что конкретный файл cloud-only.

Только явный Google-импорт сохраняется как приватная копия. Нажатие открывает полноэкранный просмотр с листанием, pinch-to-zoom, двойным нажатием, перемещением увеличенного изображения и сведениями о формате, разрешении, размере и времени добавления. Долгое нажатие и удаление доступны для импортированных копий; локальные оригиналы остаются под управлением системной галереи.

До 50 фото за операцию, до 200 МиБ на файл; одинаковые байты не сохраняются повторно. Копирование, чтение списка, удаление и декодирование выполняются вне UI-потока. Прогресс переживает пересоздание экрана, а при прерывании процесса незавершённые файлы убираются при следующем запуске. Ошибка одного фото не отменяет остальные.

Оригиналы не меняются. Доступ к фото запрашивается стандартным разрешением Android; доступ к аккаунту и сеть самому приложению не нужны. Google-копии приватные, не входят в Android backup и удаляются вместе с приложением. Это не синхронизация всей облачной библиотеки. Save copy сохраняет одно фото через системное место назначения; при ошибке Lik очищает свои временные файлы, но не удаляет внешний документ, принадлежность которого приложению не доказана. После ошибки записи там могут остаться неполные данные. Видео, редактирование, пакетный экспорт копий и ИИ — отдельные этапы. [Устройство импорта и проверки](docs/GOOGLE_PHOTOS_IMPORT.md).

## Иконка

Откройте **Настройки → Иконка приложения** и выберите один из десяти вариантов: Классическая, Изумруд, Аметист, Сапфир, Рассвет, Лунный камень, Роза, Лёд, Северное сияние или Янтарь. Первая присланная в наборе иконка выбрана по умолчанию. Выбор сразу меняет launcher alias и изображение на стартовом экране; Android сохраняет его между запусками. Лаунчер может обновить свой кэш с задержкой.

`app/src/main/res/drawable-nodpi/lik_emblem*.png` — десять предоставленных изображений, без изменений. PNG используются как foreground поверх золотистого фона, а их alpha-канал — для системных monochrome-иконок. XML-обёртки сохраняют пропорции и безопасные отступы; форму маски выбирает лаунчер. Картинки также сохраняются в исходном разрешении.

## Release

Release использует R8 и удаление неиспользуемых ресурсов. Без ключа сборка создаёт `app-release-unsigned.apk`, который нельзя установить до подписания.

Для подписи скопируйте `keystore.properties.example` в `keystore.properties`, укажите собственный keystore и выполните `./gradlew assembleRelease`. Ключи, пароли и пути SDK не хранятся в Git.
