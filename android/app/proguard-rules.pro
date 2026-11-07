# 保留 Gson 反射需要的泛型信息
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.cust.campus.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
