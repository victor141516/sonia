-dontobfuscate
-keepattributes SourceFile,LineNumberTable
-keep class com.sonia.uploader.UploaderActivity { *; }
-keep class com.sonia.uploader.ExitReceiver { *; }
# JNI: libsoniatls.so binds Java_com_sonia_uploader_NativeTls_* by name.
-keepclasseswithmembernames class com.sonia.uploader.NativeTls { native <methods>; }
