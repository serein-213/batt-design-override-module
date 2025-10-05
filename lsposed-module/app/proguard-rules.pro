# LSPosed 模块 ProGuard 规则

# 保留 Xposed 相关类
-keep class de.robv.android.xposed.** { *; }
-keep class com.override.battcaplsp.SettingsHook { *; }
-keep class com.override.battcaplsp.SettingsProvider { *; }

# 保留 Compose 相关
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# 保留 DataStore 相关
-keep class androidx.datastore.** { *; }
-keep class * extends androidx.datastore.core.Serializer { *; }

# 保留 libsu 相关
-keep class com.topjohnwu.superuser.** { *; }

# 保留反射调用的类和方法
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

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