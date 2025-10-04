#!/system/bin/sh
# 自动加载 batt_design_override.ko
# 读取同目录 common/params.conf 中的参数并 insmod
# 支持的环境变量/键：
#   MODEL_NAME   -> model_name=<val>
#   DESIGN_UAH   -> design_uah=<val>
#   DESIGN_UWH   -> design_uwh=<val>
#   OVERRIDE_ANY -> override_any=1|0
#   BATT_NAME    -> batt_name=<val>
#   VERBOSE      -> verbose=1|0
#
# 可通过创建 /data/adb/modules/batt-design-override/disable_autoload 标记文件禁用自动加载。

MODDIR=${0%/*}
COMM_DIR="$MODDIR/common"
CONF="$COMM_DIR/params.conf"
FLAG_DISABLE="$MODDIR/disable_autoload"
KERNEL_LINE="" # 仅用于区分多版本 ko（可选）

log() { echo "[batt-design-override][service] $*"; }

if [ -f "$FLAG_DISABLE" ]; then
  log "disable_autoload 存在，跳过加载"
  exit 0
fi

# 选择优先顺序：带 kernel_line 后缀的 ko -> 通用 ko
# 简单检测当前内核主版本 (如 5.15.123-gXXXX)
KREL=$(uname -r 2>/dev/null)
BASE_VER="${KREL%%-*}"   # 5.15.123
MAJOR_MINOR=$(echo "$BASE_VER" | cut -d. -f1,2) # 5.15
KO_CANDIDATES="batt_design_override-${MAJOR_MINOR}.ko batt_design_override-${MAJOR_MINOR%.*}.ko batt_design_override.ko"
KO_SELECTED=""
for f in $KO_CANDIDATES; do
  if [ -f "$COMM_DIR/$f" ]; then KO_SELECTED="$COMM_DIR/$f"; break; fi
done
if [ -z "$KO_SELECTED" ]; then
  log "未找到可用内核模块 (candidates: $KO_CANDIDATES)"; exit 1
fi

# 解析配置
[ -f "$CONF" ] && . "$CONF"
# shellcheck source=/dev/null

# 转换键为 insmod 参数
ARGS=""
[ -n "$MODEL_NAME" ] && ARGS="$ARGS model_name=$MODEL_NAME"
[ -n "$DESIGN_UAH" ] && ARGS="$ARGS design_uah=$DESIGN_UAH"
[ -n "$DESIGN_UWH" ] && ARGS="$ARGS design_uwh=$DESIGN_UWH"
[ -n "$BATT_NAME" ] && ARGS="$ARGS batt_name=$BATT_NAME"
[ -n "$OVERRIDE_ANY" ] && ARGS="$ARGS override_any=$OVERRIDE_ANY"
[ -n "$VERBOSE" ] && ARGS="$ARGS verbose=$VERBOSE"

# 去掉前导空格
ARGS=$(echo "$ARGS" | sed 's/^ *//')

log "加载模块: $KO_SELECTED 参数: $ARGS"
# 优先使用 insmod；如果失败尝试 modprobe （多数 AOSP 系统不含）
if ! insmod "$KO_SELECTED" $ARGS 2>&1; then
  if command -v modprobe >/dev/null 2>&1; then
    log "insmod 失败，尝试 modprobe"
    modprobe "$KO_SELECTED" $ARGS || log "modprobe 也失败"
  else
    log "insmod 失败且无 modprobe 可用"
  fi
fi

exit 0
