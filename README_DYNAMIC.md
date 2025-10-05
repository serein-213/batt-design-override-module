# 动态内核模块系统

这是一个改进的电池容量修改系统，支持根据手机内核版本自动下载和安装对应的内核模块。

## 系统架构

### 1. LSPosed 应用 (`lsposed-module/`)
- **功能**: Hook 设置页面显示、管理内核模块下载和安装
- **特性**:
  - 自动检测内核版本
  - 从远程服务器下载对应版本的 .ko 文件
  - 管理 Magisk 模块的安装和卸载
  - 小米设备专用的 Hook 功能

### 2. 动态 Magisk 模块 (`export-batt-module/packaging/magisk-batt-design-override-dynamic/`)
- **功能**: 轻量级 Magisk 模块，不包含 .ko 文件
- **特性**:
  - 启动时检测内核版本
  - 从应用获取对应的 .ko 文件
  - 自动加载内核模块并应用参数
  - 支持多内核版本兼容

## 使用流程

### 第一步：安装 LSPosed 应用
1. 编译并安装 `lsposed-module/` 中的应用
2. 在 LSPosed 管理器中激活该模块
3. 重启系统框架

### 第二步：安装动态 Magisk 模块
1. 使用应用中的"设置"页面 -> "模块管理"
2. 点击"安装动态模块"按钮
3. 或者手动安装: 运行 `build_dynamic_magisk_zip.sh` 打包并通过 Magisk Manager 安装

### 第三步：下载内核模块
1. 应用会自动检测当前内核版本
2. 在"模块管理"中点击对应的下载按钮
3. 应用会自动下载并安装到 Magisk 模块目录

### 第四步：配置参数
1. 在"电池"页面配置电池参数
2. 在"充电"页面配置充电参数（如果需要）
3. 小米设备可在"设置"页面配置 Hook 参数

## 支持的内核版本

目前支持以下内核版本：
- 5.4.x
- 5.10.x
- 5.15.x
- 6.1.x
- 6.6.x

## 技术特性

### 内核版本检测
- 自动解析 `/proc/version` 和 `uname -r`
- 支持向下兼容匹配
- 优先匹配完全相同的版本

### 模块下载管理
- 支持 HTTP/HTTPS 下载
- 文件完整性校验（SHA256）
- 本地缓存管理
- 下载进度显示

### Magisk 模块集成
- 动态创建模块目录结构
- 自动复制应用 APK
- 配置文件同步
- 模块状态监控

## 文件结构

```
export-batt-module/packaging/
├── magisk-batt-design-override-dynamic/    # 动态模块模板
│   ├── module.prop                         # 模块属性
│   ├── service.sh                          # 启动脚本
│   └── common/
│       └── params.conf                     # 配置文件
├── build_dynamic_magisk_zip.sh             # 动态模块打包脚本
└── build_magisk_zip.sh                     # 传统模块打包脚本

lsposed-module/app/src/main/java/com/example/battcaplsp/core/
├── KernelModuleDownloader.kt               # 下载管理器
├── MagiskModuleManager.kt                  # Magisk 模块管理器
└── ModuleManager.kt                        # 内核模块管理器（增强版）
```

## 配置选项

### 应用配置
- `AUTO_DOWNLOAD`: 是否启用自动下载
- `DOWNLOAD_SERVER`: 下载服务器 URL
- `APP_AUTOINSTALL`: 是否自动安装应用

### 模块参数
- `MODEL_NAME`: 电池型号名称
- `DESIGN_UAH`: 设计容量（微安时）
- `DESIGN_UWH`: 设计能量（微瓦时）
- `OVERRIDE_ANY`: 是否覆盖任意电池
- `VERBOSE`: 详细日志输出

## 故障排除

### 模块未加载
1. 检查 Magisk 是否正常工作
2. 查看 `logcat` 中的模块日志
3. 确认内核版本是否支持

### 下载失败
1. 检查网络连接
2. 确认服务器 URL 配置
3. 查看应用日志

### Hook 不生效（小米设备）
1. 确认 LSPosed 已激活模块
2. 重启设置应用
3. 检查 Hook 配置是否正确

## 开发说明

### 添加新内核版本支持
1. 在 `KernelModuleDownloader.kt` 中添加版本号
2. 编译对应版本的 .ko 文件
3. 上传到下载服务器

### 自定义下载服务器
1. 修改 `DEFAULT_BASE_URL` 常量
2. 按照预期的目录结构组织文件
3. 可选：添加 SHA256 校验文件

## 许可证

本项目遵循原项目的许可证条款。
