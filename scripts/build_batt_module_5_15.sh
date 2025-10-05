#!/usr/bin/env bash
set -euo pipefail

# 构建 batt_design_override.ko（针对 Android 13/14 GKI 5.15.x / 设备 5.15 内核）
# 前置条件：
#  - 已有 5.15 内核源码目录 (KERNEL_SRC) 可正常 build
#  - 已有与该源码匹配的输出目录 (KERNEL_OUT)，含 .config + Module.symvers（没有会尝试生成，但仍建议先完整构建一次）
#  - 与目标内核一致或兼容的 clang/llvm 工具链（推荐仓库同梱的 Android clang）
#
# 用法示例：
#   KERNEL_SRC=$HOME/android/kernel/gki/common-android13-5.15 \
#   KERNEL_OUT=$HOME/android/kernel/gki/out-5.15 \
#   ./scripts/build_batt_module_5_15.sh
#
# 可选环境变量：
#   ARCH=arm64 (默认)
#   LLVM=1 (默认开启 clang)
#   CLANG_PATH=<clang/bin> (若已在 PATH 可省略)
#   CROSS_COMPILE=aarch64-linux-gnu- 或 aarch64-linux-android-
#   JOBS=$(nproc)
#   VERBOSE=1  显示更多 make 命令
#   AUTO_NEW_OUT=1  若检测到现有 out 目录 utsrelease 与源码 SUBLEVEL 不匹配，自动创建全新输出目录 <orig>-fresh-$(date +%s)
#   LTO_JOBS=<n>  指定 ThinLTO backend 并行度；默认使用 $(nproc) 若未设置且检测到 ThinLTO

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WS_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
MOD_DIR="$WS_ROOT/extra_modules/batt_design_override"

# 默认尝试 android13/14 分支目录；若不存在则回退
KERNEL_SRC=${KERNEL_SRC:-}
if [[ -z "${KERNEL_SRC}" ]]; then
	for cand in \
		"$WS_ROOT/gki/common-android13-5.15"; do
		[[ -d "$cand" && -f "$cand/Makefile" ]] && KERNEL_SRC="$cand" && break
	done
fi

# 输出目录：优先用户设置；否则尝试 out-5.15 / out-fuxi / gki/out-5.15
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

# 方案1：检测版本差异，必要时创建新输出目录避免旧头文件残留
AUTO_NEW_OUT=${AUTO_NEW_OUT:-0}
SRC_SUBLEVEL=$(grep -E '^SUBLEVEL\s*=\s*' "$KERNEL_SRC/Makefile" | sed -E 's/.*=\s*//') || SRC_SUBLEVEL=?
if [[ -f "$KERNEL_OUT/include/generated/utsrelease.h" ]]; then
	UTS_LINE=$(grep UTS_RELEASE "$KERNEL_OUT/include/generated/utsrelease.h" 2>/dev/null || true)
	# 从如 #define UTS_RELEASE "5.15.41-..." 中抽取主次补丁版本
	OUT_VER=$(echo "$UTS_LINE" | sed -E 's/.*\"([^\"]+)\".*/\1/' )
	OUT_BASE=$(echo "$OUT_VER" | cut -d- -f1)
	OUT_SUB=$(echo "$OUT_BASE" | cut -d. -f3)
	echo "[i] 检测到现有 utsrelease: $OUT_VER (SUBLEVEL=$OUT_SUB) 源码 SUBLEVEL=$SRC_SUBLEVEL"
	if [[ "$AUTO_NEW_OUT" == 1 && -n "$OUT_SUB" && -n "$SRC_SUBLEVEL" && "$OUT_SUB" != "$SRC_SUBLEVEL" ]]; then
		NEW_OUT="${KERNEL_OUT}-fresh-$(date +%s)"
		echo "[i] AUTO_NEW_OUT=1 且版本不匹配 -> 切换使用新输出目录: $NEW_OUT"
		KERNEL_OUT="$NEW_OUT"
		mkdir -p "$KERNEL_OUT"
	fi
fi

# 默认 clang 路径（若存在）
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
VERBOSE=${VERBOSE:-0}
PATCH_FLAGS=${PATCH_FLAGS:-1}  # 允许关闭本脚本的 flag 修补
LTO_JOBS=${LTO_JOBS:-}

if [[ -n "${CLANG_PATH:-}" ]]; then
	export PATH="$CLANG_PATH:$PATH"
fi

echo "[i] KERNEL_SRC = $KERNEL_SRC"
echo "[i] KERNEL_OUT = $KERNEL_OUT"
echo "[i] MOD_DIR    = $MOD_DIR"
echo "[i] ARCH       = $ARCH"
echo "[i] LLVM       = $LLVM"
echo "[i] CROSS_COMPILE = ${CROSS_COMPILE:-<unset>}"
echo "[i] PATCH_FLAGS   = $PATCH_FLAGS (过滤/修补异常编译参数)"
echo "[i] AUTO_NEW_OUT  = $AUTO_NEW_OUT"
echo "[i] LTO_JOBS      = ${LTO_JOBS:-<auto>}"

[[ -f "$KERNEL_SRC/Makefile" ]] || { echo "[!] 未找到 $KERNEL_SRC/Makefile，请设置 KERNEL_SRC" >&2; exit 1; }

# 仅做提示，不强制
if grep -Eq '^VERSION\s*=\s*5' "$KERNEL_SRC/Makefile" && \
	 grep -Eq '^PATCHLEVEL\s*=\s*15' "$KERNEL_SRC/Makefile"; then
	echo "[i] 检测到 5.15 内核源码"
else
	echo "[!] 警告：VERSION/PATCHLEVEL 非 5.15，仍尝试构建..." >&2
fi

mkdir -p "$KERNEL_OUT"

if [[ ! -f "$KERNEL_OUT/.config" ]]; then
	echo "[i] $KERNEL_OUT/.config 不存在，尝试 gki_defconfig (或 vendor 定制可自行改)"
	make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
		CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" \
		LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
		READELF=llvm-readelf STRIP=llvm-strip \
		gki_defconfig
else
	echo "[i] 发现现有 .config -> olddefconfig";
	make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
		CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" \
		LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
		READELF=llvm-readelf STRIP=llvm-strip \
		olddefconfig
fi

# 缺少 cpio 时禁用 IKHEADERS
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

# 若缺少 Module.symvers，则执行一次 modules
if [[ ! -f "$KERNEL_OUT/Module.symvers" ]]; then
	echo "[i] 未发现 Module.symvers，执行 make modules (可能耗时)"
	make -C "$KERNEL_SRC" O="$KERNEL_OUT" ARCH="$ARCH" LLVM="$LLVM" LLVM_IAS="$LLVM_IAS" \
		CLANG_TRIPLE="$CLANG_TRIPLE" CROSS_COMPILE="$CROSS_COMPILE" LD=ld.lld \
		LLVM_AR=llvm-ar LLVM_NM=llvm-nm LLVM_OBJCOPY=llvm-objcopy LLVM_OBJDUMP=llvm-objdump \
		READELF=llvm-readelf STRIP=llvm-strip \
		-j"$JOBS" modules
fi

# 若启用 ThinLTO 且用户未显式给 LTO_JOBS，则自动设为 nproc
if grep -q '^CONFIG_LTO_CLANG_THIN=y' "$KERNEL_OUT/.config" 2>/dev/null; then
	if [[ -z "$LTO_JOBS" ]]; then
		LTO_JOBS=$(nproc)
		echo "[i] 自动检测 ThinLTO -> 设定 LTO_JOBS=$LTO_JOBS"
	fi
	export KBUILD_LDFLAGS="${KBUILD_LDFLAGS:-} -Wl,-plugin-opt,jobs=${LTO_JOBS}"
	echo "[i] KBUILD_LDFLAGS += -Wl,-plugin-opt,jobs=${LTO_JOBS}"
fi

echo "[i] 开始编译外部模块 (5.15) ..."
[[ "$VERBOSE" == 1 ]] && set -x || true
# --- Flag 修补逻辑 ---
if [[ "$PATCH_FLAGS" == 1 ]]; then
	# 创建一个 wrapper，剥离空 -falign-functions= 并修补 trivial-auto-var-init
	WRAP_DIR=$(mktemp -d)
	cat >"$WRAP_DIR/clang-wrapper" <<'EOF'
#!/usr/bin/env bash
ARGS=()
SEEN_TRIVIAL=0
ORIG=("$@")
echo "[wrapper] raw args: ${ORIG[*]}" >&2
for a in "$@"; do
	# 移除空对齐 flag
	if [[ "$a" == -falign-functions= ]]; then
		continue
	fi
	if [[ "$a" == -ftrivial-auto-var-init=zero ]]; then
		SEEN_TRIVIAL=1
	fi
	ARGS+=("$a")
done
# 始终保证：若使用 -ftrivial-auto-var-init=zero 则加 enable 开关；若未出现也补齐整个组合
if [[ $SEEN_TRIVIAL -eq 1 ]]; then
	# 检查是否已经有 enable 开关（防止重复）
	HAVE_ENABLE=0
	for x in "${ARGS[@]}"; do
		[[ "$x" == -enable-trivial-auto-var-init-zero-knowing-it-will-be-removed-from-clang ]] && HAVE_ENABLE=1 && break
	done
	if [[ $HAVE_ENABLE -eq 0 ]]; then
		ARGS+=("-enable-trivial-auto-var-init-zero-knowing-it-will-be-removed-from-clang")
	fi
else
	# 未显式请求也补齐（兼容内核期望零初始化行为）
	ARGS+=("-enable-trivial-auto-var-init-zero-knowing-it-will-be-removed-from-clang" "-ftrivial-auto-var-init=zero")
fi
CLANG_BIN=$(command -v clang || true)
[[ -x $(command -v clang-14 2>/dev/null) ]] && CLANG_BIN=$(command -v clang-14)
echo "[wrapper] exec: $CLANG_BIN ${ARGS[*]}" >&2
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
	READELF=llvm-readelf STRIP=llvm-strip CC="$CC" HOSTCC="$HOSTCC" KCFLAGS="$EXTRA_KCFLAGS" \
	M="$MOD_DIR" -j"$JOBS" modules
[[ "$VERBOSE" == 1 ]] && set +x || true

KO_PATH="$MOD_DIR/batt_design_override.ko"
if [[ -f "$KO_PATH" ]]; then
	echo "[✓] 完成：$KO_PATH"
	file "$KO_PATH" 2>/dev/null || true
	# 简单输出 vermagic/info（如果 modinfo 可用）
	if command -v modinfo >/dev/null 2>&1; then
		echo "[i] modinfo:"
		modinfo "$KO_PATH" | sed -n '1,8p'
	fi
else
	echo "[✗] 未生成 .ko，请检查上方错误日志" >&2
	exit 2
fi

echo "[i] 复制到 Magisk 模块 common 目录(如存在)方便打包..."
MAGISK_COMMON="$WS_ROOT/packaging/magisk-batt-design-override/common"
if [[ -d "$MAGISK_COMMON" ]]; then
	cp -f "$KO_PATH" "$MAGISK_COMMON/" && echo "[i] 已复制到 $MAGISK_COMMON" || echo "[!] 复制失败"
fi

echo "[done]"

