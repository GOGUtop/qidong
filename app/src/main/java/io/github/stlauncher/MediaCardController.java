package io.github.stlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Experimental system media-card presentation for a user-enabled WebView session.
 * This does not output sound/video, request audio focus, or use mediaPlayback FGS.
 * The timeline displays elapsed app session time (looping hourly), NOT the
 * percent completion of a SillyTavern response. OEM Dynamic Island support
 * cannot be guaranteed and other apps' media may take visual priority.
 */
final class MediaCardController {
    private static final String CHANNEL = "st_media_session_status_v1";
    private static final int NOTIFICATION_ID = 1092;
    private static final long TIMELINE_MS = 60L * 60L * 1000L;
    private static final long UPDATE_MS = 15_000L;

    private final ForegroundSessionService service;
    private final NotificationManager notifications;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSession mediaSession;
    private Bitmap artwork;
    private Boolean lastGenerating;
    private long sessionStartedAt;
    private boolean running;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateDisplay();
        }
    };

    MediaCardController(ForegroundSessionService service) {
        this.service = service;
        notifications = service.getSystemService(NotificationManager.class);
    }

    void start() {
        if (running) return;
        if (!LauncherOptions.enabled(service, LauncherOptions.KEEP_ALIVE)
                || !LauncherOptions.enabled(service, LauncherOptions.MEDIA_CARD)) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "酒馆媒体卡片（实验性）", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.setDescription("以系统媒体会话显示酒馆后台状态和会话计时，不播放音视频");
        notifications.createNotificationChannel(channel);

        sessionStartedAt = SystemClock.elapsedRealtime();
        mediaSession = new MediaSession(service, "SillyTavernStatusCard");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPause() { disableFromSystemControl(); }
            @Override public void onStop() { disableFromSystemControl(); }
        });
        artwork = createArtwork();
        lastGenerating = null;
        mediaSession.setActive(true);
        running = true;
        updateDisplay();
    }

    private void disableFromSystemControl() {
        LauncherOptions.set(service, LauncherOptions.MEDIA_CARD, false);
        stop();
        // Foreground keep-alive continues; only the optional media card stops.
    }

    private void updateDisplay() {
        handler.removeCallbacks(refresh);
        if (!running) return;
        if (!LauncherOptions.enabled(service, LauncherOptions.KEEP_ALIVE)
                || !LauncherOptions.enabled(service, LauncherOptions.MEDIA_CARD)) {
            stop();
            return;
        }

        final long now = SystemClock.elapsedRealtime();
        final long elapsed = Math.max(0L, now - sessionStartedAt);
        final long position = elapsed % TIMELINE_MS;
        // This is a user-visible timer, not generated-token progress or response ETA.
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, position, 1.0f, now)
                .setActions(PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP)
                .build());

        boolean generating = LauncherOptions.prefs(service)
                .getBoolean(LauncherOptions.GENERATING_NOW, false);
        long eventAt = LauncherOptions.prefs(service)
                .getLong(LauncherOptions.GENERATION_EVENT_AT, -1L);
        if (eventAt < 0 || now < eventAt || now - eventAt > 60 * 60 * 1000L) {
            generating = false; // ignore stale state left by a killed process
        }
        String state = generating ? "剧情回复生成中" : "后台会话运行中";
        if (lastGenerating == null || lastGenerating != generating) {
            lastGenerating = generating;
            mediaSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "SillyTavern · " + state)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "会话状态 · 不播放音视频")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "SillyTavern Launcher")
                    .putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, TIMELINE_MS)
                    .build());
        }
        long seconds = elapsed / 1000;
        String time = String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60);

        Intent open = new Intent(service, MainActivity.class)
                .setAction(MainActivity.ACTION_OPEN_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openSettings = PendingIntent.getActivity(service, 911, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent disable = new Intent(service, ForegroundSessionService.class)
                .setAction(ForegroundSessionService.ACTION_DISABLE_MEDIA);
        PendingIntent disableAction = PendingIntent.getService(service, 912, disable,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification card = new Notification.Builder(service, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("SillyTavern · " + state)
                .setContentText("会话计时 " + time + "（非回复完成百分比）")
                .setSubText("无音频播放 · 系统媒体状态展示")
                .setContentIntent(openSettings)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setShowWhen(false)
                .setProgress(3600, (int) (position / 1000), false)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "关闭媒体卡片", disableAction).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0))
                .build();
        try { notifications.notify(NOTIFICATION_ID, card); }
        catch (SecurityException ignored) { /* Notification permission/OEM restrictions. */ }
        handler.postDelayed(refresh, UPDATE_MS);
    }

    private static Bitmap createArtwork() {
        Bitmap image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(Color.rgb(10, 18, 34));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(255, 212, 59));
        paint.setTextSize(104);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-black",
                android.graphics.Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("ST", 128, 161, paint);
        return image;
    }

    void stop() {
        running = false;
        handler.removeCallbacks(refresh);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        artwork = null;
        lastGenerating = null;
        notifications.cancel(NOTIFICATION_ID);
    }
}
