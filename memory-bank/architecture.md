# Architecture

- Проект — одноплатформенное Android-приложение Kotlin + Jetpack Compose с одним Gradle-модулем `app`.
- `applicationId` и namespace: `com.gigagochi.app`; min SDK 23, target SDK 36, compile SDK 36.1.
- Пакетные границы внутри модуля: `core/model`, `core/network`, `core/database`, `core/designsystem`, `core/auth`, `feature/create`, `feature/dashboard`, `feature/travel`; Google OAuth source и Credential Manager dependencies удалены.
- `MainActivity` содержит production routes `Create`/`Dashboard` плюс recoverable `ConnectionError`/`LocalDataError`; пользовательского login route нет. При первом запуске приложение незаметно получает anonymous technical session, затем canonical anonymous owner выбирает локальный Room snapshot: существующий pet открывает Dashboard, пустой owner — Create. Intent review routes не создают и не читают production Room.
- `feature/create` разделён на immutable state machine (`CreatePetContract`), boundary `PetGenerationAdapter`, debug-only fake и real Android feature adapter. Первый ответ создаёт durable owner-scoped pending с одним `petId/requestKey`; adapter допускается только после записи точной текущей revision, а следующие ответы обновляют ту же row, не сбрасывая backend recovery state. Real adapter submit/attach/poll использует `/api/android/create/jobs`; attached job после restart только poll'ится. Как только `running` job публикует полный image set и normal video, production finalization пишет foreground snapshot и открывает Dashboard, сохраняя pending. После attach запускается unique one-shot `CreateSyncWorker` (`KEEP`, connected network, linear retry): он переживает process death, идемпотентно создаёт тот же snapshot, доставляет pet-ready notification и продолжает polling до `succeeded`. Lifecycle-STARTED `CreateBackgroundMediaCoordinator` остаётся быстрым foreground-путём; оба пути сливают late sad/happy media только при том же `assetSetId`, а pending удаляется после полного результата.
- Create background использует один локальный MP4 через Media3/TextureView и три frame-based clipping segment: initial loop, transition one-shot, formed loop. Recovery начинает с formed. Reduced motion использует статичные posters.
- Create audio локален: music loop 0.32 стартует только после trusted interaction, button WAV через SoundPool, haptic через platform view. Tilted buttons используют Haze source/effect, а не alpha-only glass.
- `PetCreatingStage` — native bitmap mosaic: разрешённые PNG уменьшаются до 18/21/24 px без фильтра и растягиваются nearest-neighbor; reduced motion показывает full-resolution static image.
- `feature/dashboard` содержит принятый Idle dashboard и inline `DashboardMode` (`Idle/Chat/Feed/Outfit/Travel`) с pure reducer, typed events и локальными fake adapter boundaries. Default Travel action открывает inline prompt поверх dashboard, не создаёт route.
- Dashboard visible snapshot persistence выполняет bounded coordinator: первая ошибка повторяет тот же snapshot один раз через 750 ms, без нового user event; после двух ошибок coordinator останавливается, cancellation пробрасывается.
- `PetDashboardState.petId` — стабильная identity питомца; `assetSetId` относится только к media set. `PendingOutfitGeneration` и `PendingTravelGeneration` хранят `petId`, а не `assetSetId`.
- Chat/Travel ограничены 1000 символами и одной активной операцией; Feed применяет stat delta до reply adapter; Outfit списывает ровно 200 XP только после принятой idempotent queue; Travel не списывает XP.
- Accepted Travel queue очищает draft, возвращает Dashboard в Idle и сохраняет typed pending (`petId/requestKey/prompt/localJobId`). Reply группируется в порции максимум по три строки; порции автоматически сменяются, финальная остаётся без continuation indicator.
- Inline modes и Idle используют один стабильный Compose media root. `DashboardVideo`/ExoPlayer не пересоздаётся при смене mode, а overlays меняются поверх него.
- Chat/Outfit/Travel запрашивают focus/IME сразу после открытия inline mode, без искусственного delay.
  Input, media и реплика интерполируют позиции по фактическому анимируемому `WindowInsets.ime`;
  input одновременно проявляется за 220 ms через ease-out. Boolean `imeVisible` не используется для
  переключения между двумя координатами.
- `feature/travel` содержит единый immutable reducer для Stage 4C entry/picker и Stage 4D active story. Fake suggestions возвращает три детерминированных варианта; default fake start по-прежнему всегда отдаёт recoverable failure и не изображает backend success. Typed `StartAccepted` переводит будущий real adapter либо локальный onboarding fixture из pending в `StoryQuestion`.
- Active story проходит `StoryQuestion → ChoicePending → StoryResult → Finished`. Отдельный `TravelStoryChoiceAdapter` возвращает keyed result; reducer проверяет `requestKey`, `travelId`, exact answer и допустимый XP до применения. Для onboarding принимается только `Млекопитающие` и ровно `+200 XP`.
- Picker/custom/pending используют один lifecycle-aware Media3/TextureView root. Active story использует отдельный стабильный Media3/TextureView root, который при переходе question → result не пересоздаётся, а переключает situation/success media item; reduced motion оставляет только poster.
- Dashboard построен в координатной плоскости 402×874 dp и равномерно масштабируется по правилу cover, заполняя viewport с crop.
- Scene использует lifecycle-aware AndroidX Media3/ExoPlayer: локальный MP4 автоматически воспроизводится без звука и зацикливается, PNG остаётся poster/error fallback. Noise overlay покрывает ту же full-viewport плоскость.
- Tap-bulge применяется в единственном Media3 GL-конвейере к декодированной видеотекстуре. Fragment shader повторяет web falloff/radius/strength; `Presentation` сохраняет dashboard cover-crop, а Compose-сердечки остаются отдельным foreground feedback.
- Scene/video/noise объединены в единственный Haze 1.7.2 source; XP и нижние actions — foreground `hazeEffect`. Text/icon content не входит в source. PlayerView использует TextureView, чтобы Media3 video участвовало в Compose graphics-layer capture.
- Glass contract хранится в `DashboardGlassContract`: effective actions blur `12.dp`/white 15%/radius 24, XP blur `8.dp`/brown alpha 16%/radius 31.927; Haze noise отключён. Actions используют два clipped Compose `innerShadow` слоя, расположенные над haze и под foreground content.
- Conversation panel и Feed tokens используют отдельный `InlineStyle` с Haze blur `18.5.dp`; foreground text/icons не входят в blur source.
- Dashboard сохраняет OpenRunde для chrome и контролов. Реплики персонажа выводятся без bubble в нижней зоне 356×72 dp, максимум в три строки, с bundled SB Sans Display Bold 20/23.9; длинные предложения автоматически делятся на последовательные порции. Каждая порция повторяет web reveal: 300 ms layer entrance и посимвольные 700 ms fade с 24 ms stagger; thinking использует стандартные три `thinking_frame_*` с шагом 200 ms.
- `core/auth` создаёт и хранит случайный canonical UUID v4 установки локально. `POST /api/auth/guest` возвращает стабильный opaque `accountId` и короткоживущую technical session без Google, имени, email или другого PII; backend хранит только SHA‑256 UUID subject. Guest endpoint используется только при отсутствии/инвалидации сохранённой session.
- Technical session хранится через Android Keystore AES‑GCM envelope v2; SharedPreferences содержат только version/IV/ciphertext. Access/refresh нужны исключительно как защита платных `/api/android` моделей и не являются пользовательской авторизацией. HTTP boundary допускает HTTPS, debug HTTP только loopback, не следует redirect, ограничивает response 64 KiB и строго проверяет UUID/session JSON.
- `core/network` содержит session-protected Android feature client и typed `/api/android` DTO. Feature transport запрещает redirect/cache, ограничивает body, строго проверяет UTF-8/JSON/enum/timestamps/job IDs/media origin и redacts secrets. Refresh single-flight защищён Mutex только вокруг encrypted session re-read/refresh/persist; feature network request выполняется вне lock, а доказанный 401 повторяет тот же serialized request максимум один раз.
- `core/database` Room 2.8.4 использует schema v8 и явные migrations 1→…→8 без destructive fallback. Помимо owner-scoped pet/pending/media/receipts он хранит chat history, отдельный per-pet compliment ledger, applied-chat receipts, user memories, pending learnings, memory state и единую notification outbox. Во всех primary/index keys первым scope является стабильный anonymous `accountId`; Android Backup отключён, поэтому удаление приложения удаляет прогресс, а переноса между устройствами нет.
- Android chat повторяет Telegram memory pipeline без Character Bible: до reply выполняются deterministic fact extraction/forget, relevance recall, добавление последних travel/outfit-состояний персонажа и передача последних 12 сообщений. Durable pending описывается как текущий `fact` (`отправился и ещё не вернулся` / `ждёт наряд и ещё не переоделся`), а подтверждённый generation outcome/asset заменяет его завершённым `episode`; для этих состояний резервируются места в ограниченном memory payload. После reply асинхронно запускаются LLM extraction и периодическая consolidation. WorkManager выбирает неупомянутые due-факты или недавний эпизод не чаще раза в локальные сутки, получает proactive reply через session-protected Android endpoint и атомарно кладёт его в durable local notification queue.
- `PetLocalRepository` — единственная публичная write boundary: валидирует bounds, атомарно списывает ровно 200 XP вместе с Outfit pending и атомарно применяет clamped Story deltas вместе с receipt. Повторные request/receipt keys не списывают и не начисляют повторно; backend job attach допускает только `null → id` или тот же id.
- Replay Outfit после atomic apply блокируется applied receipt до повторного debit/insert. Replay Travel после durable ready/consumption блокируется сохранённым owner+pet+request asset в `DashboardDurableOperations`, поэтому завершённый request не создаёт новый pending и не вызывает provider повторно.
- Room schemas экспортируются в `app/schemas/com.gigagochi.app.core.database.GigagochiDatabase/`; destructive migration не включена. Technical session/access/refresh и installation UUID в Room entities отсутствуют. Character bible валидируется как JSON object и ограничен authoritative 262144 UTF-8 bytes/depth/nodes.
- Android chat использует тот же structured compliment contract, что Telegram: отправляет до 500 durable `complimentKey`, применяет только `0/+30/+100`, атомарно сохраняет chat messages, happiness, ledger key и request receipt. Повтор одного `requestKey` не может повторно повысить happiness.
- Dashboard visual mode не зависит от dialogue `pet.mood`: sad выбирается, когда любой hunger/happiness/energy `<30`; happy — когда все три `>=70`; иначе normal. Для derived mode требуются отдельный poster и, если normal является видео, отдельное mood-видео; неполный комплект падает обратно в normal.
- Production Dashboard использует real Chat/Outfit/Travel adapters через anonymous technical session и deterministic local Feed; explicit debug routes учитываются только при `BuildConfig.DEBUG`, используют fixtures и не открывают production Room. Release игнорирует `gigagochi.route`, автоматически bootstrap'ит session и никогда не показывает OAuth. Lifecycle-STARTED foreground recovery poll'ит jobs, затем bounded повторяет только Room apply/consume для Ready rows.
- Dashboard media projection выбирает consumed Travel asset либо mood/age-specific generated pet video/poster. `StaticMediaUrlPolicy` и Media3 datasource допускают только validated same-origin `/static` URL, не следуют redirect, не используют bearer/cookies/cache и отдельно ограничивают image/video bytes/ranges. Reduced motion и lifecycle ниже STARTED оставляют poster; decode image проверяет dimensions/pixels до allocation. Локальный персонаж остаётся explicit fixture либо recoverable media fallback в той же 402×874 геометрии.
- Idle tap персонажа повторяет Mini App нативно: target соответствует web-region, исходный web fragment shader работает как Media3 `GlEffect` в том же конвейере, который выводит живое видео; center/strength/radius совпадают с web, а временной envelope настроен на 250 мс через smoothstep. Bitmap/snapshot/второй video overlay отсутствуют. Compose Canvas рисует mobile-web heart particles, исходный `pet-tap.wav` воспроизводится через SoundPool и добавлен лёгкий haptic. Reduced motion отключает particles и использует отдельный короткий envelope 100 мс. Room v1 сохраняет `petTapProgress` 0…4; только каждый пятый tap даёт +15 happiness, а одна из трёх web-реплик показывается максимум один раз за process-session, живёт 5 секунд только в UI и не меняет durable `pet.message`.
- Обычный Dashboard snapshot save использует optimistic `assetSetId` fence: stale Compose state не может перезаписать media, уже атомарно применённые из Outfit outcome.
- Scheduled short story uses exactly one additional owner-scoped Room v1 table. Each row contains
  one situation, four choices/media and at most one selected result; a durable choice request key
  fences double taps/restart replay. Dashboard performs a lifecycle-STARTED due check and only
  persists the response; default startup never auto-navigates. An explicit story-id deep link opens
  the retained one-part `InteractiveTravelStoryScreen` shell without restoring 4-part machinery.
  Choice success/replay commits the existing story receipt with the actual durable request key;
  route state and return to Dashboard use Room-authoritative experience, so restart cannot double XP.
- Background delivery uses the unique periodic WorkManager `CoroutineWorker` (`KEEP`, connected
  network, 15-minute periodic minimum) enqueued after production Dashboard startup. When the due
  endpoint reports that story generation is pending, or an Outfit/Travel backend job is attached,
  the app also enqueues the same unique one-shot run with linear retry. A one-shot/periodic pass keeps
  retrying while any safe manual generation remains unresolved. One pass
  bootstraps the anonymous Keystore session, checks due story once, performs exactly one pending
  Outfit/Travel recovery poll/apply, then drains one-channel local notifications. Every production
  kind (`PetReady`, story, outfit, travel, generation failure, proactive) is inserted idempotently into
  the owner+pet Room notification outbox before delivery; stable Android notification IDs replace a
  prior post if the process dies before the durable mark. Migration 7→8 backfills unnotified legacy
  story/outfit/travel/proactive rows. Create media recovery после
  foreground-ready выполняется на Dashboard при lifecycle STARTED, но не через WorkManager.
  Scheduled-story delivery is still a best-effort local notification, not server FCM. Event
  chronology has no backend list/backfill endpoint: it remains durable across normal app restarts,
  but clearing app data, reinstalling, or moving to another device loses older local history.
- `feature/events` merges the owner+pet `scheduled_stories` and consumed `travel_video_assets` Room
  flows into one newest-first in-app chronology. A durable owner+pet last-viewed watermark adds new
  event content to the Dashboard badge; unanswered stories remain badged until answered.
  `Помочь` reuses `ScheduledStoryRoute`. Travel cards keep the generated 9:16 ratio and share
  a securely cached MP4 through Android `FileProvider`/`ACTION_SEND`. Travel-ready notifications carry
  the durable request key; cold and singleTop warm launches open Events and scroll to that card.
  Event cards reuse the shared lifecycle-aware story media player, but only the card nearest the
  viewport center owns ExoPlayer.
- Все не-idle dashboard modes, экран событий, интерактивная история и custom-ввод Create используют один
  `ContextualGlassNavigation.Back`; отдельного close/cross action больше нет. Debug Apple fixture
  seed'ит в Room одну активную историю из банка задач, а активный выбор резолвится debug-only
  service wrapper через обычный receipt/XP pipeline без backend-вызова; проверка новых server stories
  при этом делегируется production service. Scheduled story публикуется backend'ом только с полным
  комплектом situation image/video и четырёх пар outcome image/video. Story media начинается
  после safe-area app bar, а tilted story actions используют общий create/onboarding foreground
  `#05152C`. Общий design-system gap между нижней границей contextual app bar и первым контентом —
  `ContextualAppBarContentGap = 18.dp`; новые экраны не задают этот отступ локальным числом.
- `feature/onboarding` содержит pure first-session contract/copy. Единственный durable source of truth —
  owner+pet row `first_sessions` плюс action receipts; `PetDashboardState`/`pet_snapshots` не дублируют
  active-флаг. Создание pet+initial stage, scripted food mutations, bat XP receipt и outfit attach-stage
  выполняются Room-транзакциями; обычный pet без row никогда не входит в onboarding.
  После лечебной еды инструкция всегда состоит из двух явных UI-порций: первая заканчивается
  просьбой помогать питомцу, вторая начинается с обнаружения летучей мыши. Между ними используется
  onboarding-specific auto-advance 5.5 секунды; общий splitter не определяет эту смысловую границу.
- Глобальное локальное debug-меню реализовано через variant-specific `debugmenu`: функциональный
  `src/debug` host накладывается поверх корневого route и поэтому доступен с любого экрана, а
  `src/release` содержит только no-op host/wrappers. Сгенерированный Человек-Яблоко хранится как
  второй Room pet snapshot; debug preference выбирает его при startup, не удаляя сохранённого pet.
  Stateful onboarding toggle атомарно либо перематывает durable first-session stage с очисткой
  action receipts, либо удаляет session и receipts, возвращая обычный Dashboard. Выключенное
  состояние сохраняется после process restart.
- `GigagochiTheme` владеет единым `ButtonPressAudio` и отдаёт feedback через
  `LocalButtonPressFeedback`; design-system navigation и dashboard controls используют исходный
  web `button-press.wav`. Dashboard speech reveal отдельно владеет четырьмя web speech samples и
  проигрывает неповторяющуюся последовательность с шагом 48±6 мс на время анимации символов.
