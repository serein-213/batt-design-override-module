#!/usr/bin/env bash
set -euo pipefail

# Simple kernel build helper with fast and release modes
# - fast:  switch to ThinLTO, drop heavy debug/symbol options to speed up build
# - release: restore from current_defconfig and keep vendor-like heavy options

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")"/.. && pwd)
KROOT="$ROOT_DIR/xiaomi-kernel/src/Xiaomi_Kernel_OpenSource-fuxi-t-oss"
KOUT="${KOUT:-$ROOT_DIR/out-fuxi}"

# Prefer checked-out Android clang 14.0.7
TC1="$ROOT_DIR/toolchains/clang-linux-x86-goo/clang-r450784e/bin"
TC2="$ROOT_DIR/toolchains/clang-linux-x86/clang-r450784e/bin"
if [[ -x "$TC1/clang" ]]; then
  TC_BIN="$TC1"
elif [[ -x "$TC2/clang" ]]; then
  TC_BIN="$TC2"
else
  echo "[!] Android clang 14.0.7 not found under toolchains/. Falling back to system clang." >&2
  TC_BIN=""
fi

export PATH="$TC_BIN:$PATH"

mode="${1:-fast}" # fast | release | olddefconfig | build
shift || true

ensure_out_config() {
  mkdir -p "$KOUT"
  if [[ ! -f "$KOUT/.config" ]]; then
    cp -f "$ROOT_DIR/current_defconfig" "$KOUT/.config"
  fi
}

olddefconfig() {
  echo "-- olddefconfig --"
  make -C "$KROOT" O="$KOUT" ARCH=arm64 LLVM=1 LLVM_IAS=1 olddefconfig
}

patch_config_fast() {
  echo "-- apply fast config toggles --"
  local cfg="$KOUT/.config"

  # Avoid requiring host cpio (kheaders)
  grep -q '^CONFIG_IKHEADERS=y' "$cfg" && sed -i 's/^CONFIG_IKHEADERS=y/# CONFIG_IKHEADERS is not set/' "$cfg" || true
  grep -q '^# CONFIG_IKHEADERS is not set' "$cfg" || echo '# CONFIG_IKHEADERS is not set' >> "$cfg"

  # Drop missing whitelist path and avoid symbol trimming surprises during dev
  sed -i '/^CONFIG_UNUSED_KSYMS_WHITELIST=/d' "$cfg" || true
  if grep -q '^CONFIG_TRIM_UNUSED_KSYMS=y' "$cfg"; then
    sed -i 's/^CONFIG_TRIM_UNUSED_KSYMS=y/# CONFIG_TRIM_UNUSED_KSYMS is not set/' "$cfg"
  else
    grep -q '^# CONFIG_TRIM_UNUSED_KSYMS is not set' "$cfg" || echo '# CONFIG_TRIM_UNUSED_KSYMS is not set' >> "$cfg"
  fi

  # Keep KALLSYMS but not ALL to shrink link workload
  if grep -q '^CONFIG_KALLSYMS_ALL=y' "$cfg"; then
    sed -i 's/^CONFIG_KALLSYMS_ALL=y/# CONFIG_KALLSYMS_ALL is not set/' "$cfg"
  fi

  # Reduce debug info size if enabled
  if grep -q '^CONFIG_DEBUG_INFO=y' "$cfg"; then
    sed -i 's/^CONFIG_DEBUG_INFO=y/# CONFIG_DEBUG_INFO is not set/' "$cfg"
  fi

  # Switch LTO from FULL to THIN to speed LTO link stage
  if grep -q '^CONFIG_LTO_CLANG_FULL=y' "$cfg"; then
    sed -i 's/^CONFIG_LTO_CLANG_FULL=y/# CONFIG_LTO_CLANG_FULL is not set/' "$cfg"
    if ! grep -q '^CONFIG_LTO_CLANG_THIN=y' "$cfg"; then
      sed -i '/^# CONFIG_LTO_CLANG_THIN is not set/d' "$cfg" || true
      echo 'CONFIG_LTO_CLANG_THIN=y' >> "$cfg"
    fi
  fi

  # Ensure KernelSU stays enabled
  grep -q '^CONFIG_KSU=y' "$cfg" || echo 'CONFIG_KSU=y' >> "$cfg"
}

build_targets() {
  local -a targets=(Image dtbs)
  echo "-- build: ${targets[*]} --"
  make -C "$KROOT" O="$KOUT" ARCH=arm64 LLVM=1 LLVM_IAS=1 -j"$(nproc)" "${targets[@]}"
}

case "$mode" in
  olddefconfig)
    ensure_out_config
    olddefconfig
    ;;
  release)
    echo "-- restore config from current_defconfig (release) --"
    mkdir -p "$KOUT" && cp -f "$ROOT_DIR/current_defconfig" "$KOUT/.config"
    olddefconfig
    build_targets
    ;;
  fast)
    ensure_out_config
    patch_config_fast
    olddefconfig
    build_targets
    ;;
  build)
    build_targets
    ;;
  *)
    echo "Usage: $(basename "$0") [fast|release|olddefconfig|build]" >&2
    exit 2
    ;;
esac
set -e; ROOT=/home/chen/Android/DEV/kernel/kernel_xiaomi_fuxi; KROOT="$ROOT/xiaomi-kernel/src/Xiaomi_Kernel_OpenSource-fuxi-t-oss"; KOUT="$ROOT/out-fuxi"; TC="$ROOT/toolchains/clang-linux-x86-goo/clang-r450784e/bin"; echo "-- disable IKHEADERS to avoid cpio dependency --"; sed -i 's/^CONFIG_IKHEADERS=y/# CONFIG_IKHEADERS is not set/' "$KOUT/.config"; echo "-- olddefconfig to settle changes --"; PATH="$TC:$PATH" make -C "$KROOT" O="$KOUT" ARCH=arm64 LLVM=1 LLVM_IAS=1 olddefconfig; echo "-- verify IKCONFIG/IKHEADERS --"; grep -nE 'CONFIG_IKCONFIG|CONFIG_IKHEADERS' "$KOUT/.config" | sed -n '1,40p'; echo "-- build Image+dtbs --"; PATH="$TC:$PATH" make -C "$KROOT" O="$KOUT" ARCH=arm64 LLVM=1 LLVM_IAS=1 -j$(nproc) Image dtbs
