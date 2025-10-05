#!/usr/bin/env bash
# 构建历史记录脚本
# 记录构建时间、温度、性能等信息

set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
LOG_FILE="$WS_ROOT/build_history.log"

# 创建构建记录
log_build_start() {
    local kernel_line="$1"
    local version="${2:-unknown}"
    local thermal_mode="${3:-balanced}"
    local start_time="$4"
    local start_temp="$5"
    local jobs="${6:-auto}"
    
    echo "=========================================" >> "$LOG_FILE"
    echo "构建开始: $(date -d @$start_time '+%Y-%m-%d %H:%M:%S')" >> "$LOG_FILE"
    echo "内核版本: $kernel_line" >> "$LOG_FILE"
    echo "版本号: $version" >> "$LOG_FILE"
    echo "编译模式: $thermal_mode" >> "$LOG_FILE"
    echo "初始温度: ${start_temp}°C" >> "$LOG_FILE"
    echo "编译任务数: $jobs" >> "$LOG_FILE"
}

# 记录构建完成
log_build_complete() {
    local end_time="$1"
    local end_temp="$2"
    local total_time="$3"
    local kernel_time="${4:-0}"
    local app_time="${5:-0}"
    local package_time="${6:-0}"
    local success="${7:-true}"
    
    echo "构建结束: $(date -d @$end_time '+%Y-%m-%d %H:%M:%S')" >> "$LOG_FILE"
    echo "最终温度: ${end_temp}°C" >> "$LOG_FILE"
    echo "总用时: ${total_time}秒" >> "$LOG_FILE"
    echo "内核模块用时: ${kernel_time}秒" >> "$LOG_FILE"
    echo "App用时: ${app_time}秒" >> "$LOG_FILE"
    echo "打包用时: ${package_time}秒" >> "$LOG_FILE"
    echo "构建状态: $([ "$success" = "true" ] && echo "成功" || echo "失败")" >> "$LOG_FILE"
    echo "=========================================" >> "$LOG_FILE"
    echo "" >> "$LOG_FILE"
}

# 显示构建历史统计
show_build_stats() {
    if [[ ! -f "$LOG_FILE" ]]; then
        echo "暂无构建历史记录"
        return
    fi
    
    echo "=========================================="
    echo "           构建历史统计"
    echo "=========================================="
    
    local total_builds=$(grep -c "构建开始:" "$LOG_FILE" 2>/dev/null || echo "0")
    local successful_builds=$(grep -c "构建状态: 成功" "$LOG_FILE" 2>/dev/null || echo "0")
    local failed_builds=$((total_builds - successful_builds))
    
    echo "总构建次数: $total_builds"
    echo "成功构建: $successful_builds"
    echo "失败构建: $failed_builds"
    
    if [[ $total_builds -gt 0 ]]; then
        echo ""
        echo "最近5次构建:"
        echo "----------------------------------------"
        grep -A 10 "构建开始:" "$LOG_FILE" | tail -n 55 | while IFS= read -r line; do
            if [[ $line == "=========================================" ]]; then
                echo "----------------------------------------"
            elif [[ $line =~ ^(构建开始|构建结束|总用时|构建状态): ]]; then
                echo "$line"
            fi
        done
    fi
    
    echo "=========================================="
}

# 清理旧的构建记录（保留最近50次）
cleanup_old_logs() {
    if [[ ! -f "$LOG_FILE" ]]; then
        return
    fi
    
    local total_builds=$(grep -c "构建开始:" "$LOG_FILE" 2>/dev/null || echo "0")
    if [[ $total_builds -gt 50 ]]; then
        echo "清理旧的构建记录，保留最近50次..."
        local temp_file=$(mktemp)
        tail -n 2000 "$LOG_FILE" > "$temp_file"
        mv "$temp_file" "$LOG_FILE"
    fi
}

# 参数处理
case "${1:-}" in
    "start")
        log_build_start "$2" "$3" "$4" "$5" "$6" "$7"
        ;;
    "complete")
        log_build_complete "$2" "$3" "$4" "$5" "$6" "$7" "$8"
        cleanup_old_logs
        ;;
    "stats"|"show")
        show_build_stats
        ;;
    "clean")
        if [[ -f "$LOG_FILE" ]]; then
            rm "$LOG_FILE"
            echo "构建历史记录已清理"
        else
            echo "无构建历史记录需要清理"
        fi
        ;;
    *)
        echo "构建历史记录工具"
        echo ""
        echo "用法:"
        echo "  $0 start <kernel_line> <version> <thermal_mode> <start_time> <start_temp> <jobs>"
        echo "  $0 complete <end_time> <end_temp> <total_time> [kernel_time] [app_time] [package_time] [success]"
        echo "  $0 stats     显示构建统计"
        echo "  $0 clean     清理历史记录"
        ;;
esac
