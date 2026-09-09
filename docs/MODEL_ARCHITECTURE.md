# Несколько моделей на телефоне: подход для Lik

Статус: **Task 10 реализован поверх immutable каталога Task 9**. Модели никогда не входят в APK: новая установка выбирает Balanced, но готового профиля не имеет до явной загрузки, size/SHA-проверки, self-test и подготовки включённых поколений.

## Что сохраняем из Rune и что меняем

Источники с закреплёнными commit: [Rune](REFERENCE_REVIEW.md#rune), [ReFra](REFERENCE_REVIEW.md#refra), [Ente](REFERENCE_REVIEW.md#ente), [Immich](REFERENCE_REVIEW.md#immich).

| Механизм | Rune сейчас | Lik: предлагаемая адаптация |
| --- | --- | --- |
| Каталог | Один available descriptor | Много ModelKey; роль, формат, артефакты и зависимости каждой модели |
| Установка | Один private staging, один журнал | Staging и журнал по operationId; независимый прогресс моделей |
| Проверка | size + SHA-256 + GGUF + self-test | Проверка каждого файла, контракт выбранного runtime, self-test всего набора функции |
| Активация | Глобальные active/rollback directory | Согласованная версия набора для каждой функции; предыдущий набор для отката |
| Хранение | Удерживаются active и rollback | Сохраняются все явно установленные модели; удаление только по явной политике/действию |
| Runtime | Один scorer, один runtime lease | Несколько типов runtime; единый планировщик RAM/CPU и leases на конкретные версии |
| Очередь | Последний интерактивный запрос заменяет предыдущий | Отдельно интерактивные запросы и долговечные задания индексации |
| Устаревший ответ | session/request/revision | Плюс sourceRevision, pipelineRevision и indexGeneration |
| Индекс фото | Отсутствует | Отдельные поколения text/image, face и OCR индексов |

Из ReFra берём группировку файлов по функции, из Ente — разделение ML-стадий и контроль состояния устройства, из Immich — явные зависимости задач и проверку версии при записи результата. Одиночный active-pointer и глобальную очистку Rune нельзя копировать: они удалили бы другие установленные модели.

## Пользовательская модель

Настройки показывают функции: **Поиск по описанию**, **Люди**, **Текст на фото** и следующие выбранные возможности. В Task 10 доступен поиск; «Люди» и «Текст на фото» видимы, но отключены с явной пометкой Task 11, чтобы неподготовленный product/index path не оставлял профиль в ложном состоянии `PREPARING`. В деталях профиля видны модели, версия, место на диске, загрузка/ошибка, прогресс индексации и доступные действия. Устанавливаются только три проверенных preset-набора из закреплённых Hugging Face URLs. Произвольные URL, импорт файлов моделей и пользовательские профили не поддерживаются.

Например, на телефоне одновременно установлены:

- семантический поиск: image encoder, совместимый text encoder и tokenizer;
- люди: face detector и face embedder;
- OCR: detector, recognizer нужного языка и словарь.

Точные выбранные модели и их версии перечислены ниже и в каталоге Task 9. Одну модель можно совместно использовать в нескольких функциях. Две версии одной модели могут оставаться на диске для сравнения или отката. Установленная модель не обязана быть загружена в RAM.

При отключении «Людей» семантический поиск продолжает работать. При обновлении OCR пересчитывается только соответствующий индекс. Обновление text/image поиска переключает совместимую пару, а не один encoder независимо от второго.

## Планируемые встроенные profiles

Profiles — immutable versioned sets для semantic search, OCR и people; произвольные пользовательские profiles не поддерживаются. Balanced — profile по умолчанию. Реальные URL/размеры/hash/лицензии и conversion parity закреплены в Task 9. Выбор по умолчанию не означает downloaded/ready; качество и Fold-производительность ещё не приняты.

| Profile | Semantic search | OCR | People |
| --- | --- | --- | --- |
| Compact | CLIP ViT-B/32 plus aligned multilingual text | PP-OCRv5 mobile detector/Cyrillic recognizer | YuNet/SFace |
| Balanced (default) | SigLIP 2 Base 224 | PP-OCRv5 mobile detector/Cyrillic recognizer | YuNet/SFace |
| Extended | SigLIP 2 Large 256 | PP-OCRv5 server detector/Cyrillic recognizer | YuNet/SFace |

Task 9 pins publisher and converter revisions, immutable Hugging Face runtime URLs, filenames, sizes/SHA-256, licenses and tensor/preprocessing contracts. All APKs contain manifests and license notices only. Distribution verification rejects weights/tokenizers, even when the external development cache is complete. Task 10 downloads files explicitly from Settings through one global process/file-lock coordinator using shared digest staging, durable resumable Range journals, physically preallocated shared `.part` files plus a separate fixed safety-margin reservation, foreground data-sync execution, network/retry/pause/cancel controls, whole-file size/SHA checks, corruption quarantine, real component self-tests and atomic publication. Before space admission it precreates and fsyncs all directories and transfer entries and physically allocates fixed two-slot catalog/journal/receipt/control/ledger files. Space preflight rounds the remaining physical growth of every file and the margin independently to `max(512, f_bsize, f_frsize)`; accepted progress and publication overwrite allocated blocks. This closes multi-file partial-block undercount while the ledger preserves digest ownership through pause/crash. A trusted verification receipt binds digest/size to device, inode, mtime and ctime, so normal per-photo inference does not rehash multi-gigabyte files; changed files are rehashed and quarantined. Shared files and in-progress ownership are deduplicated by digest; cleanup respects every installed profile, operation and live runtime lease. The previous active profile remains until the new artifacts and enabled index generations are complete.

## Предлагаемые сущности

Названия ниже описывают контракты; пустые классы в приложение не добавляются.

| Сущность | Ответственность |
| --- | --- |
| ModelKey | modelId + version + digest манифеста. Один ID/номер версии с другими байтами не считается прежним артефактом |
| ModelManifest | schemaVersion, роль, format/runtime contract, список файлов с size/hash и закреплённым источником, input/output schema, preprocessing/tokenizer revision, зависимости, происхождение/лицензия |
| ModelCatalog | Установленные версии, проверенные manifests, текущее поколение каталога и выбранные наборы функций |
| PipelineRevision | Неизменяемый набор точных ModelKey и параметров обработки, например согласованные text/image encoders + tokenizer |
| ModelOperation | operationId, целевые ModelKey, стадия, download ID, резерв места, прогресс, stable error code; восстановление после перезапуска |
| RuntimeLease | Временное владение конкретными версиями и резерв ресурсов для задачи; освобождение в finally |
| IndexGeneration | Отдельное пространство результатов с fingerprint pipeline, размерностью/метрикой и прогрессом заполнения |
| IndexTask | media identity, content/source revision, стадия, pipeline/index generation, attempt и checkpoint |

Выбранные наборы хранятся в одном согласованном snapshot каталога. Это источник истины для активации. UI не дублирует выбранную модель в отдельной preference. Состояние runtime («загружена в память») эфемерно и не восстанавливается из persisted boolean после перезапуска процесса.

Манифест расширяет строгую проверку Rune: неизвестная схема, неподдерживаемый backend, неверная форма входа/выхода, циклические/отсутствующие зависимости и небезопасный путь отклоняются. SHA-256 сверяется с доверенным каталогом приложения; hash из произвольного скачанного рядом файла сам по себе не устанавливает доверие. Первую версию каталога можно поставлять в APK, не вводя отдельный сервер каталога.

## Доставка и активация

```mermaid
flowchart LR
    C[Проверенный каталог] --> D[Settings download из immutable HF URLs]
    D --> S[Staging операции]
    S --> V[Размеры и SHA-256 всех файлов]
    V --> T[Проверка runtime и self-test набора]
    T --> I[Установленные версии]
    I --> G[Подготовка поколения индекса]
    G --> P[Атомарный выбор набора и индекса]
    P --> R[Предыдущий набор для отката]
```

1. Пользователь выбирает функцию/обновление. Resolver фиксирует весь набор зависимостей до загрузки. Загрузка и индексирование не запускаются просто из-за открытия галереи.
2. Операция резервирует место для отсутствующих файлов, временных копий, предыдущих версий и нового индекса. Две установки не могут обе израсходовать один и тот же свободный объём. Формулу Rune для одной модели заменяем расчётом набора по файловым томам с проверкой переполнения.
3. Каждый артефакт попадает в staging конкретной операции. Проверяются длина, digest и формат. Import через SAF проходит те же проверки; export относится к выбранной версии.
4. Набор проходит self-test с объявленными именами/формами, реальными preprocessing/tokenizer путями, представительными ненулевыми входами и зафиксированными tolerance/semantic-output контрактами для поиска, OCR, YuNet, SFace и sensitive classifier. GGUF-проверка Rune остаётся только у соответствующего адаптера, ONNX требует своего контракта.
5. Проверенные версии публикуются в private storage атомарно. Сбой не изменяет выбранный рабочий набор. Журнал позволяет завершить публикацию повторно или убрать только staging этой операции.
6. Для функции без индекса можно переключить набор после self-test. Для поиска/лиц сначала готовится новое поколение индекса. Доступный старый набор продолжает обслуживать свой старый индекс; затем одним commit каталога выбираются согласованные pipeline + indexGeneration.
7. Старый набор и индекс удерживаются на период отката в рамках явной политики хранения. При нехватке места интерфейс предлагает освободить место либо явно перестроить индекс с временной недоступностью поиска; тихое удаление других моделей недопустимо.

Перенос каталога файлов и запись метаданных БД не составляют одну файловую транзакцию. Порядок такой: durable artifact → durable index generation → атомарный catalog pointer. Неиспользуемые остатки после сбоя безопасны для последующей уборки; pointer на недописанный набор недопустим. Перед уборкой учитываются выбранные/предыдущие наборы, явные установки, незавершённые операции и живые leases.

## Выполнение и ресурсы

Начальный режим — **одна тяжёлая inference-задача за раз** при любом числе установленных моделей. Это ограничение параллелизма, а не числа моделей. Последовательность detector → embedder не требует постоянно держать весь набор в RAM. Возможность совместной загрузки маленьких моделей добавляется после измерений.

Общий scheduler считает модельные sessions, временные tensors, декодированные bitmap и резерв UI. Размер файла весов не равен RAM. Первоначальные оценки памяти проверяются измерением cold/warm запусков на Fold; TTL и LRU сами по себе не ограничивают пик памяти.

- Интерактивный поиск имеет приоритет; новый запрос заменяет предыдущий только внутри той же UI-сессии/функции. Результат проверяется по идентификаторам и поколению до показа.
- Индексация обрабатывает ограниченные порции и сохраняет checkpoint/статус каждого элемента. Новый поисковый запрос не удаляет очередь индексации. Повтор после сбоя должен быть идемпотентным.
- Длительные этапы имеют точки отмены. Индексация уступает ресурсы интерактивной работе на безопасной границе и получает возможность продолжиться после освобождения бюджета.
- При нехватке памяти или перегреве новые фоновые задачи не допускаются, свободные sessions выгружаются. Батарея/thermal state учитываются по идее Ente; пороги Rune и Ente не копируются как измеренные лимиты Lik.
- Инференс работает вне UI-процесса, как в Rune. Число private worker/runtime-процессов определим по выбранным backend; процесс на каждую модель не требуется. Для нескольких процессов бюджет должен координироваться общим владельцем.
- Нативный сбой завершает задачу с ограниченным retry/backoff, а не вызывает бесконечную перезагрузку модели. Галерея остаётся доступной.

Leases фиксируют точную версию. Пока задача использует модель, её файлы и session не удаляются. Операция удаления сначала исключает модель из новых назначений, отменяет/дожидается связанных задач, затем удаляет только ставшие ненужными версии. Если модель общая для двух функций, отключение одной функции не разрешает удалить её из-под второй.

## Совместимость индексов

Fingerprint поиска включает **веса обоих encoders, tokenizer, preprocessing, output contract, нормализацию и метрику**. Совпадение размерности векторов недостаточно: векторы двух моделей размерности 512 могут принадлежать разным пространствам.

В каждой записи нужны source ID + media ID + content revision, монотонная `accessGrantEpoch` и indexGeneration. `lastSeenAt` остаётся временем сканирования и не инвалидирует embedding при no-op MediaStore scan; неизменившаяся доступная импортированная копия также сохраняет epoch. Эпоха доступа меняется только при переходе из недоступного состояния обратно в доступное. Перед commit результата повторно проверяются доступ к источнику, актуальность контента, grant epoch и generation. Смена модели или файла во время inference не должна записывать устаревший результат в новый индекс.

Публикация поиска требует точного равенства доступного набора, актуальных строк embedding и общего числа сохранённых строк. Нативный USearch содержит membership manifest с count/digest и проверяемыми probe/top-k значениями. Построение читает Room порциями и держит только ограниченные probes/top-k, поэтому не создаёт несколько полных копий 100k векторов в памяти. Production search загружает опубликованный USearch, получает не более 512 ключей и exact-rerank выполняет только над этими кандидатами; ограниченный Room fallback не выдаётся за полную approximate выдачу.

| Изменение | Какие данные пересчитываются |
| --- | --- |
| Text/image encoder, tokenizer или preprocessing поиска | Новое поколение поиска; запросы используют только согласованную пару |
| Face detector, crop/alignment или face embedder | Затронутые face results и группы; сохранённые ручные имена/исправления мигрируют отдельно |
| OCR recognizer, язык/словарь или detector | OCR-индекс соответствующего pipeline |
| Файл изменён или удалён / доступ отозван | Задания и результаты конкретного media/source revision |
| Только выбранная иконка или тема | Ничего в ML-индексе |

Для «Людей» имена и пользовательские исправления не должны исчезать вместе с вычисляемыми embeddings. Переиндексация и миграция ручных связей — разные операции; при неуверенном сопоставлении требуется пользовательское исправление.

## Проверка первой реализации

1. Два независимых набора устанавливаются и сохраняются после перезапуска; удаление/ошибка одного не меняет второй.
2. Конкурентные установки координируют общий digest staging и физический резерв места; общий artifact не скачивается дважды.
3. Ошибка hash/size/runtime/self-test и авария в каждой фазе активации сохраняют предыдущий согласованный набор.
4. Два encoders одинаковой размерности с разными fingerprint не смешивают индексы; устаревший ответ отклоняется.
5. Отмена, process death, отзыв доступа и изменение файла возобновляют только нужную работу без дублей.
6. Удаление модели с live lease откладывается; отключение функции не удаляет зависимость другой функции.
7. На Fold измеряются cold/warm latency, память UI + runtime, температура и батарея при индексации одновременно с просмотром.

Catalog v3 contains mandatory non-model activation samples for every one of the 12 ONNX graphs. Each sample set is covered by the catalog trust anchor and bound to its full external smoke-reference SHA-256, output name/size, tolerance and output-norm ratio. OCR detector and recognizer inputs are informative deterministic byte/signed ramps, and scale-aware sample, norm and range checks reject a constant output. Product activation runs the declared inputs in the isolated runtime and must match these samples; missing metadata, missing/corrupt weights or a deterministic wrong output fails self-test. Full `.f32` references remain outside Git/APK and are used for complete-output QA comparison. Weights and tokenizers still arrive only through the explicit Settings download. `ModelCatalog` persists the verified oracle revision per profile; old installed/active state cannot serve until the current oracle succeeds. User selection/features and explicitly compatible v1/v2 generations survive the update and reactivate atomically after validation.

Task 10 реализует этот сценарий для всех трёх профилей: resumable HF staging, fsync/atomic publish, единый `ModelCatalog`, persistent-bound isolated ORT CPU runtime с binder-death/rebind leases, generation/checkpoint в Room, exact cosine oracle и локально собранный USearch 2.26.0 через JNI. Production search берёт ограниченный список кандидатов из сохранённого native generation и exact-rerank только этих строк; bounded exact fallback не выдаёт себя за полный approximate индекс. Periodic, one-shot и manual workers сериализуются по pipeline/generation, а публикация перепроверяет отмену, доступ и ревизию. Durable retirement journal удаляет указатели каталога до Room/files и завершает cleanup после process death. Интерактивный поиск получает приоритет на границе тяжёлой задачи; фоновые работы требуют opt-in функции и системных ограничений WorkManager.

Снимок `ModelCatalog` имеет фиксированный двухслотовый размер. Он всегда сохраняет active/pending поколения и по одному последнему complete и preparing поколению для каждого допустимого current/compatible fingerprint; остальные поколения удаляются из снимка до записи, затем их Room/native данные выводятся из эксплуатации. Поиск удерживает read lease поколения на всём пути Room/native use, а cleanup получает exclusive lease, повторно проверяет указатели каталога и выполняется после завершения читателей. Superseded IDs записываются в durable journal до atomic catalog switch; startup recovery идемпотентно завершает Room/native удаление после сбоя на любом шаге. Совместимый индекс установленного профиля после миграции v1/v2→v3 активируется без повторной индексации. Repair повреждённого artifact очищает уже выделенный receipt slot на месте и не создаёт новый metadata-файл после проверки свободного места.

## Task 9: зафиксированные артефакты и границы проверки

[Каталог v1](../models/catalog-v1.json) фиксирует три набора, реальные HF ONNX URLs/hash/размеры и общие зависимости. [Provenance](MODEL_PROVENANCE.md) и [воспроизводимая подготовка](../models/README.md) описывают внешнее хранилище доказательств и запрет model payloads в APK. Task 10 читает этот каталог как единственный источник URL и pipeline fingerprint; CPU остаётся эталоном, а optional Samsung NNAPI/NPU регулируется [backend policy](SAMSUNG_BACKENDS.md) и выключен.

Все профили дополнительно используют один shared `sensitive-v1` (Marqo ViT-Tiny), без дублирования байтов и без фиктивного порога. Калибровка на реальных данных не выполнена. [Task 13](SENSITIVE_MEDIA.md) задаёт quarantine новых/изменённых фото, отдельные ручные overrides и временный BIOMETRIC_STRONG reveal; подготовка классификатора не является реализацией скрытия. [AiGate](AIGATE_INTEGRATION.md) — отдельный opt-in контракт Task 10, который не заменяет offline модели.
