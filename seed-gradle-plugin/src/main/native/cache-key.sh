#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Includes the pinned ICU revision and flags through build-host.sh's contents.
{
  uname -s
  uname -m
  printf '%s\n' "${ImageOS:-local}" "${ImageVersion:-local}"
  "${CC:-cc}" --version
  "${CXX:-c++}" --version
  cat build-host.sh cache-key.sh
} | if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi | awk '{print $1}'
