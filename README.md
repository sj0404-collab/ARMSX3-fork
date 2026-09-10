XENO
=====

A PS3 emulator for ARM64 Android (fork of ARMSX3, itself RPCS3 with the recent
ARM64 improvements). Ships as the **XENO** Android app with a dark
purple/green energy theme, its own boot sting, launcher icon, and wordmark —
everything else keeps the `com.armsx3` package id and the ARMSX3 identifiers
underneath.

Highlights
----------

* Latest RPCS3 upstream code (recent ARM64 improvements included).
* ARM64 SPU/PPU tuning: GBH/GBB byte-gather paths for i8mm/dotprod cores.
  If a title ever regresses to `STOP 0x0`, set `RPCSX_DISABLE_SPU_BYTE_GATHER=1`
  to fall back to the scalar path at runtime — no rebuild needed.
* Core performance presets (Balanced / Performance / Maximum). They now report
  honestly: any rejected setting is counted and surfaced in the UI instead of
  being silently swallowed.
* Cloud save + game sync over WebDAV (`CloudSync`): per-title save archives plus
  streamed game downloads. Hardened against zip-slip, connection leaks and
  settings races; oversized archives are skipped, never truncated. No server of
  your own? One-tap presets fill the address for the free WebDAV tiers (pCloud,
  Koofr, Mail.ru Cloud, Yandex Disk, Nextcloud) — you only add your account
  login. Google Drive / Dropbox / GoFile are API-only and need developer app
  keys, so they are intentionally not offered.
* XENO branding (this branch): new launcher icon set (square + round, all
  densities), in-app mark, notification icon, boot intro sting (1080x1080/30fps
  h264+aac, same specs `BootSplashActivity` expects) and a recoloured library
  fallback background GIF. All resource-only — no source changes.

Building
--------

 arm64-v8a and armv8.2 is supported. You need the Android SDK with NDK r27 or newer,
CMake 3.30 or newer, and a JDK 17. Android Studio ships all of these.

Clone with submodules, then fetch the two third party checkouts that are not
submodules:

    git clone --recursive https://github.com/sj0404-collab/xeno.git
    cd xeno
    git clone https://github.com/SnowflakePowered/librashader 3rdparty/librashader
    git clone https://github.com/bylaws/libadrenotools android/armsx3-ui/app/src/main/cpp/libadrenotools

Build the core. This is the long part and produces an unstripped library of
around 1.3 GB:

    export ANDROID_HOME=$HOME/Library/Android/sdk
    cmake -B build-android -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/<version>/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 \
      -DCMAKE_BUILD_TYPE=RelWithDebInfo
    cmake --build build-android --target rpcsx-android -j8

Strip it and put it where the app expects it:

    llvm-strip --strip-unneeded build-android/android/libarmsx3-core.so
    cp build-android/android/libarmsx3-core.so \
       android/armsx3-ui/app/src/main/jniLibs/arm64-v8a/

Then build the app:

    cd android/armsx3-ui
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    ./gradlew :app:assembleRelease

The apk lands in app/build/outputs/apk/release/.

Note that the core library has to be rebuilt and copied again whenever anything
under rpcs3/ or android/src/ changes. Gradle does not build it for you.

The Discord Social SDK is proprietary and is not redistributed here. Get it from
Discord's developer portal and drop it in app/libs/ and
app/src/main/cpp/discord_sdk/ if you want that feature. The build skips it
otherwise.

Running it needs PS3 firmware, which is not included.

License
-------

GPL-2.0-only, the same as RPCS3. See LICENSE. Some files may be licensed
differently, check the file headers.

Based on RPCS3, https://github.com/RPCS3/rpcs3 — upstream ARMSX3 lives at
https://github.com/ARMSX2/ARMSX3