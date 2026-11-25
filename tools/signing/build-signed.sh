#!/usr/bin/env bash
# 一条命令出「可覆盖升级」的发布包：编译 → zipalign → 签名 → 打印证书指纹与 sha256。
# 前提：仓库根目录有 keystore.properties（见 keystore.properties.example）。
#
# 用法：
#   bash tools/signing/build-signed.sh 1.8.2 13
#     $1=versionName  $2=versionCode（会临时写入 build.gradle.kts，build 完还原）
#
# 需要环境变量：JAVA_HOME、ANDROID_HOME（或 ANDROID_SDK_ROOT）、GRADLE_BIN（默认 gradle）
set -euo pipefail

VNAME="${1:?用法: $0 <versionName> <versionCode>}"
VCODE="${2:?用法: $0 <versionName> <versionCode>}"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

GRADLE="${GRADLE_BIN:-gradle}"
BT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/build-tools/${BUILD_TOOLS_VERSION:-34.0.0}"
[ -x "$BT/apksigner" ] || { echo "找不到 $BT/apksigner，检查 ANDROID_HOME"; exit 1; }
[ -f keystore.properties ] || { echo "缺少 keystore.properties（参照 keystore.properties.example）"; exit 1; }

GRADLE_FILE="app/build.gradle.kts"
BAK="$(mktemp)"
cp "$GRADLE_FILE" "$BAK"
restore() { cp "$BAK" "$GRADLE_FILE"; rm -f "$BAK"; }
trap restore EXIT

sed -i -E "s/versionCode = [0-9]+/versionCode = ${VCODE}/" "$GRADLE_FILE"
sed -i -E "s/versionName = \"[^\"]+\"/versionName = \"${VNAME}\"/" "$GRADLE_FILE"
echo "临时改为 versionName=${VNAME} versionCode=${VCODE}"
grep -E 'version(Code|Name)' "$GRADLE_FILE"

"$GRADLE" :app:assembleRelease --no-daemon

RAW="app/build/outputs/apk/release/app-release.apk"
OUTDIR="dist"; mkdir -p "$OUTDIR"
NAME="HJM-Keypad-${VNAME}.apk"

# 已由 signingConfig 签过名，这里只对齐；v1+v2+v3 由 apksigner 补齐（signingConfig 已开启 v1/v2/v3）
"$BT/zipalign" -p -f 4 "$RAW" "$OUTDIR/$NAME"

echo "== 证书 =="
"$BT/apksigner" verify --print-certs "$OUTDIR/$NAME"
echo "== 校验 =="
"$BT/apksigner" verify -v "$OUTDIR/$NAME" | head -6
"$BT/apksigner" verify --print-certs "$OUTDIR/$NAME" \
  | grep -i 'SHA-256 digest' | head -1
echo "sha256(file): $(sha256sum "$OUTDIR/$NAME" | awk '{print $1}')"
echo "产物: $OUTDIR/$NAME  ($(wc -c < "$OUTDIR/$NAME") 字节)"
echo
echo "对照：v1.7 发布包证书应为 1beae2e9…6faa。若上一行不是它，说明 keystore.properties 指错了 key。"
