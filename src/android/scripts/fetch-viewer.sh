#!/usr/bin/env bash
# Fetches draw.io's viewer-static.min.js (and VERSION) for the Android build without
# checking out the whole drawio submodule, which is large: only the commit the submodule
# points at, without file contents, then just those two files. They go in
# src/android/build/drawio/, which the build reads when the submodule isn't checked out.
set -euo pipefail

root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
out="$root/src/android/build/drawio"
commit="$(git -C "$root" ls-tree HEAD drawio | awk '{print $3}')"
url="$(git -C "$root" config -f .gitmodules submodule.drawio.url)"

if [[ -f "$out/COMMIT" && "$(cat "$out/COMMIT")" == "$commit" ]]; then
	echo "drawio viewer for $commit already fetched"
	exit 0
fi

echo "Fetching the viewer from $url at $commit"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git -C "$tmp" init -q
git -C "$tmp" fetch -q --depth 1 --filter=blob:none "$url" "$commit"
mkdir -p "$out"
git -C "$tmp" show FETCH_HEAD:src/main/webapp/js/viewer-static.min.js > "$out/viewer-static.min.js"
git -C "$tmp" show FETCH_HEAD:VERSION > "$out/VERSION"
echo "$commit" > "$out/COMMIT"
ls -l "$out"
