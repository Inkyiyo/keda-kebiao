#!/usr/bin/env bash
# 不需要 Gradle：aapt2 编资源 → javac 编代码 → d8 转 dex → zipalign + apksigner 签名
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="${BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
PLATFORM="${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
MAIN="$ROOT/app/src/main"
STATIC="$ROOT/../static"
BUILD="$ROOT/build"
OUT="$ROOT/out"
KS="${HNUST_KEYSTORE:-$ROOT/../../.secrets/kebiao.jks}"
PASS="${HNUST_KS_PASS:-}"

if [ ! -f "$PLATFORM" ]; then
  echo "缺少 android.jar：$PLATFORM" >&2
  echo "请安装 Android SDK Platform 34 后重试。" >&2
  exit 1
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/obj" "$BUILD/dex" "$OUT"

echo "[1/6] 同步前端到 assets"
rm -rf "$MAIN/assets"
mkdir -p "$MAIN/assets"
cp "$STATIC"/*.css "$STATIC"/*.js "$STATIC"/*.png "$MAIN/assets/"
# App 内以 file:// 加载，绝对路径会失效，改成同级相对路径
sed 's#="/static/#="#g' "$STATIC/index.html" > "$MAIN/assets/index.html"
sed -i '/rel="manifest"/d' "$MAIN/assets/index.html"

echo "[2/6] 编译资源"
"$BT/aapt2" compile --dir "$MAIN/res" -o "$BUILD/res.zip"
"$BT/aapt2" link \
  -o "$BUILD/base.apk" \
  -I "$PLATFORM" \
  --manifest "$MAIN/AndroidManifest.xml" \
  -R "$BUILD/res.zip" \
  -A "$MAIN/assets" \
  --java "$BUILD/gen" \
  --auto-add-overlay

echo "[3/6] 编译 Java"
find "$BUILD/gen" "$MAIN/java" -name '*.java' > "$BUILD/sources.txt"
javac -nowarn -encoding UTF-8 -source 11 -target 11 \
  -classpath "$PLATFORM" \
  -d "$BUILD/obj" @"$BUILD/sources.txt"

echo "[4/6] 转成 dex"
find "$BUILD/obj" -name '*.class' > "$BUILD/classes.txt"
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" \
  --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "[5/6] 打包对齐"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q "$BUILD/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "[6/6] 签名"
# 签名密钥不进仓库：自己生成一次，口令用环境变量传，别写进脚本
if [ ! -f "$KS" ]; then
  echo "缺少签名密钥：$KS" >&2
  echo "先自建一个（只需一次）：" >&2
  echo "  keytool -genkeypair -keystore \"$KS\" -alias kebiao -keyalg RSA -keysize 2048 \\" >&2
  echo "    -validity 10000 -dname \"CN=Keda Kebiao, OU=Personal, C=CN\"" >&2
  echo "或用 HNUST_KEYSTORE 指向已有的 .jks。" >&2
  exit 1
fi
if [ -z "$PASS" ]; then
  echo "请用 HNUST_KS_PASS 提供 keystore 口令，例如：" >&2
  echo "  HNUST_KS_PASS=你的口令 ./build.sh" >&2
  exit 1
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias kebiao \
  --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/科大课表.apk" "$BUILD/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/科大课表.apk" | head -3

echo
ls -lh "$OUT/科大课表.apk"
