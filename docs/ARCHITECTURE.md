# Архитектура Lik

## Текущий каркас

Один Android-модуль `:app`. `ui.MainActivity` показывает разрешённую локальную медиатеку и импортированные Google-копии и открывает `settings.SettingsActivity`, где выбирается иконка. MainActivity и SettingsActivity не экспортируются напрямую; отдельная ShareImportActivity экспортирует только вход SEND/SEND_MULTIPLE image/*; вход из лаунчера идёт через один активный `activity-alias`. Поддерживаются Android 16–17: `minSdk = 36`, `targetSdk = compileSdk = 37`. Android 16 нужен текущей прошивке целевого Galaxy Fold, Android 17 — его следующему обновлению. Веток совместимости для более старых Android нет.

Строки вынесены в EN/RU-ресурсы; цвета и системные панели следуют светлой/тёмной теме. `RecyclerView` использует автоматическое количество колонок под ширину окна. Ориентация и размер окна не зафиксированы.

Как в Rune Keyboard, используются системные Activity/Views и встроенный в AGP Kotlin. AndroidX Activity и LiveData/ViewModel отвечают за разрешение на фото и состояние при пересоздании экрана. PhotoLibrary держит один синхронизированный PhotoStore на процесс. Room v4 хранит метаданные и организацию, Paging обслуживает ленту. Импорт, операции корзины, экспорт, превью и полноэкранное декодирование выполняются вне UI-потока. WorkManager используется только для очистки корзины и временных экспортов; сетевого клиента и галерейного ML ещё нет; библиотека ONNX Runtime присутствует для отдельной CPU-проверки.

Галерейный ML runtime пока не подключён. Task 9 фиксирует проверяемые HF download manifests и отдельный Android CPU acceptance test с внешними QA-файлами. Модельная поставка следует [архитектуре моделей](MODEL_ARCHITECTURE.md): Compact, Balanced (default) и Extended — immutable versioned sets для semantic search, OCR и people. Модели скачиваются через Settings из immutable Hugging Face URLs в Task 10; веса и токенизаторы запрещены в любом APK. Distribution gate проверяет отсутствие payloads независимо от cache. Balanced выбран по умолчанию, но становится runtime-ready только после download/проверки/self-test и индексации. Предыдущий active profile работает до атомарного переключения. Точные hashes, размеры и выполненные проверки описаны в [provenance](MODEL_PROVENANCE.md).

Manifest приложения не запрашивает доступ к сети или аккаунтам. Он запрашивает Android-доступ ко всем или выбранным локальным изображениям; MediaStore возвращает разрешённые `content://` URI. AndroidX добавляет собственное signature permission для внутренних receivers. ИИ ещё не реализован. Резервное копирование и cleartext отключены; телеметрии нет.

## Импорт

[MediaStore и Google Photos Share](GOOGLE_PHOTOS_IMPORT.md): `imports/PhotoStore` сохраняет байты во временный файл, проверяет изображение, вычисляет SHA-256 и публикует копию переименованием в той же директории. `ImportViewModel` проверяет пакет владельца URI и ведёт операцию вне UI-потока; `ImportInput` ограничивает и разбирает URI. `GalleryCatalog` объединяет разрешённые строки MediaStore с завершёнными Google-копиями. `TimelineAdapter` декодирует файлы и `content://` URI в двух worker-потоках через ограниченную очередь и LRU-кэш. Полный облачный каталог не запрашивается.

## Галерея и просмотр

`MainActivity` при первом запуске запрашивает доступ к изображениям и показывает хронологическую ленту в `RecyclerView`. Чистая модель `GalleryTimeline` группирует элементы по дате съёмки, а при её отсутствии — по дате добавления, на уровнях Фото, Дни, Недели, Месяцы и Годы. `TimelineController` строит неизменяемую ревизию и `DiffUtil` вычисляет её вне UI-потока; устаревшие ревизии не публикуются, а неизменный список во время прогресса импорта не перестраивается. Уровень «Фото» всегда использует три квадратные `centerCrop`-плитки в ряд; строка названия и общего количества над переключателем отсутствует. `TimelineAdapter` запрашивает только видимые миниатюры у двух worker-потоков с ограниченной очередью, приоритетом видимых плиток, coalescing и отменой при recycling; LRU-ключ содержит ID, ревизию источника, размер и эпоху доступа. `GalleryUiState` сохраняет масштаб, раздел и якорь, а `GallerySelection` хранит выбранные SHA-256 ID независимо от позиций. Локальные MediaStore-фото нельзя включить в удаление приватных копий.

Приватная `gallery.PhotoViewerActivity` принимает ID элемента объединённого каталога. `PhotoCursor` определяет предыдущее и следующее фото; `PhotoViewerViewModel` заново сверяет ID с `GalleryCatalog`, декодирует ограниченный bitmap и читает формат, исходное разрешение, размер, время добавления и EXIF-ориентацию. `ZoomImageView` реализует масштаб 1–5×, двойное нажатие, pan и перелистывание на базовом масштабе. Удаление доступно только для приватных Google-копий и вызывает `TrashRepository.trash` вне UI-потока. Копии сохраняются 30×24 часа; restore, durable purge и экспорт описаны в [контракте каталога](MEDIA_CATALOG.md#share-export-and-trash-task-8). Локальные MediaStore-фото остаются только для чтения.

## Исследование следующего этапа

[Матрица функциональности](FEATURE_MATRIX.md) фиксирует кандидатов из семи закреплённых [референсов](../references/README.md). Их исходники не включены в Gradle. [Разбор](REFERENCE_REVIEW.md) отделяет реализованные механизмы, документацию и заглушки.

[Архитектура моделей](MODEL_ARCHITECTURE.md) — проект следующего этапа: несколько моделей на телефоне, независимые установки, согласованные наборы функций, общий планировщик ресурсов и версионируемый индекс. Основа жизненного цикла взята из Rune Keyboard и адаптирована к нескольким моделям. Это не описание уже существующих классов; runtime и веса добавляются вместе с выбранной ML-функцией.

## Выбор иконки

`AppIcon` связывает десять PNG, подписи и устойчивые имена launcher aliases. `AppIconManager` читает выбор из состояния компонентов Android, без дублирования в SharedPreferences. `setComponentEnabledSettings` атомарно включает выбранный alias и выключает остальные с `DONT_KILL_APP`. По умолчанию включён только `Classic`. Имена aliases нельзя менять без миграции: Android хранит пользовательский выбор по этим именам.

Настройки показывают превью и RadioButton для каждого варианта. На возврате `MainActivity.onResume` обновляет свой значок из системного состояния. Сеть и дополнительные разрешения не нужны. Instrumentation проверяет все десять переключений, единственную доступную launcher Activity, восстановление выбранного пункта после пересоздания настроек и запуск через выбранный alias.

## Куда добавлять функциональность

Добавляйте пакет вместе с первой реальной возможностью, без пустых интерфейсов и фиктивных репозиториев:

- `gallery/` — экран галереи, его состояние и операции с коллекцией;
- `media/` — Android MediaStore, чтение изображений и управление доступом;
- `intelligence/` — выбранный способ обработки изображений и поиска;
- `settings/` — существующий выбор иконки и последующие настройки.

Правила и преобразования, не зависящие от Android, выносите из Activity в чистый Kotlin и проверяйте JVM-тестами. Доступ к Android API держите на границе соответствующего пакета. Отдельные Gradle-модули нужны при появлении реальной границы зависимостей.

## Проверка изменений

Для каркаса достаточно сборки, lint и instrumentation smoke-теста запуска/пересоздания Activity. Для новой логики добавляйте тесты её поведения; для изменений интерфейса проверяйте светлую/тёмную тему, API 36 и 37, маленькое окно и большой шрифт.
## Optional AI execution boundaries selected during Task 9

Built-in profile artifacts follow [the immutable model contract](MODEL_ARCHITECTURE.md); CPU is the required reference and fallback. [Samsung/NNAPI/NPU backends](SAMSUNG_BACKENDS.md) are optional device experiments gated by per-pipeline parity and measured Fold performance, never a replacement for the selected weights or an assumed API into Galaxy AI system models.

[AiGate integration](AIGATE_INTEGRATION.md) is implemented as separate opt-in Task 10 work over same-device loopback transport. It requires an explicit per-photo action before a sampled, resized, metadata-stripped image can leave Lik. Provider credentials remain in AiGate; router output cannot silently change local indexes. Real provider acceptance and the Task 13 sensitive-media reveal boundary remain open.

[Local sensitive screening and biometric hiding](SENSITIVE_MEDIA.md) adds a shared classifier artifact in Task 9 and a future Task 13 visibility policy. Current artifact preparation does not hide media. Task 13 quarantines unclassified photos and enforces a BIOMETRIC_STRONG in-memory reveal across gallery, export and AI/router boundaries.
