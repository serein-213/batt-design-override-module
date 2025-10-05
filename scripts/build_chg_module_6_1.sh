#!/usr/bin/env bash
set -euo pipefail

# 构建 chg_param_override.ko（Android 13/14 GKI 5.15.x）

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
MOD_DIR="$WS_ROOT/extra_modules/chg_param_override"

# 选择内核源码目录
KERNEL_SRC=${KERNEL_SRC:-}
if [[ -z "${KERNEL_SRC}" ]]; then
    for cand in \
        "$WS_ROOT/gki/common-android13-5.15"; do
        [[ -d "$cand" && -f "$cand/Makefile" ]] && KERNEL_SRC="$cand" && break
    done
fi

# 选择输出目录（优先使用现有）
KERNEL_OUT=${KERNEL_OUT:-}
if [[ -z "${KERNEL_OUT}" ]]; then
    for cand in \
        "$WS_ROOT/gki/out-5.15" \
        "$WS_ROOT/out-5.15" \
        "$WS_ROOT/out-fuxi" \
        "$WS_ROOT/gki/out"; do
        [[ -d "$cand" ]] && KERNEL_OUT="$cand" && break
    done
fi
KERNEL_OUT=${KERNEL_OUT:-"$WS_ROOT/gki/out-5.15"}

# 默认 clang
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
PATCH_FLAGS=${PATCH_FLAGS:-1}
AUTO_NEW_OUT=${AUTO_NEW_OUT:-0}
LTO_JOBS=${LTO_JOBS:-}

if [[ -n "${CLANG_PATH:-}" ]]; then
    export PATH="$CLANG_PATH:$PATH"
fi

echo "[i] KERNEL_SRC = $KERNEL_SRC"
echo "[i] KERNEL_OUT = $KERNEL_OUT"
echo "[i] MOD_DIR    = $MOD_DIR"
echo "[i] ARCH       = ${ARCH}"
echo "[i] LLVM       = ${LLVM}"
echo "[i] PATCH_FLAGS   = ${PATCH_FLAGS}"
echo "[i] AUTO_NEW_OUT  = ${AUTO_NEW_OUT}"

[[ -f "$KERNEL_SRC/Makefile" ]] || { echo "[!] 缺少 $KERNEL_SRC/Makefile" >&2; exit 1; }

# 检查 UTS 与 SUBLEVEL，必要时切新 out
SRC_SUBLEVEL=$(grep -E '^SUBLEVEL\s*=\s*' "$KERNEL_SRC/Makefile" | sed -E 's/.*=\s*//') || SRC_SUBLEVEL=?
if [[ -f "$KERNEL_OUT/include/generated/utsrelease.h" ]]; then
    UTS_LINE=$(grep UTS_RELEASE "$KERNEL_OUT/include/generated/utsrelease.h" 2>/dev/null || true)
    OUT_VER=$(echo "$UTS_LINE" | sed -E 's/.*"([^"]+)".*/\1/')
    OUT_BASE=$(echo "$OUT_VER" | cut -d- -f1)
    OUT_SUB=$(echo "$OUT_BASE" | cut -d. -f3)
    echo "[i] 现有 utsrelease: $OUT_VER (SUBLEVEL=$OUT_SUB) 源码 SUBLEVEL=$SRC_SUBLEVEL"
    if [[ "$AUTO_NEW_OUT" == 1 && -n "$OUT_SUB" && -n "$SRC_SUBLEVEL" && "$OUT_SUB" != "$SRC_SUBLEVEL" ]]; then
        NEW_OUT="${KERNEL_OUT}-fresh-$(date +%s)"
        echo "[i] 版本不匹配 -> 使用新输出目录: $NEW_OUT"
        KERNEL_OUT="$NEW_OUT"
    fi
fi
mkdir -p "$KERNEL_OUT"

# 生成 .config
if [[ ! -f "$KERNEL_OUT/.config" ]]; then
    echo "[i] 生成 gki_defconfig"
    make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
        CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" \
        LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
        READELF=llvm-readelf STRIP=llvm-strip \
        gki_defconfig
else
    echo "[i] olddefconfig"
    make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
        CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" \
        LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
        READELF=llvm-readelf STRIP=llvm-strip \
        olddefconfig
fi

# 无 cpio 时禁用 IKHEADERS
if ! command -v cpio >/dev/null 2>&1; then
    echo "[i] 未检测到 cpio，禁用 CONFIG_IKHEADERS"
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

# 缺少 Module.symvers 则先 make modules
if [[ ! -f "$KERNEL_OUT/Module.symvers" ]]; then
    echo "[i] 未发现 Module.symvers，执行 make modules（仅一次）"
    make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
        CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" LD=ld.lld \
        LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
        READELF=llvm-readelf STRIP=llvm-strip \
        -j"$JOBS" modules
fi

# ThinLTO 作业数
if grep -q '^CONFIG_LTO_CLANG_THIN=y' "$KERNEL_OUT/.config" 2>/dev/null; then
    if [[ -z "$LTO_JOBS" ]]; then
        LTO_JOBS=$(nproc)
        echo "[i] 检测到 ThinLTO -> LTO_JOBS=$LTO_JOBS"
    fi
    export KBUILD_LDFLAGS="${KBUILD_LDFLAGS:-} -Wl,-plugin-opt,jobs=${LTO_JOBS}"
fi

echo "[i] 开始编译 chg_param_override (5.15) ..."

# 编译器 wrapper：剥离空 -falign-functions= 并补 trivial-auto-var-init 选项
if [[ "$PATCH_FLAGS" == 1 ]]; then
    WRAP_DIR=$(mktemp -d)
    cat >"$WRAP_DIR/clang-wrapper" <<'EOF'
#!/usr/bin/env bash
ARGS=()
SEEN_TRIVIAL=0
ORIG=("$@")
for a in "$@"; do
  [[ "$a" == -falign-functions= ]] && continue
  [[ "$a" == -ftrivial-auto-var-init=zero ]] && SEEN_TRIVIAL=1
  ARGS+=("$a")
done
if [[ $SEEN_TRIVIAL -eq 1 ]]; then
  HAVE_ENABLE=0
  for x in "${ARGS[@]}"; do
    [[ "$x" == -enable-trivial-auto-var-init-zero-knowing-it-will-be-removed-from-clang ]] && HAVE_ENABLE=1 && break
  done
  [[ $HAVE_ENABLE -eq 0 ]] && ARGS+=("-enable-trivial-auto-var-init-zero-knowing-it-will-be-removed-from-clang")
else
  ARGS+=("-enable-trivial-auto-var-init-zero-knowing-it-will-be-removed-from-clang" "-ftrivial-auto-var-init=zero")
fi
CLANG_BIN=$(command -v clang || true)
[[ -x $(command -v clang-14 2>/dev/null) ]] && CLANG_BIN=$(command -v clang-14)
exec "$CLANG_BIN" "${ARGS[@]}"
EOF
    chmod +x "$WRAP_DIR/clang-wrapper"
    export CC="$WRAP_DIR/clang-wrapper"
    export HOSTCC=clang
    echo "[i] 启用编译器 wrapper: $CC"
fi

EXTRA_KCFLAGS="${KCFLAGS:-} -Wno-macro-redefined"
make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
    CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" LD=ld.lld \
    LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
    READELF=llvm-readelf STRIP=llvm-strip CC="${CC:-clang}" HOSTCC="${HOSTCC:-clang}" KCFLAGS="$EXTRA_KCFLAGS" \
    M="$MOD_DIR" -j"$JOBS" modules

KO_PATH="$MOD_DIR/chg_param_override.ko"
if [[ -f "$KO_PATH" ]]; then
    echo "[✓] 完成：$KO_PATH"
    if command -v modinfo >/dev/null 2>&1; then
        echo "[i] modinfo:"
        modinfo "$KO_PATH" | sed -n '1,16p'
    fi
else
    echo "[✗] 未生成 .ko" >&2
    exit 2
fi


echo "[done]"


