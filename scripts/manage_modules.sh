#!/usr/bin/env bash
set -euo pipefail

# 内核模块版本管理脚本
# 用于管理不同内核版本的batt和chg模块

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
MODULES_ROOT="$WS_ROOT/extra_modules"

# 支持的版本
SUPPORTED_VERSIONS=("5.4" "5.10" "5.15" "6.1")

show_usage() {
    cat <<EOF
内核模块版本管理工具

用法: $0 <命令> [选项]

命令:
    list                    - 列出所有可用版本的模块
    build <版本>            - 构建指定版本的模块
    clean <版本>            - 清理指定版本的模块构建产物
    clean-all               - 清理所有版本的构建产物
    link <版本>             - 将指定版本的模块链接到主目录
    info <版本>             - 显示指定版本模块的详细信息
    compare                 - 比较不同版本模块的大小和vermagic

版本: ${SUPPORTED_VERSIONS[*]}

示例:
    $0 list                 # 列出所有版本
    $0 build 5.15           # 构建5.15版本
    $0 link 5.15            # 将5.15版本链接到主目录
    $0 info 5.15            # 查看5.15版本信息
    $0 clean 5.10           # 清理5.10版本
    $0 compare              # 比较所有版本

环境变量:
    BUILD_BATT=0/1          # 控制是否构建batt模块（默认1）
    BUILD_CHG=0/1           # 控制是否构建chg模块（默认1）
EOF
}

validate_version() {
    local version="$1"
    for v in "${SUPPORTED_VERSIONS[@]}"; do
        [[ "$v" == "$version" ]] && return 0
    done
    echo "[!] 不支持的版本: $version" >&2
    echo "[i] 支持的版本: ${SUPPORTED_VERSIONS[*]}" >&2
    return 1
}

list_modules() {
    echo "=========================================="
    echo "           可用模块版本列表"
    echo "=========================================="
    
    for version in "${SUPPORTED_VERSIONS[@]}"; do
        local ver_dir="$MODULES_ROOT/v$version"
        echo "[版本 $version]"
        
        if [[ -d "$ver_dir" ]]; then
            local batt_ko="$ver_dir/batt_design_override/batt_design_override.ko"
            local chg_ko="$ver_dir/chg_param_override/chg_param_override.ko"
            
            if [[ -f "$batt_ko" ]]; then
                local size=$(ls -lh "$batt_ko" | awk '{print $5}')
                echo "  ✓ batt_design_override.ko ($size)"
            else
                echo "  ✗ batt_design_override.ko (未构建)"
            fi
            
            if [[ -f "$chg_ko" ]]; then
                local size=$(ls -lh "$chg_ko" | awk '{print $5}')
                echo "  ✓ chg_param_override.ko ($size)"
            else
                echo "  ✗ chg_param_override.ko (未构建)"
            fi
        else
            echo "  ✗ 目录不存在 (未初始化)"
        fi
        echo ""
    done
    
    # 显示主目录状态
    echo "[主目录 (extra_modules)]"
    local main_batt="$MODULES_ROOT/batt_design_override/batt_design_override.ko"
    local main_chg="$MODULES_ROOT/chg_param_override/chg_param_override.ko"
    
    if [[ -f "$main_batt" ]] && command -v modinfo >/dev/null 2>&1; then
        local vermagic=$(modinfo "$main_batt" | grep vermagic | cut -d: -f2 | xargs)
        local size=$(ls -lh "$main_batt" | awk '{print $5}')
        echo "  → batt_design_override.ko ($size) - $vermagic"
    else
        echo "  → batt_design_override.ko (不存在)"
    fi
    
    if [[ -f "$main_chg" ]] && command -v modinfo >/dev/null 2>&1; then
        local vermagic=$(modinfo "$main_chg" | grep vermagic | cut -d: -f2 | xargs)
        local size=$(ls -lh "$main_chg" | awk '{print $5}')
        echo "  → chg_param_override.ko ($size) - $vermagic"
    else
        echo "  → chg_param_override.ko (不存在)"
    fi
    echo "=========================================="
}

build_version() {
    local version="$1"
    validate_version "$version" || return 1
    
    local build_script="$SCRIPT_DIR/build_modules_${version}.sh"
    if [[ ! -f "$build_script" ]]; then
        echo "[!] 构建脚本不存在: $build_script" >&2
        return 1
    fi
    
    echo "[i] 构建版本 $version 的模块..."
    exec "$build_script"
}

clean_version() {
    local version="$1"
    validate_version "$version" || return 1
    
    local ver_dir="$MODULES_ROOT/v$version"
    if [[ ! -d "$ver_dir" ]]; then
        echo "[i] 版本 $version 目录不存在，无需清理"
        return 0
    fi
    
    echo "[i] 清理版本 $version 的构建产物..."
    
    for module_dir in "$ver_dir"/*; do
        [[ -d "$module_dir" ]] || continue
        
        echo "  清理 $(basename "$module_dir")..."
        find "$module_dir" -name "*.ko" -o -name "*.o" -o -name "*.mod*" -o -name "*.symvers" -o -name "*.order" | while read -r file; do
            [[ -f "$file" ]] && rm -f "$file" && echo "    删除: $(basename "$file")"
        done
    done
    
    echo "[✓] 版本 $version 清理完成"
}

clean_all() {
    echo "[i] 清理所有版本的构建产物..."
    for version in "${SUPPORTED_VERSIONS[@]}"; do
        clean_version "$version" 2>/dev/null || true
    done
    echo "[✓] 所有版本清理完成"
}

link_version() {
    local version="$1"
    validate_version "$version" || return 1
    
    local ver_dir="$MODULES_ROOT/v$version"
    local main_batt="$MODULES_ROOT/batt_design_override"
    local main_chg="$MODULES_ROOT/chg_param_override"
    local ver_batt="$ver_dir/batt_design_override"
    local ver_chg="$ver_dir/chg_param_override"
    
    if [[ ! -d "$ver_dir" ]]; then
        echo "[!] 版本 $version 目录不存在" >&2
        return 1
    fi
    
    echo "[i] 将版本 $version 链接到主目录..."
    
    # 备份现有的主目录（如果不是链接）
    for main_dir in "$main_batt" "$main_chg"; do
        if [[ -d "$main_dir" && ! -L "$main_dir" ]]; then
            local backup="${main_dir}.backup.$(date +%s)"
            echo "  备份现有目录: $(basename "$main_dir") -> $(basename "$backup")"
            mv "$main_dir" "$backup"
        elif [[ -L "$main_dir" ]]; then
            echo "  删除现有链接: $(basename "$main_dir")"
            rm -f "$main_dir"
        fi
    done
    
    # 创建符号链接
    if [[ -d "$ver_batt" ]]; then
        ln -sf "v$version/batt_design_override" "$main_batt"
        echo "  ✓ 链接 batt_design_override -> v$version"
    fi
    
    if [[ -d "$ver_chg" ]]; then
        ln -sf "v$version/chg_param_override" "$main_chg"
        echo "  ✓ 链接 chg_param_override -> v$version"
    fi
    
    echo "[✓] 版本 $version 链接完成"
}

show_info() {
    local version="$1"
    validate_version "$version" || return 1
    
    local ver_dir="$MODULES_ROOT/v$version"
    if [[ ! -d "$ver_dir" ]]; then
        echo "[!] 版本 $version 目录不存在" >&2
        return 1
    fi
    
    echo "=========================================="
    echo "         版本 $version 模块信息"
    echo "=========================================="
    
    local batt_ko="$ver_dir/batt_design_override/batt_design_override.ko"
    local chg_ko="$ver_dir/chg_param_override/chg_param_override.ko"
    
    for ko in "$batt_ko" "$chg_ko"; do
        if [[ -f "$ko" ]]; then
            echo "[$(basename "$ko")]"
            echo "  文件大小: $(ls -lh "$ko" | awk '{print $5}')"
            echo "  修改时间: $(ls -l "$ko" | awk '{print $6, $7, $8}')"
            
            if command -v modinfo >/dev/null 2>&1; then
                echo "  vermagic: $(modinfo "$ko" | grep vermagic | cut -d: -f2 | xargs)"
                echo "  描述: $(modinfo "$ko" | grep description | cut -d: -f2 | xargs || echo "无")"
            fi
            echo ""
        else
            echo "[$(basename "$ko")] - 未构建"
            echo ""
        fi
    done
}

compare_versions() {
    echo "=========================================="
    echo "           版本对比"
    echo "=========================================="
    
    printf "%-8s %-20s %-10s %-30s\n" "版本" "模块" "大小" "vermagic"
    echo "----------------------------------------"
    
    for version in "${SUPPORTED_VERSIONS[@]}"; do
        local ver_dir="$MODULES_ROOT/v$version"
        [[ -d "$ver_dir" ]] || continue
        
        local batt_ko="$ver_dir/batt_design_override/batt_design_override.ko"
        local chg_ko="$ver_dir/chg_param_override/chg_param_override.ko"
        
        for ko in "$batt_ko" "$chg_ko"; do
            if [[ -f "$ko" ]]; then
                local name=$(basename "$ko" .ko)
                local size=$(ls -lh "$ko" | awk '{print $5}')
                local vermagic="未知"
                
                if command -v modinfo >/dev/null 2>&1; then
                    vermagic=$(modinfo "$ko" | grep vermagic | cut -d: -f2 | xargs | cut -d' ' -f1)
                fi
                
                printf "%-8s %-20s %-10s %-30s\n" "$version" "${name:0:20}" "$size" "${vermagic:0:30}"
            fi
        done
    done
    echo "=========================================="
}

# 主逻辑
case "${1:-}" in
    list)
        list_modules
        ;;
    build)
        [[ -n "${2:-}" ]] || { echo "[!] 请指定版本" >&2; show_usage; exit 1; }
        build_version "$2"
        ;;
    clean)
        [[ -n "${2:-}" ]] || { echo "[!] 请指定版本" >&2; show_usage; exit 1; }
        clean_version "$2"
        ;;
    clean-all)
        clean_all
        ;;
    link)
        [[ -n "${2:-}" ]] || { echo "[!] 请指定版本" >&2; show_usage; exit 1; }
        link_version "$2"
        ;;
    info)
        [[ -n "${2:-}" ]] || { echo "[!] 请指定版本" >&2; show_usage; exit 1; }
        show_info "$2"
        ;;
    compare)
        compare_versions
        ;;
    help|--help|-h)
        show_usage
        ;;
    *)
        echo "[!] 未知命令: ${1:-}" >&2
        show_usage
        exit 1
        ;;
esac
