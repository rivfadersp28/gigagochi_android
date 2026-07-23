#!/usr/bin/env bash
set -euo pipefail

repository_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
web_directory="${1:-${repository_root}/web}"

case "$(uname -s)" in
    Darwin|Linux) ;;
    *)
        printf 'web dependency install failed: this build gate requires bash on macOS or Linux\n' >&2
        exit 1
        ;;
esac

if ! command -v node >/dev/null 2>&1; then
    printf 'web dependency install failed: node was not found in PATH\n' >&2
    exit 1
fi
if ! command -v npm >/dev/null 2>&1; then
    printf 'web dependency install failed: npm was not found in PATH\n' >&2
    exit 1
fi
if [[ ! -f "${web_directory}/package.json" || ! -f "${web_directory}/package-lock.json" ]]; then
    printf 'web dependency install failed: package.json/package-lock.json is missing in %s\n' \
        "${web_directory}" >&2
    exit 1
fi

(
    cd "${web_directory}"
    npm ci --no-audit --no-fund
)

if [[ ! -f "${web_directory}/node_modules/.package-lock.json" ]]; then
    printf 'web dependency install failed: npm ci did not produce node_modules/.package-lock.json\n' >&2
    exit 1
fi
