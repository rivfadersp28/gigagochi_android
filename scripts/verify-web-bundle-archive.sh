#!/usr/bin/env bash
set -euo pipefail

repository_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

fail() {
    printf 'embedded Web bundle archive verification failed: %s\n' "$1" >&2
    exit 1
}

if [[ "$#" -ne 2 ]]; then
    fail "usage: $0 APK_OR_ZIP EMPTY_EXTRACTION_DIRECTORY"
fi
archive_path="$1"
extraction_directory="$2"

if [[ ! -f "${archive_path}" ]]; then
    fail "archive not found: ${archive_path}"
fi
if [[ ! -d "${extraction_directory}" ]]; then
    fail "extraction directory not found: ${extraction_directory}"
fi
if find "${extraction_directory}" -mindepth 1 -print -quit | grep -q .; then
    fail "extraction directory must be empty: ${extraction_directory}"
fi

for required_tool in unzip zipinfo sort uniq awk grep; do
    if ! command -v "${required_tool}" >/dev/null 2>&1; then
        fail "required Unix tool was not found: ${required_tool}"
    fi
done

entry_list="$(mktemp "${TMPDIR:-/tmp}/gigagochi-zip-entries.XXXXXX")"
metadata_dump="$(mktemp "${TMPDIR:-/tmp}/gigagochi-zip-metadata.XXXXXX")"
trap 'rm -f -- "${entry_list}" "${metadata_dump}"' EXIT

if ! unzip -Z1 "${archive_path}" > "${entry_list}"; then
    fail "cannot list archive entries"
fi
if ! zipinfo -l "${archive_path}" > "${metadata_dump}"; then
    fail "cannot read archive entry metadata"
fi

duplicate_names="$(LC_ALL=C sort "${entry_list}" | uniq -d)"
if [[ -n "${duplicate_names}" ]]; then
    printf '%s\n' "${duplicate_names}" >&2
    fail "duplicate ZIP entry names are forbidden"
fi

for required_asset in \
    'assets/web/index.html' \
    'assets/web/web-bundle-manifest.json'
do
    if ! grep -Fxq "${required_asset}" "${entry_list}"; then
        fail "required Web bundle asset is missing: ${required_asset}"
    fi
done
if ! grep -Eq '^assets/web/assets/.*[^/]$' "${entry_list}"; then
    fail "archive does not contain bundled files under assets/web/assets/"
fi

web_entry_count=0
while IFS= read -r bundle_entry; do
    web_entry_count=$((web_entry_count + 1))
    if [[ "${bundle_entry}" =~ [[:space:][:cntrl:]] ]]; then
        fail "whitespace/control characters are forbidden in Web bundle paths"
    fi
    case "${bundle_entry}" in
        *\\*|/*|*'/../'*|*'/./'*|*'/..'|*'/.'|*'//'*)
            fail "unsafe Web bundle path in archive: ${bundle_entry}"
            ;;
    esac
done < <(grep '^assets/web/' "${entry_list}")

metadata_summary="$(awk '
$10 ~ /^assets\/web\// {
    count += 1
    if (substr($1, 1, 1) != "-") {
        print $10 > "/dev/stderr"
        invalid += 1
    }
}
END { printf "%d:%d", count, invalid }
' "${metadata_dump}")"
metadata_count="${metadata_summary%%:*}"
non_regular_count="${metadata_summary##*:}"
if [[ "${metadata_count}" -ne "${web_entry_count}" ]]; then
    fail "ZIP metadata did not describe every assets/web entry"
fi
if [[ "${non_regular_count}" -ne 0 ]]; then
    fail "non-regular or symlink entries are forbidden under assets/web"
fi

if ! unzip -qq "${archive_path}" 'assets/web/*' -d "${extraction_directory}"; then
    fail "cannot extract the embedded Web bundle"
fi
bash "${repository_root}/scripts/verify-web-bundle.sh" \
    "${extraction_directory}/assets/web"

printf 'embedded Web bundle archive verification passed: %s\n' "${archive_path}"
