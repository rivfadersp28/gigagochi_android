#!/usr/bin/env bash
set -euo pipefail

repository_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
bundle_directory="${1:-${repository_root}/app/build/generated/webAssets/web}"

node "${repository_root}/scripts/web-bundle-manifest.mjs" verify-contracts
node "${repository_root}/scripts/check-bridge-command-contract.mjs"
node "${repository_root}/scripts/check-typography-contract.mjs"

(
    cd "${repository_root}/web"
    npm run build -- --outDir "${bundle_directory}"
)

node "${repository_root}/scripts/web-bundle-manifest.mjs" generate \
    --bundle-dir "${bundle_directory}"
