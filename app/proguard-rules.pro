# ---------------------------------------------------------------------------
# Morsecode release rules
# ---------------------------------------------------------------------------
# Hilt / Dagger generate components at build time; the plugin contributes its
# own consumer rules. Only the pieces that use reflection are listed here.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute Morsecode

# Room entities and generated DAOs.
-keep class app.morsecode.core.data.db.** { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# kotlinx.serialization keeps generated serializers via @Serializable metadata.
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keep,includedescriptorclasses class app.morsecode.**$$serializer { *; }
-keepclassmembers class app.morsecode.** {
    *** Companion;
}
-keepclasseswithmembers class app.morsecode.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Nearby Connections uses Play services binder APIs that must not be stripped.
-keep class com.google.android.gms.nearby.** { *; }
-dontwarn com.google.android.gms.**

# Media3 reflection-free, but its session/notification classes are looked up by name.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# The embedded WebShare server binds sockets reflectively nowhere; keep the
# protocol DTOs because they cross a JSON boundary.
-keep class app.morsecode.webshare.server.api.** { *; }
-keep class app.morsecode.core.transfer.protocol.** { *; }
