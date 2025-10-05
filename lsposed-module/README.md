# BattCapLSP / Battery Design Override Manager

一个同时具备：
1. LSPosed Hook（伪装设置里展示的电池容量 / 型号）
2. 内核模块 `batt_design_override.ko` 参数图形化管理（加载 / 卸载 / 动态写入 / 查看状态）

的综合工具。

> 目标：继续使用内核 kretprobe 模块实现系统底层一致的设计容量覆盖，同时提供一个有界面、可热调参数、可一键加载的管理端；若未加载模块仍可通过 Hook 显示层伪装。

---

## 目录结构概览（与本子项目相关）

```
lsposed-module/
  build.gradle            # 顶层 Gradle 配置
  app/
	 build.gradle          # App 模块（包含 LSPosed + UI）
	 src/main/java/com/example/battcaplsp/
		SettingsHook.kt     # 原有 LSPosed Hook（显示层伪装）
		ui/MainActivity.kt  # 图形管理界面（Compose）
		core/RootShell.kt   # libsu Root 执行封装
		core/ModuleManager.kt
		core/ParamRepository.kt
	 src/main/res/...      # 图标 / 主题 / 字符串
  scripts/push_and_load.sh# adb 辅助脚本（push ko + 尝试加载）
```

---
## 功能列表

| 功能 | 说明 |
|------|------|
| 模块加载状态检测 | 通过 `/sys/module/batt_design_override` 是否存在判断 |
| 动态读取参数 | 读取 `/sys/module/batt_design_override/parameters/*` |
| 动态写入参数 | Root echo 写入同一路径；支持批量“应用” |
| 加载内核模块 | `insmod <ko> design_uah=... model_name=... override_any=... verbose=...` |
| 卸载内核模块 | `rmmod batt_design_override` |
| 设计容量 & 能量 & 型号 | 图形化输入框（空或 0 代表不覆盖） |
| override_any / verbose | 复选框切换 |
| 保存最近配置 | DataStore Preferences 持久化 |
| 设置 system prop (可选) | 写入 `persist.sys.batt.capacity_mah` 以配合旧 Hook 逻辑 |
| 查看内核日志片段 | dmesg 过滤 `batt_design_override`（可选刷新） |
| LSPosed Hook | 继续拦截 Settings 中的容量显示 JSON/文本 |

---
## 运行原理

### 内核模块路径 & 参数
模块通过 `module_param` 暴露的 sysfs：

```
/sys/module/batt_design_override/parameters/
  batt_name
  override_any
  verbose
  design_uah
  design_uwh
  model_name
```

写入示例：

```bash
echo 5000000 > /sys/module/batt_design_override/parameters/design_uah
echo MyModel > /sys/module/batt_design_override/parameters/model_name
```

如果尚未加载模块，可以 `insmod /data/local/tmp/batt_design_override.ko design_uah=5000000 model_name=MyModelX` 直接带初始参数。

### UI 与 Root 执行
使用 [libsu](https://github.com/topjohnwu/libsu) 进行持久 root shell，减少多次 su 开销。所有写入统一通过 `RootShell.exec()`。

### LSPosed Hook
维持原有 `SettingsHook.kt`：
1. Hook `SharedPreferencesImpl` 写入/读取 `basic_info_key` JSON
2. 尝试重写包含“电池容量”文本的 JSON/字符串
3. 读取系统属性 `persist.sys.batt.capacity_mah` 作为显示层伪装容量

（后续可改为从 DataStore 直接读取——需要在 Zygote 阶段或系统进程内安全访问持久化文件，现版本保持简单）

---
## 构建 & 安装

1. 用 Android Studio 打开 `lsposed-module/`
2. Build > Make Project
3. 产物：`app/build/outputs/apk/debug/app-debug.apk`
4. 安装 APK，打开 LSPosed：勾选模块并选择作用域 `com.android.settings`（已在 `arrays.xml` 设置建议作用域）
5. （可选）把内核模块推送：
	```bash
	adb push /path/to/batt_design_override.ko /data/local/tmp/
	```
6. 在手机上打开 “Battery Override Manager” 界面（App 图标），设置参数并执行“加载模块”或“应用参数”
7. 打开 设置 > 关于手机 > 设备信息 / 电池相关界面验证显示。

---
## ADB 一键脚本

`scripts/push_and_load.sh` 示例：

```bash
./scripts/push_and_load.sh /absolute/path/to/batt_design_override.ko 5000000 MyModelX
```

它会：
1. adb root（失败则继续）
2. push ko 到 `/data/local/tmp/`
3. 尝试 `insmod`（已存在则忽略报错）

> 之后仍建议在 App 内点击“刷新状态”确认。

---
## 权限 & 前提

| 条件 | 说明 |
|------|------|
| Root & 可加载未签名模块 | 需要解锁 + 关闭 vbmeta/强制校验（具体视设备） |
| LSPosed 已安装 | 显示层伪装功能需要 |
| Android 8.1+ (minSdk 27) | 当前配置；Compose 仍支持 |
| Busybox / 工具链（可选） | 仅用于更方便的日志 grep（若系统已自带则不必） |

---
## 常见问题 (FAQ)

1. Q: App 显示“模块未加载”？
	A: 确认 `/sys/module/batt_design_override` 是否存在；若不存在可能内核不允许加载、签名/verity 未关闭或路径错误。
2. Q: 参数写入失败？
	A: 检查 su 提示；也可以用 `adb shell su -c 'echo 5000000 > /sys/module/.../design_uah'` 手动验证。
3. Q: 已覆盖但 Settings 仍显示旧值？
	A: 可能 Settings 进程缓存，尝试强行停止设置 App 或重启；确认 LSPosed 模块生效；或确认 JSON 路径变更（ROM 版本差异）。
4. Q: 设计容量变更会影响充电算法吗？
	A: 需要视厂商电池策略是否在内核/守护进程读取该属性。Hook 只影响显示；内核模块方式更接近底层，但仍不保证所有策略逻辑依赖同一接口。

---
## 后续规划 / TODO

- [ ] 开机自启动：监听 BOOT_COMPLETED 后自动 insmod + 应用参数
- [ ] 导出 / 导入 Preset JSON
- [ ] 更多日志：解析内核 ring buffer 時间戳格式化
- [ ] 在 Hook 侧直接读取 DataStore（替代 system prop）
- [ ] 模块签名验证/失败提示

---
## 风险提示

内核 kprobe/kretprobe 可能在不兼容内核版本上造成内核崩溃（panic）。请确保：
1. 在非生产环境充分测试
2. 准备好原始 boot 镜像/恢复方案
3. 逐步增加覆盖参数，先验证单一属性

---
## License

保持与原内核模块一致（模块本身 GPL）；本 App 代码若无特别声明，默认以 Apache-2.0 发布（可按你仓库最终需求调整）。

---
## Changelog（新增管理 UI 后）

v0.2.0
 - 新增 Compose 图形界面
 - 支持 root 动态加载 / 卸载内核模块
 - 支持参数 DataStore 持久化
 - 支持日志查看 & system prop 写入

v0.1.0
 - 初始 LSPosed Hook：修改 Settings 中电池容量显示

