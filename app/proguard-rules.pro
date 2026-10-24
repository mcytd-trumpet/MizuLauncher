# ---- 数据模型：Gson 靠反射读写字段，字段名被混淆就解析不出东西 ----
-keep class com.mizulauncher.download.** { *; }
-keep class com.mizulauncher.minecraft.** { *; }
-keep class com.mizulauncher.instance.** { *; }
-keep class com.mizulauncher.bridge.** { *; }
-keep class com.mizulauncher.runtime.** { *; }

# Gson 自身
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod
-dontwarn sun.misc.**

# ---- PojavLauncher 内核 ----
-keep class net.kdt.pojavlaunch.** { *; }
-keep class org.lwjgl.** { *; }
-dontwarn org.lwjgl.**
-dontwarn net.kdt.pojavlaunch.**

# 桥接层用反射找 MainActivity 与配置类，名字不能变
-keep class com.mizulauncher.launcher.bridge.** { *; }
