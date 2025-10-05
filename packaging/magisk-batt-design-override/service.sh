#!/system/bin/sh
# 自动加载 batt_design_override.ko 与可选 chg_param_override.ko
# 读取同目录 common/params.conf 中的参数并 insmod/应用
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
logw() { echo "[batt-design-override][service][warn] $*"; }

if [ -f "$FLAG_DISABLE" ]; then
  log "disable_autoload 存在，跳过加载"
  exit 0
fi

# 选择优先顺序：带 kernel_line 后缀的 ko -> 通用 ko（batt 模块）
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

# ========== 可选：加载 chg_param_override.ko 并应用参数 ==========
# chg 模块文件名固定为 chg_param_override.ko（暂无多版本后缀需求）
CHG_KO="$COMM_DIR/chg_param_override.ko"
if [ -f "$CHG_KO" ]; then
  # 读取可能存在的 chg 配置键
  # 可用键：CHG_VMAX_UV CHG_CCC_UA CHG_TERM_UA CHG_ICL_UA CHG_LIMIT_PERCENT CHG_PD_VERIFED_ENABLED CHG_PD_VERIFED
  CHG_ARGS=""
  # chg 模块不通过 insmod 参数配置，采用 proc 接口；这里只负责加载
  log "检测到 chg 模块，尝试加载: $CHG_KO"
  if ! insmod "$CHG_KO" 2>&1; then
    if command -v modprobe >/dev/null 2>&1; then
      log "insmod chg 失败，尝试 modprobe"
      modprobe "$CHG_KO" || logw "modprobe chg 也失败"
    else
      logw "insmod chg 失败且无 modprobe 可用"
    fi
  fi

  # 通过 /proc/chg_param_override 写入配置
  PROC_PATH="/proc/chg_param_override"
  if [ -e "$PROC_PATH" ]; then
    # 组装写入项（仅写入已设置的键）
    LINES=""
    [ -n "$CHG_VMAX_UV" ] && LINES="$LINES\nvoltage_max=$CHG_VMAX_UV"
    [ -n "$CHG_CCC_UA" ] && LINES="$LINES\nconstant_charge_current=$CHG_CCC_UA"
    [ -n "$CHG_TERM_UA" ] && LINES="$LINES\ncharge_term_current=$CHG_TERM_UA"
    [ -n "$CHG_ICL_UA" ] && LINES="$LINES\ninput_current_limit=$CHG_ICL_UA"
    [ -n "$CHG_LIMIT_PERCENT" ] && LINES="$LINES\ncharge_control_limit=$CHG_LIMIT_PERCENT"
    if [ "${CHG_PD_VERIFED_ENABLED:-0}" = "1" ] && [ -n "$CHG_PD_VERIFED" ]; then
      LINES="$LINES\npd_verifed=$CHG_PD_VERIFED"
    fi
    # 去掉首行空行
    LINES=$(echo "$LINES" | sed '/^$/d')
    if [ -n "$LINES" ]; then
      log "应用 chg 参数: $(echo "$LINES" | tr '\n' ' '; echo)"
      echo "$LINES" > "$PROC_PATH" || logw "写入 $PROC_PATH 失败"
    else
      log "未设置 chg 参数，跳过写入"
    fi
  else
    logw "未找到 $PROC_PATH，可能 chg 模块未成功加载或设备不支持"
  fi
else
  log "未检测到 chg 模块 (.ko 缺失)，跳过"
fi

# ========== 可选：安装随包 APK（LSPosed 助手） ==========
# 通过 APP_AUTOINSTALL=1 控制是否自动安装（默认 1）
APP_APK="$COMM_DIR/battcaplsp.apk"
APP_PKG="${APP_PKG:-com.override.battcaplsp}"
APP_OLD_PKG="${APP_OLD_PKG:-com.example.battcaplsp}"
APP_REMOVE_OLD="${APP_REMOVE_OLD:-1}"
if [ "${APP_AUTOINSTALL:-1}" = "1" ] && [ -f "$APP_APK" ]; then
  log "检测到 APK，准备安装: $APP_APK (pkg=$APP_PKG)"
  # 等待开机完成属性，最多等待 20 秒
  n=0; while [ $n -lt 20 ]; do
    if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then break; fi
    sleep 1; n=$((n+1))
  done
  # 若启用，先检测并卸载旧包
  if [ "$APP_REMOVE_OLD" = "1" ] && [ -n "$APP_OLD_PKG" ]; then
    if command -v cmd >/dev/null 2>&1; then
      if cmd package list packages | grep -q "^package:$APP_OLD_PKG$"; then
        log "检测到旧包，卸载: $APP_OLD_PKG"
        cmd package uninstall --user 0 "$APP_OLD_PKG" >/dev/null 2>&1 || pm uninstall -k --user 0 "$APP_OLD_PKG" >/dev/null 2>&1 || logw "卸载旧包失败"
        sleep 1
      fi
    else
      if pm list packages | grep -q "^package:$APP_OLD_PKG$"; then
        log "检测到旧包，卸载: $APP_OLD_PKG"
        pm uninstall -k --user 0 "$APP_OLD_PKG" >/dev/null 2>&1 || logw "卸载旧包失败"
        sleep 1
      fi
    fi
  fi

  # 已安装检查
  INSTALLED=0
  if command -v cmd >/dev/null 2>&1; then
    if cmd package list packages | grep -q "^package:$APP_PKG$"; then INSTALLED=1; fi
  else
    if pm list packages | grep -q "^package:$APP_PKG$"; then INSTALLED=1; fi
  fi
  if [ $INSTALLED -eq 1 ] && [ "${APP_FORCE_REINSTALL:-0}" != "1" ]; then
    log "检测到已安装且未启用强制重装，跳过安装"
  else
    if command -v cmd >/dev/null 2>&1; then
      cmd package install -r -d -g --user 0 "$APP_APK" || pm install -r -d -g "$APP_APK" || logw "App 安装失败"
    else
      pm install -r -d -g "$APP_APK" || logw "App 安装失败"
    fi
  fi
else
  log "未启用 APP_AUTOINSTALL 或 APK 缺失，跳过 App 安装"
fi

exit 0
