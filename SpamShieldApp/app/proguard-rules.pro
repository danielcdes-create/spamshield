-keepattributes *Annotation*,InnerClasses,Signature,RuntimeVisibleAnnotations,AnnotationDefault,EnclosingMethod

-keep class br.com.spamshield.data.remote.** { *; }
-keep class br.com.spamshield.data.local.entity.** { *; }

-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <init>(...);
}
-keep class **_HiltModules* { *; }
-keep class **Hilt_* { *; }
-keep class br.com.spamshield.sync.SyncWorker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class androidx.hilt.work.** { *; }

-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
