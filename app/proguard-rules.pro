# --- kotlinx.serialization (wire messages in app.barq.engine, web, data) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.barq.**$$serializer { *; }
-keepclassmembers class app.barq.** { *** Companion; }
-keepclasseswithmembers class app.barq.** { kotlinx.serialization.KSerializer serializer(...); }


# --- ZXing embedded scanner reflects on its capture activity ---
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

# --- Keep stack traces readable in Play Console ---
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
