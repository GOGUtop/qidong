package io.github.stlauncher;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

/** Status notification with Settings and Unbind actions, and optional completion alerts. */
final class NotificationHelper {
    static final int STATUS_ID = 1068;
    private static final String STATUS_CHANNEL = "st_status_v2";
    private static final int REPLY_ID = 2108;
    private static final String ALERT_PREFIX = "st_reply_v2_";

    private NotificationHelper() {}

    static boolean permitted(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent activityIntent(Context context, int requestCode, String action) {
        Intent intent = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (action != null) intent.setAction(action);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification createStatus(Context context) {
        ensureStatusChannel(context);
        String address = LauncherOptions.prefs(context).getString(MainActivity.KEY_URL, "");
        boolean protection = LauncherOptions.enabled(context, LauncherOptions.KEEP_ALIVE);
        return new Notification.Builder(context, STATUS_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(protection ? "SillyTavern · 增强后台模式" : "SillyTavern · 已连接")
                .setContentText(BoundUrl.origin(address))
                .setContentIntent(activityIntent(context, 1, null))
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_preferences,
                        "软件设置", activityIntent(context, 2, MainActivity.ACTION_OPEN_SETTINGS)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                        "注销绑定", activityIntent(context, 3, MainActivity.ACTION_CONFIRM_UNBIND)).build())
                .setStyle(new Notification.BigTextStyle().bigText(
                        "酒馆：" + BoundUrl.origin(address) + "\n点击『软件设置』调整功能，或注销绑定切换酒馆。"))
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    static void show(Context context) {
        String url = LauncherOptions.prefs(context).getString(MainActivity.KEY_URL, null);
        if (url == null || url.isEmpty() || !permitted(context)) return;
        context.getSystemService(NotificationManager.class).notify(STATUS_ID, createStatus(context));
    }

    private static void ensureStatusChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(STATUS_CHANNEL,
                "酒馆快捷操作", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("进入设置、切换酒馆及后台运行状态");
        channel.setSound(null, null);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    static void showReply(Context context, boolean sound, boolean vibrate) {
        if (!permitted(context)) return;
        String channelId = ALERT_PREFIX + (sound ? "s" : "n") + (vibrate ? "v" : "n");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(channelId, "酒馆回复完成提醒",
                sound || vibrate ? NotificationManager.IMPORTANCE_HIGH
                        : NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("SillyTavern 已完成一次回复；可在启动器设置里关闭");
        channel.enableVibration(vibrate);
        if (sound) {
            channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        } else {
            channel.setSound(null, null);
        }
        manager.createNotificationChannel(channel);
        Notification notification = new Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("酒馆回复已完成")
                .setContentText("角色已完成本次回复，点击返回聊天")
                .setContentIntent(activityIntent(context, 4, null))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setShowWhen(true)
                .build();
        manager.notify(REPLY_ID, notification);
    }

    static void cancel(Context context) {
        context.getSystemService(NotificationManager.class).cancel(STATUS_ID);
    }
}
