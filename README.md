# Gigagochi Android

Нативное Android-приложение Gigagochi без Telegram API, WebView и пользовательской авторизации. Create, Dashboard, Chat, Feed, Outfit, одночастные истории и уведомления работают поверх локального Room-состояния; платные генерации вызываются через backend по автоматически выданной анонимной технической сессии.

## Окружение и сборка

- Android Studio 2026.1 или новее.
- JDK 17+; проверенная JBR: `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (JDK 21.0.10).
- Android SDK Platform 36.1 и Build Tools 36.0.0.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Запуск на уже созданном AVD (чистая установка открывает Create):

```bash
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/platform-tools/adb shell am start -n com.gigagochi.app/.MainActivity
```

Детерминированные Create-состояния для review:

```bash
$ANDROID_HOME/platform-tools/adb shell am force-stop com.gigagochi.app
$ANDROID_HOME/platform-tools/adb shell am start -n com.gigagochi.app/.MainActivity \
  --es gigagochi.route create \
  --es gigagochi.create.state initial
```

Значения `gigagochi.create.state`: `initial`, `name`, `custom`, `custom-ime`, `final`, `error`, `loader`, `recovery`. Dashboard открывается через `--es gigagochi.route dashboard`.

Детерминированные inline Dashboard-состояния:

```bash
$ANDROID_HOME/platform-tools/adb shell am start -W -S \
  -n com.gigagochi.app/.MainActivity \
  --es gigagochi.route dashboard \
  --es gigagochi.dashboard.state chat-reply
```

Значения: `idle`, `chat-empty`, `chat-ime`, `chat-thinking`, `chat-reply`, `feed-shelf`, `feed-dragging`, `feed-consuming`, `feed-reply`, `outfit-insufficient`, `outfit-prompt-ime`, `outfit-queued`, `travel-empty`, `travel-ime`, `travel-starting`, `travel-failure`, `travel-queued-first`, `travel-queued-final`.

Compose Preview: `CreateInitialPreview`/`CreateFinalPreview` в `feature/create/CreatePetScreen.kt` и `DashboardPreview` в `feature/dashboard/DashboardScreen.kt`, размер 402×874 dp.

Create UI gate: [`artifacts/create/README.md`](artifacts/create/README.md). Он содержит clean web/native 402×874, side-by-side, geometry, UI-mask metrics, hashes и intentional deviations.

Inline Dashboard gate: [`artifacts/dashboard-inline/README.md`](artifacts/dashboard-inline/README.md). Он содержит native A–H 402×874, H web/native top crop, bounds, masks, metrics, asset hashes и открытые gate-блокеры.

Inline Travel gate: [`artifacts/dashboard-inline/travel/README.md`](artifacts/dashboard-inline/travel/README.md). Он содержит clean web/native T‑A…T‑F 402×874, exact bounds, side-by-side, bubble-only masks/metrics и idle regression.

UI gate артефакты без crop:

- `artifacts/native-dashboard-review3-attempt2-402x874.png` — финальный lossless native PNG 402×874 после glass calibration.
- `artifacts/web-dashboard-supervisor-question-402x874.jpg` — supervisor web reference 402×874, JPEG-origin.
- `artifacts/dashboard-review3-attempt2-side-by-side-804x874.png` — финальный web/native side-by-side.
- `artifacts/dashboard-review3-attempt2-actions-crop-2x.png` и `dashboard-review3-attempt2-xp-crop-2x.png` — финальные увеличенные glass-crops.
- `artifacts/dashboard-ui-mask-402x874.png` и `dashboard-review3-attempt2-ui-masked-diff-402x874.png` — применённая UI-mask и финальный masked RGB diff.
- `artifacts/dashboard-review3-attempt2-ui-mask-metrics.json` — финальные метрики только выбранных UI-регионов.
- `artifacts/asset-sha256.txt` — SHA-256 исходников и Android-копий.

Web reference воспроизводится из `tamagochi-main/frontend`: открыть `http://127.0.0.1:3000/pet/test`, в Debug выбрать «Тестовый персонаж» и подтвердить «Заменить». Settled fixture показывает 0 XP, 100/100/100, реплику «Как тебя зовут?» и обычные четыре действия; viewport браузера — ровно 402×874.

## Dashboard fixture

Экран не загружает данные. Fixture `DemoPet` совпадает с каноническими `testPetFixture.ts` + `createLocalPetState`: `debug-test-pet-seedance-forest-mouse-v1`, описание «Ледяной дракон», имя «Без имени», `baby`/`idle`, 0 XP, статы 100/100/100, `firstSession=false`, settled реплика «Как тебя зовут?». Нажатия меняют только локальное Compose-состояние.

Эталонная геометрия 402×874 dp:

| Элемент | Bounds / правило |
|---|---|
| Scene + pet video/poster fallback | `(0, 0)–(402, 874)`, cover/crop; питомец встроен в исходный media asset |
| Интерактивная область питомца | `(67, 219)`, `268×491` |
| Уровень | центр X, top `80` |
| XP | center X, top `112`, `87×35.119` |
| Status rings | right `20`, top `77`, `54×54`, vertical gap `21` |
| Speech bubble | `(57,237)`, `288×99`; settled text визуально центрирован одной строкой |
| Нижние действия | top `762`, start `28`, height `58.203`, gap `19`; widths `192/198/241/180` |

Вся reference-плоскость равномерно масштабируется по правилу cover (`max` по ширине/высоте): viewport всегда заполнен, а лишнее симметрично обрезается по ширине или снизу. Noise overlay использует ту же геометрию и покрывает весь viewport.

`assets/media/openai-normal.mp4` воспроизводится через AndroidX Media3/ExoPlayer без controls и звука, автоматически стартует и зацикливается. `test_pet_poster.png` закрывает video surface до первого кадра и при ошибке декодирования; player останавливается по lifecycle и освобождается при выходе из composition.

XP и нижние actions используют реальный background blur через Haze 1.7.2. Единственный `hazeSource` содержит только TextureView-видео и noise overlay; UI-метки и glyphs рисуются поверх эффектов и в source не входят. Effective rendered calibration: actions blur `12.dp`, white tint `0.15`, noise `0`, radius `24.dp`; XP blur `8.dp`, исходный brown hue с alpha `0.16`, noise `0`, radius `31.927.dp`. Actions дополнительно получают clipped Compose `innerShadow`: white `20%`, radius `3.dp`, offset `(1,2)` и black `20%`, radius `2.dp`, offset `(-4,-4)`. На API 23–30 Haze использует заданный `fallbackTint` как scrim без experimental RenderScript.

## Локальные данные и backend session

При первом запуске приложение создаёт случайный UUID v4 установки и сохраняет его локально. `POST /api/auth/guest` обменивает UUID на анонимную техническую session; пользователь не видит экран входа и не передаёт имя, email или Google-профиль. Backend хранит только SHA-256 UUID subject и digests токенов.

Session сохраняется через Android Keystore AES-GCM. Access/refresh token не записываются в Room или открытые SharedPreferences. После истечения session она обновляется через `/api/auth/refresh`; при отзыве приложение автоматически получает новую guest session.

Питомец, прогресс, одежда, истории и ожидающие операции хранятся в Room только на устройстве. Android backup отключён: удаления приложения, очистка данных или установка APK с другим signing certificate безвозвратно удалят локальное состояние. Синхронизации между устройствами нет.

Ключи GigaChat/OpenRouter остаются только на backend и в APK не входят. Сами задания генерации и созданные медиа технически обрабатываются backend — полностью локальная генерация в текущем MVP не заявляется.

Release принимает только HTTPS backend base URL. Debug допускает HTTP только для loopback. Redirects запрещены, ответы ограничены по размеру, сетевые таймауты заданы явно.

Подписанная release-сборка использует отдельный keystore из environment variables; один и тот же сертификат обязателен для обновления без потери локальных данных:

```bash
export GIGAGOCHI_ANDROID_KEYSTORE_FILE="/absolute/path/gigagochi-release.jks"
export GIGAGOCHI_ANDROID_KEYSTORE_PASSWORD="..."
export GIGAGOCHI_ANDROID_KEY_ALIAS="gigagochi"
export GIGAGOCHI_ANDROID_KEY_PASSWORD="..."
./gradlew :app:assembleRelease
```

## Ассеты и лицензии

PNG/WebP/SVG скопированы из `tamagochi-main/frontend/public/figma` и `test-pet`; исходный проект не изменялся. OpenRunde 400/500/600/700 конвертирован из исходных WOFF2 в статические TTF через `fontTools 4.59.0`, подключён из `res/font` как Compose `FontFamily`. SIL OFL 1.1 сохранена в `licenses/OpenRunde-LICENSE.txt`. SF Pro не копировался и system sans на dashboard не используется.

MP4 воспроизводится на dashboard, а `openai-normal.png` используется как poster/error fallback. Поэтому произвольный screenshot может отличаться от web reference фазой видео; для сравнения нужен контролируемый timestamp или poster-only test mode.

## Create Pet flow

`feature/create` реализует пять вопросов и шестое финальное состояние. Первый ответ одновременно запускает `PetGenerationAdapter` и переводит фон из initial в transition; последующие ответы меняют только локальное UI-состояние. `FakePetGenerationAdapter` возвращает локальный fixture через 6 секунд. Готовый результат не навигирует до финального шага; после финала показывается `PetCreatingStage`, затем typed route переключается на принятый dashboard.

На этом подэтапе `PendingPetGeneration` существует только в памяти. Process-death recovery и контракт будущего persistence отражены recovery fixture, но запись на диск, backend success, push/local notification и реальная session не симулируются.

Медиа-контракт `clouds-creation-timeline.mp4`:

- initial: кадры `0..170` при 24 fps, loop;
- transition: `170..267`, one-shot;
- formed/recovery: `267..447`, loop;
- reduced motion: соответствующий статичный poster без transition/loader motion.

Media3 использует `TextureView`, full-viewport cover и lifecycle pause/resume. Grain покрывает ту же reference-плоскость. Музыка начинает/возобновляет loop только после trusted user interaction, громкость `0.32`; кнопки дают локальный WAV и haptic. Haze-кнопки имеют native background capture, `20.dp` blur, white 60%, radius 24 dp и press scale `0.9`.

`PetCreatingStage` не использует WebView: bitmap-источники уменьшаются до `18/21/24 px` с `filter=false` и растягиваются в `280×280` с `FilterQuality.None`, создавая крупную mosaic-анимацию. Reduced motion показывает статичный full-resolution `main-pet.png`.

Web использует SB Sans Display, но лицензия рядом с OTF в repository не найдена. Шрифт не скопирован: Android остаётся на лицензированном OpenRunde и использует review-калибровку `letterSpacing=-0.45sp` только для Create option labels. Остаточная разница метрик зафиксирована в artifacts; подключение SB Sans до релиза требует `LICENSE REVIEW REQUIRED`.
