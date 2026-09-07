# Разбор референсов Lik

Дата: **2026-09-07**. Основание — исходники закреплённых подмодулей и документация самих проектов. Прочитаны перечисленные ниже ключевые участки, а не весь код монорепозиториев. APK референсов не собирались и не запускались; расход памяти, батареи, качество русского поиска и Fold UX не измерялись.

[Матрица функций](FEATURE_MATRIX.md) — основной документ для обсуждения. [Модели Lik](MODEL_ARCHITECTURE.md) — предложение адаптации Rune. Названия лицензий относятся к указанным файлам репозиториев; права на конкретные веса, датасеты и сторонние компоненты этим не устанавливаются. В этом изменении сторонний runtime-код в приложение Lik не переносится.

<a id="refra"></a>
## ReFra

Kotlin/Compose, Apache-2.0. Сборка включает Room, Hilt, WorkManager, ONNX, adaptive-библиотеки и native-компоненты: [сборка](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/app/build.gradle.kts). Закреплённый commit отмечен тегом 5.1.3. [Описание возможностей](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/fastlane/metadata/android/en-US/full_description.txt) перечисляет ленту, альбомы/группы, motion photo, панорамы, карту, редактор, vault, casting и поиск.

**Что прочитано и полезно для Lik:**

- [MediaCapabilityProvider.kt](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/app/src/main/kotlin/com/dot/gallery/cloud/core/MediaCapabilityProvider.kt) и [ProviderCapability.kt](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/app/src/main/kotlin/com/dot/gallery/cloud/core/ProviderCapability.kt): источник сообщает возможности; TRASH означает восстановимое удаление, а не любое удаление. Это основа для доступных действий по источнику. [ProviderType.kt](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/app/src/main/kotlin/com/dot/gallery/cloud/core/ProviderType.kt) перечисляет Immich, ownCloud, Nextcloud, WebDAV, SMB/NFS; enum сам по себе не доказывает полноту каждого адаптера.
- [ModelManager.kt](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/app/src/main/kotlin/com/dot/gallery/core/ml/ModelManager.kt): четыре группы файлов с независимыми состояниями и прогрессом — Search, Cutout, FaceDetect, FaceRecognition. CLIP-группа содержит image/text encoders и tokenizer assets. Есть SHA-256 и processorRevision. Берём принцип набора файлов для функции.
- [SmartScanWorker.kt](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/app/src/main/kotlin/com/dot/gallery/core/workers/SmartScanWorker.kt): run/phase leases, сохранение прерывания, processor revision, разветвлённые стадии. Полезен как второй референс для возобновляемой индексации вместе с Ente.
- [SearchVisionHelper.kt](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/app/src/main/kotlin/com/dot/gallery/feature_node/presentation/search/helpers/SearchVisionHelper.kt) и [SearchHelperImpl.kt](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/app/src/main/kotlin/com/dot/gallery/feature_node/presentation/search/SearchHelperImpl.kt): text/image ONNX sessions, preprocessing, embeddings и cosine ranking.
- [FaceIndexerWorker.kt](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/app/src/main/kotlin/com/dot/gallery/core/workers/FaceIndexerWorker.kt): обнаружение лиц, embedding, incremental clustering; recognizer используется при наличии отдельной модели, runtime закрывается в finally. Модель обнаружения также нужна для blur faces, описанного в [ML-документации](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/ml-models/README.md).

**Конкретные ограничения:**

В SearchVisionHelper.getTextEmbedding используется фильтр **[^A-Za-z0-9 ]**. Кириллица удаляется до токенизации. Это факт просмотренного пути поиска, а не результат теста русской релевантности: переносить этот preprocessing в Lik нельзя.

[LocalOcrProvider.kt](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/app/src/main/kotlin/com/dot/gallery/cloud/local/LocalOcrProvider.kt) — явная заглушка: extractText возвращает null, searchByText — пустой результат. OCR в списке capabilities нельзя считать работающим локальным OCR.

[MediaRepository.kt](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/app/src/main/kotlin/com/dot/gallery/feature_node/domain/repository/MediaRepository.kt) соединяет альбомы, файлы, vault, настройки, embeddings и ActivityResultLauncher. Для Lik предлагаем разделить чтение медиатеки, операции, индекс и запуск системных подтверждений. FaceIndexerWorker сначала получает полную медиатеку и список уже индексированных ID, а SearchHelper сортирует список embeddings в памяти. Это основания для нагрузочных проверок, не измеренное доказательство тормозов.

Текущие ModelGroup и имена файлов фиксированы. Не подтверждён общий бюджет памяти для всех групп и транзакционный откат согласованного набора после обновления. Манифесты и ряд URL указывают main; для Lik нужны закреплённые артефакты с обязательными hash/size. ML README отдельно называет UltraFace, ArcFace, MobileSAM и YOLO-эксперименты: окончательный набор весов ещё не выбран.

**Предлагаем взять:** capabilities источников, группировку моделей по функции, этапы индексации, контракты text/image поиска. Статус: кандидаты для собственной реализации. Форк всего приложения и переход Lik на Compose не выбраны.

<a id="lavender"></a>
## Lavender Photos

Kotlin/Compose, GPL-3.0; Room/Paging, Hilt, Glide и WorkManager видны в [сборке](https://github.com/kaii-lb/LavenderPhotos/blob/d2d60148c62fc14071333911b307401fdab0cf17/app/build.gradle.kts). [README](https://github.com/kaii-lb/LavenderPhotos/blob/d2d60148c62fc14071333911b307401fdab0cf17/README.md) описывает теги, группы альбомов, локальный редактор фото/видео, избранное, корзину, Secure Folder, Immich backup и SAF/RSAF.

[SAFRepository.kt](https://github.com/kaii-lb/LavenderPhotos/blob/d2d60148c62fc14071333911b307401fdab0cf17/app/src/main/java/com/kaii/photos/repositories/SAFRepository.kt) строит Pager из Room DAO, кэширует поток в scope, обновляет записи транзакционно порциями по 500. Но refresh собирает множества существующих/пришедших ID; paging экрана не означает, что сканирование тоже целиком постраничное. После стартовой задержки работает refresh-цикл с паузой 15 секунд. В Lik предлагаем события изменения источника и управляемое обновление с прогрессом; конкретный scheduler выберем при реализации.

[SearchRepository.kt](https://github.com/kaii-lb/LavenderPhotos/blob/d2d60148c62fc14071333911b307401fdab0cf17/app/src/main/java/com/kaii/photos/repositories/SearchRepository.kt) явно имеет SearchMode Name/Date/Tag и запросы к DAO. Это обычный структурированный поиск. [TagRepository.kt](https://github.com/kaii-lb/LavenderPhotos/blob/d2d60148c62fc14071333911b307401fdab0cf17/app/src/main/java/com/kaii/photos/repositories/TagRepository.kt) — создание/чтение/удаление тегов. Полезная первая функция без зависимости от ML.

[CloudTrashOperation.kt](https://github.com/kaii-lb/LavenderPhotos/blob/d2d60148c62fc14071333911b307401fdab0cf17/app/src/main/java/com/kaii/photos/file_management/managers/operations/CloudTrashOperation.kt) публикует Started/ItemDone/Finished и использует SyncTaskRecorder для локального применения с последующей удалённой операцией. Отмечаем различие удаления файла, удаления из альбома и восстановления. Статусы и повторяемые операции полезны для Lik; поведение при обрыве сети ещё нужно проверить.

**Предлагаем взять:** paging медиатеки, разделение источников/операций, теги и группы альбомов, прогресс файловых задач, идеи адаптера Immich. Не объявляем поддержанными Google Photos или Apple Photos: системный файловый провайдер — другой контракт. Статус: обсуждение; исходники в Lik не перенесены.

<a id="aves"></a>
## Aves

Flutter/Dart с Android/Kotlin-слоем, BSD-3-Clause. [README](https://github.com/deckerst/aves/blob/5785d8597b02c635c3f1eb25d0724bf90259b415/README.md) описывает motion photos, панорамы, 360° video, TIFF/GeoTIFF, metadata navigation, viewer/picker, widgets, shortcuts и Android TV.

В [Metadata.kt](https://github.com/deckerst/aves/blob/5785d8597b02c635c3f1eb25d0724bf90259b415/android/app/src/main/kotlin/deckers/thibault/aves/metadata/Metadata.kt) есть обработка EXIF-ориентации, EXIF/XMP и защита чтения некоторых больших файлов: вместо полного файла может читаться начальный фрагмент. Порог и размер фрагмента являются эвристикой Aves; они не гарантируют полноту метаданных и не становятся нормативом для Lik.

[MultiTrackMedia.kt](https://github.com/deckerst/aves/blob/5785d8597b02c635c3f1eb25d0724bf90259b415/android/app/src/main/kotlin/deckers/thibault/aves/metadata/MultiTrackMedia.kt) различает индекс трека и индекс изображения в контейнере и освобождает MediaExtractor/MetadataRetriever. Это полезный ориентир для HEIF/multitrack и необычных файлов на Samsung.

[Каталогизация](https://github.com/deckerst/aves/blob/5785d8597b02c635c3f1eb25d0724bf90259b415/lib/model/entry/extensions/catalog.dart) разделяет первичную информацию, платформенное чтение и уточнение видео/AVIF/GeoTIFF; уже каталогизированные элементы можно пропускать. [analysis_service.dart](https://github.com/deckerst/aves/blob/5785d8597b02c635c3f1eb25d0724bf90259b415/lib/services/analysis_service.dart) и [analysis_controller.dart](https://github.com/deckerst/aves/blob/5785d8597b02c635c3f1eb25d0724bf90259b415/lib/model/source/analysis_controller.dart) показывают управляемый фоновый разбор, прогресс и сигнал остановки. [Редактирование метаданных](https://github.com/deckerst/aves/blob/5785d8597b02c635c3f1eb25d0724bf90259b415/lib/model/entry/extensions/metadata_edition.dart) включает очистку EXIF GPS и обновление координат MP4 в editLocation. Полнота удаления всех приватных метаданных не проверена.

**Предлагаем взять:** матрицу медиаформатов, orientation/metadata pipeline, отдельную каталогизацию, сложные тестовые сценарии motion/multitrack. Flutter UI не переносим в текущий Kotlin/Views-каркас. Статус: обсуждение; сравнение декодеров на тестовых файлах впереди.

<a id="fossify"></a>
## Fossify Gallery

Kotlin/Views, GPL-3.0. [Сборка](https://github.com/FossifyOrg/Gallery/blob/24017e2116ac965405c66df8af357caa0ac46b1d/app/build.gradle.kts) включает ViewBinding, Room и Fossify Commons; это ближе к текущему UI-стеку Lik, чем было оценено в присланной записке.

[MediaFetcher.kt](https://github.com/FossifyOrg/Gallery/blob/24017e2116ac965405c66df8af357caa0ac46b1d/app/src/main/kotlin/org/fossify/gallery/helpers/MediaFetcher.kt) разделяет MediaStore, папки, OTG, избранное, фильтры и сортировку. Есть path-based и legacy-ветки, а [manifest](https://github.com/FossifyOrg/Gallery/blob/24017e2116ac965405c66df8af357caa0ac46b1d/app/src/main/AndroidManifest.xml) содержит запросы широкого доступа к файлам. В Lik поддержка только нового Android: изучаем сценарии, разрешения проектируем под выбранные действия.

[Файловые операции](https://github.com/FossifyOrg/Gallery/blob/24017e2116ac965405c66df8af357caa0ac46b1d/app/src/main/kotlin/org/fossify/gallery/extensions/Activity.kt) содержит корзину, проверку объёма копии, восстановление даты, альтернативное имя при конфликте и fallback-папку для восстановления. Полезны конкретные случаи: прежняя папка недоступна, имя занято, источник OTG. Корзина реализована через файловые операции; это не основание назвать её системной MediaStore-корзиной.

[ExifUtils.kt](https://github.com/FossifyOrg/Gallery/blob/24017e2116ac965405c66df8af357caa0ac46b1d/app/src/main/kotlin/org/fossify/gallery/extensions/ExifUtils.kt) переносит EXIF, исключая размерностные поля. Список прямо назван неполным. [Описание](https://github.com/FossifyOrg/Gallery/blob/24017e2116ac965405c66df8af357caa0ac46b1d/README.md) заявляет редактор, удаление EXIF и защиту PIN/паттерном/биометрией. Последнее не подтверждает шифрование содержимого файлов.

**Предлагаем взять:** поведение локальной галереи, восстановление/конфликты имён, сохранение метаданных при правках, checklist для Views. Не переносить целиком Activity-утилиты и старые storage-ветки. Статус: обсуждение.

<a id="ente"></a>
## Ente Photos

Flutter/Dart, Android-платформенные компоненты и Rust ML-код; основной LICENSE — AGPL-3.0. Работа с моделями находится в мобильном приложении внутри монорепозитория.

[Gallery Mode FAQ](https://github.com/ente/ente/blob/9ad0263664a79a8fc5e1c91db8a656e900b4a98d/docs/docs/photos/faq/gallery-mode.md) подтверждает просмотр локальных фото, лица, magic search, карту и воспоминания без аккаунта. Доступность режима управляется feature flag. Избранное, альбомы Ente, совместные альбомы и публичные ссылки требуют аккаунт.

[ml_model_assets.dart](https://github.com/ente/ente/blob/9ad0263664a79a8fc5e1c91db8a656e900b4a98d/mobile/apps/photos/lib/services/machine_learning/ml_model_assets.dart) содержит отдельные detector/embedding-модели лиц, MobileCLIP image/text, словарь и модели питомцев, с SHA-256. [ml_model_download_service.dart](https://github.com/ente/ente/blob/9ad0263664a79a8fc5e1c91db8a656e900b4a98d/mobile/apps/photos/lib/services/machine_learning/ml_model_download_service.dart) разделяет indexing/non-indexing assets, проверяет ML consent и настройку индексации, повторяет загрузку при изменении сети. Питомцы зависят от флага и настройки. В local gallery mode есть исключение из проверки high-bandwidth; сетевую политику Lik следует задать явно.

[ml_service.dart](https://github.com/ente/ente/blob/9ad0263664a79a8fc5e1c91db8a656e900b4a98d/mobile/apps/photos/lib/services/machine_learning/ml_service.dart) различает local-gallery и account-результаты, отдельно записывает faces/CLIP и учитывает выполненные стадии. [ml_versions.dart](https://github.com/ente/ente/blob/9ad0263664a79a8fc5e1c91db8a656e900b4a98d/mobile/apps/photos/lib/models/ml/ml_versions.dart) хранит версии face/clip/cluster и flags runtime. [ML-документация](https://github.com/ente/ente/blob/9ad0263664a79a8fc5e1c91db8a656e900b4a98d/docs/docs/photos/features/search-and-discovery/machine-learning.md) описывает E2EE синхронизацию индексов для аккаунта.

[device_health_policy.dart](https://github.com/ente/ente/blob/9ad0263664a79a8fc5e1c91db8a656e900b4a98d/mobile/apps/photos/lib/services/machine_learning/device_health_policy.dart) проверяет свежесть наблюдений, батарею, температуру и thermal state. Это полезная часть планировщика Lik; конкретные числа Ente не считаем измеренными для Fold.

[ocr_service.dart](https://github.com/ente/ente/blob/9ad0263664a79a8fc5e1c91db8a656e900b4a98d/mobile/apps/photos/lib/services/machine_learning/ocr_service.dart) выбирает legacy/Rust/Vision backend по платформе и флагу, предоставляет подготовку моделей, detect и cancel. [rust_ocr_backend.dart](https://github.com/ente/ente/blob/9ad0263664a79a8fc5e1c91db8a656e900b4a98d/mobile/apps/photos/lib/services/machine_learning/ocr/rust_ocr_backend.dart) действительно вызывает OCR engine и обрабатывает ошибки моделей. Полная фоновая OCR-индексация всей галереи и качество кириллицы здесь не проверялись.

[cluster_feedback.dart](https://github.com/ente/ente/blob/9ad0263664a79a8fc5e1c91db8a656e900b4a98d/mobile/apps/photos/lib/services/machine_learning/face_ml/feedback/cluster_feedback.dart) содержит предложения по группам людей, но просмотренный путь явно отключён в gallery mode. [similar_images_service.dart](https://github.com/ente/ente/blob/9ad0263664a79a8fc5e1c91db8a656e900b4a98d/mobile/apps/photos/lib/services/machine_learning/similar_images_service.dart) использует embeddings и фильтрует по uploadedFileID/владельцу: не приписываем этот путь автономной локальной галерее.

**Предлагаем взять:** разделение стадий ML, independent assets, версионирование индекса, управление остановкой/здоровьем устройства, подход к OCR и ручным исправлениям групп. E2EE sync — отдельная опциональная функция. Статус: обсуждение; отсутствие аккаунта в Lik не должно запрещать локальные альбомы и избранное.

<a id="immich"></a>
## Immich

AGPL-3.0. Flutter-клиент, TypeScript-сервер и Python ML-service. [README](https://github.com/immich-app/immich/blob/58fb1ed4a9d2dce5f3d1db479c91a863ae61a537/README.md) разделяет возможности Mobile/Web: backup, лента, EXIF/RAW, motion/live, sharing, карты, memories, offline/read-only; отдельные функции доступны только в Web. Автономная галерея телефона без сервера в этом обзоре не подтверждена.

[Поиск](https://github.com/immich-app/immich/blob/58fb1ed4a9d2dce5f3d1db479c91a863ae61a537/docs/docs/features/searching.md) объединяет серверные CLIP/metadata/OCR-фильтры и описывает multilingual-модели. Сравнительные CPU/RAM-данные получены на настольном Linux, не на Fold: используем их как список кандидатов, не прогноз телефона.

[base.py](https://github.com/immich-app/immich/blob/58fb1ed4a9d2dce5f3d1db479c91a863ae61a537/machine-learning/immich_ml/models/base.py) разделяет model identity, формат/runtime, download/load/predict. [cache.py](https://github.com/immich-app/immich/blob/58fb1ed4a9d2dce5f3d1db479c91a863ae61a537/machine-learning/immich_ml/models/cache.py) кэширует по имени/типу/задаче; [main.py](https://github.com/immich-app/immich/blob/58fb1ed4a9d2dce5f3d1db479c91a863ae61a537/machine-learning/immich_ml/main.py) содержит preload нескольких моделей и idle lifecycle. Это серверная реализация; TTL не заменяет бюджет Android RAM.

[recognition.py](https://github.com/immich-app/immich/blob/58fb1ed4a9d2dce5f3d1db479c91a863ae61a537/machine-learning/immich_ml/models/ocr/recognition.py) явно зависит от OCR detector, имеет языковой вариант recognizer, проверку SHA-256 при загрузке, batching и фильтрацию confidence. Берём разделение задач/зависимостей и типизированный результат OCR.

[smart-info.service.ts](https://github.com/immich-app/immich/blob/58fb1ed4a9d2dce5f3d1db479c91a863ae61a537/server/src/services/smart-info.service.ts) проверяет модель, меняет размерность индекса или удаляет embeddings при смене модели; запись результата отменяется, если модель сменилась во время работы. Автоматическое назначение полной переиндексации в этом месте остаётся TODO. Для Lik предлагаем новый index generation с согласованным переключением, чтобы не смешивать пространства даже одинаковой размерности.

[Разбор дубликатов](https://github.com/immich-app/immich/blob/58fb1ed4a9d2dce5f3d1db479c91a863ae61a537/docs/docs/features/duplicates-utility.md) предусматривает выбор сохраняемых изображений, корзину, stacking и перенос метаданных. Это хороший UX-референс для различения «похожие» и «точные копии».

**Предлагаем взять:** контракт опционального backend, поиск с фильтрами, зависимости ML-стадий, review дубликатов и защиту от устаревшего inference-результата. Сервер не становится обязательным для просмотра локальных фото. Статус: обсуждение.

<a id="rune"></a>
## Rune Keyboard

Собственный соседний проект пользователя: Kotlin/Views и отдельный llama.cpp runtime. Закреплён тот же commit, что в соседнем checkout. Корневой LICENSE отсутствует; лицензии вложенных компонентов и модельных материалов учитываются отдельно.

- [ModelTypes.kt](https://github.com/Mesteriis/rune.keyboard/blob/d7fb270a8e941bcac55f5d3a60053584bdf583e1/app/src/main/java/io/github/mesteriis/rune/keyboard/intelligence/model/ModelTypes.kt) разделяет descriptor, installed, candidate, operation и snapshot. Сейчас available/active/rollback — одиночные значения.
- [ModelManifestParser.kt](https://github.com/Mesteriis/rune.keyboard/blob/d7fb270a8e941bcac55f5d3a60053584bdf583e1/app/src/main/java/io/github/mesteriis/rune/keyboard/intelligence/model/ModelManifestParser.kt) строго проверяет поля, ID, размер, SHA-256, URL, GGUF и runtime contract. Реализация привязана к конкретной архитектуре qwen3/fileType, это не универсальный парсер весов.
- [CandidateInstaller.kt](https://github.com/Mesteriis/rune.keyboard/blob/d7fb270a8e941bcac55f5d3a60053584bdf583e1/app/src/main/java/io/github/mesteriis/rune/keyboard/intelligence/delivery/CandidateInstaller.kt) копирует во временный private-каталог, сверяет размер/hash, проверяет GGUF, синхронизирует файлы и атомарно публикует candidate.
- [ModelDeliveryManager.kt](https://github.com/Mesteriis/rune.keyboard/blob/d7fb270a8e941bcac55f5d3a60053584bdf583e1/app/src/main/java/io/github/mesteriis/rune/keyboard/intelligence/delivery/ModelDeliveryManager.kt) управляет DownloadManager, согласованием по журналу, retry/cancel, SAF import/export и удалением. [StorageRequirement.kt](https://github.com/Mesteriis/rune.keyboard/blob/d7fb270a8e941bcac55f5d3a60053584bdf583e1/app/src/main/java/io/github/mesteriis/rune/keyboard/intelligence/delivery/StorageRequirement.kt) учитывает временную копию и запас места для одной операции.
- [ModelActivationTransaction.kt](https://github.com/Mesteriis/rune.keyboard/blob/d7fb270a8e941bcac55f5d3a60053584bdf583e1/app/src/main/java/io/github/mesteriis/rune/keyboard/intelligence/runtime/ModelActivationTransaction.kt) переключает active/rollback, восстанавливает транзакцию и удаляет версии вне двух удерживаемых. [ModelOperationGate.kt](https://github.com/Mesteriis/rune.keyboard/blob/d7fb270a8e941bcac55f5d3a60053584bdf583e1/app/src/main/java/io/github/mesteriis/rune/keyboard/intelligence/storage/ModelOperationGate.kt) сериализует файловые изменения между процессами. Такое глобальное удаление нельзя переносить в каталог нескольких моделей.
- [LatestScoringWorker.kt](https://github.com/Mesteriis/rune.keyboard/blob/d7fb270a8e941bcac55f5d3a60053584bdf583e1/app/src/main/java/io/github/mesteriis/rune/keyboard/intelligence/inference/LatestScoringWorker.kt) содержит одну активную и одну заменяемую pending-задачу, отмену и idle unload. [ModelDutyOwner.kt](https://github.com/Mesteriis/rune.keyboard/blob/d7fb270a8e941bcac55f5d3a60053584bdf583e1/app/src/main/java/io/github/mesteriis/rune/keyboard/intelligence/inference/ModelDutyOwner.kt) учитывает CPU, lease и memory-pressure state; его численные лимиты — development profile.
- [LatestReplyGuard.kt](https://github.com/Mesteriis/rune.keyboard/blob/d7fb270a8e941bcac55f5d3a60053584bdf583e1/app/src/main/java/io/github/mesteriis/rune/keyboard/intelligence/client/LatestReplyGuard.kt) отбрасывает ответы другой сессии/ревизии. [Manifest](https://github.com/Mesteriis/rune.keyboard/blob/d7fb270a8e941bcac55f5d3a60053584bdf583e1/app/src/main/AndroidManifest.xml) разводит private worker/runtime-процессы.
- [LocalModelRuntime.kt](https://github.com/Mesteriis/rune.keyboard/blob/d7fb270a8e941bcac55f5d3a60053584bdf583e1/runtime-llama/src/main/java/io/github/mesteriis/rune/runtime/llama/LocalModelRuntime.kt) предоставляет load/selfTest/scoreCandidates/cancel/unload. Это не готовый runtime визуальных embeddings или генерации описаний.

**Принимаем как архитектурный ориентир по запросу пользователя:** проверяемая доставка, candidate/self-test/активация/откат, восстановление и изоляция inference. **Адаптация для Lik:** каталог многих моделей и согласованных наборов, per-operation staging, независимые версии, общий бюджет ресурсов, возобновляемая очередь индексации и index generation. Подробности — [MODEL_ARCHITECTURE.md](MODEL_ARCHITECTURE.md); runtime-код пока не написан.

## Границы текущего обзора

Проверены source paths, лицензии репозиториев и конкретные механизмы выше. Не проверены сборки каждого референса, все облачные адаптеры, практическая безопасность vault, лицензии всех весов/датасетов, точность ML и нагрузка на Fold. Неизвестное явно оставлено в матрице.

Присланная записка используется как список исходных кандидатов. Её рекомендация сделать форк ReFra и предпочтение Compose не являются выбранным направлением Lik. Решения о переносе функций фиксируем по ID D01–D15 после обсуждения.
