#!/usr/bin/env bash
set -euo pipefail
src=$1
dest=$2
tools="$ANDROID_HOME/build-tools/36.0.0"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$tmp/release.jks"
mkdir -p "$(dirname "$dest")"
"$tools/zipalign" -P 16 -f 4 "$src" "$tmp/aligned.apk"
"$tools/apksigner" sign --v4-signing-enabled false --ks "$tmp/release.jks" --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
  --out "$dest" "$tmp/aligned.apk"
"$tools/apksigner" verify "$dest"
(cd "$(dirname "$dest")" && sha256sum "$(basename "$dest")" > "$(basename "$dest").sha256")
