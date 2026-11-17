#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-/home/user/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/android-ndk-r26d}"
APP="$ROOT/android-standalone"
OUT="$ROOT/build/standalone"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$OUT" "$ROOT/dist"
rm -rf "$OUT"; mkdir -p "$OUT"

# 1. native libs: arm64-v8a + armeabi-v7a（32 位手机也能装）
cmake --build "$APP/build-android" --target agentllama -j 4
SO="$(find "$APP/build-android" -name 'libagentllama.so' | head -1)"
[ -n "$SO" ] || { echo "arm64 libagentllama.so 未找到"; exit 1; }
STRIP="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
"$STRIP" --strip-unneeded "$SO" || true

V7="$APP/build-android-v7a"
if [ ! -f "$V7/CMakeCache.txt" ]; then
  cmake -S "$APP" -B "$V7" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-23 -DCMAKE_BUILD_TYPE=Release \
    -DGGML_NATIVE=OFF -DGGML_LLAMAFILE=OFF -DCMAKE_C_FLAGS="-mfpu=neon" \
    -DCMAKE_CXX_FLAGS="-mfpu=neon" -DLLAMA_CURL=OFF -DGGML_OPENMP=OFF
fi
cmake --build "$V7" --target agentllama -j 4
SO_V7="$(find "$V7" -name 'libagentllama.so' | head -1)"
[ -n "$SO_V7" ] || { echo "armeabi-v7a libagentllama.so 未找到"; exit 1; }
"$STRIP" --strip-unneeded "$SO_V7" || true

# 2. resources
"$BT/aapt2" compile --dir "$APP/app/src/main/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" \
  --manifest "$APP/app/src/main/AndroidManifest.xml" \
  -A "$APP/app/src/main/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 23 --target-sdk-version 34 \
  "$OUT/res.zip"

# 3. java + dex
mkdir -p "$OUT/classes" "$OUT/dex"
find "$APP/app/src/main/java" -name '*.java' > "$OUT/sources.txt"
echo "$OUT/gen/com/etwqfd/agentetc/R.java" >> "$OUT/sources.txt"
javac --release 8 -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"
find "$OUT/classes" -name '*.class' -print0 | xargs -0 "$BT/d8" --release --min-api 23 --lib "$PLATFORM" --output "$OUT/dex"

cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex )
mkdir -p "$OUT/lib/arm64-v8a" "$OUT/lib/armeabi-v7a"
cp "$SO" "$OUT/lib/arm64-v8a/libagentllama.so"
cp "$SO_V7" "$OUT/lib/armeabi-v7a/libagentllama.so"
# 把 Termux 关键 32 位运行库同时放进 nativeLibraryDir 作为兜底，
# 防止私有 lib 目录被覆盖安装/系统清理后出现 not found。
TMP_LIB="$OUT/termux-libs"; mkdir -p "$TMP_LIB"
for n in libandroid-support.so libiconv.so libreadline.so.8.2; do
  unzip -p "$APP/app/src/main/assets/termux-arm.zip" "lib/$n" > "$TMP_LIB/$n"
done
cp "$TMP_LIB/libandroid-support.so" "$OUT/lib/armeabi-v7a/libandroid-support.so"
cp "$TMP_LIB/libiconv.so" "$OUT/lib/armeabi-v7a/libiconv.so"
cp "$TMP_LIB/libreadline.so.8.2" "$OUT/lib/armeabi-v7a/libreadline.so"
( cd "$OUT" && zip -q -0 unsigned.apk lib/arm64-v8a/libagentllama.so lib/armeabi-v7a/libagentllama.so lib/armeabi-v7a/libandroid-support.so lib/armeabi-v7a/libiconv.so lib/armeabi-v7a/libreadline.so )

# 4. align + sign
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign \
  --ks "$ROOT/certs/ETC-CodeSigning.pfx" --ks-type PKCS12 \
  --ks-pass pass:ETC2026-agent-etc --key-pass pass:ETC2026-agent-etc \
  --ks-key-alias "etc code signing" \
  --out "$ROOT/dist/deepseek-mobile-2.0.0-android.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$ROOT/dist/deepseek-mobile-2.0.0-android.apk" | head -4
ls -lh "$ROOT/dist/deepseek-mobile-2.0.0-android.apk"
