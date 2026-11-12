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

    static final String ACTION_DISABLE_MEDIA = "io.github.stlauncher.DISABLE_MEDIA_CARD";
    private MediaCardController mediaCard;

    @Override public void onCreate() {
        super.onCreate();
        mediaCard = new MediaCardController(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISABLE_MEDIA.equals(intent.getAction())) {
            LauncherOptions.set(this, LauncherOptions.MEDIA_CARD, false);
            if (mediaCard != null) mediaCard.stop();
            return START_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (mediaCard != null) mediaCard.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            NotificationHelper.show(this);
            return START_NOT_STICKY;
        }
        boolean allowed = LauncherOptions.enabled(this, LauncherOptions.KEEP_ALIVE)
                && LauncherOptions.prefs(this).contains(MainActivity.KEY_URL);
        if (!allowed) {
            if (mediaCard != null) mediaCard.stop();
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
            if (mediaCard != null) {
                if (LauncherOptions.enabled(this, LauncherOptions.MEDIA_CARD)) mediaCard.start();
                else mediaCard.stop();
            }
            return START_STICKY;
        } catch (Exception failure) {
            if (mediaCard != null) mediaCard.stop();
            LauncherOptions.set(this, LauncherOptions.KEEP_ALIVE, false);
            NotificationHelper.show(this);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    @Override public void onDestroy() {
        if (mediaCard != null) mediaCard.stop();
        super.onDestroy();
        // Stopping a foreground service removes its FGS notification. Repost the
        // ordinary status shortcut unless the user has unbound the site.
        if (LauncherOptions.prefs(this).contains(MainActivity.KEY_URL)) {
            NotificationHelper.show(this);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
