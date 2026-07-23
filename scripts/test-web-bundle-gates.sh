#!/usr/bin/env bash
set -euo pipefail

repository_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
bundle_directory="${1:-${repository_root}/app/build/generated/webAssets/web}"

fail() {
    printf 'Web bundle gate test failed: %s\n' "$1" >&2
    exit 1
}

for required_tool in zip unzip zipinfo find sort cp node npm; do
    if ! command -v "${required_tool}" >/dev/null 2>&1; then
        fail "required Unix test tool was not found: ${required_tool}"
    fi
done
if [[ ! -f "${bundle_directory}/web-bundle-manifest.json" ]]; then
    fail "generated bundle is missing; run :app:buildWebBundle first"
fi

if ! node - "${bundle_directory}/index.html" <<'NODE'
const fs = require("node:fs");

const html = fs.readFileSync(process.argv[2], "utf8");
const linkTags = html.match(/<link\b[^>]*>/gis) ?? [];
const readAttribute = (tag, name) => {
  const match = tag.match(new RegExp(`\\b${name}\\s*=\\s*(["'])(.*?)\\1`, "is"));
  return match?.[2] ?? null;
};
const iconTags = linkTags.filter((tag) =>
  (readAttribute(tag, "rel") ?? "")
    .toLowerCase()
    .split(/\s+/)
    .includes("icon"),
);

if (iconTags.length !== 1) {
  process.exit(1);
}
const href = readAttribute(iconTags[0], "href");
const prefix = "data:image/png;base64,";
if (href === null || !href.startsWith(prefix)) {
  process.exit(1);
}
const decoded = Buffer.from(href.slice(prefix.length), "base64");
const pngSignature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
if (decoded.length <= pngSignature.length || !decoded.subarray(0, 8).equals(pngSignature)) {
  process.exit(1);
}
NODE
then
    fail "generated index must embed an offline favicon data URL"
fi

test_directory="$(mktemp -d "${TMPDIR:-/tmp}/gigagochi-build-gates.XXXXXX")"
trap 'rm -rf -- "${test_directory}"' EXIT

archive_from_directory() {
    source_directory="$1"
    destination_archive="$2"
    (
        cd "${source_directory}"
        find assets/web -type f -print | LC_ALL=C sort | zip -q "${destination_archive}" -@
    )
}

expect_archive_failure() {
    expected_message="$1"
    archive_path="$2"
    extraction_path="$3"
    output_path="$4"
    mkdir -p "${extraction_path}"
    if bash "${repository_root}/scripts/verify-web-bundle-archive.sh" \
        "${archive_path}" "${extraction_path}" > "${output_path}" 2>&1
    then
        fail "corrupt archive unexpectedly passed: ${archive_path}"
    fi
    if ! grep -Fq "${expected_message}" "${output_path}"; then
        sed -n '1,20p' "${output_path}" >&2
        fail "corrupt archive failed for the wrong reason"
    fi
}

valid_source="${test_directory}/valid-source"
mkdir -p "${valid_source}/assets/web"
cp -R "${bundle_directory}/." "${valid_source}/assets/web/"
valid_archive="${test_directory}/valid.zip"
archive_from_directory "${valid_source}" "${valid_archive}"
mkdir "${test_directory}/valid-extracted"
bash "${repository_root}/scripts/verify-web-bundle-archive.sh" \
    "${valid_archive}" "${test_directory}/valid-extracted" >/dev/null

corrupt_source="${test_directory}/corrupt-source"
cp -R "${valid_source}" "${corrupt_source}"
corrupt_file="$(find "${corrupt_source}/assets/web/assets" -type f -print -quit)"
if [[ -z "${corrupt_file}" ]]; then
    fail "fixture has no bundled asset to corrupt"
fi
printf '\ncorrupt-fixture\n' >> "${corrupt_file}"
corrupt_archive="${test_directory}/corrupt.zip"
archive_from_directory "${corrupt_source}" "${corrupt_archive}"
expect_archive_failure \
    'bundle manifest does not match bridge contracts or packaged files' \
    "${corrupt_archive}" \
    "${test_directory}/corrupt-extracted" \
    "${test_directory}/corrupt-output.txt"

symlink_source="${test_directory}/symlink-source"
cp -R "${valid_source}" "${symlink_source}"
ln -s index.html "${symlink_source}/assets/web/forbidden-link"
symlink_archive="${test_directory}/symlink.zip"
(
    cd "${symlink_source}"
    find assets/web \( -type f -o -type l \) -print | \
        LC_ALL=C sort | zip -y -q "${symlink_archive}" -@
)
expect_archive_failure \
    'non-regular or symlink entries are forbidden under assets/web' \
    "${symlink_archive}" \
    "${test_directory}/symlink-extracted" \
    "${test_directory}/symlink-output.txt"

if command -v python3 >/dev/null 2>&1; then
    duplicate_archive="${test_directory}/duplicate.zip"
    python3 - "${valid_archive}" "${duplicate_archive}" <<'PYTHON'
import sys
import warnings
import zipfile

source_path, destination_path = sys.argv[1:]
with zipfile.ZipFile(source_path, "r") as source:
    entries = [(entry, source.read(entry.filename)) for entry in source.infolist()]
with warnings.catch_warnings():
    warnings.simplefilter("ignore", UserWarning)
    with zipfile.ZipFile(destination_path, "w") as destination:
        for entry, content in entries:
            destination.writestr(entry, content)
        destination.writestr(entries[0][0], entries[0][1])
PYTHON
    expect_archive_failure \
        'duplicate ZIP entry names are forbidden' \
        "${duplicate_archive}" \
        "${test_directory}/duplicate-extracted" \
        "${test_directory}/duplicate-output.txt"
fi

lock_mismatch_directory="${test_directory}/lock-mismatch"
mkdir "${lock_mismatch_directory}"
mkdir "${lock_mismatch_directory}/dependency"
cat > "${lock_mismatch_directory}/package.json" <<'JSON'
{
  "name": "lock-mismatch-fixture",
  "version": "1.0.0",
  "dependencies": {
    "fixture-dependency": "file:dependency"
  }
}
JSON
cat > "${lock_mismatch_directory}/dependency/package.json" <<'JSON'
{
  "name": "fixture-dependency",
  "version": "1.0.0"
}
JSON
cat > "${lock_mismatch_directory}/package-lock.json" <<'JSON'
{
  "name": "lock-mismatch-fixture",
  "version": "1.0.0",
  "lockfileVersion": 3,
  "requires": true,
  "packages": {
    "": {
      "name": "lock-mismatch-fixture",
      "version": "1.0.0"
    }
  }
}
JSON
if bash "${repository_root}/scripts/install-web-dependencies.sh" \
    "${lock_mismatch_directory}" > "${test_directory}/lock-mismatch-output.txt" 2>&1
then
    fail "npm ci unexpectedly accepted a package-lock mismatch"
fi
if ! grep -Fq 'package.json' "${test_directory}/lock-mismatch-output.txt"; then
    sed -n '1,20p' "${test_directory}/lock-mismatch-output.txt" >&2
    fail "npm ci lock mismatch failed for the wrong reason"
fi

contract_fixture_root="${test_directory}/contract-fixture"
while IFS= read -r contract_source; do
    if [[ -z "${contract_source}" || "${contract_source}" == \#* ]]; then
        continue
    fi
    mkdir -p "${contract_fixture_root}/$(dirname "${contract_source}")"
    cp "${repository_root}/${contract_source}" \
        "${contract_fixture_root}/${contract_source}"
done < "${repository_root}/scripts/wire-contract-sources.txt"

contract_tool_arguments=(
    --source-root "${contract_fixture_root}"
    --contract-list "${repository_root}/scripts/wire-contract-sources.txt"
    --kotlin "${contract_fixture_root}/app/src/main/java/com/gigagochi/app/core/webview/BridgeModels.kt"
    --typescript "${contract_fixture_root}/web/src/contracts.ts"
)
node "${repository_root}/scripts/web-bundle-manifest.mjs" verify-contracts \
    "${contract_tool_arguments[@]}" >/dev/null

stale_contract_root="${test_directory}/stale-contract"
cp -R "${contract_fixture_root}" "${stale_contract_root}"
printf '\n// stale wire change fixture\n' >> \
    "${stale_contract_root}/app/src/main/java/com/gigagochi/app/core/webview/WebFeedback.kt"
stale_contract_arguments=(
    --source-root "${stale_contract_root}"
    --contract-list "${repository_root}/scripts/wire-contract-sources.txt"
    --kotlin "${stale_contract_root}/app/src/main/java/com/gigagochi/app/core/webview/BridgeModels.kt"
    --typescript "${stale_contract_root}/web/src/contracts.ts"
)
if node "${repository_root}/scripts/web-bundle-manifest.mjs" verify-contracts \
    "${stale_contract_arguments[@]}" > "${test_directory}/stale-contract-output.txt" 2>&1
then
    fail "stale schema hash unexpectedly accepted a changed wire source"
fi
if ! grep -Fq 'bridge schema hash is stale' \
    "${test_directory}/stale-contract-output.txt"
then
    sed -n '1,20p' "${test_directory}/stale-contract-output.txt" >&2
    fail "changed wire source failed for the wrong reason"
fi

comment_contract_root="${test_directory}/comment-contract"
cp -R "${contract_fixture_root}" "${comment_contract_root}"
cat >> "${comment_contract_root}/app/src/main/java/com/gigagochi/app/core/webview/BridgeModels.kt" <<'KOTLIN'

/* A declaration-looking line inside a block comment is not a duplicate.
internal const val BridgeProtocolVersion = 999
*/
KOTLIN
comment_contract_arguments=(
    --source-root "${comment_contract_root}"
    --contract-list "${repository_root}/scripts/wire-contract-sources.txt"
    --kotlin "${comment_contract_root}/app/src/main/java/com/gigagochi/app/core/webview/BridgeModels.kt"
    --typescript "${comment_contract_root}/web/src/contracts.ts"
)
comment_schema_output="$(node "${repository_root}/scripts/web-bundle-manifest.mjs" schema-hash \
    "${comment_contract_arguments[@]}")"
comment_expected_schema="$(printf '%s\n' "${comment_schema_output}" | \
    sed -n 's/^expectedSchemaHash=//p')"
if [[ -z "${comment_expected_schema}" ]]; then
    fail "schema-hash command did not print expectedSchemaHash"
fi
sed -E \
    's|^([[:space:]]*internal const val BridgeSchemaHash = ).*$|\1"'"${comment_expected_schema}"'"|' \
    "${comment_contract_root}/app/src/main/java/com/gigagochi/app/core/webview/BridgeModels.kt" \
    > "${test_directory}/BridgeModels.updated.kt"
mv "${test_directory}/BridgeModels.updated.kt" \
    "${comment_contract_root}/app/src/main/java/com/gigagochi/app/core/webview/BridgeModels.kt"
sed -E \
    's|^(export const BRIDGE_SCHEMA_HASH = ).*;[[:space:]]*$|\1"'"${comment_expected_schema}"'";|' \
    "${comment_contract_root}/web/src/contracts.ts" \
    > "${test_directory}/contracts.updated.ts"
mv "${test_directory}/contracts.updated.ts" \
    "${comment_contract_root}/web/src/contracts.ts"
node "${repository_root}/scripts/web-bundle-manifest.mjs" verify-contracts \
    "${comment_contract_arguments[@]}" >/dev/null

crlf_contract_root="${test_directory}/crlf-contract"
cp -R "${contract_fixture_root}" "${crlf_contract_root}"
node -e '
const fs = require("node:fs");
const file = process.argv[1];
const source = fs.readFileSync(file, "utf8").replace(/\r?\n/g, "\r\n");
fs.writeFileSync(file, source);
' "${crlf_contract_root}/web/src/EventStoryTypes.ts"
node "${repository_root}/scripts/web-bundle-manifest.mjs" verify-contracts \
    --source-root "${crlf_contract_root}" \
    --contract-list "${repository_root}/scripts/wire-contract-sources.txt" \
    --kotlin "${crlf_contract_root}/app/src/main/java/com/gigagochi/app/core/webview/BridgeModels.kt" \
    --typescript "${crlf_contract_root}/web/src/contracts.ts" >/dev/null

if bash "${repository_root}/scripts/verify-release-apk.sh" \
    > "${test_directory}/strict-no-apk-output.txt" 2>&1
then
    fail "strict release verifier unexpectedly accepted a missing APK argument"
fi
if ! grep -Fq 'signed release APK verification failed: usage:' \
    "${test_directory}/strict-no-apk-output.txt"
then
    fail "strict release verifier did not fail closed with explicit usage"
fi
if bash "${repository_root}/scripts/verify-apk-structure.sh" \
    > "${test_directory}/structural-no-apk-output.txt" 2>&1
then
    fail "structural verifier unexpectedly accepted a missing APK argument"
fi
if ! grep -Fq 'APK structural verification failed: usage:' \
    "${test_directory}/structural-no-apk-output.txt"
then
    fail "structural verifier did not identify itself clearly"
fi

printf 'Web bundle gate script tests passed\n'
