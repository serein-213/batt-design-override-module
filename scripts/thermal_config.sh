#!/usr/bin/env bash
# 温度控制配置文件
# 使用方法: source scripts/thermal_config.sh

# ==============================================
# 温度控制配置
# ==============================================

# 温度阈值设置（单位：摄氏度）
export MAX_TEMP=85                    # 最大允许温度，超过此温度将暂停编译
export TEMP_WARNING=80                # 警告温度，超过此温度开始降低编译强度
export TEMP_SAFE=75                   # 安全温度，低于此温度可以全速编译

# 温度检查和控制参数
export TEMP_CHECK_INTERVAL=30         # 温度检查间隔（秒）
export TEMP_COOL_DOWN=60              # 过热时的降温等待时间（秒）
export AUTO_ADJUST_JOBS=1             # 是否自动调整编译任务数 (1=开启, 0=关闭)

# ==============================================
# 编译任务数量控制策略
# ==============================================

# 根据温度自动调整编译任务数
# 温度范围 -> 使用CPU核心数的比例
export JOBS_RATIO_COOL=100            # <75°C: 100% CPU核心数
export JOBS_RATIO_WARM=75             # 75-80°C: 75% CPU核心数  
export JOBS_RATIO_HOT=50              # 80-85°C: 50% CPU核心数
export JOBS_RATIO_CRITICAL=25         # >85°C: 25% CPU核心数

# 最小和最大任务数限制
export MIN_JOBS=1                     # 最小编译任务数
export MAX_JOBS=$(nproc)              # 最大编译任务数（默认为CPU核心数）

# ==============================================
# 编译器优化设置
# ==============================================

# LTO (Link Time Optimization) 任务数控制
# LTO会消耗大量CPU和内存，在高温时应该限制
export LTO_JOBS_COOL=$(($(nproc) / 2))      # 低温时的LTO任务数
export LTO_JOBS_WARM=$(($(nproc) / 4))      # 中温时的LTO任务数  
export LTO_JOBS_HOT=1                       # 高温时的LTO任务数

# ==============================================
# 系统资源监控
# ==============================================

# 内存使用监控
export MEMORY_THRESHOLD=90            # 内存使用率阈值（百分比）
export CHECK_MEMORY=1                 # 是否检查内存使用率

# CPU负载监控
export LOAD_THRESHOLD=8.0             # CPU负载阈值
export CHECK_LOAD=1                   # 是否检查CPU负载

# ==============================================
# 预设配置模式
# ==============================================

# 选择编译模式
# THERMAL_MODE 可以是: conservative, balanced, performance
export THERMAL_MODE=${THERMAL_MODE:-balanced}

case "$THERMAL_MODE" in
    "conservative")
        # 保守模式：优先保护硬件，编译速度较慢
        export MAX_TEMP=80
        export TEMP_WARNING=75
        export TEMP_SAFE=70
        export JOBS_RATIO_COOL=75
        export JOBS_RATIO_WARM=50
        export JOBS_RATIO_HOT=25
        export JOBS_RATIO_CRITICAL=10
        echo "🐌 启用保守编译模式：优先保护硬件温度"
        ;;
    "performance")
        # 性能模式：追求编译速度，温度控制相对宽松
        export MAX_TEMP=90
        export TEMP_WARNING=85
        export TEMP_SAFE=80
        export JOBS_RATIO_COOL=100
        export JOBS_RATIO_WARM=100
        export JOBS_RATIO_HOT=75
        export JOBS_RATIO_CRITICAL=50
        echo "🚀 启用性能编译模式：追求最大编译速度"
        ;;
    "balanced"|*)
        # 平衡模式：在温度和性能之间取得平衡（默认）
        export MAX_TEMP=85
        export TEMP_WARNING=80
        export TEMP_SAFE=75
        export JOBS_RATIO_COOL=100
        export JOBS_RATIO_WARM=75
        export JOBS_RATIO_HOT=50
        export JOBS_RATIO_CRITICAL=25
        echo "⚖️  启用平衡编译模式：温度与性能并重"
        ;;
esac

# ==============================================
# 辅助函数
# ==============================================

# 显示当前配置
show_thermal_config() {
    echo "=========================================="
    echo "        温度控制配置"
    echo "=========================================="
    echo "编译模式: $THERMAL_MODE"
    echo "最大温度: ${MAX_TEMP}°C"
    echo "警告温度: ${TEMP_WARNING}°C"
    echo "安全温度: ${TEMP_SAFE}°C"
    echo "自动调整任务数: $([ "$AUTO_ADJUST_JOBS" == "1" ] && echo "开启" || echo "关闭")"
    echo "最大编译任务数: $MAX_JOBS"
    echo "=========================================="
}

# 如果直接运行此脚本，显示配置信息
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    show_thermal_config
fi
