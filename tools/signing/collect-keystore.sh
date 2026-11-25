#!/usr/bin/env bash
# 只读采集：把 v1.7 签名 key 的交接信息打成一个可回传的文本包。
# 除 keystore-handoff/ 目录外不写任何文件，不修改、不复制原始 keystore。
#
# 用法（在持有 v1.7 签名环境的机器上跑）：
#   bash tools/signing/collect-keystore.sh <keystore路径> <store密码> [key别名] [key密码]
# 例：
#   bash tools/signing/collect-keystore.sh /root/.android/debug.keystore android AndroidDebugKey android
#
# 可选：OLD_APK=/path/HJM-Keypad-1.7.apk 一起做证书对照（推荐，能直接判断 key 对不对）
#
# 产物：./keystore-handoff/
#   handoff.txt    证书指纹 + 校验结论（先看这个）
#   keystore.b64   整把 key 的 base64 单行文本，方便粘贴回传
#
# 验收标准：handoff.txt 里的 SHA-256 必须等于 GitHub 上 v1.7 安装包证书的指纹
#           1B:EA:E2:E9…:6F:AA（记作 1beae2e9…6faa）。不一致 = 拿错 key。
set -u

KS="${1:-}"
STOREPASS="${2:-}"
ALIAS="${3:-}"
KEYPASS="${4:-$STOREPASS}"
OUT="$(pwd)/keystore-handoff"
KEYTOOL="${KEYTOOL_BIN:-keytool}"
APKSIGNER="${APKSIGNER_BIN:-apksigner}"

die() { echo "ERROR: $*" >&2; exit 1; }

[ -n "$KS" ] || die "用法: $0 <keystore路径> <store密码> [key别名] [key密码]"
[ -f "$KS" ] || die "找不到 keystore: $KS"
command -v "$KEYTOOL" >/dev/null 2>&1 || die "$KEYTOOL 不可用，可设 KEYTOOL_BIN=/path/to/keytool"

mkdir -p "$OUT"
H="$OUT/handoff.txt"

{
  echo "# keystore 交接包"
  echo "生成时间: $(date -Is)"
  echo "主机: $(hostname 2>/dev/null || echo unknown)"
  echo "文件: $KS"
  echo "大小: $(wc -c < "$KS") 字节"
  echo "sha256(file): $(sha256sum "$KS" | awk '{print $1}')"
  echo "格式探测: $(file -b "$KS" 2>/dev/null || echo n/a)"
  echo
  echo "## 1) keytool 视角"
} > "$H"

if [ -n "$ALIAS" ]; then
  keytool -list -v -keystore "$KS" -storepass "$STOREPASS" -alias "$ALIAS" >> "$H" 2>&1 \
    || echo "!! keytool 读取失败（密码或别名不对）" >> "$H"
else
  keytool -list -v -keystore "$KS" -storepass "$STOREPASS" >> "$H" 2>&1 \
    || echo "!! keytool 读取失败（密码不对？）" >> "$H"
fi

{
  echo
  echo "## 2) 旧版 APK 证书对照"
} >> "$H"

if [ -n "${OLD_APK:-}" ] && [ -f "${OLD_APK}" ]; then
  if command -v "$APKSIGNER" >/dev/null 2>&1; then
    echo "### $(basename "$OLD_APK")" >> "$H"
    "$APKSIGNER" verify --print-certs "$OLD_APK" >> "$H" 2>&1 || echo "（该 APK 校验失败）" >> "$H"
  else
    echo "!! apksigner 不可用，可设 APKSIGNER_BIN=\$ANDROID_HOME/build-tools/34.0.0/apksigner" >> "$H"
  fi
else
  echo "未提供 OLD_APK，跳过。用法：OLD_APK=/path/HJM-Keypad-1.7.apk bash $0 …" >> "$H"
fi

{
  echo
  echo "## 3) 可回传的 base64"
  echo "（把 keystore.b64 整个内容发回，别名/密码另外单独发）"
} >> "$H"
base64 -w0 "$KS" > "$OUT/keystore.b64" 2>/dev/null || base64 "$KS" | tr -d '\n' > "$OUT/keystore.b64"
echo "base64 长度: $(wc -c < "$OUT/keystore.b64") 字符" >> "$H"
echo "base64 sha256: $(sha256sum "$OUT/keystore.b64" | awk '{print $1}')" >> "$H"

echo "== 完成 =="
echo "看这个: $H"
echo "要回传的: $OUT/keystore.b64 + 别名(${ALIAS:-<未指定>}) + store/key 密码"
echo
sed -n '1,200p' "$H"
