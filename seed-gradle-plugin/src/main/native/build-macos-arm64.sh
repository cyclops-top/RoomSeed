#!/bin/sh
set -eu
cd "$(dirname "$0")"
: "${ICU_PREFIX:=/opt/homebrew/opt/icu4c}"
test "$(uname -m)" = arm64
output=../resources/native/macos-aarch64/libroomseedicu.dylib
mkdir -p "$(dirname "$output")"
clang -dynamiclib -O2 -I"$ICU_PREFIX/include" extension.c \
  "$ICU_PREFIX/lib/libicui18n.a" "$ICU_PREFIX/lib/libicuuc.a" \
  "$ICU_PREFIX/lib/libicudata.a" -lc++ -o "$output"
otool -L "$output"
