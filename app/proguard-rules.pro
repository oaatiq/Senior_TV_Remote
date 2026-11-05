# Release builds are not minified (see app/build.gradle.kts). If minification is enabled,
# keep the generated protobuf-lite classes, which are accessed reflectively.
-keep class app.seniorremote.protocol.atvr2.proto.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
