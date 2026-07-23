#!/usr/bin/env bash
set -euo pipefail

repository_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ "$#" -lt 1 || "$#" -gt 2 ]]; then
    printf 'APK structural verification failed: usage: %s APK_PATH [R8_MAPPING_DIRECTORY]\n' \
        "$0" >&2
    exit 1
fi

exec bash "${repository_root}/scripts/verify-release-apk.sh" \
    --structural-only "$@"
