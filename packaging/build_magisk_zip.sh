#!/usr/bin/env bash
#
# 打包 batt_design_override Magisk 模块 ZIP
# 解决之前 CI 失败原因: 行中出现拼写 '2OUT_DIR=' 导致执行错误 (exit 127)
#
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)

MODULE_DIR="$WS_ROOT/packaging/magisk-batt-design-override"
KO_PATH=""
KERNEL_LINE="unknown"
VERSION=""
OUT_DIR="$WS_ROOT/dist"
ID_SUFFIX=""

usage(){
  cat <<EOF
用法: $0 --ko <path> [--kernel-line 5.15] [--version 1.2] [--module-dir DIR] [--output dist] [--id-suffix test]
EOF
}

die(){ echo "[x] $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ko) KO_PATH="$2"; shift 2;;
    --kernel-line) KERNEL_LINE="$2"; shift 2;;
    --version) VERSION="$2"; shift 2;;
    --module-dir) MODULE_DIR="$2"; shift 2;;
    --output) OUT_DIR="$2"; shift 2;;
    --id-suffix) ID_SUFFIX="$2"; shift 2;;
    -h|--help) usage; exit 0;;
    *) die "未知参数: $1";;
  esac
done

[[ -n "$KO_PATH" ]] || die "必须指定 --ko <path>"
[[ -f "$KO_PATH" ]] || die ".ko 不存在: $KO_PATH"
[[ -d "$MODULE_DIR" ]] || die "模块目录不存在: $MODULE_DIR"

MODULE_PROP="$MODULE_DIR/module.prop"
[[ -f "$MODULE_PROP" ]] || die "缺少 module.prop: $MODULE_PROP"

if [[ -z "$VERSION" ]]; then
  VERSION=$(grep -E '^version=' "$MODULE_PROP" | head -n1 | cut -d= -f2- | tr -d '\r')
  [[ -n "$VERSION" ]] || die "无法解析版本号"
fi

BASE_ID=$(grep -E '^id=' "$MODULE_PROP" | cut -d= -f2- | tr -d '\r')
[[ -n "$BASE_ID" ]] || BASE_ID="batt-design-override"

mkdir -p "$OUT_DIR"
OUT_DIR=$(cd -- "$OUT_DIR" && pwd)   # 修正: 原错误 '2OUT_DIR='

STAGE="$OUT_DIR/${BASE_ID}-${KERNEL_LINE}-stage"
rm -rf "$STAGE" && mkdir -p "$STAGE/common"
rsync -a "$MODULE_DIR/" "$STAGE/"

# 放置 .ko（保留一个固定名 + 带 kernel line 的别名，方便多版本共存）
cp -f "$KO_PATH" "$STAGE/common/batt_design_override.ko"
cp -f "$KO_PATH" "$STAGE/common/batt_design_override-${KERNEL_LINE}.ko" || true

# 更新 stage 里的 module.prop 版本号（不回写原始源码）
sed -i "s/^version=.*/version=$VERSION/" "$STAGE/module.prop"

ZIP_NAME="${BASE_ID}-${VERSION}-${KERNEL_LINE}${ID_SUFFIX:+-$ID_SUFFIX}.zip"
(
  cd "$STAGE"
  zip -r9 "$OUT_DIR/$ZIP_NAME" . >/dev/null
)

echo "[i] KO: $KO_PATH"
echo "[i] VERSION: $VERSION"
echo "[i] KERNEL_LINE: $KERNEL_LINE"
echo "[i] OUT: $OUT_DIR/$ZIP_NAME"
echo "[✓] 打包完成"
