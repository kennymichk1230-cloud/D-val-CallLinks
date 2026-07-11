# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep Room entities and DAOs
-keep class com.example.data.entity.** { *; }
-keep class com.example.data.dao.** { *; }
-keep class com.example.data.database.** { *; }

# Keep Firebase/Firestore data classes for safe reflection deserialization
-keep class com.example.data.api.FirebaseUserSession { *; }
-keep class com.example.data.api.FirestoreCallSession { *; }

# Keep WebRTC model and state structures
-keep class com.example.webrtc.CallSession { *; }
-keep class com.example.webrtc.CallState { *; }
