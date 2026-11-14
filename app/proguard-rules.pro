# Malachite release build: R8 shrinks and optimizes the code (the Compose interface runs much
# faster with it), but every name stays as it is, so logs and crash reports remain readable.
-dontobfuscate

# The tag library creates its frame classes by name (reflection): it is kept whole.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
# It also mentions desktop Java classes that Android does not have (those paths are never used here).
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-dontwarn java.beans.**
-dontwarn javax.sound.**

# Shizuku's process call is reached by reflection (data/Privileged.kt).
-keep class rikka.shizuku.Shizuku { *; }

# The core library is plain Kotlin: its classes and members stay, R8 only optimizes them.
-keep,allowoptimization class app.malachite.core.** { *; }
