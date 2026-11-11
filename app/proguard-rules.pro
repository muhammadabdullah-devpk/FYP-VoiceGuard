# Release minification is disabled in this project. If you enable it, keep JNA and Vosk classes:
-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-keep class org.vosk.** { *; }
-keep class ai.onnxruntime.** { *; }
