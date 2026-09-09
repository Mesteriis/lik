# Матрица функциональности для обсуждения

Срез референсов: **7 сентября 2026**. Все семь проектов закреплены в [references](../references/README.md). Это список кандидатов для Lik, а не обещание реализовать все функции. В приложении работают чтение разрешённых фото MediaStore без копирования, явный Google Photos → Share → Lik с приватной копией, лента, просмотр, организация, корзина, три скачиваемых ИИ-профиля и локальный семантический поиск.

**К** — прочитан код соответствующего механизма; **Д** — подтверждено документацией проекта; **З** — заглушка; **?** — в этом проходе не проверено, а не «функции нет». Ни одна отметка не означает проверку APK на Fold. Ссылки в ячейках ведут к разбору с постоянными ссылками на исходники.

Статусы Lik ниже разделяют принятый сценарий, код, локальные автоматические проверки и реальную приёмку. Test-provider подтверждает приём `content://` URI, но не настоящий Google cloud Share. Внешний экран Fold проверен; внутренний экран при физическом раскрытии и cloud-only Share остаются открытыми.

## Галерея, организация и файлы

| ID · Возможность | ReFra | Lavender Photos | Aves | Fossify Gallery | Ente Photos | Immich |
| --- | --- | --- | --- | --- | --- | --- |
| G01 · Локальная медиатека без аккаунта | [Д: локальный/offline вариант][R] | [К: локальные и SAF-источники][L] | [К: MediaStore и каталогизация][A] | [К: MediaStore, папки, OTG][F] | [Д: gallery mode, feature flag][E] | [Д: offline/read-only; автономный режим без сервера не проверен][I] |
| G02 · Лента фото/видео по датам | [Д: сетка и мозаика][R] | [К: paging и группы по датам][L] | [Д: коллекция и навигация][A] | [К: сортировка медиатеки][F] | [Д: локальная галерея][E] | [Д: timeline, virtual scroll][I] |
| G03 · Альбомы и группы альбомов | [Д: группы, коллекции, закрепление][R] | [Д: альбомы и группы][L] | [Д: альбомы][A] | [К: папки][F] | [Д: альбомы Ente требуют аккаунт][E] | [Д: серверные альбомы][I] |
| G04 · Ручные теги и поиск по имени/дате | [Д: фильтры метаданных; теги отдельно не проверены][R] | [К: имя, дата, теги][L] | [Д: теги и навигация][A] | ? | ? | [Д: метаданные, теги; README отмечает теги в Web][I] |
| G05 · Избранное | [К: контракт операций][R] | [Д: с интеграцией Immich][L] | ? | [К: отдельная выборка][F] | [Д: требует аккаунт][E] | [Д: есть][I] |
| G06 · Корзина и восстановление | [К: контракт trash/restore и возможности источника][R] | [К: операции облачной корзины; локальная описана][L] | ? | [К: копирование, восстановление, конфликт имён][F] | ? | [Д: используется в разборе дубликатов][I] |
| G07 · Копирование/перемещение между источниками | [К: контракт, включая очистку копий при отмене][R] | [Д: между локальными/облачными источниками][L] | ? | [К: операции файлов и восстановление][F] | ? | ? |
| G08 · SAF и сетевые файловые источники | [К: типы провайдеров; готовность каждого протокола не проверена][R] | [К: SAF; Д: RSAF и системные провайдеры][L] | ? | [К: OTG/SAF в файловых операциях][F] | ? | ? |
| G09 · EXIF, ориентация, сведения о файле | [Д: просмотр метаданных][R] | [Д: Quick Info][L] | [К: EXIF/XMP, каталогизация, ограничения больших файлов][A] | [К: чтение и перенос EXIF][F] | [Д: GPS для карты][E] | [Д: EXIF/карта/RAW][I] |
| G10 · Удаление метаданных перед передачей | [К: контракт очистки; полнота не проверена][R] | [К: ClearExif в контракте поиска][L] | [К: очистка GPS в editLocation; полнота не проверена][A] | [Д: удаление EXIF][F] | ? | ? |
| G11 · Motion Photo, панорамы, сложные форматы | [Д: Samsung/Google motion, 360°, RAW+JPG][R] | ? | [К: multitrack/GeoTIFF; Д: motion, TIFF, 360°][A] | [Д: RAW, AVIF, JXL и другие][F] | ? | [Д: motion/live; 360° в Web][I] |
| G12 · Редактор фото/видео | [Д: фото, crop, markup, фильтры, backup правок][R] | [Д: фото и видео офлайн][L] | ? | [Д: crop, resize, rotate, draw, фильтры][F] | ? | ? |
| G13 · Закрытая папка / защита доступа | [Д: зашифрованный vault][R] | [Д: Secure Folder AES-256][L] | ? | [Д: PIN/паттерн/биометрия; шифрование файлов не подтверждено][F] | [Д: E2EE облака — другой механизм][E] | ? |
| G14 · Карта и воспоминания | [Д: карта][R] | ? | [Д: карта и теги мест][A] | ? | [Д: карта и воспоминания в gallery mode][E] | [Д: карта и воспоминания][I] |
| G15 · Backup, синхронизация, общий доступ | [Д: облачные провайдеры; К: capabilities][R] | [Д: Immich backup; К: запись отложенной операции][L] | ? | ? | [Д: E2EE и общий доступ с аккаунтом][E] | [Д: self-hosted backup, sharing, API][I] |
| G16 · Системная интеграция | ? | ? | [Д: viewer/picker, widgets, shortcuts, TV][A] | ? | [Д: системный share sheet][E] | ? |
| G17 · Качество на Fold: раскрытие, два окна, большой шрифт | Не проверено | Не проверено | Не проверено | Не проверено | Не проверено | Не проверено |

Контракт операции в G05–G10 подтверждает устройство API, но не доказывает надёжность файловой операции. Google Drive через SAF/RSAF в Lavender не означает готовую интеграцию с Google Photos; Apple Photos также не подтверждён.

## ИИ и управление моделями

Rune Keyboard добавлен отдельным столбцом: это референс жизненного цикла моделей, а не галерея. Для Lavender, Aves и Fossify локальный семантический ML-контур в этом проходе не подтверждён; их сильные стороны отражены выше.

| ID · Возможность | ReFra | Ente Photos | Immich | Rune Keyboard |
| --- | --- | --- | --- | --- |
| M01 · Поиск по описанию фото | [К: локальные CLIP text/image encoders][R] | [К: локальные MobileCLIP text/image assets; Д: magic search][E] | [К: серверные CLIP embeddings; Д: фильтры][I] | Другой сценарий: оценка текстовых кандидатов |
| M02 · Русские запросы | [К: в SearchVisionHelper фильтр удаляет кириллицу][R] | Качество русского не измерено | [Д: multilingual-модели и сравнительные данные; это серверные измерения][I] | Не референс поиска по фото |
| M03 · Поиск похожих изображений | [К: image embedding и cosine ranking][R] | [К: SimilarImagesService, путь для файлов аккаунта][E] | [Д: визуально похожие/дубликаты][I] | — |
| M04 · Лица и группировка людей | [К: detector → embedding → clustering][R] | [К: этапы ML и версии; Д: локальная группировка][E] | [К: серверные detection/recognition-модели][I] | — |
| M05 · Исправление групп людей | ? | [К: предложения и feedback; просмотренный путь требует аккаунт][E] | ? | — |
| M06 · OCR: текст на изображениях | [З: LocalOcrProvider возвращает null/пустой поиск][R] | [К: OCR backends, detector/recognizer; выбор зависит от флага/платформы][E] | [К: серверный OCR, отдельные detector/recognizer][I] | — |
| M07 · Вырезание объекта / размытие лиц | [К/Д: MobileSAM; detector для blur][R] | ? | ? | — |
| M08 · Группировка питомцев | ? | [К: отдельные модели собак/кошек; загрузка по флагу и настройке][E] | ? | — |
| M09 · Несколько моделей на устройстве | [К: независимые группы Search/Cutout/FaceDetect/FaceRecognition][R] | [К: несколько assets и наборов загрузки][E] | [К: несколько моделей в серверном кэше][I] | [К: одна active + одна rollback][K] |
| M10 · Проверка и установка моделей | [К: SHA-256, группы файлов, bundled/on-demand][R] | [К: SHA-256, download lock, consent][E] | [К: загрузка по runtime/task; OCR с hash][I] | [К: размер, SHA-256, GGUF, private staging, self-test, атомарная активация][K] |
| M11 · Откат версии и восстановление после сбоя | Полная транзакция обновления не подтверждена | Полная транзакция обновления не подтверждена | ? | [К: журнал, active/rollback, повторяемая активация][K] |
| M12 · Контроль RAM/CPU, пауза индексации | [К: ограничение threads, закрытие face runtime][R] | [К: process lock, stop control, батарея/температура][E] | [К: серверный model cache/TTL][I] | [К: CPU budget, memory pressure, idle unload, latest request][K] |
| M13 · Индекс зависит от версии моделей | [К: processorRevision группы][R] | [К: face/clip/cluster versions, flags runtime][E] | [К: сброс embeddings при смене модели/размерности; reindex вручную][I] | Галерейного индекса нет |
| M14 · Синхронизация ML-индекса | ? | [К/Д: версия + E2EE sync для аккаунта; локальный путь отдельный][E] | [К: индекс на сервере][I] | — |
| M15 · LLM/VLM: описания, чат с галереей | Не подтверждено | Не подтверждено | Не подтверждено | [К: scorer на llama.cpp, не готовый VLM/chat API][K] |

Наличие нескольких файлов моделей не означает, что все они должны одновременно находиться в RAM. Предложение для Lik — [каталог моделей, наборы для функций и общий планировщик](MODEL_ARCHITECTURE.md).

## Что обсуждаем и откуда предлагаем брать

**Текущий scope от 2026-09-09:** реализована photo-only ветка T00–T13: read-only MediaStore, явные Google Share-копии, Room/Paging-каталог с кэшированием EXIF по ревизии, лента/просмотр/выбор, папки/альбомы/избранное/метки/поиск, экспорт и 30-дневная корзина приватных копий. Доступны три загружаемых offline AI-профиля, semantic search, OCR/People, точные дубликаты/похожие фото и карантин с biometric reveal. Веса/tokenizers не входят в APK; automatic SAFE выключен до настоящей калибровки. Качество моделей, физический Fold и настоящий Google Photos Share остаются отдельной приёмкой; видео не входит в scope. [MVP-план](superpowers/plans/2026-09-08-gallery-mvp.md) документирует исторические решения Photo Picker/GridView, заменённые текущим контрактом.

Ниже — **предложение порядка работ**, а не утверждённый roadmap. «Первая версия» означает первый работающий галерейный прототип. Решение по каждой строке пока открыто; в обсуждении можно ссылаться на её ID.

| ID | Возможность для Lik | Основной референс | Предлагаемый этап | Что нужно решить |
| --- | --- | --- | --- | --- |
| D01 | Локальная лента, просмотр фото/видео, альбомы | Собственная реализация; идеи Lavender + Fossify | **Частично реализовано:** MediaStore без копирования, Google Share-копии, Room/Paging-лента, viewer по ID; incremental scan; папки по volume/bucket/path и виртуальные альбомы Room | Видео и группы альбомов не реализованы; внутренний Fold ещё не принят |
| D02 | Избранное, множественный выбор, корзина/восстановление | Собственная реализация; идеи Fossify + ReFra + Lavender | **Реализовано:** Room-избранное, выбор обоих источников; приватная корзина на 30×24 часа, restore с сохранением связей, подтверждаемый purge, повторный SHA-импорт восстанавливает копию | Device/Google originals не удаляются; WorkManager и app-start cleanup могут выполнить физическую очистку позже срока при ограничениях ОС |
| D03 | Метаданные, ориентация, корректный просмотр Samsung Motion Photo | Собственная реализация; сценарии Aves + ReFra | **Частично реализовано:** формат, разрешение, размер, время добавления и EXIF 1–8; [Room-каталог](MEDIA_CATALOG.md), volume/version/generation reconciliation, revision cache даты/ориентации и отсутствующего EXIF для обоих источников; сохранённая зона библиотеки | Полный EXIF-каталог и Motion Photo ещё не реализованы |
| D04 | Поиск по имени/дате/тегам | Собственная реализация; идея Lavender | **Реализовано:** ручные метки, NFC/Unicode-поиск по имени, диапазону дат, источнику и метке; EN/RU native UI, страницы по 60 строк | Семантический поиск — отдельная вкладка Task 10; OCR-поиск относится к Task 11 |
| D05 | Несколько моделей, установка, обновление, удаление и откат | Rune + группы ReFra + assets Ente | **Реализовано:** три immutable HF-профиля, resumable download, физический remaining-byte reserve, digest dedup, trusted verification receipts/corrupt repair, self-test, поколения индекса и атомарный active pointer; веса вне APK | Реальные quality/Fold измерения не заявлены; Samsung backend выключен |
| D06 | Локальный поиск по русскому описанию | ReFra + Ente; сравнение моделей Immich | **Реализовано:** RU/EN tokenizer/preprocessing и image/text inference всех трёх профилей, Room v6 revision/grant epoch, streamed exact-membership USearch JNI и видимый охват | Качество RU/EN, память и скорость на Fold не измерены |
| D07 | Люди: группы, имена и исправление ошибок | Ente + ReFra | **Вычислительный pipeline Task 11:** YuNet/SFace в isolated CPU runtime, поколения detections/embeddings, устойчивые person ID и отдельные durable name/merge/split/move/exclude решения | Task 13 допускает только revision-matched `SAFE` в people pipeline и EN/RU результаты; качество кластеров и порог требуют физической приёмки |
| D08 | OCR для скриншотов и документов | Ente + Immich | **Вычислительный pipeline Task 11:** downloaded PP-OCRv5 mobile/server detector + Cyrillic recognizer, generation/revision/epoch persistence и карантин | Task 13 допускает только revision-matched `SAFE` в OCR pipeline, viewer/copy/search и видимый coverage; качество и Fold ещё не измерены |
| D09 | Похожие фото и разбор дубликатов | ReFra + Ente + Immich | **Реализовано:** cross-source SHA-256 группы с paged members; DCT v2, полный при Hamming≤14 16-band lookup, unique-hash bounded/resumable visual scan with durable tranche budget, capped relations, current revision/access/SAFE-aware EN/RU UI | Пользователь сам выбирает удаление; только приватная копия идёт в 30-дневную корзину, оригиналы read-only; качество/порог на физическом Fold не измерены |
| D10 | SAF, сетевые папки, подключаемые источники | Собственная реализация; идея Lavender + capabilities ReFra | **Частично реализовано:** Share выбранных фото через FileProvider и узкие READ grants; Save copy одного фото через CREATE_DOCUMENT | Сетевые папки/другие источники и пакетный Save copy не реализованы; исходные байты сохраняются. При ошибке записи очищается app temp; внешний документ не удаляется и может содержать неполные данные |
| D11 | Необязательный Immich: backup и серверные функции | Immich + Lavender/ReFra | Отдельный этап | Только backup или также чтение и изменение серверной библиотеки |
| D12 | Зашифрованная папка | Lavender + ReFra; Ente для отдельной темы E2EE | Отдельный этап | Граница приватных фото: поиск, превью, backup, восстановление ключа |
| D13 | Редактор, cutout, blur faces | ReFra + Lavender + Fossify | Позже базовой галереи | Минимальная правка или полноценный редактор |
| D14 | Карта, воспоминания, питомцы, widgets/casting | Aves + Ente + ReFra | По выбору | Что отличает Lik и стоит отдельной сложности |
| D15 | Чат/генерация описаний через VLM/LLM | Локальный отдельный AiGate/router | **Частично реализовано:** opt-in loopback health/models/chat, отдельное одноразовое подтверждение фото, metadata-free JPEG | Task 13 требует biometric reveal для sensitive transfer и отклоняет работу старой reveal epoch; реальный AiGate/cloud run открыт |

Уже задано пользователем: нативное Android-приложение, несколько моделей на телефоне, подход Rune к их жизненному циклу, десять выбираемых иконок и три profiles — Compact, Balanced (default), Extended. Task 9 закрепил точные HF URLs/артефакты/hash; distribution gate и обычные сборки запрещают любые model payloads. Task 10 реализовал Settings download, isolated CPU runtime, атомарную активацию и semantic USearch поколения. [Фактическая проверка поставки](MODEL_PROVENANCE.md) отделена от невыполненных quality/Fold измерений. Для Google Photos отдельно принят D16 — явный импорт выбранных фото, описанный ниже.

## Проверки перед переносом решений

| Область | Что должно быть доказано в Lik |
| --- | --- |
| Медиа | Постраничная выдача; прерывание сканирования; повторный доступ после отзыва разрешения; неизменность оригиналов при отмене операций |
| Fold | Малый и большой экран, раскрытие/сворачивание, multi-window, увеличение шрифта, сохранение позиции и выделения |
| Поиск | RU/EN-набор запросов, измеренная релевантность, cold/warm latency, корректность ориентации и preprocessing |
| Несколько моделей | Независимая установка и удаление, отказ одной модели не ломает остальные, бюджет RAM учитывает декодированные изображения |
| Обновление | Несовместимые embeddings не смешиваются; сбой self-test сохраняет рабочий набор; отмена/перезапуск не теряют прогресс |
| Источник заимствования | Конкретный commit и файлы, идея/адаптация/код, лицензия исходников и отдельно выбранных весов |

[R]: REFERENCE_REVIEW.md#refra
[L]: REFERENCE_REVIEW.md#lavender
[A]: REFERENCE_REVIEW.md#aves
[F]: REFERENCE_REVIEW.md#fossify
[E]: REFERENCE_REVIEW.md#ente
[I]: REFERENCE_REVIEW.md#immich
[K]: REFERENCE_REVIEW.md#rune


## Принято: D16 — импорт из Google Photos (2026-09-08)

Пользователь уточнил модель хранения: локальные фото читаются напрямую через MediaStore без копирования, а приватные копии создаются только при явном Google Photos → «Поделиться → Лик». Реализованы импорт до 50 фото, SHA-256-дедупликация, прогресс, частичные ошибки, одноразовый итог операции с числами добавленных/дубликатов/ошибок и устойчивое к пересозданию Activity отображение, единая сетка, просмотр и удаление только импортированных копий. Ввод отклоняется отдельными типизированными причинами для некорректного набора и уже выполняемой операции. Прямой OEM API и синхронизация облачного каталога не входят в решение. Task 10 отдельно подключил скачиваемые профили и локальный семантический поиск.

Источник — публичные Android MediaStore и Share Intent API; реализация написана для Lik, runtime-код референсов не копировался. Файлы: `gallery/GalleryPhoto.kt`, `imports/PhotoStore.kt`, `PhotoLibrary.kt`, `ImportInput.kt`, `ImportAdmission.kt`, `ImportViewModel.kt`, `ShareImportActivity.kt`, `ui/MainActivity.kt`, `ui/TimelineAdapter.kt`, `gallery/PhotoViewerActivity.kt`, `PhotoViewerViewModel.kt`, `ZoomImageView.kt`. [Спецификация и источники](GOOGLE_PHOTOS_IMPORT.md). Успешный импорт test-provider не считается проверкой облачного аккаунта Google Photos.

## Выбрано направление: лента и Fold (2026-09-08)

Пользователь выбрал верхнюю панель с облаком и настройками, плотный уровень «Фото» по три квадратные плитки в ряд, мозаику дней, нижнюю навигацию и масштабирование «Фото → Дни → Недели → Месяцы → Годы». Между верхней панелью и переключателем нет отдельной строки названия/количества. Группировка использует дату съёмки, при её отсутствии — дату добавления. Для обзорных уровней приняты обложки и счётчики; при раскрытии Fold сохраняются масштаб и место в библиотеке. Реализация собственная: при pinch и resize она сохраняет ближайшее к focus фото и относительное смещение, а если это фото исчезло — ближайший кадр по хронологии; сторонний runtime-код не переносился.

[Спецификация и четыре макета](superpowers/specs/2026-09-08-gallery-zoom-fold-design.md) стали основой реализованного интерфейса. `GalleryTimeline` строит пять уровней по дате съёмки, а `TimelineController` публикует только последнюю неизменяемую ревизию после worker-построения и worker-DiffUtil. Альбомы и поиск реализованы native Views; Task 11 добавил раздел «Люди», а Task 13 ограничил его pipeline и результаты revision-matched `SAFE`. «Места» остаются зарезервированными.

## Task 9 additions

Pinned HF download manifests, verified external bytes, metadata-only APK enforcement and synthetic conversion evidence: [MODEL_PROVENANCE.md](MODEL_PROVENANCE.md). The APK gate covers all app/device-test variants and every ZIP entry: renamed raw resources/blobs, unapproved PNG/runtime bytes and hidden archive payloads fail. Full source-bound AAPT2/D8/R8 receipts plus SDK parsing reject compiled-container wrappers; unique fully consumed signing records reject opaque duplicate blocks. Reviewed X.509 field/extension semantics and complete signing-envelope HF fingerprint scans also reject certificate payloads while standard RSA/EC release signing remains supported. See [APK policy](APK_PAYLOAD_POLICY.md) for manual compiler-only receipt review and exact toolchain provenance. Real downloaded weights/tokenizers remain outside Git. The optional Samsung backend remains unaccepted until [physical per-pipeline measurements](SAMSUNG_BACKENDS.md). [AiGate](AIGATE_INTEGRATION.md) is implemented as a separate explicit loopback-only opt-in with no real provider acceptance yet. One shared HF Marqo sensitive classifier is downloaded for all profiles. [Task 13](SENSITIVE_MEDIA.md) implements fail-closed quarantine, separate manual decisions and process-local `BIOMETRIC_STRONG` reveal across gallery, AI, export and AiGate boundaries. Calibration remains absent, so automatic `SAFE` publication stays disabled.
