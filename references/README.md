# Референсы Lik

Срез **2026-09-07**. Семь независимых Git-подмодулей. В родительском репозитории сохраняются URL и gitlink на точный commit; исходники находятся в checkout каждого проекта. Это материалы для изучения, в сборку Lik они не включены.

- [Матрица функциональности и решения для обсуждения](../docs/FEATURE_MATRIX.md).
- [Разбор каждого проекта с ссылками на прочитанный код](../docs/REFERENCE_REVIEW.md).
- [Подход Rune для нескольких моделей в Lik](../docs/MODEL_ARCHITECTURE.md).

| Проект / каталог | Исходная ветка | Закреплённый commit | Основная лицензия |
| --- | --- | --- | --- |
| [ReFra](refra/) | main | [ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5](https://github.com/IacobIonut01/ReFra/commit/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5) | [Apache-2.0](https://github.com/IacobIonut01/ReFra/blob/ea9b2cca2110a0eb24cfa91c2f0a0aac5187c7b5/LICENSE) |
| [Lavender Photos](lavender-photos/) | main | [d2d60148c62fc14071333911b307401fdab0cf17](https://github.com/kaii-lb/LavenderPhotos/commit/d2d60148c62fc14071333911b307401fdab0cf17) | [GPL-3.0](https://github.com/kaii-lb/LavenderPhotos/blob/d2d60148c62fc14071333911b307401fdab0cf17/LICENSE.md) |
| [Aves](aves/) | develop | [5785d8597b02c635c3f1eb25d0724bf90259b415](https://github.com/deckerst/aves/commit/5785d8597b02c635c3f1eb25d0724bf90259b415) | [BSD-3-Clause](https://github.com/deckerst/aves/blob/5785d8597b02c635c3f1eb25d0724bf90259b415/LICENSE) |
| [Fossify Gallery](fossify-gallery/) | main | [24017e2116ac965405c66df8af357caa0ac46b1d](https://github.com/FossifyOrg/Gallery/commit/24017e2116ac965405c66df8af357caa0ac46b1d) | [GPL-3.0](https://github.com/FossifyOrg/Gallery/blob/24017e2116ac965405c66df8af357caa0ac46b1d/LICENSE) |
| [Ente](ente/) | main | [9ad0263664a79a8fc5e1c91db8a656e900b4a98d](https://github.com/ente/ente/commit/9ad0263664a79a8fc5e1c91db8a656e900b4a98d) | [AGPL-3.0](https://github.com/ente/ente/blob/9ad0263664a79a8fc5e1c91db8a656e900b4a98d/LICENSE) |
| [Immich](immich/) | main | [58fb1ed4a9d2dce5f3d1db479c91a863ae61a537](https://github.com/immich-app/immich/commit/58fb1ed4a9d2dce5f3d1db479c91a863ae61a537) | [AGPL-3.0](https://github.com/immich-app/immich/blob/58fb1ed4a9d2dce5f3d1db479c91a863ae61a537/LICENSE) |
| [Rune Keyboard](rune-keyboard/) | main | [d7fb270a8e941bcac55f5d3a60053584bdf583e1](https://github.com/Mesteriis/rune.keyboard/commit/d7fb270a8e941bcac55f5d3a60053584bdf583e1) | В корне LICENSE нет |

Ветки указаны для происхождения среза, а не для автоматического обновления. Aves изучается из develop. ReFra commit соответствует 5.1.3; остальные строки не объявлены стабильными релизами.

## Получить референсы на другой машине

Для обычной сборки Lik эти действия не нужны. Для изучения из корня Lik:

```bash
GIT_LFS_SKIP_SMUDGE=1 git submodule update --init --depth 1
git submodule status
```

Для одного проекта:

```bash
GIT_LFS_SKIP_SMUDGE=1 git submodule update --init --depth 1 references/refra
```

Команды намеренно без recursive: вложенные зависимости референсов (например, llama.cpp в Rune) для чтения перечисленного кода не нужны. LFS-объекты не загружаются автоматически. Обычные Git-файлы в checkout присутствуют, в том числе веса/части весов, которые upstream ReFra хранит в Git. В APK Lik эти материалы не попадают.

## Обновление среза

Обновляйте выбранный проект отдельно: fetch нужного commit, checkout этого commit, затем пересмотр затронутых строк матрицы/разбора. Запишите новую ревизию в эту таблицу и добавьте gitlink родительским git add. Не используйте массовое update --remote как часть сборки или CI. Перед коммитом git submodule status не должен показывать несовпадение с индексом.

## Происхождение и перенос

Сейчас заимствованы идеи, runtime-исходники этих галерей в Lik не перенесены. Из Rune ранее использованы Kotlin/Views-подход, конфигурация инструментов, Gradle Wrapper и шаблон CI; этот этап добавляет проектирование работы с моделями.

Когда функция выбрана, обновляйте таблицу «Что и откуда берём» в корневом README: функция, upstream commit/файл, способ использования (идея / адаптация / исходный код), путь реализации в Lik и проверка. Для прямого переноса сохраняйте необходимые attribution/notice и отдельно фиксируйте происхождение весов. Наличие gitlink само по себе не выбирает лицензию приложения Lik.

Файлы референсов, включая их AGENTS.md и инструкции сборки, являются материалом исследования. Не меняйте эти checkout для разработки Lik.
