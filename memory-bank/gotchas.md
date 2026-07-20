# Gotchas

- Установлен только SDK Platform `android-36.1`; для него нужен AGP 8.13+ и minor API DSL `release(36) { minorApiLevel = 1 }`.
- Системный `java` не предполагается: проверенный JDK находится в Android Studio JBR.
- `local.properties` в Android-проекте отсутствует; CLI build должен явно получить `ANDROID_HOME=/Users/sergejegorov/Library/Android/sdk`, иначе AGP останавливается до task execution с `SDK location not found`.
- OpenRunde в исходном frontend хранится как WOFF2, который Android `res/font` не принимает. Проверенный путь: `fontTools 4.59.0` + WOFF2 extras, `TTFont.flavor = None`, статические TTF в `res/font`; dashboard использует их, а не system sans.
- SF Pro упоминается в web CSS как системный fallback, но лицензированного файла в Android не переносится.
- SB Sans Display из Paper нельзя адресовать на Android по имени установленного macOS-шрифта: Bold-файл должен оставаться bundled в `res/font`, иначе реплики незаметно откатятся к системному sans.
- SVG из web содержат CSS custom properties (`var(--fill-0, ...)`) и не являются Android VectorDrawable. Оригиналы сохранены byte-for-byte в `res/raw`; status paths рендерятся Compose Canvas, XP/action paths перенесены в VectorDrawable, speech SVG с inner-shadow filter заранее rasterized в lossless PNG 360×203 и масштабируется до baseline bounds.
- Координаты `PathParser` находятся в единицах SVG viewBox, а Compose Canvas рисует в физических px. Status glyphs надо масштабировать из viewBox 54×54 в фактический размер Canvas; без этого при density 420 они выглядят как почти невидимые штрихи.
- Масштаб reference-плоскости через `min(widthRatio, heightRatio)` даёт letterbox на viewport другого aspect ratio. Dashboard использует cover через `max(...)`; poster, видео и noise overlay разделяют одну геометрию.
- Источник истины для status glyph — фактически используемый web `StatProgressRing.tsx`, а не неиспользуемый `status-energy-new.svg`; energy на dashboard — heart path.
- Browser screenshot transport может сохранить JPEG bytes под расширением `.png`. Перед pixel diff обязательны `file`, magic bytes и dimensions; JPEG-origin noise нельзя считать Compose mismatch.
- Haze не может надёжно захватить отдельный SurfaceView в source graphics layer. Dashboard Media3 PlayerView поэтому создаётся из XML с `surface_type="texture_view"`; возврат к SurfaceView сломает реальный background blur видео.
- Haze 1.7.2 использует native RenderEffect на Android 12+/API 31+. Для minSdk 23–30 применяется обычный `fallbackTint` scrim; experimental RenderScript fallback не включён.
- Числовой CSS `backdrop-filter` radius нельзя механически переносить в Haze: для этой scene rendered parity потребовал effective `12.dp` у actions и `8.dp` у XP. CSS inset shadows воспроизводятся отдельными `Modifier.innerShadow` overlays над haze и под labels/icons; один gradient border визуально недостаточен.
- Локально установлен единственный AVD system image API 37.1. Проект компилируется против SDK 36.1/target 36, но connected tests фактически запускаются на API 37, пока не установлен отдельный API 36 image.
- `pointerInput` key нельзя связывать с фазой того же drag. Если `onDragStart` меняет `Idle → Dragging`, recomposition отменяет gesture coroutine до медленного mouse/pointer release. Drag key должен оставаться стабильным до drop; быстрый synthetic `swipe` способен скрыть эту ошибку, поэтому нужен mouse-тест с промежуточной recomposition.
- При `minSdk 23` нельзя обращаться к `URLConnection.contentLengthLong` (API 24); bounded HTTP transports читают и безопасно парсят `Content-Length`, сохраняя потоковый byte-limit как authoritative fallback.
- `POST_NOTIFICATIONS` может быть отозван между permission-check и `notify()`. Эмиттер сначала проверяет permission/system notification switch и всё равно обрабатывает `SecurityException`, не помечая процесс упавшим.
- Ключи GigaChat/OpenRouter нельзя помещать в APK. Local-only означает локальные Room-данные и отсутствие user login; платные модели остаются за backend и используют автоматически выданную anonymous technical session. Общий статический app token также небезопасен, потому что извлекается из APK.
- Один installation UUID обязан давать стабильный anonymous `accountId`, иначе после refresh/session loss локальный Room owner станет недоступен. UUID хранится локально, backend сохраняет только `guest:` + SHA‑256; `android:allowBackup=false` запрещает перенос UUID/Room на другое устройство.
- Release APK должен всегда подписываться одним и тем же отдельным keystore: смена сертификата потребует удалить приложение и тем самым уничтожит local-only данные. Keystore/password не коммитятся; Gradle читает их только из `GIGAGOCHI_ANDROID_KEYSTORE_*` environment variables.
- В Compose `Modifier.offset` меняет placement, но не measured height. Create gradient panels нельзя делать `fillMaxSize().offset(...)`: web bounds требуют явных `402×519` от y=355 и `402×262` от y=612, иначе gradient не достигает bottom viewport.
- Create timeline режется по frame boundaries при 24 fps (`170/267/447`), а не приблизительным миллисекундам. Initial/formed должны loop, transition — закончиться один раз и перевести state в formed; recovery сразу formed.
- Create Haze, как и dashboard, требует `TextureView` внутри source. Alpha-white surface не эквивалентен `backdrop-filter`; на API ниже 31 действует fallback tint.
- SB Sans Display Bold включён в APK для реплик по новому Paper-макету, но отдельная лицензия рядом не найдена: перед release остаётся `LICENSE REVIEW REQUIRED`. Для Create option labels по-прежнему используется OpenRunde с `letterSpacing=-0.45sp`.
- Android 14+ может открыть Gboard stylus-handwriting toolbar для Compose text field. Это состояние IME/AVD, не app layout и не повод отключать handwriting API. Для inline gate внешний Gboard preference «Use stylus to write in text fields» отключён только на AVD; app-код не менялся. Docked QWERTY после этого отдаёт IME inset: keyboard top `y=542`, input panel `y=463`.
- Web Create error CSS реально накладывает `ErrorNotice` (`y=810`) на retry (`y≈809`). Supervisor принял native исправление без overlap: error поднят к `y=724`, retry остаётся у bottom. Это намеренное расхождение, а не ошибка измерения.
- Web `img-fx` loader темнит и абстрагирует источники сильнее простого pixel scaling. Native gate использует требуемый nearest-neighbor contract 18/21/24 px и сохраняет больше исходного цвета; interior loader-card исключён из UI-mask как motion/media region.
- Browser reference screenshots Create имеют JPEG bytes и честное `.jpg` расширение. Временная копия frontend/`.next` для clean capture удалена из Android-проекта после съёмки; её нельзя хранить как artifact.
- `PendingPetGeneration` в production обязан иметь Room counterpart с теми же stable `petId/requestKey`; startup восстанавливает именно эту identity. Review/debug route остаётся fixture-only и не открывает production Room.
- `petId` и `assetSetId` нельзя взаимозаменять: первый адресует identity/Room/API recovery, второй — media asset set. Pending Outfit обязан сохранять `petId`.
- То же правило обязательно для Travel pending: queue adapter принимает отдельный stable `petId`; `assetSetId` нельзя использовать как ключ background job/recovery.
- Native 3000 ms reply timer переключает следующую трёхстрочную portion и не очищает последнюю реплику. Reducer использует `AdvanceReply`, а не auto-clear.
- Ветвление целого Dashboard composable по mode пересоздаёт ExoPlayer и даёт poster/black flash. Media root должен оставаться одной и той же composable group, а mode менять только overlays.
- Android `RenderEffect` на `TextureView` либо его `PlayerView`-родителе не искажает отдельный video hardware layer. Нельзя возвращаться к bitmap/второму `TextureView` overlay: он меняет presentation geometry. Pet bulge должен оставаться одним Media3 `GlEffect`; нулевая strength даёт обычное семплирование без пересборки player pipeline.
- Horizontal actions сохраняют `scrollLeft`. Web/native side-by-side допустим только при одинаковом offset; иначе actions надо исключить из mask и явно зафиксировать mismatch.
- Gradle connected tests могут удалить установленный target APK при cleanup. Перед device screenshots APK надо установить заново; каждый capture проверять через `am start -W` и `topResumedActivity`, иначе `screencap` может молча сохранить launcher/transition.
- Web InteractiveTravelScreen зависит одновременно от local pet seed и `/api/travel/interactive/suggestions`. Без backend route возвращает 500 и UI показывает deterministic fallback вместе с видимым error; clean reference надо получать через browser-only localStorage seed + route fulfill, не меняя `tamagochi-main`.
- `appliedStoryTravelIds` для production восстанавливается из Room receipts. Story UI не применяет XP до успешной receipt transaction; uniqueness по `receiptKey` и `(ownerId, travelId, partKey)` защищает replay/restart, а выход из route обязан перечитать Room-authoritative pet.
- `data class` с raw ID/access/refresh token автоматически раскрывает секрет через `toString()`. Все auth/session tokens обязаны оставаться в `SensitiveToken` с redacted `toString`; raw value доступен только узкому HTTP serializer.
- Coroutine cancellation нельзя превращать в `Network`/`Unknown`: HTTP adapters явно rethrow `CancellationException`, иначе остановленный bootstrap может получить late error.
- Release auth base URL принимает только HTTPS. Debug HTTP разрешён только для loopback (`localhost`, `127.0.0.1`, `10.0.2.2`, `::1`); response redirects и oversized body не принимаются.
- `expiresAt` auth response — epoch milliseconds. Сравнение как epoch seconds делает session практически бессрочной; parser/model/bootstrap используют только `expiresAtEpochMillis`.
- Refresh rotation сначала инвалидирует старый refresh на backend, затем атомарно пишет новый encrypted envelope. Если local commit не удался, bootstrap обязан fail closed и очистить старую запись: её refresh уже replay-invalid.
- SharedPreferences для session не являются secret storage сами по себе: допустимы только `version`/IV/AES‑GCM ciphertext. Access/refresh token нельзя дублировать в Room, DataStore, plaintext preferences, exception messages или `toString()`.
- Ошибка чтения Room при валидной encrypted session не является logout: сохраняй session/AuthHeaderProvider и показывай отдельный retry boundary. Auth envelope и owner Room data при этом не очищаются.
- `LaunchedEffect(state.pet)` сам по себе не повторит failed save, если `state.pet` не изменился. Retry должен жить внутри bounded persistence coordinator и повторять тот же snapshot без tight loop и без нового user event; cancellation нельзя превращать в save failure.
- Ошибка финальной записи Create не должна повторно запускать generation adapter: generated result остаётся ready, а retry повторяет только persistence. Иначе будущий paid backend adapter может создать дубль.
- Stable Create `requestKey` не является persistence marker: все шаги используют один key. Gate paid generation обязан сравнивать fingerprint точной durable revision; stale completion старого write не может разрешить adapter после failure более новой revision.
- Active Travel media root нельзя удалять на `ChoicePending`: иначе question → pending → result создаёт второй ExoPlayer. Pending держит `StoryScrollableContent` composed с alpha 0, очищенными semantics и выключенными scroll/actions; thinking рисуется поверх.
- Любая pet/pending/receipt запись в Room обязана адресоваться составным ключом с anonymous `ownerId`: глобальный `petId` или `requestKey` может смешать данные после перевыпуска technical session. `ownerId` передаёт caller; access token не является локальным identity key.
- Pending rows намеренно не имеют cascading foreign key на pet snapshot: Create pending появляется до готового snapshot, а replace/delete snapshot не должен молча удалить recovery job. DAO остаётся `internal`, а bounds обеспечиваются публичной repository boundary; Room 2.8.4 не даёт чистого annotation API для SQLite `CHECK` без ручного DDL, поэтому schema v1 не подменяет compiler-generated DDL.
- Backend job ID — compare-and-set поле. Повтор того же ID идемпотентен, но другой ID после привязки считается конфликтом; обычный `UPDATE` без `backendJobId IS NULL` может перепривязать recovery к чужой job.
- Production idempotency key для будущих feature mutations — raw canonical UUID v4 длиной 36 без operation prefix. Prefix допустим только в deterministic debug fixtures; retry/recovery переиспользует key из pending row.
- Android feature JSON обязан принимать unknown additive поля, но fail closed на missing required fields, invalid enum/type/timestamp и malformed UTF-8. `java.time` нельзя использовать без desugaring при minSdk 23; текущий ISO parser построен на API23-safe `GregorianCalendar` с strict validation.
- Backend media URL может иметь cache-buster `?v=...`. Разрешён только один bounded same-origin `v`; userinfo/fragment/cross-origin/http, duplicate query и encoded `%2e/%2f/%5c` запрещены. Present-but-invalid optional URL ломает весь result, а не превращается в `null`.
- Async Outfit/Travel ready result нельзя писать в active pet snapshot или удалять вместе с pending до atomic apply. Outcome images хранятся по `(ownerId, requestKey, stage, mood)`, иначе два последовательных outfit результата склеиваются с текущими `pet_mood_images`.
- Near-expiry access без refresh token можно использовать до фактического expiry. Но после реального 401 тот же rejected access повторять нельзя: session становится invalid. Transient encrypted-storage read failure очищает только in-memory header текущего запроса, не envelope и не owner Room data.
- Foreground recovery должен быть `repeatOnLifecycle(STARTED)` и получать explicit signal после attach нового job. Однократный poll при входе в пустой Dashboard теряет jobs, созданные позже; обычный `LaunchedEffect` без lifecycle gate продолжает сеть после Activity STOP.
- API 37.1 AVD/Gradle incremental androidTest packaging может оставить stale project dex: Kotlin test classes есть в `built_in_kotlinc`, но отсутствуют в APK и runner даёт ClassNotFound. Проверенный recovery — rerun только androidTest dex/package, проверить APK, затем manual install/instrument; это tooling failure, не migration failure.
- Release lintVital AGP 32.1.1 требует uncached `org.codehaus.groovy:groovy:3.0.22`. Maven Central 403 блокирует полный `assembleRelease`; `packageRelease` с явно исключёнными lintVital tasks доказывает compile/package, но не считается green lint/assemble gate.
- Ready Outfit/Travel нельзя исключать из foreground poll и затем молча вернуть: `StorageFailure`/`NotReady` должны bounded повторять только локальный apply/consume. Детерминированный fence conflict становится durable `Failed/APPLY_CONFLICT` и ведёт в recoverable local-data route; provider submit/poll при этом не повторяется.
- Повторный `@Upsert` Travel ready asset после consumption может сбросить `consumedAt` в `NULL`. Ready media сохраняется только insert-or-verify: exact replay — no-op, mismatch — conflict.
- Media3 `DataSpec.Builder` сам запрещает `length=0`; datasource всё равно имеет defensive zero-length branch, но основной контракт тестируется как pre-connection rejection. Для unknown Content-Length лимит требует extra-byte probe: достижение ровно лимита ещё не доказывает EOF.
- Encoded image byte cap не защищает от decompression bomb. Перед bitmap allocation надо читать bounds, ограничивать source dimensions/pixels и выбирать `inSampleSize` для viewport.
- AVD visual gate может сохранить transient system bars, show-taps pointer или global dim overlay. Такие captures хранятся только как rejected evidence; pass screenshot снимается после `show_touches=0`, `pointer_location=0`, скрытия bars и stable frame. Fixture screenshot доказывает common chrome, но не remote-content rendering.
- Memory tables добавлены Room migration 1→2, compliment ledger и applied-chat receipts — migration 2→3; удалять приложение для обновления нельзя, иначе пропадут owner-scoped pet и история. Любое следующее изменение схемы требует новой явной migration и экспортированного JSON schema.
- Нельзя определять sad/happy по dialogue `pet.mood` или искать повтор комплимента только в 12 последних chat messages. Визуальный mode выводится из трёх stats, а семантическая уникальность супер-комплимента требует отдельного bounded per-pet ledger, передаваемого в `complimentHistory`.
- Проактивность Android — локальный WorkManager/notification queue, поэтому системный Doze может задержать вопрос. Генерация требует развернутых `/api/android/proactive` и memory endpoints; deterministic имя/срок и recall работают на клиенте, но LLM extraction/consolidation без этих routes не выполняются.
- Удаление pending после успешного apply не означает, что mutation key можно забыть: Outfit replay должен проверять applied receipt до debit, а Travel replay — durable asset до нового submit. Иначе тот же requestKey после restart повторяет платную/долгую операцию уже после очистки pending.
- Terminal failed Create job нельзя «ретраить» повторным poll того же backendJobId: серверный status неизменяем и мгновенно вернёт старую ошибку. Явный retry сохраняет pet/ответы, но получает новый requestKey и транзакционно заменяет только локальную pending-row со state `Failed`; transient/unknown ошибки продолжают использовать исходную identity, чтобы не задвоить платную генерацию.
- Production Create занял около 12 минут. Polling window Android должен быть заметно длиннее: короткий client timeout показывает ложную ошибку, пока backend продолжает и успешно завершает job.
- Системный permission dialog может вернуть navigation/status bars после однократного immersive-hide в
  `onCreate`. В fullscreen Activity повторно применяй immersive mode при возврате window focus, а
  нижние controls размещай по фактически видимой границе reference frame с учётом safe bottom inset
  и cover-scale; иначе на физическом устройстве они визуально обрезаются и попадают в системную touch zone.
- Pet-ready notification is emitted locally when the Create polling coroutine receives success while
  the Activity is stopped. It uses the Telegram copy and a request-key-stable notification ID, but it
  is not FCM or WorkManager delivery: force-stopping/killing the app during generation can suppress it.
  Android 13+ notification permission must therefore be requested on Create, not first on Dashboard.
- Splitter реплик целится в 4 строки через консервативную оценку ширины glyph в em, чтобы pure reducer не зависел от Compose/Android font measurement. Compose и контейнер допускают аварийные 6 строк. При изменении SB Sans, размера 20sp или ширины 356dp одновременно калибруй `DashboardReplyLineWidthEm`, иначе шестистрочный предел снова может скрыть хвост реплики.
- Dashboard reveal синхронизирован с прежним web `PetCharacterMessage`: 300/700/24 ms и те же cubic-bezier. Loader нельзя заменять generic dots — его source of truth остаются три `thinking_frame_*` drawable, переключаемые каждые 200 ms.
- Не ключуй `pointerInput` нестабильной callback-лямбдой у анимируемой Compose-кнопки: собственная
  scale/reveal recomposition перезапускает gesture detector и отменяет текущий press. Храни callback
  через `rememberUpdatedState`, а стабильный detector ключуй `Unit`.
- `partycles` измеряет heart `lifetime` в simulation frames, уменьшая life на 1.2 при 60 fps; это не миллисекунды. Mobile web оптимизирует 16/170/42 до 9 частиц, 136 frames и 33px, поэтому native burst длится около 1.889s. Media3 `GlEffect` обновляет динамический shader на decoded video frames, а `setVideoEffects` без явного `Presentation` crop меняет aspect/letterbox; native tap использует smoothstep envelope 250 мс при постоянном cover-crop, без snapshot и смены геометрии.
- WorkManager periodic interval 15 minutes is only a legal minimum, not a delivery deadline: OS
  batching, Doze and network constraints may delay it. This MVP is local best-effort notification,
  not FCM/push. Never replace the one-pass `recoverForeground(maxPollAttempts=1)` with `watch()` or
  long polling inside the worker.
- `AnimatedContent` продолжает компоновать уходящий route до конца exit-анимации. Нельзя очищать
  payload активного экрана (`activeStory` и аналоги) одновременно со сменой route, если исходная
  ветка делает `requireNotNull(payload)`: сначала переключай route, сохраняй snapshot payload внутри
  content lambda и очищай его только после выхода либо допускай `null` без падения.
- Terminal `Failed` Outfit/Travel rows are audit history, not active recovery pending. Startup must
  exclude them, and durable acceptance lookup must not coalesce a new request into them. Foreground
  recovery reloads Main only for failures created during that pass; treating old failures as an
  edge retriggers startup forever and blocks a new request key.
- First-session bat receipt и stage намеренно разделены: correct choice атомарно даёт ровно +200 XP,
  но оставляет `AwaitingTravel`; только durable Finish переводит в `AwaitingCompletionMessage`.
  Иначе process death между result и Finish либо повторно начисляет XP, либо пропускает финальный экран.
- Debug prompt/event logging нельзя встраивать прямо в production adapters: строки и вызовы тогда
  попадают в release. Оборачивай adapters variant-specific функциями из `src/debug`; `src/release`
  обязан возвращать исходный delegate.
- Apple debug fixture не запускает повторную генерацию, но его `/static/generated` изображения и
  видео остаются сетевыми same-origin URL. Это стабильный сохранённый персонаж, не offline fixture.
- Debug scheduled-story wrapper не должен всегда возвращать пустой `dueStory` для тестового pet:
  это маскирует реальные server stories. Story fixture не использует idle media персонажа как
  обложку; при неполном комплекте situation/outcome media backend не переводит story в ready.
- Dashboard action `События` резервирует дополнительную ширину только при видимом badge: без badge
  `184.dp`, с badge `216.dp`. Постоянные `216.dp` дают визуально лишние горизонтальные поля.
- Compose `painterResource` не умеет загружать XML `<shape>` как painter. Placeholder для story/event
  media должен быть vector/raster drawable, иначе экран падает до завершения сетевой загрузки.
- Нельзя шарить remote travel URL как `text/plain` или отдавать `file://`: мессенджерам нужен сам MP4
  через read-only `content://`. Копирование использует ограниченный same-origin media datasource,
  отдельный cache-path `shared-travel-videos/` и `FLAG_GRANT_READ_URI_PERMISSION`; весь `cacheDir`
  через `FileProvider` не публикуется.
