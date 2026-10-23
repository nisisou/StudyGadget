# Keep Room entities & DAOs
-keep class androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep kotlinx.serialization classes & DTOs
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <init>(...);
}

# Keep Ktor server
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
