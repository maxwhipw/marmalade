#!/usr/bin/env bash
#
# Restore the binary assets that are kept out of git.
#
# Large binaries (sherpa-onnx .aar bundles, the prebuilt JNI .so libraries,
# and the STT / wake-word .onnx models) are not committed. `assets-manifest.json`
# in this module records each file's path, size and sha256; this script puts
# them back and verifies the hashes. It is idempotent — a file that is already
# present with the right hash is left alone.
#
# One of two sources must be selected explicitly — there is no default:
#
#   1. Local directory (offline). Point MARMALADE_ASSETS_DIR at a tree that
#      contains the same relative paths as the manifest:
#
#        MARMALADE_ASSETS_DIR=/path/to/assets ./scripts/fetch-assets.sh
#
#   2. Download. Each asset is fetched from
#      "$MARMALADE_ASSETS_BASE_URL/<asset>", where <asset> is the flattened
#      filename recorded in the manifest.
#
#        MARMALADE_ASSETS_BASE_URL=https://example.test/assets ./scripts/fetch-assets.sh
#
# Any hash mismatch is a hard failure.
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$MODULE_DIR/assets-manifest.json"

if [[ -z "${MARMALADE_ASSETS_DIR:-}" && -z "${MARMALADE_ASSETS_BASE_URL:-}" ]]; then
  echo "error: no asset source configured." >&2
  echo "  set MARMALADE_ASSETS_BASE_URL to the release-assets URL," >&2
  echo "  or MARMALADE_ASSETS_DIR to a local copy of the assets." >&2
  exit 1
fi

if [[ ! -f "$MANIFEST" ]]; then
  echo "error: manifest not found: $MANIFEST" >&2
  exit 1
fi

# path<TAB>asset<TAB>sha256, one per line.
read_manifest() {
  python3 -c '
import json, sys
with open(sys.argv[1]) as fh:
    manifest = json.load(fh)
for a in manifest["assets"]:
    print("\t".join([a["path"], a["asset"], a["sha256"]]))
' "$MANIFEST"
}

sha_of() {
  sha256sum "$1" | cut -d" " -f1
}

fetched=0
skipped=0

while IFS=$'\t' read -r path asset want; do
  dest="$MODULE_DIR/$path"

  if [[ -f "$dest" && "$(sha_of "$dest")" == "$want" ]]; then
    skipped=$((skipped + 1))
    continue
  fi

  mkdir -p "$(dirname "$dest")"

  if [[ -n "${MARMALADE_ASSETS_DIR:-}" ]]; then
    src="$MARMALADE_ASSETS_DIR/$path"
    if [[ ! -f "$src" ]]; then
      echo "error: missing source file: $src" >&2
      exit 1
    fi
    cp "$src" "$dest"
  else
    url="$MARMALADE_ASSETS_BASE_URL/$asset"
    echo "fetching $path"
    if ! curl -fsSL --retry 3 -o "$dest" "$url"; then
      echo "error: download failed: $url" >&2
      rm -f "$dest"
      exit 1
    fi
  fi

  got="$(sha_of "$dest")"
  if [[ "$got" != "$want" ]]; then
    echo "error: sha256 mismatch for $path" >&2
    echo "  expected $want" >&2
    echo "  got      $got" >&2
    exit 1
  fi
  fetched=$((fetched + 1))
done < <(read_manifest)

echo "assets ok: $fetched fetched, $skipped already present"
