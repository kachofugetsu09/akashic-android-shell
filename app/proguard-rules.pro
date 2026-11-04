-keepclassmembers class com.akashic.shell.PanelBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep HelperMain entry point for app_process UID 2000 invocation
-keep class com.akashic.shell.HelperMain {
    public static void main(java.lang.String[]);
}
-keep class com.akashic.shell.HelperHandoffProvider { *; }
-keep class com.akashic.shell.IAkashicShell* { *; }
