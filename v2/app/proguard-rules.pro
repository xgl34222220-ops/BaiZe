# 白泽 Release 混淆规则。
#
# 开启了 minifyEnabled + shrinkResources，但此前只有两条 keep：
#   -keep class io.github.xgl34222220.baize.root.** { *; }
#   -keep class com.topjohnwu.superuser.ipc.** { *; }
# AIDL Stub 恰好落在 root 包下侥幸没被裁掉，其余反射入口全无保护。
# 混淆问题只会在 Release 包装到用户手机上才暴露，排查成本很高，这里补全。

# ——— Root 服务与 AIDL ———
# RootService 由 libsu 在独立进程中通过反射实例化，类名与构造器都不能改。
-keep class io.github.xgl34222220.baize.root.** { *; }
-keep class com.topjohnwu.superuser.** { *; }
-keep class com.topjohnwu.superuser.ipc.** { *; }

# AIDL 生成的 Stub / Proxy 依赖接口描述符字符串配对，两端必须一致；
# 重命名会导致 Binder invocation to an incorrect interface 这类隐蔽错误。
-keep interface io.github.xgl34222220.baize.root.IBaiZeRootService { *; }
-keep interface io.github.xgl34222220.baize.root.IProfileRootService { *; }
-keep interface io.github.xgl34222220.baize.root.IPersistentCleanPlanService { *; }
-keep interface io.github.xgl34222220.baize.root.ICleanPlanResumeService { *; }
-keep interface io.github.xgl34222220.baize.root.ITaskProgressCallback { *; }
-keep class * implements android.os.IInterface { *; }
-keepclassmembers class * extends android.os.Binder {
    public *;
}

# ——— 反射与序列化 ———
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

# org.json 全项目用于跨进程数据交换，字段名即协议的一部分。
-dontwarn org.json.**

# Parcelable CREATOR 由框架反射读取。
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# 枚举的 values/valueOf 由序列化路径反射调用。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ——— WorkManager ———
# Worker 由 WorkManager 按类名反射实例化。
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class io.github.xgl34222220.baize.FileOrganizerWorker { *; }

# ——— Compose ———
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# ——— 协程 ———
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ——— 崩溃可读性 ———
# 保留行号，否则线上崩溃栈无法回溯到源码位置。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
