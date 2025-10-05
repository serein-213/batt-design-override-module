# 温度控制构建系统使用说明

## 概述

我已经为你的内核构建系统添加了完整的温度监控和控制功能，可以有效防止编译过程中CPU过热，同时保持合理的编译速度。

## 主要功能

### 🌡️ 温度监控
- 实时监控CPU温度（支持k10temp传感器）
- 自动温度阈值检测和警告
- 过热时自动暂停编译等待降温

### 🔧 智能任务调节
- 根据CPU温度自动调整编译任务数
- 三种编译模式：保守、平衡、性能
- 防止系统过载和崩溃

### 📊 系统状态监控
- CPU使用率监控
- 内存使用率检查
- 系统负载实时显示

## 使用方法

### 1. 基本构建（使用默认温度控制）
```bash
./scripts/build_all.sh --kernel-line 5.15 --version 1.0.6
```

### 2. 保守模式构建（优先保护硬件）
```bash
THERMAL_MODE=conservative ./scripts/build_all.sh --kernel-line 5.15
```

### 3. 性能模式构建（追求速度）
```bash
THERMAL_MODE=performance ./scripts/build_all.sh --kernel-line 6.1
```

### 4. 自定义温度限制
```bash
MAX_TEMP=80 ./scripts/build_all.sh --kernel-line 5.15
```

### 5. 手动指定编译任务数
```bash
./scripts/build_all.sh --jobs 4 --kernel-line 5.15
```

## 温度监控工具

### 实时监控CPU温度
```bash
./scripts/temp_monitor.sh
# 或
./scripts/temp_monitor.sh monitor
```

### 单次检查系统状态
```bash
./scripts/temp_monitor.sh check
```

### 查看当前配置
```bash
./scripts/temp_monitor.sh config
```

## 配置选项

### 环境变量

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `THERMAL_MODE` | balanced | 编译模式 (conservative/balanced/performance) |
| `MAX_TEMP` | 85 | 最大允许温度（°C） |
| `TEMP_WARNING` | 80 | 警告温度（°C） |
| `TEMP_SAFE` | 75 | 安全温度（°C） |
| `AUTO_ADJUST_JOBS` | 1 | 自动调整编译任务数 |

### 编译模式说明

#### 🐌 保守模式 (conservative)
- 最大温度：80°C
- 编译任务数：较少
- 适合：长时间编译、散热较差的系统

#### ⚖️ 平衡模式 (balanced) - 默认
- 最大温度：85°C  
- 编译任务数：中等
- 适合：大多数情况

#### 🚀 性能模式 (performance)
- 最大温度：90°C
- 编译任务数：较多
- 适合：散热良好的系统、追求编译速度

## 温度控制逻辑

系统会根据当前CPU温度自动调整编译任务数：

- **< 75°C**: 使用100%CPU核心数
- **75-80°C**: 使用75%CPU核心数  
- **80-85°C**: 使用50%CPU核心数
- **> 85°C**: 使用25%CPU核心数，并暂停等待降温

## 故障排除

### 温度读取失败
如果温度监控无法正常工作，检查：
1. 是否安装了 `lm-sensors` 包
2. 运行 `sensors-detect` 检测传感器
3. 确认 `/sys/class/hwmon/` 目录存在

### 编译任务数过少
如果自动调整的任务数太少：
1. 检查CPU温度是否过高
2. 改善系统散热
3. 使用 `--jobs` 参数手动指定

### 过热保护触发
如果频繁触发过热保护：
1. 清理CPU散热器灰尘
2. 检查散热膏是否需要更换
3. 使用保守模式编译
4. 降低 `MAX_TEMP` 设置

## 监控示例

当前你的系统状态：
- CPU温度：58°C（安全）
- 建议编译任务数：12
- 适合使用平衡或性能模式编译

## 文件说明

- `scripts/build_all.sh` - 主构建脚本（已添加温度控制）
- `scripts/thermal_config.sh` - 温度控制配置文件
- `scripts/temp_monitor.sh` - 温度监控工具

现在你可以安全地进行内核编译，系统会自动监控温度并调整编译强度，有效防止过热问题！
