# 应用版本检查功能

## 功能概述

在设置页面新增了应用版本检查功能，可以从GitHub Release中检查并下载最新版本的APK。

## 功能特性

### 🔍 **版本检查**
- 自动检测当前应用版本
- 从GitHub Release API获取最新版本信息
- 智能版本比较，支持语义化版本号

### 📱 **更新下载**
- 一键下载最新APK
- 支持WiFi和移动网络下载
- 实时显示下载进度
- 自动清理旧版本APK文件

### 🚀 **安装管理**
- 下载完成后自动启动安装程序
- 支持Android原生安装流程
- 错误处理和用户反馈

## 使用方法

### 1. 检查更新
1. 打开应用，进入「设置」页面
2. 在「应用更新」卡片中点击「检查更新」按钮
3. 系统会自动检查GitHub Release中的最新版本

### 2. 下载安装
1. 如果发现新版本，会弹出更新对话框
2. 点击「立即更新」开始下载
3. 下载完成后会自动启动安装程序
4. 按照系统提示完成安装

## 技术实现

### 📁 **新增文件**

#### `GitHubReleaseClient.kt`
- GitHub Release API客户端
- 版本检查和比较逻辑
- 支持自定义仓库配置

#### `ApkDownloadManager.kt`
- APK下载管理器
- 使用Android DownloadManager
- 支持下载状态监控和错误处理

### 🎨 **UI组件**
- 版本检查卡片：显示当前版本和检查结果
- 更新对话框：显示新版本信息和下载进度
- 错误处理：网络错误和下载失败的友好提示

## 配置说明

### GitHub仓库配置
在 `GitHubReleaseClient.kt` 中需要配置：

```kotlin
companion object {
    private const val REPO_OWNER = "serein-213" // 仓库所有者
    private const val REPO_NAME = "kernel_xiaomi_fuxi" // 仓库名称
    private const val RELEASE_TAG_PREFIX = "app-v" // APK release的tag前缀
}
```

### Release标签格式
APK release的标签需要遵循以下格式：
- 标签格式：`app-v{版本号}`，如 `app-v1.2.3`
- APK文件名：以 `.apk` 结尾
- Release描述：包含更新内容说明

## 版本比较逻辑

支持语义化版本号比较：
- 格式：`主版本.次版本.修订版本`
- 示例：`1.2.3` vs `1.2.4`
- 比较规则：从左到右逐级比较数字

## 错误处理

### 网络错误
- API请求失败
- 下载中断
- 超时处理

### 文件错误
- APK文件损坏
- 权限不足
- 存储空间不足

### 安装错误
- 签名验证失败
- 权限拒绝
- 系统限制

## 安全考虑

- 使用HTTPS下载APK
- 验证文件完整性
- 遵循Android安全策略
- 用户确认安装流程

## 注意事项

1. **网络权限**：需要INTERNET权限访问GitHub API
2. **存储权限**：需要WRITE_EXTERNAL_STORAGE权限下载APK
3. **安装权限**：需要REQUEST_INSTALL_PACKAGES权限安装APK
4. **仓库配置**：确保GitHub仓库配置正确
5. **Release格式**：确保Release标签和文件格式正确

## 未来改进

- [ ] 支持增量更新
- [ ] 添加更新日志查看
- [ ] 支持自动检查更新
- [ ] 添加更新通知
- [ ] 支持多架构APK选择
