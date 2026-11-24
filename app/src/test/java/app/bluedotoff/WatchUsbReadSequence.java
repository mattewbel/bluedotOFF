package app.bluedotoff;

import android.content.ContentResolver;
import android.provider.Settings;
import java.util.ArrayDeque;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSettings;

@Implements(Settings.Global.class)
public class WatchUsbReadSequence extends ShadowSettings.ShadowGlobal {
    private static final ArrayDeque<Integer> reads = new ArrayDeque<>();

    public static void set(int... values) {
        reads.clear();
        for (int value : values) reads.addLast(value);
    }

    @Implementation
    protected static int getInt(ContentResolver resolver, String key, int fallback) {
        if (Settings.Global.ADB_ENABLED.equals(key) && !reads.isEmpty()) return reads.removeFirst();
        return ShadowSettings.ShadowGlobal.getInt(resolver, key, fallback);
    }
}
