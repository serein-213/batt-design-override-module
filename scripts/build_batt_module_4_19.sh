#!/usr/bin/env bash
set -euo pipefail

# 构建 batt_design_override.ko（针对 Android 10/厂商 4.19 / GKI 4.19 内核）
# 逻辑与 5.4/5.10 脚本类似；若你的仓库不存在所设默认路径，请手动传入 KERNEL_SRC。
# 示例：
#   KERNEL_SRC=$HOME/kernel/vendor-4.19 \
#   KERNEL_OUT=$HOME/kernel/out-4.19 \
#   ./scripts/build_batt_module_4_19.sh

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
MOD_DIR="$WS_ROOT/extra_modules/batt_design_override"

KERNEL_SRC=${KERNEL_SRC:-"$WS_ROOT/gki/common-android10-4.19"}
KERNEL_OUT=${KERNEL_OUT:-"$WS_ROOT/gki/out-4.19"}

AUTO_NEW_OUT=${AUTO_NEW_OUT:-0}
STRICT_VERMAGIC=${STRICT_VERMAGIC:-0}
LTO_JOBS=${LTO_JOBS:-}

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
echo "[i] STRICT_VERMAGIC = $STRICT_VERMAGIC"

[[ -f "$KERNEL_SRC/Makefile" ]] || { echo "[!] 未找到 $KERNEL_SRC/Makefile (请设置 KERNEL_SRC)" >&2; exit 1; }

SRC_SUBLEVEL=$(grep -E '^SUBLEVEL\s*=\s*' "$KERNEL_SRC/Makefile" | sed -E 's/.*=\s*//') || SRC_SUBLEVEL=?
if grep -Eq '^VERSION\s*=\s*4' "$KERNEL_SRC/Makefile" && \
   grep -Eq '^PATCHLEVEL\s*=\s*19' "$KERNEL_SRC/Makefile"; then
  echo "[i] 检测到 4.19 内核源码 (SUBLEVEL=$SRC_SUBLEVEL)"
else
  echo "[!] 警告：非 4.19 版本 (继续尝试构建)" >&2
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
      echo "[i] AUTO_NEW_OUT=1 -> 新输出目录 $NEW_OUT"
      KERNEL_OUT="$NEW_OUT"; mkdir -p "$KERNEL_OUT"
    elif [[ "$STRICT_VERMAGIC" == 1 ]]; then
      echo "[x] STRICT_VERMAGIC=1 -> 停止" >&2; exit 2
    fi
  fi
fi

mkdir -p "$KERNEL_OUT"

if [[ ! -f "$KERNEL_OUT/.config" ]]; then
  echo "[i] 生成 gki_defconfig (如不适合请自行传入设备 defconfig)"
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

echo "[i] 开始编译外部模块 (4.19)"
make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
  CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" LD=ld.lld \
  LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
  READELF=llvm-readelf STRIP=llvm-strip \
  M="$MOD_DIR" -j"$JOBS" modules

KO_PATH="$MOD_DIR/batt_design_override.ko"
[[ -f "$KO_PATH" ]] && echo "[✓] 完成: $KO_PATH" || { echo "[x] 失败: 未生成 .ko" >&2; exit 3; }

exit 0
