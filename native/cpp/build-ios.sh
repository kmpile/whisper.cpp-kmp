#!/bin/bash
set -e

# Builds the Apple static libraries used by the Kotlin/Native cinterop (iOS + macOS).
# whisper.cpp is built from the submodule via add_subdirectory (see CMakeLists.txt),
# producing separate static archives (whisper, ggml*). Kotlin/Native only links the
# single `libwhisperkmp.a` named in whisperkmp.def, so after each build we merge every
# static archive for that SDK into that one libwhisperkmp.a.
#
# Run on macOS, then build the :core apple targets.

cd "$(dirname "$0")"

build_target() {
  local SDK=$1        # iphoneos | iphonesimulator | macosx
  local ARCHS=$2
  local METAL=$3      # ON | OFF
  local BUILD_DIR=$4  # build-ios | build-macos
  local CONFIG=$5     # Xcode output dir: Release-iphoneos | Release-iphonesimulator | Release
  local IOS=$6        # yes (iOS cross-build) | no (native macOS)

  echo "==> Configuring $SDK ($ARCHS), Metal=$METAL"
  local args=(
    -GXcode
    -DWHISPERKMP_BUILD_APPLE=YES
    -DCMAKE_OSX_ARCHITECTURES="$ARCHS"
    -DCMAKE_OSX_SYSROOT="$SDK"
    -DCMAKE_XCODE_ATTRIBUTE_ONLY_ACTIVE_ARCH=NO
    -DBUILD_SHARED_LIBS=OFF
    -DWHISPER_BUILD_TESTS=OFF
    -DWHISPER_BUILD_EXAMPLES=OFF
    -DWHISPER_BUILD_SERVER=OFF
    -DWHISPER_CURL=OFF
    -DGGML_OPENMP=OFF
    -DGGML_METAL="$METAL"
    -DGGML_METAL_EMBED_LIBRARY="$METAL"
  )
  if [ "$IOS" = "yes" ]; then
    args+=(-DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0)
  else
    args+=(-DCMAKE_OSX_DEPLOYMENT_TARGET=12.0)
  fi
  cmake "${args[@]}" -B "$BUILD_DIR"

  echo "==> Building whisperkmp for $SDK"
  cmake --build "$BUILD_DIR" --config Release --target whisperkmp

  echo "==> Merging static archives for $SDK"
  local OUT="$BUILD_DIR/$CONFIG"
  libtool -static -o "$OUT/libwhisperkmp_full.a" \
    $(find "$BUILD_DIR" -path "*$CONFIG*" -name '*.a' ! -name 'libwhisperkmp_full.a')
  mv "$OUT/libwhisperkmp_full.a" "$OUT/libwhisperkmp.a"
  echo "==> Done: $OUT/libwhisperkmp.a"
}

rm -rf build-ios build-macos
build_target iphoneos        "arm64"        ON  build-ios   Release-iphoneos        yes
build_target iphonesimulator "arm64;x86_64" OFF build-ios   Release-iphonesimulator yes
build_target macosx          "arm64"        ON  build-macos Release                 no
