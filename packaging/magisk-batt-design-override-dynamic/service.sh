#!/system/bin/sh
# 动态加载内核模块服务脚本
# 该脚本会从应用获取对应内核版本的 .ko 文件并加载

MODDIR=${0%/*}
COMM_DIR="$MODDIR/common"
CONF="$COMM_DIR/params.conf"
FLAG_DISABLE="$MODDIR/disable_autoload"
APP_PACKAGE="com.override.battcaplsp"

log() { echo "[batt-design-override][dynamic] $*"; }
logw() { echo "[batt-design-override][dynamic][warn] $*"; }

if [ -f "$FLAG_DISABLE" ]; then
    log "disable_autoload 存在，跳过加载"
    exit 0
fi

# 等待系统启动完成
n=0; while [ $n -lt 30 ]; do
    if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then break; fi
    sleep 2; n=$((n+1))
done

# 检测内核版本
KREL=$(uname -r 2>/dev/null)
BASE_VER="${KREL%%-*}"
MAJOR_MINOR=$(echo "$BASE_VER" | cut -d. -f1,2)

log "检测到内核版本: $KREL (主版本: $MAJOR_MINOR)"

# 查找可用的 .ko 文件（按优先级排序）
KO_CANDIDATES="batt_design_override-${MAJOR_MINOR}.ko batt_design_override-${MAJOR_MINOR%.*}.ko batt_design_override.ko"
KO_SELECTED=""

for f in $KO_CANDIDATES; do
    if [ -f "$COMM_DIR/$f" ]; then 
        KO_SELECTED="$COMM_DIR/$f"
        break
    fi
done

if [ -z "$KO_SELECTED" ]; then
    log "未找到匹配的内核模块，尝试通知应用下载"
    # 创建内核版本信息文件供应用读取
    echo "$MAJOR_MINOR" > "$COMM_DIR/kernel_version"
    echo "$KREL" > "$COMM_DIR/kernel_release"
    
    # 发送广播通知应用（如果应用已安装）
    if pm list packages | grep -q "^package:$APP_PACKAGE$"; then
        am broadcast -a com.override.battcaplsp.KERNEL_MODULE_NEEDED \
            --es kernel_version "$MAJOR_MINOR" \
            --es kernel_release "$KREL" \
            --es module_path "$COMM_DIR" >/dev/null 2>&1 || true
        log "已通知应用下载内核模块"
    else
        log "应用未安装，无法自动下载模块"
    fi
    
    log "请在应用中手动下载对应版本的内核模块"
    exit 1
fi

# 解析配置
[ -f "$CONF" ] && . "$CONF"

# 构建 insmod 参数
ARGS=""
[ -n "$MODEL_NAME" ] && ARGS="$ARGS model_name=$MODEL_NAME"
[ -n "$DESIGN_UAH" ] && ARGS="$ARGS design_uah=$DESIGN_UAH"
[ -n "$DESIGN_UWH" ] && ARGS="$ARGS design_uwh=$DESIGN_UWH"
[ -n "$BATT_NAME" ] && ARGS="$ARGS batt_name=$BATT_NAME"
[ -n "$OVERRIDE_ANY" ] && ARGS="$ARGS override_any=$OVERRIDE_ANY"
[ -n "$VERBOSE" ] && ARGS="$ARGS verbose=$VERBOSE"

ARGS=$(echo "$ARGS" | sed 's/^ *//')

log "加载模块: $KO_SELECTED 参数: $ARGS"
if ! insmod "$KO_SELECTED" $ARGS 2>&1; then
    logw "insmod 失败"
    exit 1
fi

# 可选：加载充电参数模块
CHG_KO="$COMM_DIR/chg_param_override.ko"
if [ -f "$CHG_KO" ]; then
    log "加载充电参数模块: $CHG_KO"
    insmod "$CHG_KO" 2>/dev/null || logw "充电模块加载失败"
    
    # 应用充电参数
    PROC_PATH="/proc/chg_param_override"
    if [ -e "$PROC_PATH" ]; then
        LINES=""
        [ -n "$CHG_VMAX_UV" ] && LINES="$LINES\nvoltage_max=$CHG_VMAX_UV"
        [ -n "$CHG_CCC_UA" ] && LINES="$LINES\nconstant_charge_current=$CHG_CCC_UA"
        [ -n "$CHG_TERM_UA" ] && LINES="$LINES\ncharge_term_current=$CHG_TERM_UA"
        [ -n "$CHG_ICL_UA" ] && LINES="$LINES\ninput_current_limit=$CHG_ICL_UA"
        [ -n "$CHG_LIMIT_PERCENT" ] && LINES="$LINES\ncharge_control_limit=$CHG_LIMIT_PERCENT"
        if [ "${CHG_PD_VERIFED_ENABLED:-0}" = "1" ] && [ -n "$CHG_PD_VERIFED" ]; then
            LINES="$LINES\npd_verifed=$CHG_PD_VERIFED"
        fi
        
        LINES=$(echo "$LINES" | sed '/^$/d')
        if [ -n "$LINES" ]; then
            echo "$LINES" > "$PROC_PATH" || logw "写入充电参数失败"
        fi
    fi
fi

log "模块加载完成"
exit 0
