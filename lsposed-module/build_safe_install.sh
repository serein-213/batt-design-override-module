#!/usr/bin/env bash
set -euo pipefail


SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
APP_DIR="$SCRIPT_DIR/app"

# 检查 Gradle 是否可用
if ! command -v gradle >/dev/null 2>&1; then
    echo "[!] 警告: 未检测到 gradle 命令。尝试使用 gradlew。"
    if ! command -v "$SCRIPT_DIR/gradlew" >/dev/null 2>&1; then
        echo "[x] 错误: gradlew 脚本也不可用。请确保 Gradle 已安装或 gradlew 可执行。" >&2
        exit 1
    fi
    GRADLE_CMD="$SCRIPT_DIR/gradlew"
else
    GRADLE_CMD="gradle"
fi

echo "[i] 使用命令: $GRADLE_CMD"

# 清理旧的构建
echo "[i] 清理旧的构建..."
"$GRADLE_CMD" -p "$APP_DIR" clean

# 构建 release APK
echo "[i] 开始构建 release APK..."
"$GRADLE_CMD" -p "$APP_DIR" assembleRelease

APK_PATH="$APP_DIR/build/outputs/apk/release/app-release.apk"

if [[ -f "$APK_PATH" ]]; then
    echo "[✓] APK 构建成功: $APK_PATH"
    echo "[i] 文件大小: $(ls -lh "$APK_PATH" | awk '{print $5}')"
else
    echo "[x] 错误: APK 构建失败，未找到文件: $APK_PATH" >&2
    exit 2
fi

echo "=========================================="
echo "           构建完成总结"
echo "=========================================="
echo "[✓] LSPosed 模块 APK 已生成: $APK_PATH"
echo ""
echo "下一步: 将此 APK 安装到您的设备进行测试。"
echo "提示: 原'安全安装'独立流程已整合为默认安装逻辑，相关指南已废弃。"
echo "=========================================="

echo "[done]"