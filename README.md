# Gigagochi Android

Нативный Android-каркас миграции Gigagochi. Принятые Dashboard/Create сохранены; inline Chat, Feed, Outfit и Travel работают поверх стабильной dashboard scene на локальных fake adapters. Стартовый route — Google Credential Manager с encrypted session bootstrap. Additive backend endpoints реализованы, но без реального Web OAuth client ID/deployment production session получить нельзя. Telegram API и WebView не используются.

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

Запуск на уже созданном AVD (по умолчанию открывается Auth):

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

Детерминированные Auth-состояния не открывают системный account picker и не симулируют успешную session:

```bash
$ANDROID_HOME/platform-tools/adb shell am start -W -S \
  -n com.gigagochi.app/.MainActivity \
  --es gigagochi.route auth \
  --es gigagochi.auth.state ready
```

Значения `gigagochi.auth.state`: `missing-config`, `ready`, `credential-pending`, `exchange-pending`, `error`. Контракт Stage 5A: [`artifacts/auth/README.md`](artifacts/auth/README.md).

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

## Google Sign-In и backend session

Stage 5A использует Android Credential Manager `1.6.0`, Play Services auth bridge `1.6.0` и Google ID `1.2.0`. Явная кнопка создаёт `GetSignInWithGoogleOption(serverClientId)` с новым 32-byte `SecureRandom` URL-safe nonce. Адаптер принимает только `CustomCredential` типа `GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL`; ID token не декодируется на устройстве как доверенная session и нигде не логируется. Google button использует официальный Google Sans Medium 14/20 только для своего label; provenance и branding-use зафиксированы в `licenses/Google-Sans-BRANDING-USE.md`.

Настройка Google Cloud/Firebase:

1. Создать/выбрать проект Google Cloud (Firebase необязателен).
2. В Google Cloud Console настроить OAuth consent screen.
3. Создать Android OAuth client для package `com.gigagochi.app` и SHA-1/SHA-256 debug/release сертификатов.
4. Создать Web application OAuth client. Именно его client ID передавать как `serverClientId` для запроса Google ID token.
5. Если используется Firebase: добавить Android app `com.gigagochi.app`, зарегистрировать SHA-1/SHA-256, включить Google provider. `google-services.json` не нужен, пока Firebase SDK не подключён.
6. Задать Web client ID и origin backend через Gradle properties либо environment. Реальные значения не коммитить:

   ```bash
   export GIGAGOCHI_GOOGLE_WEB_CLIENT_ID="000000000000-example.apps.googleusercontent.com"
   export GIGAGOCHI_BACKEND_BASE_URL="https://api.example.com/"
   ./gradlew :app:assembleDebug
   ```

   Альтернатива для локального `~/.gradle/gradle.properties` использует те же имена. Значения попадают в `BuildConfig.GOOGLE_WEB_CLIENT_ID` и `BuildConfig.BACKEND_BASE_URL`; control characters отвергаются при конфигурации Gradle.
7. Backend обязан валидировать подпись JWT, `iss`, `aud` (равен Web client ID), `exp` и `nonce`; Android не считает декодированный JWT подтверждённой сессией.

Backend contract:

```http
POST /api/auth/google
Content-Type: application/json

{"idToken":"<google-id-token>","nonce":"<url-safe-nonce>"}
```

```json
{
  "accessToken": "opaque-or-jwt-session-token",
  "refreshToken": "optional-opaque-token",
  "expiresAt": 1780000000000
}
```

Refresh rotation использует тот же строгий response shape:

```http
POST /api/auth/refresh
Content-Type: application/json

{"refreshToken":"<opaque-refresh-token>"}
```

Ответ разбирается строго: разрешены только `accessToken`, nullable/optional `refreshToken` и `expiresAt`; access token непустой, present refresh token непустой, expiry строго в будущем. Android различает `400`, `401`, `409`, `429`, `5xx`, network и invalid response, но не показывает response body пользователю.

Release принимает только HTTPS base URL. Debug дополнительно допускает HTTP только для `localhost`, `127.0.0.1`, `10.0.2.2` и `::1`. Redirects запрещены, response ограничен 64 KiB, connect/read timeout — 10/15 секунд. Placeholder `https://api.example.invalid/` намеренно переводит UI в `MissingConfiguration`.

Session сохраняется через Android Keystore AES‑GCM. Отдельные SharedPreferences содержат только `version`, Base64 IV и ciphertext; access/refresh token не записываются в Room/DataStore/plaintext. При старте valid session восстанавливается в память, near-expiry session атомарно ротируется через `/api/auth/refresh`, а invalid/revoked/corrupt запись очищается и ведёт в Auth. Успешный Google exchange переходит в Create только после подтверждённой encrypted записи.

Backend требует отдельные настройки без секретов в репозитории:

```env
GOOGLE_AUTH_WEB_CLIENT_ID=<Web application OAuth client ID>
AUTH_SESSION_STORE_PATH=data/push/google_auth_sessions.sqlite3
AUTH_ACCESS_TOKEN_TTL_SECONDS=900
AUTH_REFRESH_TOKEN_TTL_SECONDS=2592000
```

SQLite backend хранит только SHA‑256 digests opaque access/refresh tokens; refresh rotation атомарна и старый refresh повторно не принимается. До задания реального client ID, HTTPS deployment и runtime env рабочий end-to-end login не заявляется.

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
