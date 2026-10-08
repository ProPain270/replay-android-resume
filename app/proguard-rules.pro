# JNI entry points are resolved by their generated Java/Kotlin class names in
# the native bridge. Keep the class and all external methods stable when R8
# shrinks and obfuscates the release application.
-keep class dev.codex.libretroplatform.runtime.host.NativeRuntime { *; }
