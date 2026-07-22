# Обновление и публикация Gigagochi Android

Этот runbook нужен для выпуска APK, который устанавливается поверх предыдущей версии без удаления приложения и потери локального питомца.

## Критические инварианты

- Package ID всегда `com.gigagochi.app`.
- Каждое обновление получает увеличенный `versionCode` и новый `versionName` в `app/build.gradle.kts`.
- Пользовательский APK подписывается тем же release keystore, что и предыдущие версии.
- Эталонный SHA-256 сертификата Gigagochi Release:

  ```text
  453318508e26ae11efddc22cffe4cedfe16829fc76035de84460a692ca0de0cd
  ```

- `app-debug.apk` нельзя отдавать как обновление. Он подписан Android Debug сертификатом и конфликтует с установленной release-версией.
- Нельзя удалять установленное приложение для обхода конфликта подписи: состояние хранится локально в Room и будет потеряно.
- Доставка выполняется только из чистого commit в `main`, а не из незакоммиченной рабочей копии.

Если release keystore или его пароль потеряны, выпустить совместимое обновление для уже установленного package ID невозможно. Файл keystore должен храниться в защищённом резервном хранилище отдельно от репозитория; пароли — в менеджере секретов или macOS Keychain.

## 1. Подготовить scope и версию

Проверить ветку и все локальные изменения:

```bash
git status -sb
git diff --stat
git diff --check
```

Не использовать `git add -A`, если в рабочей копии есть изменения из разных задач. Добавлять в релиз только явно проверенные файлы.

В `app/build.gradle.kts` увеличить оба значения, например:

```kotlin
versionCode = 12
versionName = "0.1.11"
```

`versionCode` должен быть больше кода любой ранее установленной или опубликованной сборки. Если менялась Room-схема, проверить наличие нового JSON в `app/schemas/` и migration-теста обновления с предыдущей версии БД.

## 2. Подключить release signing

Gradle читает следующие переменные окружения:

```bash
export GIGAGOCHI_ANDROID_KEYSTORE_FILE="/absolute/path/gigagochi-release.jks"
export GIGAGOCHI_ANDROID_KEYSTORE_PASSWORD="..."
export GIGAGOCHI_ANDROID_KEY_ALIAS="gigagochi"
export GIGAGOCHI_ANDROID_KEY_PASSWORD="..."
```

На основном release Mac keystore уже хранится по адресу `~/.config/gigagochi/gigagochi-release.jks`, а общий пароль keystore/key — в macOS Keychain под service `com.gigagochi.android.release` и account `sergejegorov`. Поднять signing-окружение без вывода пароля можно так:

```bash
release_secret=$(security find-generic-password \
  -a sergejegorov \
  -s com.gigagochi.android.release \
  -w)

export GIGAGOCHI_ANDROID_KEYSTORE_FILE="$HOME/.config/gigagochi/gigagochi-release.jks"
export GIGAGOCHI_ANDROID_KEYSTORE_PASSWORD="$release_secret"
export GIGAGOCHI_ANDROID_KEY_ALIAS="gigagochi"
export GIGAGOCHI_ANDROID_KEY_PASSWORD="$release_secret"
```

После сборки выполнить `unset release_secret` и `unset` для четырёх `GIGAGOCHI_ANDROID_*` переменных либо закрыть терминальную сессию.

Не сохранять реальные значения в репозитории, `gradle.properties`, shell history или текст задачи. Перед сборкой выполнить fail-fast проверку:

```bash
: "${GIGAGOCHI_ANDROID_KEYSTORE_FILE:?release keystore path is missing}"
: "${GIGAGOCHI_ANDROID_KEYSTORE_PASSWORD:?release keystore password is missing}"
: "${GIGAGOCHI_ANDROID_KEY_ALIAS:?release key alias is missing}"
: "${GIGAGOCHI_ANDROID_KEY_PASSWORD:?release key password is missing}"
test -f "$GIGAGOCHI_ANDROID_KEYSTORE_FILE"
```

## 3. Проверить код и собрать кандидата

Не запускать `clean`: предыдущий APK может понадобиться для сравнения подписи.

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew \
  :app:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin \
  :app:assembleRelease
```

Если подключено устройство или запущен эмулятор:

```bash
adb devices
./gradlew :app:connectedDebugAndroidTest
```

Release APK появится здесь:

```text
app/build/outputs/apk/release/app-release.apk
```

## 4. Обязательно проверить подпись и метаданные

```bash
APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
EXPECTED_CERT="453318508e26ae11efddc22cffe4cedfe16829fc76035de84460a692ca0de0cd"

actual_cert=$(
  "$APKSIGNER" verify --print-certs "$RELEASE_APK" 2>/dev/null |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
)

test "$actual_cert" = "$EXPECTED_CERT" || {
  echo "STOP: APK signed with the wrong certificate" >&2
  exit 1
}

"$AAPT" dump badging "$RELEASE_APK" | sed -n '1p'
```

В первой строке должны быть `package: name='com.gigagochi.app'`, ожидаемые `versionCode` и `versionName`.

Если есть предыдущий delivery APK, дополнительно сравнить сертификаты напрямую:

```bash
PREVIOUS_APK="app/build/outputs/apk/delivery/gigagochi-previous.apk"

previous_cert=$(
  "$APKSIGNER" verify --print-certs "$PREVIOUS_APK" 2>/dev/null |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
)

test "$actual_cert" = "$previous_cert"
```

## 5. Проверить обновление на реальном устройстве

Проверка должна выполняться поверх предыдущей release-версии с уже созданным питомцем:

```bash
RELEASE_APK="app/build/outputs/apk/release/app-release.apk"

adb shell pm path com.gigagochi.app
adb install -r "$RELEASE_APK"
adb shell am start -n com.gigagochi.app/.MainActivity
```

После запуска вручную проверить:

- приложение обновилось без удаления;
- питомец и история событий сохранились;
- приложение не падает при миграции Room;
- разрешение на уведомления сохранилось;
- основной сценарий изменения релиза работает;
- фоновая генерация создаёт событие и показывает уведомление.

Ошибка `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, «конфликтует с другим пакетом» или «подписи не совпадают» означает неверный signing certificate. Остановить релиз и пересобрать APK с исходным release keystore. Не предлагать пользователю удалять приложение.

Ошибка `INSTALL_FAILED_VERSION_DOWNGRADE` означает, что `versionCode` не увеличен.

## 6. Commit, PR и merge

Сначала закоммитить только подтверждённый scope и отправить отдельную ветку:

```bash
git add <явно перечисленные файлы>
git commit -m "Release <version>"
git push -u origin <release-branch>
gh pr create --base main --fill
```

После успешной проверки PR смержить в `main`. В этом репозитории нет отдельной ветки `merge`: корректный поток — `push → PR → merge в main`.

После merge получить точный опубликованный commit и убедиться, что рабочая копия чистая:

```bash
git switch main
git pull --ff-only origin main
git status --short
```

Повторно собрать release APK из чистого `main` и ещё раз выполнить разделы 4–5. Delivery APK должен соответствовать именно этому commit.

## 7. Подготовить и опубликовать APK

Назвать файл по версии и commit:

```bash
version_name="0.1.11"
commit_sha=$(git rev-parse --short HEAD)
RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
delivery_apk="app/build/outputs/apk/delivery/gigagochi-${version_name}-${commit_sha}.apk"

mkdir -p app/build/outputs/apk/delivery
cp "$RELEASE_APK" "$delivery_apk"
shasum -a 256 "$delivery_apk"
```

Создать GitHub Release и приложить именно проверенный delivery APK:

```bash
gh release create "v${version_name}" "$delivery_apk" \
  --title "Gigagochi ${version_name}" \
  --generate-notes \
  --target main
```

Перед отправкой ссылки пользователю скачать asset из GitHub Release и ещё раз проверить его SHA-256 и signing certificate. В сообщении указывать версию, короткий commit SHA, checksum и прямую ссылку на release asset.

## Короткий release gate

- [ ] Scope проверен; незакоммиченные изменения не смешаны случайно.
- [ ] `versionCode` и `versionName` увеличены.
- [ ] Room schema и migration-тест добавлены, если менялась БД.
- [ ] Unit-тесты прошли; Android-тесты как минимум скомпилированы.
- [ ] Release APK собран с заданными четырьмя signing variables.
- [ ] SHA-256 сертификата равен `453318…0cd`.
- [ ] Package ID и версия проверены через `aapt`.
- [ ] `adb install -r` поверх предыдущего release APK успешен.
- [ ] Локальные данные сохранились после обновления.
- [ ] PR смержен в `main`; APK пересобран из чистого merge commit.
- [ ] GitHub Release содержит именно проверенный APK.
- [ ] Скачанный release asset повторно прошёл checksum и signature verification.
