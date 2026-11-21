# xsystem4-android-dohnadohna

An Android (`arm64-v8a`) port of [xsystem4](https://github.com/nunuhara/xsystem4) with AIN v14 support targeting *Dohna Dohna*.

This repository combines the Android frontend and NDK build system from [kichikuou/xsystem4-android](https://github.com/kichikuou/xsystem4-android) with the AIN v14 engine and library work from [USADA-KARROT/xsystem4](https://github.com/USADA-KARROT/xsystem4) and [USADA-KARROT/libsys4](https://github.com/USADA-KARROT/libsys4), along with Android-specific fixes for OpenGL ES 3, ARM64 ABI compatibility, Shift-JIS/GBK dual encoding support, native video/audio playback, and touch input.

> No game assets or copyrighted files are included in this repository. You must provide your own copy of the game.

## Supported Environments

- **OS:** Android 5.0 (API 21) or later
- **ABI:** `arm64-v8a` only

## Setup & Usage

1. Install the APK (`xsystem4-arm64-*.apk`).
2. On first launch, grant storage access when prompted (`All files access` on Android 11+, or standard storage permissions on Android 6–10).
3. Place the unpacked game directory (containing `AliceStart.ini` or `System40.ini`) in one of the auto-scanned locations:
   - `/storage/emulated/0/<GameFolder>`
   - `/storage/emulated/0/Download/<GameFolder>`
   - `/storage/emulated/0/xsystem4/<GameFolder>`
4. If your game folder is stored elsewhere, tap the `+` button (or **Add Game Folder** in the menu) to select it using Android's folder picker.

## Touch Controls

| Action | Gesture / Input |
| ------ | --------------- |
| Left-click | Tap |
| Right-click | Two-finger tap, or tap outside the game viewport |
| Ctrl (skip) | Touch and hold |
| Mouse wheel scroll | Two-finger swipe up/down |
| Escape | Android Back button |

## Building

### Prerequisites

- Linux or macOS
- JDK 17
- Android SDK
- Android NDK (r23 or newer)
- CMake (3.21 or newer)
- Ninja
- `flex` and `bison`

### Build Steps

1. Set `ANDROID_SDK_ROOT` and `ANDROID_NDK_HOME`:
   ```sh
   export ANDROID_SDK_ROOT=/path/to/android/sdk
   export ANDROID_NDK_HOME=/path/to/android/ndk
   ```
2. Build the native shared libraries (`arm64-v8a`) and collect third-party licenses:
   ```sh
   ./build-shared-libs.sh
   ```
3. Build the APK with Gradle:
   ```sh
   cd project
   ./gradlew assembleDebug
   ```
   For a signed release build, configure `keystore.jks` and the corresponding environment variables (`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and run `./gradlew assembleRelease`.

CI builds are configured in `.github/workflows/build.yml` and publish the resulting `xsystem4-arm64-apk` artifact on each push.

## Credits

This project builds directly on the work of the following repositories and authors:

- **[kichikuou/xsystem4-android](https://github.com/kichikuou/xsystem4-android)** by **kichikuou** — Android launcher, `XSystem4Activity`, SDL2/NDK integration, CMake/Gradle build setup, and the stripped [kichikuou/libsndfile](https://github.com/kichikuou/libsndfile) fork.
- **[USADA-KARROT/xsystem4](https://github.com/USADA-KARROT/xsystem4)** by **USADA-KARROT** (`wip/post-checkpoint-2026-07-06`, base commit `8afc86b`) — AIN v14 VM, PartsEngine v14, `.pactex` loading, HLL implementations, audio mixer, and reverse-engineering work for *Dohna Dohna*.
- **[USADA-KARROT/libsys4](https://github.com/USADA-KARROT/libsys4)** by **USADA-KARROT** (`cn-on-upstream`, base commit `247f544`) — AIN v14 format and string/file support library.
- **[nunuhara/xsystem4](https://github.com/nunuhara/xsystem4)** and **[nunuhara/libsys4](https://github.com/nunuhara/libsys4)** by **Nunuhara Cabbage** — original authors of the open-source System 4 engine and library.

### Third-Party Libraries & Fonts

SDL2, FreeType, cglm, GNU libiconv, libffi, libsndfile, libogg, libvorbis, libpng, libjpeg-turbo, libwebp, VL-Gothic-Regular, and HanaMinA. Complete license texts are bundled in the APK under **Open source licenses**.

## License

`xsystem4` and `libsys4` are licensed under the GNU General Public License v2.0 or later (see `xsystem4/COPYING` and `xsystem4/subprojects/libsys4/COPYING`). Third-party components retain their respective licenses.
