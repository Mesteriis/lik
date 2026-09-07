# Лик · Lik

Android-галерея с искусственным интеллектом. Сейчас это стартовый каркас со стартовым экраном и настройкой иконки приложения. Работа с фотографиями и ИИ ещё не реализована.

Основа следует соседнему проекту Rune Keyboard: Kotlin, системные Android Views, минимум зависимостей и строгий Android Lint.

## Быстрый старт

Требуются JDK 17, Android SDK Platform 37 и Build Tools 36.0.0. Gradle 9.4.1 загружается через включённый в репозиторий Wrapper; отдельно устанавливать Gradle не нужно. AGP — 9.2.1 со встроенной поддержкой Kotlin.

```bash
git clone git@github.com:Mesteriis/lik.git
cd lik
```

Откройте корень проекта в Android Studio с поддержкой AGP 9.2 и дождитесь Gradle Sync. Для Gradle JDK выберите JDK 17, затем запустите конфигурацию `app` на устройстве с Android 17 (API 37). Старые версии Android не поддерживаются: `minSdk`, `targetSdk` и `compileSdk` равны 37.

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

`lint` считает предупреждения ошибками. GitHub Actions собирает debug/release APK, проверяет lint и запускает instrumentation-тесты на эмуляторе Android 17.

Для проверки запуска, пересоздания Activity и переключения всех десяти иконок выберите **эмулятор API 37** из `adb devices`:

```bash
ANDROID_SERIAL=<emulator-serial> ./gradlew connectedDebugAndroidTest
```

Без `ANDROID_SERIAL` Gradle может запустить тесты на всех подключённых устройствах. `testDebugUnitTest` подготовлен для будущей чистой логики; JVM-тестов в текущем каркасе нет, выбор иконки проверяется через настоящие Android Activity и PackageManager.

## Структура

```text
app/
  src/main/
    java/io/github/mesteriis/lik/ui/        # стартовый экран и системные отступы
    java/io/github/mesteriis/lik/settings/  # выбор иконки и PackageManager
    res/layout/             # стартовый экран
    res/values*/            # EN/RU, светлая и тёмная темы
    res/drawable-nodpi/      # исходная иконка пользователя
    res/mipmap-anydpi/       # adaptive launcher icon
  src/androidTest/          # запуск, lifecycle и переключение launcher aliases
gradle/                     # Wrapper и каталог версий
.github/workflows/ci.yml    # сборка и lint
docs/ARCHITECTURE.md         # границы каркаса и точки расширения
```

Идентификатор приложения: `io.github.mesteriis.lik`; версия: `0.1.0`. На русском устройстве название — «Лик», на остальных — «Lik». Тема следует системе.

## Иконка

Откройте **Настройки → Иконка приложения** и выберите один из десяти вариантов: Классическая, Изумруд, Аметист, Сапфир, Рассвет, Лунный камень, Роза, Лёд, Северное сияние или Янтарь. Первая присланная в наборе иконка выбрана по умолчанию. Выбор сразу меняет launcher alias и изображение на стартовом экране; Android сохраняет его между запусками. Лаунчер может обновить свой кэш с задержкой.

`app/src/main/res/drawable-nodpi/lik_emblem*.png` — десять предоставленных изображений, без изменений. PNG используются как foreground поверх золотистого фона, а их alpha-канал — для системных monochrome-иконок. XML-обёртки сохраняют пропорции и безопасные отступы; форму маски выбирает лаунчер. Картинки также сохраняются в исходном разрешении.

## Release

Release использует R8 и удаление неиспользуемых ресурсов. Без ключа сборка создаёт `app-release-unsigned.apk`, который нельзя установить до подписания.

Для подписи скопируйте `keystore.properties.example` в `keystore.properties`, укажите собственный keystore и выполните `./gradlew assembleRelease`. Ключи, пароли и пути SDK не хранятся в Git.
