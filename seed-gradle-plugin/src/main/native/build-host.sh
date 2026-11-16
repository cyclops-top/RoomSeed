#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source_dir="$PWD"
work="$source_dir/../../../build/native-host"
mkdir -p "$work"
case "$(uname -s)/$(uname -m)" in
  Darwin/arm64) platform=macos-aarch64; filename=libroomseedicu.dylib; target=MacOSX ;;
  Darwin/x86_64) platform=macos-x86_64; filename=libroomseedicu.dylib; target=MacOSX ;;
  Linux/x86_64) platform=linux-x86_64; filename=libroomseedicu.so; target=Linux ;;
  Linux/aarch64) platform=linux-aarch64; filename=libroomseedicu.so; target=Linux ;;
  MINGW*/x86_64) platform=windows-x86_64; filename=roomseedicu.dll; target=MinGW ;;
  *) echo "Unsupported native build host" >&2; exit 1 ;;
esac
if [[ -z "${ICU_PREFIX:-}" ]]; then
  if [[ ! -d "$work/icu/.git" ]]; then
    git clone --depth 1 --branch release-78.3 https://github.com/unicode-org/icu.git "$work/icu"
  fi
  test "$(git -C "$work/icu" rev-parse HEAD)" = 21d1eb0f306e1141c10931e914dfc038c06121da
  # ICU 78.3 MinGW config overrides ICULIBS_* without the static "s" prefix,
  # so host tools incorrectly search for -licutu instead of -lsicutu.
  # Keep the upstream archive names and repair only the linker references.
  if [[ "$target" == MinGW ]]; then
    for config in "$work/icu/icu4c/source/config/mh-mingw" "$work/icu/icu4c/source/config/mh-mingw64"; do
      sed 's/-l$(ICUPREFIX)/-l$(STATIC_PREFIX_WHEN_USED)$(ICUPREFIX)/g' "$config" > "$config.roomseed"
      mv "$config.roomseed" "$config"
    done
  fi
  ICU_PREFIX="$work/install"
  mkdir -p "$work/icu-build"
  (
    cd "$work/icu-build"
    CFLAGS="-O2 -fPIC" CXXFLAGS="-O2 -fPIC" \
      "$work/icu/icu4c/source/runConfigureICU" "$target" --prefix="$ICU_PREFIX" \
      --enable-static --disable-shared --disable-tests --disable-samples
    make -j "${BUILD_JOBS:-4}"
    make install
  )
fi
grep -q 'U_ICU_VERSION "78.3"' "$ICU_PREFIX/include/unicode/uvernum.h"
if [[ -z "${SQLITE_INCLUDE:-}" ]]; then
  curl --fail --location --retry 3 https://www.sqlite.org/2025/sqlite-amalgamation-3500400.zip -o "$work/sqlite.zip"
  unzip -o -q "$work/sqlite.zip" -d "$work"
  SQLITE_INCLUDE="$work/sqlite-amalgamation-3500400"
fi
output="$source_dir/../resources/native/$platform"
mkdir -p "$output"
cc="${CC:-cc}"
flags=(-shared -fPIC)
libs=(-lpthread -ldl -lm -lstdc++)
if [[ "$target" == MacOSX ]]; then
  flags=(-dynamiclib)
  libs=(-lc++)
elif [[ "$target" == MinGW ]]; then
  flags=(-shared -static -static-libgcc -static-libstdc++)
  libs=(-lstdc++ -lm -ladvapi32)
fi
# ICU's MinGW build uses the Windows static-library names on some releases.
i18n="$ICU_PREFIX/lib/libicui18n.a"
uc="$ICU_PREFIX/lib/libicuuc.a"
data="$ICU_PREFIX/lib/libicudata.a"
if [[ "$target" == MinGW && ! -f "$i18n" ]]; then
  i18n="$ICU_PREFIX/lib/libsicuin.a"
  uc="$ICU_PREFIX/lib/libsicuuc.a"
  data="$ICU_PREFIX/lib/libsicudt.a"
fi
"$cc" "${flags[@]}" -O2 -DU_STATIC_IMPLEMENTATION -I"$SQLITE_INCLUDE" -I"$ICU_PREFIX/include" \
  "$source_dir/extension.c" "$i18n" \
  "$uc" "$data" \
  "${libs[@]}" -o "$output/$filename"
case "$target" in
  MacOSX) otool -L "$output/$filename" ;;
  Linux) ldd "$output/$filename" ;;
  MinGW) objdump -p "$output/$filename" | grep 'DLL Name' ;;
esac
printf 'Built %s\n' "$output/$filename"
