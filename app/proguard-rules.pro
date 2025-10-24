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

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep annotation processing classes that are referenced but not available at runtime
-keep class javax.annotation.processing.** { *; }
-keep class javax.lang.model.** { *; }
-keep class javax.lang.model.element.** { *; }
-keep class javax.lang.model.type.** { *; }
-keep class javax.lang.model.util.** { *; }
-keep class javax.tools.** { *; }

# Keep AutoValue related classes
-keep class com.google.auto.value.** { *; }
-keep class autovalue.shaded.** { *; }

# Keep MediaPipe classes and protobuf
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.mediapipe.proto.** { *; }

# Keep CameraX classes
-keep class androidx.camera.** { *; }

# Keep Compose classes
-keep class androidx.compose.** { *; }

# Keep all classes that might be referenced by annotation processors
-keep class * extends javax.annotation.processing.AbstractProcessor { *; }
-keep class * implements javax.annotation.processing.Processor { *; }

# Keep service files for annotation processors
-keep class META-INF.services.javax.annotation.processing.Processor { *; }

# Keep all classes with @AutoValue annotation
-keep @com.google.auto.value.AutoValue class * { *; }

# Keep all classes with @AutoValue.Builder annotation  
-keep @com.google.auto.value.AutoValue.Builder class * { *; }

# Keep all classes with @Memoized annotation
-keep @com.google.auto.value.extension.memoized.Memoized class * { *; }

# Keep all classes with @Serializable annotation
-keep @com.google.auto.value.extension.serializable.Serializable class * { *; }