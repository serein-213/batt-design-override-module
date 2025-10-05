#!/usr/bin/env bash
set -euo pipefail

# 统一构建脚本：batt_design_override.ko + chg_param_override.ko
# 针对 Android 12 GKI 5.10 或任意 5.10 设备内核

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
# 为不同内核版本创建独立的模块目录，避免冲突
BATT_MOD_DIR="$WS_ROOT/extra_modules/v5.10/batt_design_override"
CHG_MOD_DIR="$WS_ROOT/extra_modules/v5.10/chg_param_override"

# 内置默认路径（可被环境变量覆盖）
KERNEL_SRC=${KERNEL_SRC:-"$WS_ROOT/gki/common-android12-5.10"}
KERNEL_OUT=${KERNEL_OUT:-"$WS_ROOT/gki/out-5.10"}

AUTO_NEW_OUT=${AUTO_NEW_OUT:-0}
STRICT_VERMAGIC=${STRICT_VERMAGIC:-0}
LTO_JOBS=${LTO_JOBS:-}
BUILD_BATT=${BUILD_BATT:-1}
BUILD_CHG=${BUILD_CHG:-1}

# 若仓库内存在 AOSP r450784e 的 clang，则默认加入 PATH
DEFAULT_CLANG_BIN="$WS_ROOT/toolchains/clang-linux-x86-goo/clang-r450784e/bin"
if [[ -d "$DEFAULT_CLANG_BIN" && -z "${CLANG_PATH:-}" ]]; then
  CLANG_PATH="$DEFAULT_CLANG_BIN"
fi

ARCH=${ARCH:-arm64}
LLVM=${LLVM:-1}
LLVM_IAS=${LLVM_IAS:-1}
CLANG_TRIPLE=${CLANG_TRIPLE:-aarch64-linux-gnu-}
# 提供 CROSS_COMPILE 仅用于推导 --target= 三元组；在 LLVM=1 时优先使用 llvm-* 工具
CROSS_COMPILE=${CROSS_COMPILE:-aarch64-linux-gnu-}
JOBS=${JOBS:-$(nproc)}

if [[ -n "${CLANG_PATH:-}" ]]; then
  export PATH="$CLANG_PATH:$PATH"
fi

echo "=========================================="
echo "    统一模块构建脚本 - 内核版本 5.10"
echo "=========================================="
echo "[i] KERNEL_SRC = $KERNEL_SRC"
echo "[i] KERNEL_OUT = $KERNEL_OUT"
echo "[i] BATT_MOD_DIR = $BATT_MOD_DIR"
echo "[i] CHG_MOD_DIR  = $CHG_MOD_DIR"
echo "[i] ARCH         = $ARCH"
echo "[i] LLVM         = $LLVM"
echo "[i] CROSS_COMPILE = ${CROSS_COMPILE:-<unset>}"
echo "[i] AUTO_NEW_OUT  = $AUTO_NEW_OUT"
echo "[i] STRICT_VERMAGIC = $STRICT_VERMAGIC"
echo "[i] LTO_JOBS      = ${LTO_JOBS:-<auto>}"
echo "[i] BUILD_BATT    = $BUILD_BATT"
echo "[i] BUILD_CHG     = $BUILD_CHG"
echo "=========================================="

if [[ ! -f "$KERNEL_SRC/Makefile" ]]; then
  echo "[!] 未找到 $KERNEL_SRC/Makefile，请确认 KERNEL_SRC 正确" >&2
  exit 1
fi

# 确保版本特定的模块目录存在，并复制源文件
mkdir -p "$(dirname "$BATT_MOD_DIR")"
mkdir -p "$(dirname "$CHG_MOD_DIR")"
if [[ ! -d "$BATT_MOD_DIR" ]]; then
  echo "[i] 创建5.10版本的batt模块目录"
  cp -r "$WS_ROOT/extra_modules/batt_design_override" "$BATT_MOD_DIR"
fi
if [[ ! -d "$CHG_MOD_DIR" ]]; then
  echo "[i] 创建5.10版本的chg模块目录"
  cp -r "$WS_ROOT/extra_modules/chg_param_override" "$CHG_MOD_DIR"
fi

SRC_SUBLEVEL=$(grep -E '^SUBLEVEL\s*=\s*' "$KERNEL_SRC/Makefile" | sed -E 's/.*=\s*//') || SRC_SUBLEVEL=?
if grep -Eq '^VERSION\s*=\s*5' "$KERNEL_SRC/Makefile" && \
   grep -Eq '^PATCHLEVEL\s*=\s*10' "$KERNEL_SRC/Makefile"; then
  echo "[i] 检测到 5.10 内核源码 (SUBLEVEL=$SRC_SUBLEVEL)"
else
  echo "[!] 警告：未检测到 5.10（VERSION/PATCHLEVEL），继续尝试构建..." >&2
fi

# 若已有 utsrelease.h，做版本比对
if [[ -f "$KERNEL_OUT/include/generated/utsrelease.h" ]]; then
  UTS_LINE=$(grep UTS_RELEASE "$KERNEL_OUT/include/generated/utsrelease.h" 2>/dev/null || true)
  OUT_VER=$(echo "$UTS_LINE" | sed -E 's/.*\"([^\"]+)\".*/\1/')
  OUT_BASE=$(echo "$OUT_VER" | cut -d- -f1)
  OUT_SUB=$(echo "$OUT_BASE" | cut -d. -f3)
  echo "[i] 现有 utsrelease: $OUT_VER (SUBLEVEL=$OUT_SUB)"
  if [[ -n "$OUT_SUB" && -n "$SRC_SUBLEVEL" && "$OUT_SUB" != "$SRC_SUBLEVEL" ]]; then
    echo "[!] 输出目录版本($OUT_SUB) 与源码($SRC_SUBLEVEL) 不一致"
    if [[ "$AUTO_NEW_OUT" == 1 ]]; then
      NEW_OUT="${KERNEL_OUT}-fresh-$(date +%s)"
      echo "[i] AUTO_NEW_OUT=1 -> 切换到新输出目录 $NEW_OUT"
      KERNEL_OUT="$NEW_OUT"
      mkdir -p "$KERNEL_OUT"
    elif [[ "$STRICT_VERMAGIC" == 1 ]]; then
      echo "[x] STRICT_VERMAGIC=1 -> 停止构建" >&2
      exit 2
    else
      echo "[!] 继续使用原输出目录，可能生成错误 vermagic" >&2
    fi
  fi
fi

# 确保输出目录存在
mkdir -p "$KERNEL_OUT"

# 需要 .config 以匹配目标设备/GKI 配置
if [[ ! -f "$KERNEL_OUT/.config" ]]; then
  echo "[i] $KERNEL_OUT/.config 不存在，尝试使用 gki_defconfig 生成（如非 GKI，请按设备 defconfig 自行调整）"
  make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
    CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" \
    LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
    READELF=llvm-readelf STRIP=llvm-strip \
    gki_defconfig
else
  echo "[i] 检测到现有 .config，执行 olddefconfig 以自动接受新选项默认值（避免交互）"
  make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
    CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" \
    LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
    READELF=llvm-readelf STRIP=llvm-strip \
    olddefconfig
fi

# 若系统缺少 cpio，禁用 IKHEADERS 以避免 gen_kheaders.sh 失败
if ! command -v cpio >/dev/null 2>&1; then
  echo "[i] 未检测到 cpio，禁用 CONFIG_IKHEADERS 以绕过 kheaders 打包"
  if grep -q '^CONFIG_IKHEADERS=' "$KERNEL_OUT/.config"; then
    sed -i 's/^CONFIG_IKHEADERS=.*/# CONFIG_IKHEADERS is not set/' "$KERNEL_OUT/.config"
  else
    echo '# CONFIG_IKHEADERS is not set' >> "$KERNEL_OUT/.config"
  fi
  make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
    CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" \
    LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
    READELF=llvm-readelf STRIP=llvm-strip \
    olddefconfig
fi

# 推荐先最小化构建以产出 Module.symvers，保证 MODVERSIONS 符号 CRC 匹配
if [[ ! -f "$KERNEL_OUT/Module.symvers" ]]; then
  echo "[i] 未发现 Module.symvers，执行最小化构建以生成（make modules）。这可能需要较长时间..."
  make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
    CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" LD=ld.lld \
    LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
    READELF=llvm-readelf STRIP=llvm-strip \
    -j"$JOBS" modules
fi

# ThinLTO 并行（仅在配置启用时生效）
if grep -q '^CONFIG_LTO_CLANG_THIN=y' "$KERNEL_OUT/.config" 2>/dev/null; then
  if [[ -z "$LTO_JOBS" ]]; then
    LTO_JOBS=$(nproc)
    echo "[i] 自动设定 LTO_JOBS=$LTO_JOBS (ThinLTO)"
  fi
  export KBUILD_LDFLAGS="${KBUILD_LDFLAGS:-} -Wl,-plugin-opt,jobs=${LTO_JOBS}"
  echo "[i] KBUILD_LDFLAGS += -Wl,-plugin-opt,jobs=${LTO_JOBS}"
fi

# 构建 batt_design_override 模块
if [[ "$BUILD_BATT" == 1 ]]; then
  echo ""
  echo "=========================================="
  echo "    开始编译 batt_design_override (5.10)"
  echo "=========================================="
  make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
    CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" LD=ld.lld \
    LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
    READELF=llvm-readelf STRIP=llvm-strip \
    M="$BATT_MOD_DIR" -j"$JOBS" modules

  BATT_KO_PATH="$BATT_MOD_DIR/batt_design_override.ko"
  if [[ -f "$BATT_KO_PATH" ]]; then
    echo "[✓] batt_design_override 完成: $BATT_KO_PATH"
  else
    echo "[x] batt_design_override 失败: 未生成 .ko" >&2
    exit 3
  fi
fi

# 构建 chg_param_override 模块
if [[ "$BUILD_CHG" == 1 ]]; then
  echo ""
  echo "=========================================="
  echo "    开始编译 chg_param_override (5.10)"
  echo "=========================================="
  make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
    CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" LD=ld.lld \
    LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
    READELF=llvm-readelf STRIP=llvm-strip \
    M="$CHG_MOD_DIR" -j"$JOBS" modules

  CHG_KO_PATH="$CHG_MOD_DIR/chg_param_override.ko"
  if [[ -f "$CHG_KO_PATH" ]]; then
    echo "[✓] chg_param_override 完成: $CHG_KO_PATH"
  else
    echo "[x] chg_param_override 失败: 未生成 .ko" >&2
    exit 4
  fi
fi

echo ""
echo "=========================================="
echo "           构建完成总结 (5.10)"
echo "=========================================="
if [[ "$BUILD_BATT" == 1 && -f "$BATT_KO_PATH" ]]; then
  echo "[✓] batt_design_override.ko: $(ls -lh "$BATT_KO_PATH" | awk '{print $5}')"
  command -v modinfo >/dev/null 2>&1 && echo "    $(modinfo "$BATT_KO_PATH" | grep vermagic || true)"
fi
if [[ "$BUILD_CHG" == 1 && -f "$CHG_KO_PATH" ]]; then
  echo "[✓] chg_param_override.ko: $(ls -lh "$CHG_KO_PATH" | awk '{print $5}')"
  command -v modinfo >/dev/null 2>&1 && echo "    $(modinfo "$CHG_KO_PATH" | grep vermagic || true)"
fi
echo "=========================================="

exit 0
