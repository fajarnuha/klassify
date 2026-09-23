#!/bin/sh
set -eu

case "$(uname -s)" in
  Linux) ;;
  *) echo "Klassify installer supports Linux only" >&2; exit 1 ;;
esac

case "$(uname -m)" in
  x86_64|amd64) arch=x64 ;;
  aarch64|arm64) arch=arm64 ;;
  *) echo "Unsupported Linux architecture" >&2; exit 1 ;;
esac

version=@VERSION@
archive="klassify-linux-$arch.tar.gz"
base_url=${KLASSIFY_BASE_URL:-"https://github.com/fajarnuha/klassify/releases/download/$version"}
install_dir=${KLASSIFY_INSTALL_DIR:-"$HOME/.local/bin"}
workdir=$(mktemp -d)
trap 'rm -rf -- "$workdir"' EXIT

curl -fsSL --retry 3 -o "$workdir/$archive" "$base_url/$archive"
curl -fsSL --retry 3 -o "$workdir/SHA256SUMS" "$base_url/SHA256SUMS"
checksum=$(awk -v file="$archive" '$2 == file { print $1 }' "$workdir/SHA256SUMS")
if [ -z "$checksum" ]; then
  echo "No checksum for $archive" >&2
  exit 1
fi
printf '%s  %s\n' "$checksum" "$workdir/$archive" | sha256sum -c - >/dev/null
tar -xzf "$workdir/$archive" -C "$workdir" klassify
install -d "$install_dir"
install -m 755 "$workdir/klassify" "$install_dir/klassify"
printf 'Installed klassify %s to %s/klassify\n' "$version" "$install_dir"
