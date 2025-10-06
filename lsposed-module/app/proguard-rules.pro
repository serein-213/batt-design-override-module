# LSPosed 模块 ProGuard 规则

# 保留 Xposed 相关类
-keep class de.robv.android.xposed.** { *; }
-keep class com.override.battcaplsp.SettingsHook { *; }
-keep class com.override.battcaplsp.SettingsProvider { *; }

# Compose: 仅保留可能通过反射/生成访问的 Composable 类成员注解元数据
# 避免全量 keep 以便 R8 剔除未使用 UI 组合
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
# 保留生成的 Compose Compiler runtime 内部（必要核心）
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# 保留 DataStore 相关
-keep class androidx.datastore.** { *; }
-keep class * extends androidx.datastore.core.Serializer { *; }

# 保留 libsu 相关
-keep class com.topjohnwu.superuser.** { *; }

# 其余反射保留已由上方规则覆盖

# 保留枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留序列化相关
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 移除日志（可选，进一步减小体积）
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}