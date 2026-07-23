#!/usr/bin/env bash
set -euo pipefail

repository_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
bundle_directory="${1:-${repository_root}/app/build/generated/webAssets/web}"
stamp_path="${2:-}"

arguments=(
    verify
    --bundle-dir "${bundle_directory}"
)
if [[ -n "${stamp_path}" ]]; then
    arguments+=(--stamp "${stamp_path}")
fi

exec node "${repository_root}/scripts/web-bundle-manifest.mjs" "${arguments[@]}"
