# libsignal uses JNI callbacks into these classes
-keep class org.signal.libsignal.** { *; }
# SQLCipher
-keep class net.zetetic.database.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.wichat.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
