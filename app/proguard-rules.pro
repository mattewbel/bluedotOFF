# The helper is started by name over Local ADB:
#   CLASSPATH=<base.apk> app_process /system/bin app.bluedotoff.adb.MinimalPrivilegedHelper <base.apk>
# (see core/.../LocalAdbProtocol.kt HelperLaunch). Nothing calls main() directly, so R8 must keep it.
-keep class app.bluedotoff.adb.MinimalPrivilegedHelper {
    public static void main(java.lang.String[]);
}
-keep class app.bluedotoff.adb.ResidentWatchHelper {
    public static void main(java.lang.String[]);
}

# Readable stack traces when combined with build/outputs/mapping/localRelease/mapping.txt.
-keepattributes SourceFile,LineNumberTable
