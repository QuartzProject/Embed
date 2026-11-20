#!/usr/bin/env bash
# Usage: fetch-quartz.sh <tag>
# Unpacks the Linux-x64 package of that Quartz release into .deps/quartz-dist and writes QUARTZ_DIST and QUARTZ_LIBRARY.
set -euo pipefail

tag="${1:?tag}"
mkdir -p .deps/download
gh release download "$tag" --repo QuartzProject/Quartz --pattern '*-Linux-x64.tar.xz' --dir .deps/download --clobber
rm -rf .deps/quartz-dist
mkdir -p .deps/quartz-dist
tar -xJf .deps/download/*-Linux-x64.tar.xz -C .deps/quartz-dist --strip-components=1
dist="$PWD/.deps/quartz-dist"
{
    echo "QUARTZ_DIST=$dist"
    echo "QUARTZ_LIBRARY=$(find "$dist/lib" -maxdepth 1 -name 'libquartz.so*' -type f | head -1)"
} >>"$GITHUB_ENV"
