## batt-design-override 项目说明

一个用于覆盖 Android 设备电池设计参数 (Design Capacity / Design Energy / Model Name) 的轻量 **GKI / out-of-tree** 内核模块，并提供可打包为 **Magisk 模块** 的脚本与最小目录结构，便于快速定制 & 分发。

### ✨ 功能特性
- 通过 kretprobe / power_supply 属性覆盖展示的设计容量、能量、型号等字段
- 可选强制覆盖（不匹配原电池名称亦生效）
- Magisk 自动加载脚本（`service.sh`）开机注入，支持配置文件参数
- 参数热重载：修改 `params.conf` 后重新执行脚本或手动 rmmod/insmod
- 多内核线构建（本地或 CI）友好，纯 out-of-tree，不污染源码树

### 📂 目录结构
```
extra_modules/
  batt_design_override/
    batt_design_override.c   # 模块源码
    Makefile                 # Kbuild 描述
packaging/
  build_magisk_zip.sh        # 打包脚本
packaging/magisk-batt-design-override/
  module.prop                # Magisk 基本信息（version 可被覆盖）
  service.sh                 # 自动加载脚本
  common/params.conf         # 默认参数（可编辑）
```

### 🔧 可配置参数 (params.conf / insmod)
| 键 | 说明 | 对应 insmod 参数 | 示例 |
|----|------|------------------|------|
| MODEL_NAME | 电池显示型号 | model_name | MODEL_NAME=SuperCell |
| DESIGN_UAH | 设计容量 (uAh) | design_uah | DESIGN_UAH=5300000 |
| DESIGN_UWH | 设计能量 (uWh) | design_uwh | DESIGN_UWH=20000000 |
| BATT_NAME  | 目标 power_supply | batt_name | BATT_NAME=battery |
| OVERRIDE_ANY | 忽略名称强制覆盖 (1/0) | override_any | OVERRIDE_ANY=1 |
| VERBOSE | 调试日志 (1/0) | verbose | VERBOSE=1 |

Magisk 自动加载时会读取 `params.conf` 并转换为对应 insmod 参数。

### 🚀 设备端使用（Magisk 场景）
1. 刷入打包好的 ZIP（或将 dist 里生成的 ZIP 通过 Magisk / KernelSU 安装）
2. 重启后模块自动加载；使用 `dmesg | grep batt_design_override` 可确认
3. 修改参数：
```bash
su
cd /data/adb/modules/batt-design-override/common
vi params.conf   # 修改需要的键值
rmmod batt_design_override 2>/dev/null || true
sh ../service.sh # 重新加载
```
4. 暂停自动加载：
```bash
su -c 'touch /data/adb/modules/batt-design-override/disable_autoload'
```

手动直接加载示例（不依赖 service.sh）：
```bash
su -c 'insmod /data/adb/modules/batt-design-override/common/batt_design_override.ko \
  design_uah=5300000 model_name=MyBatt verbose=1'
```

### 🏗️ 本地编译 (已有 GKI 源码)
假设：`KERNEL_SRC=/path/to/gki-source` 已完成对应 target defconfig + `make modules_prepare`。
```bash
export KERNEL_SRC=/path/to/gki-source
cd /path/to/export-batt-module
make -C "$KERNEL_SRC" M="$PWD/extra_modules/batt_design_override" modules
```
输出：`extra_modules/batt_design_override/batt_design_override.ko`

若遇到 vermagic / clang 相关错误，确认：
1. 与设备内核相同或兼容的 config / toolchain
2. 传递必要的交叉编译变量（例如）：
```bash
make -C "$KERNEL_SRC" M="$PWD/extra_modules/batt_design_override" \
  CROSS_COMPILE=aarch64-linux-gnu- \
  ARCH=arm64 \
  CLANG_TRIPLE=aarch64-linux-gnu- modules
```

### 📦 打包 Magisk 模块
```bash
chmod +x packaging/build_magisk_zip.sh
bash packaging/build_magisk_zip.sh \
  --ko extra_modules/batt_design_override/batt_design_override.ko \
  --kernel-line 5.15 \
  --output dist \
  --version 1.0.0   # 可选，覆盖 module.prop
```
生成：`dist/batt-design-override-1.0.0-5.15.zip`

ZIP 内含：
```
module.prop
service.sh
common/
  batt_design_override.ko
  batt_design_override-5.15.ko   # 版本后缀便于多内核线共存选择
  params.conf
```

### 🔍 验证生效
1. 通过 `cat /sys/class/power_supply/battery/uevent | grep -i design` 查看被覆盖的容量/能量
2. dmesg 里搜索 `batt_design_override`：
```bash
dmesg | grep -i batt_design_override
```

### ❓ 常见问题 (FAQ)
Q: 需要匹配特定电池名称才能生效吗？
A: 默认需匹配 `BATT_NAME`；设置 `OVERRIDE_ANY=1` 可忽略名称。

Q: 修改 params.conf 没生效？
A: 需 rmmod 后重新执行 `service.sh` 或重启；确认没有 `disable_autoload` 文件。

Q: vermagic 不匹配 / Unknown symbol？
A: 说明编译用的内核源码与设备当前运行的内核不一致，需使用对应 defconfig 与相同 toolchain 生成的内核头与 Module.symvers。

Q: 还能用 GitHub Actions 自动构建吗？
A: 可以，但本 README 不再展开。保留脚本/工作流时，可自行根据需要调整或恢复旧版说明。

### 🧩 后续可扩展想法
- 基于真实 upstream commit hash 的缓存 key
- 自动探测目标 power_supply 并回退策略
- 通过 sysfs/param 接口做在线参数修改（代替卸载重载）
- 添加单元/集成测试（kunit）验证 hook 行为

### ⚠️ 风险提示
错误的容量/能量上报可能影响系统电量估算或充电策略，请了解风险后再使用；仅供学习与调试，勿用于商业分发。

### 📜 License
本仓库已添加 `LICENSE` 文件，采用 GNU General Public License v2（GPLv2）。

---
需要补充 CI 相关文档或添加新功能，直接提交 issue / 继续交流即可。
