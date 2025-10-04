git add .
## batt-design-override 最小可构建仓库

该目录可直接初始化为独立 GitHub 仓库，一键使用内置 GitHub Actions 自动：
1. 拉取指定 GKI 分支源码 (android11-5.4 / android12-5.10 / android13-5.15 / android14-6.1)；若首选分支拉取失败按候选顺序回退，最终回退 android-mainline。
2. 初始化配置 + 生成 Module.symvers
3. 构建外部内核模块 `batt_design_override.ko`
4. 打包生成 Magisk 模块 ZIP（含版本号与内核线后缀）
5. （可选）发布 Release（手动触发时勾选 release=true）

### 目录结构
```
export-batt-module/
  README.md
  .github/workflows/build.yml              # 自动拉取+缓存+构建+打包+发布
  extra_modules/batt_design_override/
    batt_design_override.c                 # 模块源码（kretprobe 覆盖电池属性）
  packaging/
    build_magisk_zip.sh                    # 通用打包脚本
### GKI 分支映射 (动态候选)
| kernel_line | 候选分支顺序（依次尝试） | 示例 OUT 目录 |
|-------------|--------------------------|--------------|
| 5.4  | android11-5.4 → android12-5.10 → android-mainline | gki/out-5.4  |
| 5.10 | android12-5.10 → android13-5.10 → android-mainline | gki/out-5.10 |
| 5.15 | android13-5.15 → android14-6.1 → android-mainline | gki/out-5.15 |
| 6.1  | android14-6.1 → android-mainline | gki/out-6.1  |
      common/params.conf                   # 可自定义默认参数（如后续加入加载脚本）
```
### 使用提示（设备端）
已包含自动加载 `service.sh`：正常情况下无需手动 insmod；若需手动覆盖参数可：
```bash
su
cd /data/adb/modules/batt-design-override/common
rmmod batt_design_override 2>/dev/null || true
insmod batt_design_override.ko design_uah=5300000 verbose=1
```

### 快速初始化步骤
```bash
cd export-batt-module
rm -rf .git 2>/dev/null || true
git init
git add .
git commit -m "init: batt-design-override minimal"
git branch -M main
git remote add origin <your_repo_url>
git push -u origin main
```
然后进入仓库 Settings -> Actions 确认允许 workflow 运行。

### Workflow 手动触发参数 (workflow_dispatch)
| 输入名 | 说明 | 示例 | 默认 |
|--------|------|------|------|
| kernel_lines | 逗号分隔内核主线；空=全部 | `5.10,5.15` | 空 |
| version | 覆盖 `module.prop` version 字段 | `1.0.3` | 读取 module.prop |
| release | 构建完成后发布 Release | `true` / `false` | `false` |
| refresh_sources | 忽略缓存强制重新 clone GKI 源码 | `true` / `false` | `false` |

> 说明：不再支持 4.19；如果需要 4.19 可使用自定义仓库 (custom_repo_url + custom_ref) 指向厂商/旧 AOSP 分支。

### 缓存策略
| 缓存对象 | Key 组成 | 刷新条件 |
|----------|----------|----------|
| 源码目录 gki/<branch> | `gki-src-<branch>-<YYYYMMDD>` | 日期变更 / refresh_sources=true |
| 内核 OUT | `gki-out-<branch>-<DATE>-<MOD_HASH>` | 源码日期或模块源码改变 |
| ccache | `ccache-<branch>-<MOD_HASH>` | 模块源码改变 |

说明：源码 cache 当前用“当天日期”近似代表 HEAD；若需更精确，可改成抓取远程最新 commit hash（TODO 可扩展）。

### 模块源码哈希 (MOD_HASH)
通过 `sha256sum` 汇总 `batt_design_override.c` 与 `Makefile`，源码任何更改会导致 OUT / ccache 重新编译，避免脏结果。

### 强制刷新示例
在 Actions 手动触发界面将 `refresh_sources` 设为 `true`，会删除已有缓存的源码目录后重新克隆。

### 输出产物
每个内核线产出：
```
extra_modules/batt_design_override/batt_design_override.ko
dist/batt-design-override-<version>-<kernel_line>.zip
```
所有 zip 与 ko 会作为 Artifact；若 release=true 则一并发布到 Release 页面。

### 自动加载 (Magisk service.sh)
仓库已包含 `service.sh`，Magisk 在开机 late_start 服务阶段会执行，用于：
1. 自动选择最匹配的 ko：优先 `batt_design_override-<主版本.次版本>.ko`，否则回退通用 `batt_design_override.ko`
2. 读取 `common/params.conf` 组装 insmod 参数
3. 支持创建 `disable_autoload` 文件关闭自动加载：
  ```bash
  su -c 'touch /data/adb/modules/batt-design-override/disable_autoload'
  ```
4. 可修改参数后手动重新加载：
  ```bash
  su
  cd /data/adb/modules/batt-design-override/common
  vi ../common/params.conf   # 修改
  rmmod batt_design_override 2>/dev/null || true
  sh ../service.sh
  ```

`params.conf` 支持键：
| 键 | 说明 | 映射 insmod 参数 | 示例 |
|----|------|------------------|------|
| MODEL_NAME | 电池显示型号 | model_name | MODEL_NAME=SuperCell |
| DESIGN_UAH | 设计容量(uAh) | design_uah | DESIGN_UAH=5300000 |
| DESIGN_UWH | 设计能量(uWh) | design_uwh | DESIGN_UWH=20000000 |
| BATT_NAME  | 目标 power_supply 名 | batt_name | BATT_NAME=battery |
| OVERRIDE_ANY | 忽略名称强制覆盖 | override_any (1/0) | OVERRIDE_ANY=1 |
| VERBOSE | 输出日志 | verbose (1/0) | VERBOSE=1 |

禁用自动加载后仍可手动：
```bash
su -c 'insmod /data/adb/modules/batt-design-override/common/batt_design_override.ko design_uah=5300000 model_name=MyBatt verbose=1'
```

### Magisk ZIP 内容说明
打包脚本会：
1. 拷贝模板目录 `magisk-batt-design-override`
2. 覆盖/写入模块版本号
3. 复制 `batt_design_override.ko` 两份：
   - `common/batt_design_override.ko`
   - `common/batt_design_override-<kernel_line>.ko`

方便后续脚本（如果你添加）可根据系统内核线自动选择最匹配的 ko。

### 使用提示（设备端）
已包含自动加载脚本 `service.sh`，正常情况下无需手动 insmod；如需自定义参数即时生效：
```bash
su
cd /data/adb/modules/batt-design-override/common
rmmod batt_design_override 2>/dev/null || true
insmod batt_design_override.ko design_uah=5300000 verbose=1
```

### 常见问题 (FAQ)
Q: 为什么没有锁定特定 commit？  
A: 简化首次实现；日期级缓存可避免频繁重新 clone。可增强为 curl AOSP 获取 commit。  
Q: 加载 ko 报 vermagic 不匹配？  
A: 尝试使用与目标设备编译器更接近的预编译 clang，或在本 workflow 增加自定义 TOOLCHAIN 下载支持。  
Q: 修改源码后没有重新编译？  
A: 确认改动在 `batt_design_override.c` / `Makefile` 内；否则 MOD_HASH 不变。  
Q: 想只构建 5.15？  
A: kernel_lines 输入 `5.15`。  

### 构建错误记录 & 修复
2025-10: 在 5.4 / 5.10 分支下出现：
```
scripts/Makefile.build:42: .../extra_modules/batt_design_override/Makefile: No such file or directory
No rule to make target '.../extra_modules/batt_design_override/Makefile'.  Stop.
```
原因：`make -C <KERNEL_SRC> M=extra_modules/...` 时 Kbuild 以 `<KERNEL_SRC>` 为基准解析相对路径，**而本仓库模块目录在工作区根目录**，并未复制进入 `gki/<branch>` 源树，导致找不到。

修复：改用绝对路径 `M=$PWD/extra_modules/batt_design_override`，Kbuild 支持外部 out-of-tree 模块目录，不再依赖相对位置。Workflow 已更新相应步骤，并在执行前检测 `Makefile` 是否存在。

如果你想回退到早期相对路径方式，可在 workflow 的“构建模块”步骤把 `MOD_DIR_ABS=$(pwd)/...` 改回 `M=extra_modules/batt_design_override`，同时在 `gki/<branch>` 下创建同名目录软链接：
```bash
ln -s ../../../extra_modules gki/<branch>/extra_modules
```
（不推荐，绝对路径更直接稳定。）

2025-10 (后续)：出现大量 `error: unknown register name 'x0' in asm` (来自 `arch/arm64/include/asm/atomic_lse.h`)。
原因：在某些 runner 场景下，最终 `make M=... modules` 阶段未显式传递 `CROSS_COMPILE/CLANG_TRIPLE`，触发 Kbuild 选择宿主默认 triple（可能导致用错误的汇编器模式解析 inline asm），进而 clang 报寄存器名无效。

新修复：
1. 在模块编译命令显式携带 `CLANG_TRIPLE` 与 `CROSS_COMPILE`。
2. 增加调试输出打印关键环境变量。
3. 使用 `modules_prepare` 代替完整 `modules`，减少不必要的全量内核模块编译时间。
4. 去除之前的内核源码目录软链接回退（避免顶层递归构建再次触发路径歧义）。

如仍出现寄存器报错，可检查 clang 版本或改为使用 AOSP 预编译 clang（后续可扩展）。

附加：打包阶段 `build_magisk_zip.sh: Permission denied (exit 126)`：
原因：脚本执行位在某些 clone/patch 情况下被剥离；Workflow 已改为在执行前 `ls -l` + 条件 `chmod +x` 并用 `bash` 调用，避免失败。如果本地执行遇到：
```bash
chmod +x packaging/build_magisk_zip.sh
bash packaging/build_magisk_zip.sh --ko extra_modules/batt_design_override/batt_design_override.ko --kernel-line 5.10 --output dist
```

2025-10 (再次) 5.15 分支构建在 `LTO vmlinux.o` 阶段被 Terminated：
原因：全量链接 (ThinLTO/Full LTO) 内存峰值高，GitHub 共享 runner 上可能 OOM 被系统杀死。
处理：Workflow 在生成 `.config` 后强制：
```
# CONFIG_LTO_CLANG_THIN is not set
# CONFIG_LTO_CLANG_FULL is not set
CONFIG_LTO_NONE=y
# CONFIG_THINLTO is not set
```
并降级初次 `modules` 并发 (-j nproc/2)。这样只准备模块符号与头文件而不必成功完成 vmlinux LTO 全量链接。

### 后续可拓展 TODO（可选）
- 使用真实 HEAD commit 替代日期做源码 cache key
- 支持自定义工具链下载（输入 TOOLCHAIN_URL）
- 增加自动 insmod/卸载脚本
- 增加符号探测（nm vmlinux 验证 hook 目标存在）

### License
当前未附带单独 LICENSE 文件，如需开源请添加（推荐 GPLv2 以匹配内核模块许可）。

---
如需我继续实现“真实 commit 缓存”或“自动加载脚本”，直接提出即可。
