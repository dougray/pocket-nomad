# zstd-jni and the bundled SQLite driver call into Java from native code.
-keep class com.github.luben.zstd.** { *; }
-keep class androidx.sqlite.driver.bundled.** { *; }
