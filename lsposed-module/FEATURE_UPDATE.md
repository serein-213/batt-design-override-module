# 功能更新说明

## 新增功能

### 1. Root 权限检查功能

- **位置**: `RootShell.kt`
- **功能**: 
  - 检查设备是否已获取 Root 权限
  - 提供详细的 Root 状态信息
  - 在 UI 中显示 Root 权限状态
- **使用方法**: 
  - 在电池设置页面右上角显示 Root 状态
  - 点击状态按钮可查看详细信息
  - 自动检查权限并缓存结果

### 2. LSPosed 模块设置入口（简化版）

- **实现方式**: 使用 `activity-alias` 直接跳转到主界面
- **功能**:
  - 在 LSPosed 管理器中提供模块设置入口
  - 直接打开应用主界面，无需单独的设置界面
  - 当桌面图标被隐藏时的备选入口
- **优势**:
  - 避免重复界面，保持代码简洁
  - 用户体验一致，直接访问完整功能
  - 维护成本低

### 3. 桌面图标管理

- **位置**: `LauncherIconManager.kt`
- **功能**:
  - 隐藏/显示应用桌面图标
  - 在设置页面中控制图标可见性
  - 提供安全的入口备选方案

## 配置说明

### AndroidManifest.xml 更新

```xml
<!-- 模块设置入口 - 直接跳转到主界面 -->
<activity-alias
    android:name="com.override.battcaplsp.ModuleSettings"
    android:exported="true"
    android:enabled="true"
    android:targetActivity="com.override.battcaplsp.ui.MainActivity"
    android:label="@string/module_settings_title">
    <!-- LSPosed 模块设置入口 -->
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="de.robv.android.xposed.category.MODULE_SETTINGS" />
    </intent-filter>
</activity-alias>
```

### 新增字符串资源

- `module_settings_title`: 模块设置
- `root_permission_check`: Root 权限检查
- `root_available`: Root 权限已获取
- `root_unavailable`: Root 权限未获取

## 使用流程

### 正常使用流程
1. 安装 APK 并在 LSPosed 中激活模块
2. 通过桌面图标或 LSPosed 模块设置进入应用
3. 检查 Root 权限状态
4. 配置电池/充电参数

### 隐藏图标使用流程
1. 在设置页面中隐藏桌面图标
2. 通过 LSPosed 管理器 → 模块列表 → 模块设置进入
3. 可在设置页面中重新显示桌面图标

## 技术实现

### Root 权限检查
- 使用 libsu 库进行 Root 权限验证
- 执行 `id` 命令检查 uid=0
- 缓存检查结果避免重复验证

### 模块设置入口（简化版）
- 使用 `activity-alias` 而非独立 Activity
- 直接跳转到 MainActivity，避免代码重复
- 保持用户体验一致性

### 桌面图标管理
- 使用 PackageManager API 控制组件状态
- 通过 activity-alias 实现图标的动态显示/隐藏
- 提供安全的备选入口方案

## 构建说明

项目已成功构建，所有新功能已集成到现有架构中。可以直接使用 `./gradlew assembleDebug` 构建 APK。

## 注意事项

1. Root 权限检查需要设备已获取 Root 权限
2. 模块设置入口需要 LSPosed 框架支持
3. 桌面图标隐藏后，请确保记住通过 LSPosed 管理器进入的方法
4. 简化的实现避免了重复界面，保持代码整洁
5. 建议在测试环境中验证所有功能正常工作后再在生产环境使用
