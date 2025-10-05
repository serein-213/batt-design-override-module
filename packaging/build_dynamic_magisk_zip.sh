#!/usr/bin/env bash
#
# 打包动态版 batt_design_override Magisk 模块 ZIP
# 不包含 .ko 文件，由应用动态下载和管理
#
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)

MODULE_DIR="$WS_ROOT/packaging/magisk-batt-design-override-dynamic"
VERSION="2.0.0"
OUT_DIR="$WS_ROOT/dist"
MODULE_ID="batt-design-override-dynamic"

usage(){
  cat <<EOF
用法: $0 [--version 2.0.0] [--output dist] [--apk-path /path/to/app.apk]
打包动态版 Magisk 模块，不包含 .ko 文件
EOF
}

die(){ echo "[x] $*" >&2; exit 1; }

APK_PATH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="$2"; shift 2;;
    --output) OUT_DIR="$2"; shift 2;;
    --apk-path) APK_PATH="$2"; shift 2;;
    -h|--help) usage; exit 0;;
    *) die "未知参数: $1";;
  esac
done

[[ -d "$MODULE_DIR" ]] || die "模块目录不存在: $MODULE_DIR"

MODULE_PROP="$MODULE_DIR/module.prop"
[[ -f "$MODULE_PROP" ]] || die "缺少 module.prop: $MODULE_PROP"

mkdir -p "$OUT_DIR"
OUT_DIR=$(cd -- "$OUT_DIR" && pwd)

STAGE="$OUT_DIR/${MODULE_ID}-stage"
rm -rf "$STAGE" && mkdir -p "$STAGE/common"
rsync -a "$MODULE_DIR/" "$STAGE/"

# 更新版本号
sed -i "s/^version=.*/version=$VERSION/" "$STAGE/module.prop"

# 如果提供了 APK 路径，复制到模块目录
if [[ -n "$APK_PATH" && -f "$APK_PATH" ]]; then
    cp -f "$APK_PATH" "$STAGE/common/battcaplsp.apk"
    echo "[i] 已包含应用 APK: $APK_PATH"
fi

# 设置脚本权限
chmod 755 "$STAGE/service.sh"

ZIP_NAME="${MODULE_ID}-${VERSION}.zip"
(
  cd "$STAGE"
  zip -r9 "$OUT_DIR/$ZIP_NAME" . >/dev/null
)

echo "[i] VERSION: $VERSION"
echo "[i] OUT: $OUT_DIR/$ZIP_NAME"
echo "[✓] 动态模块打包完成"
echo ""
echo "注意："
echo "- 此模块不包含 .ko 文件"
echo "- 需要安装配套应用来下载和管理内核模块"
echo "- 应用会根据内核版本自动下载对应的 .ko 文件"
