# 白泽 Release 混淆规则。
# Root 服务、反射实例化入口和跨进程接口必须保留。

# RootService 由 libsu 在独立进程中通过反射实例化。
-keep class io.github.xgl34222220.baize.root.** { *; }
-keep class com.topjohnwu.superuser.** { *; }
-keep class com.topjohnwu.superuser.ipc.** { *; }
-keep interface io.github.xgl34222220.baize.root.IBaiZeRootService { *; }
-keep interface io.github.xgl34222220.baize.root.IProfileRootService { *; }
-keep interface io.github.xgl34222220.baize.root.IPersistentCleanPlanService { *; }
-keep interface io.github.xgl34222220.baize.root.ICleanPlanResumeService { *; }
-keep interface io.github.xgl34222220.baize.root.ITaskProgressCallback { *; }
-keep class * implements android.os.IInterface { *; }
-keepclassmembers class * extends android.os.Binder {
    public *;
}

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-dontwarn org.json.**

-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Worker 由 WorkManager 按类名反射实例化。
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class io.github.xgl34222220.baize.FileOrganizerWorker { *; }

# 30001 cold-start crash, reproduced from the published, minified APK:
# InitializationProvider -> WorkManagerInitializer -> Room ->
# NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
# R8 full mode does not implicitly retain a constructor when retaining a class.
# Room derives the generated class name from WorkDatabase and invokes its no-arg
# constructor before Application.onCreate. Retain this small reflection boundary;
# do NOT disable WorkManager, wipe its database, or turn off R8 for the entire App.
-keep class androidx.work.impl.WorkDatabase { *; }
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
    *;
}

-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
