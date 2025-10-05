#!/usr/bin/env bash
set -euo pipefail

# 统一构建脚本：batt_design_override.ko + chg_param_override.ko
# 针对 Android 11 GKI 5.4 / 其他 5.4 设备内核

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
# 为不同内核版本创建独立的模块目录，避免冲突
BATT_MOD_DIR="$WS_ROOT/extra_modules/v5.4/batt_design_override"
CHG_MOD_DIR="$WS_ROOT/extra_modules/v5.4/chg_param_override"

KERNEL_SRC=${KERNEL_SRC:-"$WS_ROOT/gki/common-android11-5.4"}
KERNEL_OUT=${KERNEL_OUT:-"$WS_ROOT/gki/out-5.4"}

AUTO_NEW_OUT=${AUTO_NEW_OUT:-0}
STRICT_VERMAGIC=${STRICT_VERMAGIC:-0}
LTO_JOBS=${LTO_JOBS:-}
BUILD_BATT=${BUILD_BATT:-1}
BUILD_CHG=${BUILD_CHG:-1}

DEFAULT_CLANG_BIN="$WS_ROOT/toolchains/clang-linux-x86-goo/clang-r450784e/bin"
if [[ -d "$DEFAULT_CLANG_BIN" && -z "${CLANG_PATH:-}" ]]; then
  CLANG_PATH="$DEFAULT_CLANG_BIN"
fi

ARCH=${ARCH:-arm64}
LLVM=${LLVM:-1}
LLVM_IAS=${LLVM_IAS:-1}
CLANG_TRIPLE=${CLANG_TRIPLE:-aarch64-linux-gnu-}
CROSS_COMPILE=${CROSS_COMPILE:-aarch64-linux-gnu-}
JOBS=${JOBS:-$(nproc)}

if [[ -n "${CLANG_PATH:-}" ]]; then
  export PATH="$CLANG_PATH:$PATH"
fi

echo "=========================================="
echo "    统一模块构建脚本 - 内核版本 5.4"
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
echo "[i] BUILD_BATT    = $BUILD_BATT"
echo "[i] BUILD_CHG     = $BUILD_CHG"
echo "=========================================="

[[ -f "$KERNEL_SRC/Makefile" ]] || { echo "[!] 未找到 $KERNEL_SRC/Makefile" >&2; exit 1; }

# 确保版本特定的模块目录存在，并复制源文件
mkdir -p "$(dirname "$BATT_MOD_DIR")"
mkdir -p "$(dirname "$CHG_MOD_DIR")"
if [[ ! -d "$BATT_MOD_DIR" ]]; then
  echo "[i] 创建5.4版本的batt模块目录"
  cp -r "$WS_ROOT/extra_modules/batt_design_override" "$BATT_MOD_DIR"
fi
if [[ ! -d "$CHG_MOD_DIR" ]]; then
  echo "[i] 创建5.4版本的chg模块目录"
  cp -r "$WS_ROOT/extra_modules/chg_param_override" "$CHG_MOD_DIR"
fi

SRC_SUBLEVEL=$(grep -E '^SUBLEVEL\s*=\s*' "$KERNEL_SRC/Makefile" | sed -E 's/.*=\s*//') || SRC_SUBLEVEL=?
if grep -Eq '^VERSION\s*=\s*5' "$KERNEL_SRC/Makefile" && \
   grep -Eq '^PATCHLEVEL\s*=\s*4' "$KERNEL_SRC/Makefile"; then
  echo "[i] 检测到 5.4 内核源码 (SUBLEVEL=$SRC_SUBLEVEL)"
else
  echo "[!] 警告：非 5.4 版本，继续尝试构建" >&2
fi

if [[ -f "$KERNEL_OUT/include/generated/utsrelease.h" ]]; then
  UTS_LINE=$(grep UTS_RELEASE "$KERNEL_OUT/include/generated/utsrelease.h" 2>/dev/null || true)
  OUT_VER=$(echo "$UTS_LINE" | sed -E 's/.*"([^"]+)".*/\1/')
  OUT_BASE=$(echo "$OUT_VER" | cut -d- -f1)
  OUT_SUB=$(echo "$OUT_BASE" | cut -d. -f3)
  echo "[i] 现有 utsrelease: $OUT_VER (SUBLEVEL=$OUT_SUB)"
  if [[ -n "$OUT_SUB" && -n "$SRC_SUBLEVEL" && "$OUT_SUB" != "$SRC_SUBLEVEL" ]]; then
    echo "[!] 输出目录版本($OUT_SUB) 与源码($SRC_SUBLEVEL) 不一致"
    if [[ "$AUTO_NEW_OUT" == 1 ]]; then
      NEW_OUT="${KERNEL_OUT}-fresh-$(date +%s)"
      echo "[i] AUTO_NEW_OUT=1 -> 使用新输出目录 $NEW_OUT"
      KERNEL_OUT="$NEW_OUT"
      mkdir -p "$KERNEL_OUT"
    elif [[ "$STRICT_VERMAGIC" == 1 ]]; then
      echo "[x] STRICT_VERMAGIC=1 -> 退出" >&2; exit 2
    fi
  fi
fi

mkdir -p "$KERNEL_OUT"

if [[ ! -f "$KERNEL_OUT/.config" ]]; then
  echo "[i] 生成 gki_defconfig (可根据设备修改)"
  make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
    CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" \
    LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
    READELF=llvm-readelf STRIP=llvm-strip \
    gki_defconfig
else
  make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
    CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" \
    LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
    READELF=llvm-readelf STRIP=llvm-strip \
    olddefconfig
fi

if ! command -v cpio >/dev/null 2>&1; then
  echo "[i] 禁用 CONFIG_IKHEADERS (缺少 cpio)"
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

if [[ ! -f "$KERNEL_OUT/Module.symvers" ]]; then
  echo "[i] 生成 Module.symvers (make modules)"
  make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
    CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" LD=ld.lld \
    LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
    READELF=llvm-readelf STRIP=llvm-strip \
    -j"$JOBS" modules
fi

# 构建 batt_design_override 模块
if [[ "$BUILD_BATT" == 1 ]]; then
  echo ""
  echo "=========================================="
  echo "    开始编译 batt_design_override (5.4)"
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
  echo "    开始编译 chg_param_override (5.4)"
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
echo "           构建完成总结 (5.4)"
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
