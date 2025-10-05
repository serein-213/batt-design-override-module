#!/usr/bin/env bash
# CPU温度监控脚本
# 使用方法: ./scripts/temp_monitor.sh

set -euo pipefail

# 导入温度配置
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/thermal_config.sh"

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

# 获取CPU使用率
get_cpu_usage() {
    top -bn1 | grep "Cpu(s)" | sed "s/.*, *\([0-9.]*\)%* id.*/\1/" | awk '{print 100 - $1}'
}

# 获取内存使用率
get_memory_usage() {
    free | grep Mem | awk '{printf "%.1f", $3/$2 * 100.0}'
}

# 获取CPU负载
get_cpu_load() {
    uptime | awk -F'load average:' '{print $2}' | awk '{print $1}' | sed 's/,//'
}

# 温度状态判断
get_temp_status() {
    local temp=$1
    if [[ $temp -lt $TEMP_SAFE ]]; then
        echo "🟢 安全"
    elif [[ $temp -lt $TEMP_WARNING ]]; then
        echo "🟡 注意"
    elif [[ $temp -lt $MAX_TEMP ]]; then
        echo "🟠 警告"
    else
        echo "🔴 过热"
    fi
}

# 建议的编译任务数
suggest_jobs() {
    local temp=$1
    local cores=$(nproc)
    local jobs
    
    if [[ $temp -lt $TEMP_SAFE ]]; then
        jobs=$((cores * JOBS_RATIO_COOL / 100))
    elif [[ $temp -lt $TEMP_WARNING ]]; then
        jobs=$((cores * JOBS_RATIO_WARM / 100))
    elif [[ $temp -lt $MAX_TEMP ]]; then
        jobs=$((cores * JOBS_RATIO_HOT / 100))
    else
        jobs=$((cores * JOBS_RATIO_CRITICAL / 100))
    fi
    
    # 确保在合理范围内
    [[ $jobs -lt $MIN_JOBS ]] && jobs=$MIN_JOBS
    [[ $jobs -gt $MAX_JOBS ]] && jobs=$MAX_JOBS
    
    echo "$jobs"
}

# 主监控循环
monitor_loop() {
    echo "=========================================="
    echo "        CPU温度实时监控"
    echo "=========================================="
    echo "编译模式: $THERMAL_MODE"
    echo "温度阈值: 安全<${TEMP_SAFE}°C | 警告<${TEMP_WARNING}°C | 最大<${MAX_TEMP}°C"
    echo "按 Ctrl+C 退出监控"
    echo "=========================================="
    
    while true; do
        local temp=$(get_cpu_temp)
        local cpu_usage=$(get_cpu_usage)
        local memory_usage=$(get_memory_usage)
        local cpu_load=$(get_cpu_load)
        local temp_status=$(get_temp_status $temp)
        local suggested_jobs=$(suggest_jobs $temp)
        
        # 清除上一行并显示新信息
        printf "\r\033[K"
        printf "🌡️  温度: %3d°C %s | 💻 CPU: %5.1f%% | 🧠 内存: %5.1f%% | ⚡ 负载: %s | 🔧 建议任务数: %d" \
               "$temp" "$temp_status" "$cpu_usage" "$memory_usage" "$cpu_load" "$suggested_jobs"
        
        sleep 2
    done
}

# 单次检查模式
single_check() {
    local temp=$(get_cpu_temp)
    local cpu_usage=$(get_cpu_usage)
    local memory_usage=$(get_memory_usage)
    local cpu_load=$(get_cpu_load)
    local temp_status=$(get_temp_status $temp)
    local suggested_jobs=$(suggest_jobs $temp)
    
    echo "=========================================="
    echo "        系统状态检查"
    echo "=========================================="
    echo "🌡️  CPU温度: ${temp}°C ${temp_status}"
    echo "💻 CPU使用率: ${cpu_usage}%"
    echo "🧠 内存使用率: ${memory_usage}%"
    echo "⚡ CPU负载: ${cpu_load}"
    echo "🔧 建议编译任务数: ${suggested_jobs}"
    echo "=========================================="
    
    # 导出环境变量供其他脚本使用
    echo "export CURRENT_TEMP=$temp"
    echo "export SUGGESTED_JOBS=$suggested_jobs"
    echo "export TEMP_STATUS='$temp_status'"
}

# 参数处理
case "${1:-monitor}" in
    "monitor"|"-m"|"--monitor")
        monitor_loop
        ;;
    "check"|"-c"|"--check")
        single_check
        ;;
    "config"|"--config")
        show_thermal_config
        ;;
    "help"|"-h"|"--help")
        echo "CPU温度监控脚本"
        echo ""
        echo "用法:"
        echo "  $0 [monitor|check|config|help]"
        echo ""
        echo "选项:"
        echo "  monitor, -m    实时监控模式（默认）"
        echo "  check, -c      单次检查模式"
        echo "  config         显示当前配置"
        echo "  help, -h       显示此帮助信息"
        echo ""
        echo "环境变量:"
        echo "  THERMAL_MODE   编译模式 (conservative|balanced|performance)"
        echo ""
        echo "示例:"
        echo "  $0                    # 实时监控"
        echo "  $0 check              # 单次检查"
        echo "  THERMAL_MODE=conservative $0    # 保守模式监控"
        ;;
    *)
        echo "未知选项: $1"
        echo "使用 '$0 help' 查看帮助信息"
        exit 1
        ;;
esac
