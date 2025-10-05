#!/usr/bin/env bash
set -euo pipefail

# 构建 batt_design_override.ko（针对 Android 16+/主线 6.6 / 其它 6.6 设备内核）
# 说明：本脚本尽量保持与 6.1 版本一致，但默认不假设存在 AOSP gki_defconfig。
# 若使用 AOSP android-6.6 分支，请设置 KERNEL_SRC/KERNEL_OUT 对应目录。

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
MOD_DIR="$WS_ROOT/extra_modules/batt_design_override"

# 默认不强制指定 android-6.6 源码目录；若未设置则尝试使用 gki/common 作为回退
KERNEL_SRC=${KERNEL_SRC:-}
if [[ -z "${KERNEL_SRC}" ]]; then
  for cand in \
    "$WS_ROOT/gki/common"; do
    [[ -d "$cand" && -f "$cand/Makefile" ]] && KERNEL_SRC="$cand" && break
  done
fi
[[ -n "${KERNEL_SRC}" ]] || { echo "[!] 请设置 KERNEL_SRC 指向 6.6 内核源码" >&2; exit 1; }

KERNEL_OUT=${KERNEL_OUT:-"$WS_ROOT/gki/out-6.6"}

AUTO_NEW_OUT=${AUTO_NEW_OUT:-0}
LTO_JOBS=${LTO_JOBS:-}
VERBOSE=${VERBOSE:-0}
PATCH_FLAGS=${PATCH_FLAGS:-1}

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

echo "[i] KERNEL_SRC = $KERNEL_SRC"
echo "[i] KERNEL_OUT = $KERNEL_OUT"
echo "[i] MOD_DIR    = $MOD_DIR"
echo "[i] ARCH       = $ARCH"
echo "[i] LLVM       = $LLVM"
echo "[i] CROSS_COMPILE = ${CROSS_COMPILE:-<unset>}"
echo "[i] AUTO_NEW_OUT  = $AUTO_NEW_OUT"

[[ -f "$KERNEL_SRC/Makefile" ]] || { echo "[!] 未找到 $KERNEL_SRC/Makefile" >&2; exit 1; }

SRC_SUBLEVEL=$(grep -E '^SUBLEVEL\s*=\s*' "$KERNEL_SRC/Makefile" | sed -E 's/.*=\s*//') || SRC_SUBLEVEL=?
if grep -Eq '^VERSION\s*=\s*6' "$KERNEL_SRC/Makefile" && \
   grep -Eq '^PATCHLEVEL\s*=\s*6' "$KERNEL_SRC/Makefile"; then
  echo "[i] 检测到 6.6 内核源码 (SUBLEVEL=$SRC_SUBLEVEL)"
else
  echo "[!] 警告：非 6.6 版本，仍尝试构建" >&2
fi

if [[ -f "$KERNEL_OUT/include/generated/utsrelease.h" ]]; then
  UTS_LINE=$(grep UTS_RELEASE "$KERNEL_OUT/include/generated/utsrelease.h" 2>/dev/null || true)
  OUT_VER=$(echo "$UTS_LINE" | sed -E 's/.*"([^"]+)".*/\1/')
  OUT_BASE=$(echo "$OUT_VER" | cut -d- -f1)
  OUT_SUB=$(echo "$OUT_BASE" | cut -d. -f3)
  echo "[i] 现有 utsrelease: $OUT_VER (SUBLEVEL=$OUT_SUB)"
  if [[ "$AUTO_NEW_OUT" == 1 && -n "$OUT_SUB" && -n "$SRC_SUBLEVEL" && "$OUT_SUB" != "$SRC_SUBLEVEL" ]]; then
    NEW_OUT="${KERNEL_OUT}-fresh-$(date +%s)"
    echo "[i] 版本不匹配 -> 使用新输出目录 $NEW_OUT"
    KERNEL_OUT="$NEW_OUT"; mkdir -p "$KERNEL_OUT"
  fi
fi

mkdir -p "$KERNEL_OUT"

# 生成 .config：优先 gki_defconfig；否则 defconfig；若均无则提示手动准备
if [[ ! -f "$KERNEL_OUT/.config" ]]; then
  if [[ -f "$KERNEL_SRC/arch/$ARCH/configs/gki_defconfig" ]]; then
    make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
      CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" \
      LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
      READELF=llvm-readelf STRIP=llvm-strip \
      gki_defconfig
  elif [[ -f "$KERNEL_SRC/arch/$ARCH/configs/defconfig" ]]; then
    make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
      CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" \
      LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
      READELF=llvm-readelf STRIP=llvm-strip \
      defconfig
  else
    echo "[!] 未找到 gki_defconfig/defconfig，请先准备 $KERNEL_OUT/.config" >&2
  fi
else
  make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
    CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" \
    LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
    READELF=llvm-readelf STRIP=llvm-strip \
    olddefconfig
fi

# 缺少 cpio 时禁用 IKHEADERS
if ! command -v cpio >/dev/null 2>&1; then
  echo "[i] 缺少 cpio -> 禁用 CONFIG_IKHEADERS"
  if grep -q '^CONFIG_IKHEADERS=' "$KERNEL_OUT/.config" 2>/dev/null; then
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

# 若缺少 Module.symvers，先最小化构建生成一次
if [[ ! -f "$KERNEL_OUT/Module.symvers" ]]; then
  echo "[i] 生成 Module.symvers"
  make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
    CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" LD=ld.lld \
    LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
    READELF=llvm-readelf STRIP=llvm-strip \
    -j"$JOBS" modules
fi

# ThinLTO 并行
if grep -q '^CONFIG_LTO_CLANG_THIN=y' "$KERNEL_OUT/.config" 2>/dev/null; then
  if [[ -z "$LTO_JOBS" ]]; then LTO_JOBS=$(nproc); fi
  export KBUILD_LDFLAGS="${KBUILD_LDFLAGS:-} -Wl,-plugin-opt,jobs=${LTO_JOBS}"
fi

[[ "$VERBOSE" == 1 ]] && set -x || true
make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
  CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" LD=ld.lld \
  LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
  READELF=llvm-readelf STRIP=llvm-strip \
  M="$MOD_DIR" -j"$JOBS" modules
[[ "$VERBOSE" == 1 ]] && set +x || true

KO_PATH="$MOD_DIR/batt_design_override.ko"
if [[ -f "$KO_PATH" ]]; then
  echo "[✓] 完成: $KO_PATH"
  command -v modinfo >/dev/null 2>&1 && modinfo "$KO_PATH" | sed -n '1,6p'
else
  echo "[x] 失败：未生成 .ko" >&2; exit 3
fi

exit 0


