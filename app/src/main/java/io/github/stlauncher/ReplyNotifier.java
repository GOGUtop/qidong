package io.github.stlauncher;

import android.content.Context;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/** Plays local alerts in foreground, uses notification channels for background alerts. */
final class ReplyNotifier {
    private ReplyNotifier() {}

    static void notifyCompleted(Context context, boolean activityIsVisible) {
        if (!LauncherOptions.enabled(context, LauncherOptions.REPLY_ALERTS)) return;
        boolean sound = LauncherOptions.enabled(context, LauncherOptions.SOUND);
        boolean vibrate = LauncherOptions.enabled(context, LauncherOptions.VIBRATE);
        if (!sound && !vibrate) return;
        if (!activityIsVisible) {
            NotificationHelper.showReply(context, sound, vibrate);
            return;
        }
        if (sound) {
            try {
                Ringtone tone = RingtoneManager.getRingtone(context,
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
                if (tone != null) tone.play();
            } catch (Exception ignored) { }
        }
        if (vibrate) {
            try {
                Vibrator vibrator;
                if (Build.VERSION.SDK_INT >= 31) {
                    VibratorManager manager = context.getSystemService(VibratorManager.class);
                    vibrator = manager.getDefaultVibrator();
                } else {
                    vibrator = context.getSystemService(Vibrator.class);
                }
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createOneShot(320,
                            VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } catch (Exception ignored) { }
        }
    }
}
