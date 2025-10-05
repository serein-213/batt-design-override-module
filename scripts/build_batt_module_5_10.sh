#!/usr/bin/env bash
set -euo pipefail

# 构建 batt_design_override.ko（针对 Android 12 GKI 5.10 或任意 5.10 设备内核）
# 先决条件：
#  - 已准备好 5.10 的内核源码目录（KERNEL_SRC），且能被正常构建
#  - 已准备好 5.10 的构建输出目录（KERNEL_OUT），其中包含 .config；建议先完整/最小化构建一次以生成 Module.symvers
#  - 可用的 Clang/LLVM 工具链（Android prebuilt clang 或主机 clang 均可，但为保证 vermagic 一致，推荐与目标内核一致的 clang 版本）
#
# 用法示例：
#   KERNEL_SRC=$HOME/android/kernel/common-android12-5.10 \
#   KERNEL_OUT=$HOME/android/kernel/out-5.10 \
#   ./scripts/build_batt_module_5_10.sh
#
# 可选环境变量：
#   ARCH=arm64 (默认)
#   LLVM=1     (默认，使用 clang)
#   CLANG_PATH=</path/to/clang/bin>  (若已加入 PATH 可不设)
#   CROSS_COMPILE=aarch64-linux-gnu- 或 aarch64-linux-android- （按内核构建习惯设置）
#   JOBS=$(nproc)
#   AUTO_NEW_OUT=1  检测 utsrelease 与源码 SUBLEVEL 不一致时自动创建新输出目录
#   STRICT_VERMAGIC=1  若发现版本不匹配立即退出（默认 0 仅警告）
#   LTO_JOBS=<n>  若启用 ThinLTO，设置后端并行度（未设置时自动 = nproc）

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
MOD_DIR="$WS_ROOT/extra_modules/batt_design_override"

# 内置默认路径（可被环境变量覆盖）
KERNEL_SRC=${KERNEL_SRC:-"$WS_ROOT/gki/common-android12-5.10"}
KERNEL_OUT=${KERNEL_OUT:-"$WS_ROOT/gki/out-5.10"}

AUTO_NEW_OUT=${AUTO_NEW_OUT:-0}
STRICT_VERMAGIC=${STRICT_VERMAGIC:-0}
LTO_JOBS=${LTO_JOBS:-}

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

echo "[i] KERNEL_SRC = $KERNEL_SRC"
echo "[i] KERNEL_OUT = $KERNEL_OUT"
echo "[i] MOD_DIR    = $MOD_DIR"
echo "[i] ARCH       = $ARCH"
echo "[i] LLVM       = $LLVM"
echo "[i] CROSS_COMPILE = ${CROSS_COMPILE:-<unset>}"
echo "[i] AUTO_NEW_OUT  = $AUTO_NEW_OUT"
echo "[i] STRICT_VERMAGIC = $STRICT_VERMAGIC"
echo "[i] LTO_JOBS      = ${LTO_JOBS:-<auto>}"

if [[ ! -f "$KERNEL_SRC/Makefile" ]]; then
  echo "[!] 未找到 $KERNEL_SRC/Makefile，请确认 KERNEL_SRC 正确" >&2
  exit 1
fi

SRC_SUBLEVEL=$(grep -E '^SUBLEVEL\\s*=\\s*' "$KERNEL_SRC/Makefile" | sed -E 's/.*=\\s*//') || SRC_SUBLEVEL=?
if grep -Eq '^VERSION\\s*=\\s*5' "$KERNEL_SRC/Makefile" && \
   grep -Eq '^PATCHLEVEL\\s*=\\s*10' "$KERNEL_SRC/Makefile"; then
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

echo "[i] 开始编译外部模块..."
# ThinLTO 并行（仅在配置启用时生效）
if grep -q '^CONFIG_LTO_CLANG_THIN=y' "$KERNEL_OUT/.config" 2>/dev/null; then
  if [[ -z "$LTO_JOBS" ]]; then
    LTO_JOBS=$(nproc)
    echo "[i] 自动设定 LTO_JOBS=$LTO_JOBS (ThinLTO)"
  fi
  export KBUILD_LDFLAGS="${KBUILD_LDFLAGS:-} -Wl,-plugin-opt,jobs=${LTO_JOBS}"
  echo "[i] KBUILD_LDFLAGS += -Wl,-plugin-opt,jobs=${LTO_JOBS}"
fi
make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
  CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" LD=ld.lld \
  LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
  READELF=llvm-readelf STRIP=llvm-strip \
  M="$MOD_DIR" -j"$JOBS" modules

echo "[✓] 完成：$(ls -l "$MOD_DIR"/batt_design_override.ko 2>/dev/null || echo '未生成 .ko，请检查上方日志')"
