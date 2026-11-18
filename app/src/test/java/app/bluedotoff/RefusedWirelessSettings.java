package app.bluedotoff;

import android.content.ContentResolver;
import android.provider.Settings;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSettings;

/** Simulates how the OS answers a single wireless-enable request on the JVM. */
@Implements(Settings.Global.class)
public class RefusedWirelessSettings extends ShadowSettings.ShadowGlobal {
    static boolean rejectWrite;
    static boolean acceptEnable;
    static boolean missingRead;
    static int enableAttempts;

    @Implementation
    protected static boolean putInt(ContentResolver resolver, String key, int value) {
        if ("adb_wifi_enabled".equals(key) && value == 1) {
            enableAttempts++;
            if (acceptEnable) return ShadowSettings.ShadowGlobal.putInt(resolver, key, value);
            return !rejectWrite;
        }
        return ShadowSettings.ShadowGlobal.putInt(resolver, key, value);
    }

    @Implementation
    protected static int getInt(ContentResolver resolver, String key, int fallback) {
        if (missingRead && "adb_wifi_enabled".equals(key)) return fallback;
        return ShadowSettings.ShadowGlobal.getInt(resolver, key, fallback);
    }
}
