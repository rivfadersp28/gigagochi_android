#!/usr/bin/env bash
set -euo pipefail

repository_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
verification_mode="signed-release"
if [[ "${1:-}" == "--structural-only" ]]; then
    verification_mode="structural-only"
    shift
fi

fail() {
    if [[ "${verification_mode}" == "structural-only" ]]; then
        printf 'APK structural verification failed: %s\n' "$1" >&2
    else
        printf 'signed release APK verification failed: %s\n' "$1" >&2
    fi
    exit 1
}

if [[ "$#" -lt 1 || "$#" -gt 2 ]]; then
    fail "usage: $0 [--structural-only] APK_PATH [R8_MAPPING_DIRECTORY]"
fi
apk_path="$1"
mapping_directory="${2:-${repository_root}/app/build/outputs/mapping/release}"

if [[ ! -f "${apk_path}" ]]; then
    fail "APK not found: ${apk_path}"
fi
if [[ ! -f "${mapping_directory}/mapping.txt" ]]; then
    fail "R8 mapping not found: ${mapping_directory}/mapping.txt"
fi

apkanalyzer_path="${APKANALYZER:-}"
if [[ -n "${apkanalyzer_path}" && ! -x "${apkanalyzer_path}" ]]; then
    fail "APKANALYZER is not executable: ${apkanalyzer_path}"
fi
if [[ -z "${apkanalyzer_path}" ]]; then
    apkanalyzer_path="$(command -v apkanalyzer || true)"
fi
if [[ -z "${apkanalyzer_path}" ]]; then
    for sdk_root in \
        "${ANDROID_SDK_ROOT:-}" \
        "${ANDROID_HOME:-}" \
        "${HOME:-}/Library/Android/sdk" \
        "${HOME:-}/Android/Sdk"
    do
        candidate="${sdk_root}/cmdline-tools/latest/bin/apkanalyzer"
        if [[ -x "${candidate}" ]]; then
            apkanalyzer_path="${candidate}"
            break
        fi
    done
fi
if [[ -z "${apkanalyzer_path}" ]]; then
    fail "apkanalyzer was not found; set APKANALYZER or ANDROID_SDK_ROOT"
fi

manifest_dump="$(mktemp "${TMPDIR:-/tmp}/gigagochi-manifest.XXXXXX")"
class_dump="$(mktemp "${TMPDIR:-/tmp}/gigagochi-classes.XXXXXX")"
certificate_dump="$(mktemp "${TMPDIR:-/tmp}/gigagochi-certificates.XXXXXX")"
web_bundle_directory="$(mktemp -d "${TMPDIR:-/tmp}/gigagochi-web-bundle.XXXXXX")"
trap 'rm -f -- "${manifest_dump}" "${class_dump}" "${certificate_dump}"; rm -rf -- "${web_bundle_directory}"' EXIT

if [[ "${verification_mode}" == "signed-release" ]]; then
    expected_application_id='com.gigagochi.app'
    expected_version_code='17'
    expected_version_name='0.1.16'
    expected_certificate_sha256='453318508e26ae11efddc22cffe4cedfe16829fc76035de84460a692ca0de0cd'

    if ! application_id="$("${apkanalyzer_path}" manifest application-id "${apk_path}" | tr -d '\r\n')"; then
        fail "applicationId metadata could not be read"
    fi
    if ! version_code="$("${apkanalyzer_path}" manifest version-code "${apk_path}" | tr -d '\r\n')"; then
        fail "versionCode metadata could not be read"
    fi
    if ! version_name="$("${apkanalyzer_path}" manifest version-name "${apk_path}" | tr -d '\r\n')"; then
        fail "versionName metadata could not be read"
    fi
    if [[ "${application_id}" != "${expected_application_id}" ]]; then
        fail "expected applicationId ${expected_application_id}; found ${application_id:-missing}"
    fi
    if [[ "${version_code}" != "${expected_version_code}" ]]; then
        fail "expected versionCode ${expected_version_code}; found ${version_code:-missing}"
    fi
    if [[ "${version_name}" != "${expected_version_name}" ]]; then
        fail "expected versionName ${expected_version_name}; found ${version_name:-missing}"
    fi

    apksigner_path="${APKSIGNER:-}"
    if [[ -n "${apksigner_path}" && ! -x "${apksigner_path}" ]]; then
        fail "APKSIGNER is not executable: ${apksigner_path}"
    fi
    if [[ -z "${apksigner_path}" ]]; then
        apksigner_path="$(command -v apksigner || true)"
    fi
    if [[ -z "${apksigner_path}" ]]; then
        for sdk_root in \
            "${ANDROID_SDK_ROOT:-}" \
            "${ANDROID_HOME:-}" \
            "${HOME:-}/Library/Android/sdk" \
            "${HOME:-}/Android/Sdk"
        do
            if [[ ! -d "${sdk_root}/build-tools" ]]; then
                continue
            fi
            candidate="$(find "${sdk_root}/build-tools" -type f -name apksigner -perm -111 2>/dev/null | LC_ALL=C sort | tail -n 1)"
            if [[ -n "${candidate}" ]]; then
                apksigner_path="${candidate}"
                break
            fi
        done
    fi
    if [[ -z "${apksigner_path}" ]]; then
        fail "apksigner was not found; set APKSIGNER or ANDROID_SDK_ROOT"
    fi
    if ! "${apksigner_path}" verify --print-certs "${apk_path}" > "${certificate_dump}" 2>&1; then
        sed -n '1,20p' "${certificate_dump}" >&2
        fail "APK signature verification failed"
    fi
    certificate_sha256="$(awk -F': ' '
/^Signer #[0-9]+ certificate SHA-256 digest:/ { print tolower($2) }
' "${certificate_dump}")"
    if [[ "${certificate_sha256}" != "${expected_certificate_sha256}" ]]; then
        fail "expected the sole signer certificate SHA-256 ${expected_certificate_sha256}; found ${certificate_sha256:-missing}"
    fi
fi

"${apkanalyzer_path}" manifest print "${apk_path}" > "${manifest_dump}"

debuggable="$("${apkanalyzer_path}" manifest debuggable "${apk_path}")"
if [[ "${debuggable}" != "false" ]]; then
    fail "release manifest is debuggable (${debuggable})"
fi

if grep -Fq 'com.gigagochi.app.MainActivity' "${manifest_dump}"; then
    fail "MainActivity is present in the release manifest"
fi
if grep -Fq 'com.gigagochi.app.webview.WebViewPreviewActivity' "${manifest_dump}"; then
    fail "WebViewPreviewActivity is present in the release manifest"
fi

launcher_activities="$(awk '
function finish_activity() {
    if (has_main && has_launcher) {
        print activity_name
    }
    in_activity = 0
    activity_name = ""
    has_intent_filter = 0
    has_main = 0
    has_launcher = 0
}
/^[[:space:]]*<activity([[:space:]>]|$)/ {
    in_activity = 1
    activity_name = ""
    has_intent_filter = 0
    has_main = 0
    has_launcher = 0
}
in_activity && activity_name == "" && /android:name="[^"]+"/ {
    activity_name = $0
    sub(/^.*android:name="/, "", activity_name)
    sub(/".*$/, "", activity_name)
}
in_activity && /<intent-filter([[:space:]>]|$)/ { has_intent_filter = 1 }
in_activity && /android:name="android.intent.action.MAIN"/ { has_main = 1 }
in_activity && /android:name="android.intent.category.LAUNCHER"/ { has_launcher = 1 }
in_activity && /<\/activity>/ { finish_activity(); next }
in_activity && !has_intent_filter && /\/>/ { finish_activity(); next }
END { if (in_activity) finish_activity() }
' "${manifest_dump}")"

expected_launcher="com.gigagochi.app.GigagochiWebViewActivity"
if [[ "${launcher_activities}" != "${expected_launcher}" ]]; then
    fail "expected the sole launcher to be ${expected_launcher}; found: ${launcher_activities:-none}"
fi

bash "${repository_root}/scripts/verify-web-bundle-archive.sh" \
    "${apk_path}" \
    "${web_bundle_directory}"

"${apkanalyzer_path}" dex packages \
    --defined-only \
    --proguard-folder "${mapping_directory}" \
    "${apk_path}" > "${class_dump}"

for forbidden_symbol in \
    'androidx.compose' \
    'androidx.activity.compose' \
    'dev.chrisbanes.haze' \
    'com.gigagochi.app.MainActivity' \
    'CreatePetScreenKt' \
    'DashboardScreenKt' \
    'EventHistoryScreenKt' \
    'InteractiveTravelStoryScreenKt' \
    'TravelEntryScreenKt' \
    'ScheduledStoryRouteKt' \
    'LoopingStoryMediaKt' \
    'NotificationPermissionRequestKt' \
    'DebugMenuHostKt'
do
    if grep -Fq "${forbidden_symbol}" "${class_dump}"; then
        grep -F -m 5 "${forbidden_symbol}" "${class_dump}" >&2
        fail "forbidden native UI symbol survived R8: ${forbidden_symbol}"
    fi
done

for required_symbol in \
    'com.gigagochi.app.GigagochiWebViewActivity' \
    'com.gigagochi.app.core.background.CreateSyncWorker' \
    'com.gigagochi.app.core.background.GigagochiSyncWorker' \
    'com.gigagochi.app.core.database.GigagochiDatabase_Impl'
do
    if ! grep -Fq "${required_symbol}" "${class_dump}"; then
        fail "required runtime symbol is missing: ${required_symbol}"
    fi
done

if [[ "${verification_mode}" == "structural-only" ]]; then
    printf 'APK structural verification passed (signature, certificate, applicationId and version were NOT checked): %s\n' \
        "${apk_path}"
else
    printf 'signed release APK verification passed: %s\n' "${apk_path}"
fi
