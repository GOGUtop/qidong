package io.github.stlauncher;

import android.content.Context;
import android.content.SharedPreferences;

/** All preferences are kept locally; no accounts, analytics or remote services. */
final class LauncherOptions {
    static final String FULL_SCREEN = "full_screen";
    static final String KEEP_ALIVE = "keep_alive";
    static final String MEDIA_CARD = "media_card";
    static final String GENERATING_NOW = "generation_active";
    static final String GENERATION_EVENT_AT = "generation_event_at";
    static final String REPLY_ALERTS = "reply_alerts";
    static final String SOUND = "reply_sound";
    static final String VIBRATE = "reply_vibrate";
    static final String BLOCK_SELECTION = "block_android_selection";
    static final String CLEAR_ON_EXIT = "clear_cache_on_exit";

    private LauncherOptions() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context, String key) {
        return prefs(context).getBoolean(key, SOUND.equals(key));
    }

    static void set(Context context, String key, boolean value) {
        prefs(context).edit().putBoolean(key, value).apply();
    }
}
