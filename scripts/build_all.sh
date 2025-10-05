#!/usr/bin/env bash
set -euo pipefail

# 一键构建脚本：
# - 编译内核外部模块 batt_design_override.ko / chg_param_override.ko（当前支持 5.15）
# - 构建 LSPosed App (release)
# - 将产物拷入 Magisk 模块目录并打包 zip
#
# 用法示例：
#   ./scripts/build_all.sh --kernel-line 5.15 --version 1.0.6 --id-suffix fuxi
#
# 可选参数：
#   --kernel-line <ver>  目标内核主线段 (5.10|5.15|6.1|6.6)，默认为 5.15（chg 模块仅有 5.15 脚本）
#   --version <ver>      打包版本；缺省读取 module.prop
#   --id-suffix <suf>    产物名附加后缀
#   --skip-app           跳过 App 构建
#   --skip-batt          跳过电池模块构建
#   --skip-chg           跳过充电模块构建
#   --jobs <n>           传递给模块编译脚本的 JOBS（覆盖环境变量）
#   --out <dir>          输出目录（默认 dist）
#
# 温度控制环境变量：
#   THERMAL_MODE         编译模式 (conservative|balanced|performance)
#   MAX_TEMP             最大允许温度（默认85°C）
#   AUTO_ADJUST_JOBS     自动调整编译任务数（默认开启）
#
# 使用示例：
#   ./scripts/build_all.sh --kernel-line 5.15 --version 1.0.6
#   THERMAL_MODE=conservative ./scripts/build_all.sh --kernel-line 6.1
#   MAX_TEMP=80 ./scripts/build_all.sh --jobs 4
#
# 依赖：bash、zip、Java/Android SDK（用于 gradle 构建）

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)

# 导入温度控制配置
source "$SCRIPT_DIR/thermal_config.sh"

KERNEL_LINE="5.15"
VERSION=""
ID_SUFFIX=""
SKIP_APP=0
SKIP_BATT=0
SKIP_CHG=0
JOBS_ARG=""
OUT_DIR="$WS_ROOT/dist"

die(){ 
    echo "[x] $*" >&2
    # 记录构建失败
    if [[ -n "${BUILD_START_TIME:-}" ]]; then
        local fail_time=$(date +%s)
        local fail_temp=$(get_cpu_temp 2>/dev/null || echo "unknown")
        local fail_duration=$((fail_time - BUILD_START_TIME))
        "$SCRIPT_DIR/build_logger.sh" complete "$fail_time" "$fail_temp" "$fail_duration" "0" "0" "0" "false" 2>/dev/null || true
    fi
    exit 1
}
log(){ echo "[i] $*"; }

# 获取CPU温度的函数
get_cpu_temp() {
    local temp_file="/sys/class/hwmon/hwmon*/temp*_input"
    local max_temp=0
    
    # 尝试从 k10temp 传感器获取温度
    if command -v sensors >/dev/null 2>&1; then
        local tctl_temp=$(sensors k10temp-pci-* 2>/dev/null | grep -E "Tctl:" | awk '{print $2}' | sed 's/[+°C]//g' | head -n1)
        if [[ -n "$tctl_temp" && "$tctl_temp" != "" ]]; then
            echo "${tctl_temp%.*}"  # 去掉小数部分
            return
        fi
    fi
    
    # 备选方案：从 /sys 文件系统读取
    for temp in $temp_file; do
        if [[ -r "$temp" ]]; then
            local current_temp=$(($(cat "$temp") / 1000))
            if [[ $current_temp -gt $max_temp ]]; then
                max_temp=$current_temp
            fi
        fi
    done
    
    echo "$max_temp"
}

# 温度监控和控制函数
monitor_temperature() {
    local current_temp=$(get_cpu_temp)
    if [[ $current_temp -gt $MAX_TEMP ]]; then
        log "⚠️  CPU温度过高: ${current_temp}°C (限制: ${MAX_TEMP}°C)"
        log "暂停编译，等待降温 ${TEMP_COOL_DOWN} 秒..."
        sleep "$TEMP_COOL_DOWN"
        return 1
    else
        log "🌡️  CPU温度: ${current_temp}°C"
        return 0
    fi
}

# 自动调整编译任务数
adjust_jobs() {
    local current_temp=$(get_cpu_temp)
    local cpu_cores=$(nproc)
    local suggested_jobs
    
    if [[ $current_temp -lt $TEMP_SAFE ]]; then
        suggested_jobs=$((cpu_cores * JOBS_RATIO_COOL / 100))
    elif [[ $current_temp -lt $TEMP_WARNING ]]; then
        suggested_jobs=$((cpu_cores * JOBS_RATIO_WARM / 100))
    elif [[ $current_temp -lt $MAX_TEMP ]]; then
        suggested_jobs=$((cpu_cores * JOBS_RATIO_HOT / 100))
    else
        suggested_jobs=$((cpu_cores * JOBS_RATIO_CRITICAL / 100))
    fi
    
    # 确保在合理范围内
    [[ $suggested_jobs -lt $MIN_JOBS ]] && suggested_jobs=$MIN_JOBS
    [[ $suggested_jobs -gt $MAX_JOBS ]] && suggested_jobs=$MAX_JOBS
    
    # 输出日志到stderr，避免影响函数返回值
    log "🔧 根据温度 ${current_temp}°C 调整编译任务数为: $suggested_jobs" >&2
    echo "$suggested_jobs"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --kernel-line) KERNEL_LINE="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --id-suffix) ID_SUFFIX="$2"; shift 2 ;;
    --skip-app) SKIP_APP=1; shift ;;
    --skip-batt) SKIP_BATT=1; shift ;;
    --skip-chg) SKIP_CHG=1; shift ;;
    --jobs) JOBS_ARG="JOBS=$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "未知参数: $1" ;;
  esac
done

mkdir -p "$OUT_DIR"

# 记录构建开始时间
BUILD_START_TIME=$(date +%s)
BUILD_START_READABLE=$(date '+%Y-%m-%d %H:%M:%S')
log "🚀 构建开始时间: $BUILD_START_READABLE"

# 初始化温度监控
log "🌡️ 开始温度监控，最大允许温度: ${MAX_TEMP}°C"
monitor_temperature
INITIAL_TEMP=$(get_cpu_temp)

# 记录构建开始信息
"$SCRIPT_DIR/build_logger.sh" start "$KERNEL_LINE" "${VERSION:-unknown}" "$THERMAL_MODE" "$BUILD_START_TIME" "$INITIAL_TEMP" "${JOBS:-auto}"

# 自动调整编译任务数
if [[ "$AUTO_ADJUST_JOBS" == 1 && -z "$JOBS_ARG" ]]; then
    SUGGESTED_JOBS=$(adjust_jobs)
    JOBS_ARG="JOBS=$SUGGESTED_JOBS"
    log "🔧 自动设置编译任务数: $JOBS_ARG"
fi

# --- 1) 构建内核外部模块 ---
BATTMOD_KO="$WS_ROOT/extra_modules/batt_design_override/batt_design_override.ko"
CHGMOD_KO="$WS_ROOT/extra_modules/chg_param_override/chg_param_override.ko"

# 根据内核版本设置正确的模块路径
case "$KERNEL_LINE" in
  5.4)  
    BATTMOD_KO="$WS_ROOT/extra_modules/v5.4/batt_design_override/batt_design_override.ko"
    CHGMOD_KO="$WS_ROOT/extra_modules/v5.4/chg_param_override/chg_param_override.ko"
    ;;
  5.10) 
    BATTMOD_KO="$WS_ROOT/extra_modules/v5.10/batt_design_override/batt_design_override.ko"
    CHGMOD_KO="$WS_ROOT/extra_modules/v5.10/chg_param_override/chg_param_override.ko"
    ;;
  5.15) 
    BATTMOD_KO="$WS_ROOT/extra_modules/v5.15/batt_design_override/batt_design_override.ko"
    CHGMOD_KO="$WS_ROOT/extra_modules/v5.15/chg_param_override/chg_param_override.ko"
    ;;
  6.1)  
    BATTMOD_KO="$WS_ROOT/extra_modules/v6.1/batt_design_override/batt_design_override.ko"
    CHGMOD_KO="$WS_ROOT/extra_modules/v6.1/chg_param_override/chg_param_override.ko"
    ;;
esac

# 统一构建内核模块（电池和充电模块一起构建）
if [[ "$SKIP_BATT" != 1 || "$SKIP_CHG" != 1 ]]; then
  KERNEL_BUILD_START=$(date +%s)
  log "🔨 开始构建内核模块 (内核版本: $KERNEL_LINE)..."
  monitor_temperature || { log "等待降温后继续..."; monitor_temperature; }
  
  # 设置构建参数（通过环境变量）
  export BUILD_BATT=1
  export BUILD_CHG=1
  [[ "$SKIP_BATT" == 1 ]] && export BUILD_BATT=0
  [[ "$SKIP_CHG" == 1 ]] && export BUILD_CHG=0
  
  # 传递JOBS参数
  if [[ -n "$JOBS_ARG" ]]; then
    export JOBS="${JOBS_ARG#JOBS=}"
    log "🔧 设置编译任务数: $JOBS"
  fi
  
  case "$KERNEL_LINE" in
    5.4)  "$WS_ROOT/scripts/build_modules_5_4.sh" ;;
    5.10) "$WS_ROOT/scripts/build_modules_5_10.sh" ;;
    5.15) "$WS_ROOT/scripts/build_modules_5_15.sh" ;;
    6.1)  "$WS_ROOT/scripts/build_modules_6_1.sh" ;;
    6.6)  "$WS_ROOT/scripts/build_modules_6_6.sh" ;;
    *) die "不支持的 --kernel-line: $KERNEL_LINE" ;;
  esac
  
  # 计算内核模块构建用时
  KERNEL_BUILD_END=$(date +%s)
  KERNEL_BUILD_TIME=$((KERNEL_BUILD_END - KERNEL_BUILD_START))
  KERNEL_BUILD_MIN=$((KERNEL_BUILD_TIME / 60))
  KERNEL_BUILD_SEC=$((KERNEL_BUILD_TIME % 60))
  
  # 检查构建结果
  if [[ "$SKIP_BATT" != 1 ]]; then
    [[ -f "$BATTMOD_KO" ]] || die "未生成电池模块: $BATTMOD_KO"
    log "✅ 电池模块构建完成: $BATTMOD_KO"
  fi
  
  if [[ "$SKIP_CHG" != 1 ]]; then
    if [[ "$KERNEL_LINE" == "5.15" ]]; then
      [[ -f "$CHGMOD_KO" ]] || log "提示：未生成充电模块 .ko（可接受）"
      [[ -f "$CHGMOD_KO" ]] && log "✅ 充电模块构建完成: $CHGMOD_KO"
    fi
  fi
  
  log "⏱️  内核模块构建用时: ${KERNEL_BUILD_MIN}分${KERNEL_BUILD_SEC}秒"
fi

# --- 2) 构建 App (LSPosed 模块 APK) ---
APP_APK="$WS_ROOT/lsposed-module/app/build/outputs/apk/release/app-release.apk"
if [[ "$SKIP_APP" != 1 ]]; then
  APP_BUILD_START=$(date +%s)
  log "📱 开始构建 App (release) ..."
  monitor_temperature || { log "等待降温后继续..."; monitor_temperature; }
  
  (
    cd "$WS_ROOT/lsposed-module"
    # 使用系统安装的 gradle 而不是 wrapper，避免版本兼容问题
    gradle --no-daemon --stacktrace :app:assembleRelease -x lint
  )
  [[ -f "$APP_APK" ]] || die "App 构建失败，未找到: $APP_APK"
  
  # 计算App构建用时
  APP_BUILD_END=$(date +%s)
  APP_BUILD_TIME=$((APP_BUILD_END - APP_BUILD_START))
  APP_BUILD_MIN=$((APP_BUILD_TIME / 60))
  APP_BUILD_SEC=$((APP_BUILD_TIME % 60))
  
  log "✅ App 构建完成"
  log "⏱️  App构建用时: ${APP_BUILD_MIN}分${APP_BUILD_SEC}秒"
fi

# --- 3) 同步产物到 Magisk 模块目录 ---
MAGISK_COMMON="$WS_ROOT/packaging/magisk-batt-design-override/common"
[[ -d "$MAGISK_COMMON" ]] || die "缺少目录: $MAGISK_COMMON"

if [[ -f "$BATTMOD_KO" ]]; then
  cp -f "$BATTMOD_KO" "$MAGISK_COMMON/batt_design_override.ko"
  log "已复制 batt .ko -> $MAGISK_COMMON"
fi
if [[ -f "$CHGMOD_KO" ]]; then
  cp -f "$CHGMOD_KO" "$MAGISK_COMMON/chg_param_override.ko"
  log "已复制 chg .ko -> $MAGISK_COMMON"
fi
if [[ -f "$APP_APK" ]]; then
  cp -f "$APP_APK" "$MAGISK_COMMON/battcaplsp.apk"
  log "已复制 App APK -> $MAGISK_COMMON/battcaplsp.apk"
fi

# --- 4) 打包 Magisk 模块 zip ---
PKG_SH="$WS_ROOT/packaging/build_magisk_zip.sh"
[[ -x "$PKG_SH" ]] || chmod +x "$PKG_SH"

log "开始打包 Magisk 模块 ..."
PACKAGE_START=$(date +%s)
ZIP_PATH=$("$PKG_SH" --ko "$BATTMOD_KO" --kernel-line "$KERNEL_LINE" ${VERSION:+--version "$VERSION"} ${ID_SUFFIX:+--id-suffix "$ID_SUFFIX"} --output "$OUT_DIR")
PACKAGE_END=$(date +%s)
PACKAGE_TIME=$((PACKAGE_END - PACKAGE_START))

# 计算总构建时间
BUILD_END_TIME=$(date +%s)
BUILD_END_READABLE=$(date '+%Y-%m-%d %H:%M:%S')
TOTAL_BUILD_TIME=$((BUILD_END_TIME - BUILD_START_TIME))
TOTAL_BUILD_MIN=$((TOTAL_BUILD_TIME / 60))
TOTAL_BUILD_SEC=$((TOTAL_BUILD_TIME % 60))

echo ""
echo "=========================================="
echo "           构建完成统计"
echo "=========================================="
echo "🚀 构建开始时间: $BUILD_START_READABLE"
echo "🏁 构建结束时间: $BUILD_END_READABLE"
echo "⏱️  总构建用时: ${TOTAL_BUILD_MIN}分${TOTAL_BUILD_SEC}秒"

# 显示各阶段用时（如果存在）
if [[ -n "${KERNEL_BUILD_TIME:-}" ]]; then
    echo "🔨 内核模块用时: ${KERNEL_BUILD_MIN}分${KERNEL_BUILD_SEC}秒"
fi
if [[ -n "${APP_BUILD_TIME:-}" ]]; then
    echo "📱 App构建用时: ${APP_BUILD_MIN}分${APP_BUILD_SEC}秒"
fi
echo "📦 打包用时: ${PACKAGE_TIME}秒"

# 显示温度信息
FINAL_TEMP=$(get_cpu_temp)
echo "🌡️  最终CPU温度: ${FINAL_TEMP}°C"

# 显示构建效率信息
if [[ -n "${JOBS:-}" ]]; then
    echo "🔧 使用编译任务数: $JOBS"
fi
echo "🎯 编译模式: $THERMAL_MODE"
echo "=========================================="

# 记录构建完成信息
"$SCRIPT_DIR/build_logger.sh" complete "$BUILD_END_TIME" "$FINAL_TEMP" "$TOTAL_BUILD_TIME" "${KERNEL_BUILD_TIME:-0}" "${APP_BUILD_TIME:-0}" "$PACKAGE_TIME" "true"

echo "[✓] 完成：$ZIP_PATH"
echo "$ZIP_PATH"


