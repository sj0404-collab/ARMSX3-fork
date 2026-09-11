#!/usr/bin/env bash
# ARMSX3 Android arm64 configure.
#
# Configures from the REPO ROOT, not from android/ -- upstream RPCS3 assumes
# CMAKE_SOURCE_DIR is the repo root (FindWolfSSL.cmake, FindZLIB.cmake,
# 3rdparty/protobuf, 3rdparty/llvm all build paths off it).
#
# Usage:  android/configure.sh [extra cmake args...]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
# NDK 29 (clang 21), NOT NDK 28.2 (clang 19.0.1).
# Upstream's stated floor is clang-19 and 28.2 sits exactly on it, but clang
# 19.0.1 mis-analyses fmt::throw_exception -- that is a CTAD struct whose
# constructor AND destructor are [[noreturn]], not a function -- so every switch
# default: that ends in it trips -Werror,-Wreturn-type. It killed 4 TUs in
# rpcs3_emu (SPUThread.{h,cpp}, SPULLVMRecompiler.cpp, SPUCommonRecompiler.cpp,
# lv2.cpp) at 2510/3123. Verified: all 4 compile with 0 errors under clang 21.
# Do NOT "fix" this with -Wno-error=return-type; the code is fine, the old
# compiler was not. rpcsx-ui-android pins the NDK 29 line for the same reason.
: "${NDK_VERSION:=29.0.14206865}"
: "${CMAKE_VERSION:=3.30.5}"
: "${ANDROID_API:=33}"          # keep in step with armsx3-ui minSdk
: "${BUILD_DIR:=$ROOT/build-android}"
# Size-reduction pass. 1 (default): compile every TU with function/data sections
# and let the final link --gc-sections drop dead code -- RPCS3 links the whole
# static LLVM in, and gc-sections is what stops that from shipping every symbol.
# Cost: no runtime speed change (sections + gc are link-time only), marginally
# longer links. 0 disables it for the upstream-flat behaviour.
: "${SIZE_OPT:=1}"

NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
CM="$ANDROID_HOME/cmake/$CMAKE_VERSION/bin"

[ -d "$NDK" ] || { echo "NDK not found: $NDK" >&2; exit 1; }
[ -x "$CM/cmake" ] || { echo "cmake not found: $CM/cmake" >&2; exit 1; }

# Size flags are appended before "$@" so an explicit override still wins.
size_flags=()
if [[ "$SIZE_OPT" != "0" ]]; then
	size_flags=(
		-DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections"
		-DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections"
		-DCMAKE_EXE_LINKER_FLAGS="-Wl,--gc-sections"
		-DCMAKE_SHARED_LINKER_FLAGS="-Wl,--gc-sections"
		-DCMAKE_MODULE_LINKER_FLAGS="-Wl,--gc-sections"
	)
fi

# Windows-host cross builds: LLVM's GetHostTriple.cmake returns an EMPTY
# LLVM_HOST_TRIPLE here. When MSVC / MinGW tests are false (NDK clang on an
# Android target) it falls into the config.guess branch, which is guarded by
# "CMAKE_HOST_SYSTEM_NAME STREQUAL Windows AND NOT MSYS" and only prints a
# warning -- so the cache variable stays "", llvm-config.h never emits the
# #cmakedefine, and every TU including llvm/lib/TargetParser/Host.cpp dies
# with "use of undeclared identifier 'LLVM_HOST_TRIPLE'". Pin the device
# triple explicitly so the define is always generated on CI.
llvm_host_triple=()
if [[ "${RUNNER_OS:-}" == "Windows" ]]; then
	llvm_host_triple=("-DLLVM_HOST_TRIPLE=aarch64-none-linux-android${ANDROID_API}")
fi

exec "$CM/cmake" -S "$ROOT" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM="android-$ANDROID_API" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_MAKE_PROGRAM="$CM/ninja" \
  `# wrong-for-cross-compile upstream defaults` \
  -DUSE_NATIVE_INSTRUCTIONS=OFF \
  -DUSE_SDL=OFF \
  -DUSE_SYSTEM_SDL=OFF \
  -DUSE_GAMEMODE=OFF \
  `# no system libs inside the NDK sysroot` \
  -DUSE_SYSTEM_LIBUSB=OFF \
  -DUSE_SYSTEM_CURL=OFF \
  -DUSE_SYSTEM_OPENCV=OFF \
  -DUSE_SYSTEM_FFMPEG=OFF \
  -DUSE_SYSTEM_ZLIB=ON \
  `# desktop-only features` \
  -DUSE_DISCORD_RPC=OFF \
  -DUSE_FAUDIO=OFF \
  -DUSE_LIBEVDEV=OFF \
  `# LLVM 22 from the pinned submodule, statically linked` \
  -DWITH_LLVM=ON \
  -DBUILD_LLVM=ON \
  -DSTATIC_LINK_LLVM=ON \
  `# LTO off for bring-up: large link RAM/disk cost, no benefit while iterating` \
  -DUSE_LTO=OFF \
  -DASMJIT_NO_SHM_OPEN=ON \
  "${size_flags[@]}" \
  "${llvm_host_triple[@]}" \
  "$@"
