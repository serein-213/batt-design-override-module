# 统一模块构建脚本说明

## 概述

本目录包含了按内核版本分类的统一构建脚本，每个脚本都能同时构建 `batt_design_override.ko` 和 `chg_param_override.ko` 两个内核模块。

## 脚本列表

| 脚本名称 | 目标内核版本 | 支持的Android版本 |
|---------|-------------|------------------|
| `build_modules_5_4.sh` | 5.4.x | Android 11 |
| `build_modules_5_10.sh` | 5.10.x | Android 12 |
| `build_modules_5_15.sh` | 5.15.x | Android 13/14 |
| `build_modules_6_1.sh` | 6.1.x | Android 15+ |

## 主要特性

### 1. 双模块构建
- 每个脚本都能构建两个模块：`batt_design_override` 和 `chg_param_override`
- 可通过环境变量单独控制每个模块的构建

### 2. 智能路径检测
- 自动检测内核源码目录和输出目录
- 支持多种常见的目录结构

### 3. 版本兼容性检查
- 检测内核源码版本与输出目录的匹配性
- 支持自动创建新的输出目录避免版本冲突

### 4. 编译优化
- 支持 ThinLTO 并行编译优化
- 自动处理编译器参数兼容性问题
- 智能处理缺失依赖（如 cpio）

## 使用方法

### 基本用法
```bash
# 构建5.15版本的两个模块
./scripts/build_modules_5_15.sh

# 构建6.1版本的两个模块
./scripts/build_modules_6_1.sh
```

### 环境变量控制

#### 路径设置
```bash
# 指定内核源码目录
export KERNEL_SRC=/path/to/kernel/source

# 指定输出目录
export KERNEL_OUT=/path/to/kernel/out

# 指定clang工具链路径
export CLANG_PATH=/path/to/clang/bin
```

#### 构建控制
```bash
# 只构建batt模块
export BUILD_BATT=1
export BUILD_CHG=0

# 只构建chg模块
export BUILD_BATT=0
export BUILD_CHG=1

# 构建两个模块（默认）
export BUILD_BATT=1
export BUILD_CHG=1
```

#### 高级选项
```bash
# 版本不匹配时自动创建新输出目录
export AUTO_NEW_OUT=1

# 启用详细输出
export VERBOSE=1

# 设置并行作业数
export JOBS=8

# ThinLTO作业数
export LTO_JOBS=4

# 禁用编译器参数修补
export PATCH_FLAGS=0
```

## 输出文件

构建成功后，模块文件将生成在：
- `extra_modules/batt_design_override/batt_design_override.ko`
- `extra_modules/chg_param_override/chg_param_override.ko`

5.15版本的脚本还会自动将生成的模块复制到Magisk模块目录：
- `packaging/magisk-batt-design-override/common/`

## 故障排除

### 常见问题

1. **缺少内核源码**
   ```
   [!] 未找到 /path/to/kernel/Makefile
   ```
   解决：设置正确的 `KERNEL_SRC` 环境变量

2. **版本不匹配警告**
   ```
   [!] 输出目录版本(41) 与源码(74) 不一致
   ```
   解决：设置 `AUTO_NEW_OUT=1` 自动创建新输出目录

3. **缺少Module.symvers**
   ```
   [i] 未发现 Module.symvers，执行 make modules
   ```
   这是正常的，脚本会自动生成所需文件

4. **编译器参数错误**
   5.15和6.1版本的脚本包含编译器wrapper来处理参数兼容性问题

### 调试选项

```bash
# 启用详细输出查看完整编译命令
export VERBOSE=1

# 查看模块信息
modinfo extra_modules/batt_design_override/batt_design_override.ko
```

## 与原脚本的区别

| 特性 | 原脚本 | 统一脚本 |
|-----|--------|----------|
| 模块数量 | 单个模块 | 双模块 |
| 输出格式 | 简单 | 结构化+总结 |
| 错误处理 | 基础 | 增强 |
| 自动复制 | 部分支持 | 智能复制 |
| 版本检测 | 基础 | 增强 |

原有的单模块脚本仍然保留，可以继续使用。统一脚本提供了更好的用户体验和更强的功能。
