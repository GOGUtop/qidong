package io.github.stlauncher;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** User-enabled foreground-service mode, not a guarantee against OS process eviction. */
public final class ForegroundSessionService extends Service {
    static final String ACTION_START = "io.github.stlauncher.START_FOREGROUND_SESSION";
    static final String ACTION_STOP = "io.github.stlauncher.STOP_FOREGROUND_SESSION";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            NotificationHelper.show(this);
            return START_NOT_STICKY;
        }
        boolean allowed = LauncherOptions.enabled(this, LauncherOptions.KEEP_ALIVE)
                && LauncherOptions.prefs(this).contains(MainActivity.KEY_URL);
        if (!allowed) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NotificationHelper.STATUS_ID, NotificationHelper.createStatus(this),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NotificationHelper.STATUS_ID, NotificationHelper.createStatus(this));
            }
            return START_STICKY;
        } catch (Exception failure) {
            LauncherOptions.set(this, LauncherOptions.KEEP_ALIVE, false);
            NotificationHelper.show(this);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    @Override public void onDestroy() {
        super.onDestroy();
        // Stopping a foreground service removes its FGS notification. Repost the
        // ordinary status shortcut unless the user has unbound the site.
        if (LauncherOptions.prefs(this).contains(MainActivity.KEY_URL)) {
            NotificationHelper.show(this);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
