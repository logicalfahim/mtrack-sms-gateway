# Proguard / R8 Optimization Rules for MTrack SMS Gateway

# Keep all classes in the app package to prevent reflection or lifecycle issues
-keep class com.mtrack.sms_gateway.** { *; }

# WorkManager Worker preservation
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# AndroidX Lifecycle & Core
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}

# OkHttp & Okio rules for R8 optimization
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# Keep native methods and enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
