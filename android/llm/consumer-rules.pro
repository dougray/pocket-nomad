# JNI looks these up by name.
-keepclasseswithmembernames class org.nomadpocket.llm.** { native <methods>; }
-keep interface org.nomadpocket.llm.TokenSink { *; }
