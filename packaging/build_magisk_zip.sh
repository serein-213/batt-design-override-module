#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
MODULE_DIR="$WS_ROOT/packaging/magisk-batt-design-override"
KO_PATH=""
KERNEL_LINE="unknown"
VERSION=""
OUT_DIR="$WS_ROOT/dist"
ID_SUFFIX=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ko) KO_PATH="$2"; shift 2;;
    --kernel-line) KERNEL_LINE="$2"; shift 2;;
    --version) VERSION="$2"; shift 2;;
    --module-dir) MODULE_DIR="$2"; shift 2;;
    --output) OUT_DIR="$2"; shift 2;;
    --id-suffix) ID_SUFFIX="$2"; shift 2;;
    -h|--help) echo "用法: $0 --ko <path> [--kernel-line 5.15] [--version 1.0.3]"; exit 0;;
    *) echo "未知参数: $1" >&2; exit 1;;
  esac
done
[[ -n "$KO_PATH" ]] || { echo "需要 --ko" >&2; exit 1; }
[[ -f "$KO_PATH" ]] || { echo "找不到 $KO_PATH" >&2; exit 1; }
MODULE_PROP="$MODULE_DIR/module.prop"
[[ -f "$MODULE_PROP" ]] || { echo "缺少 module.prop" >&2; exit 1; }
if [[ -z "$VERSION" ]]; then
  VERSION=$(grep -E '^version=' "$MODULE_PROP" | head -n1 | cut -d= -f2- | tr -d '\r')
fi
BASE_ID=$(grep -E '^id=' "$MODULE_PROP" | cut -d= -f2- | tr -d '\r')
mkdir -p "$OUT_DIR"
STAGE="$OUT_DIR/${BASE_ID}-${KERNEL_LINE}-stage"
rm -rf "$STAGE"; mkdir -p "$STAGE"
rsync -a "$MODULE_DIR/" "$STAGE/"
cp -f "$KO_PATH" "$STAGE/common/batt_design_override.ko"
cp -f "$KO_PATH" "$STAGE/common/batt_design_override-${KERNEL_LINE}.ko" || true
sed -i "s/^version=.*/version=$VERSION/" "$STAGE/module.prop"
ZIP_NAME="${BASE_ID}-${VERSION}-${KERNEL_LINE}${ID_SUFFIX:+-$ID_SUFFIX}.zip"
(cd "$STAGE" && zip -r9 "$OUT_DIR/$ZIP_NAME" . >/dev/null)
echo "[✓] $OUT_DIR/$ZIP_NAME"
