package com.nakas.skate3;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the game out of the cached-app kill bucket while a session is live.
 *
 * This is the whole reason a session does not survive being backgrounded
 * today. Android picks victims by unevictable footprint, and this process is
 * the fattest thing on the device - measured at 1358 MB PSS on a Galaxy S23 FE
 * with 1246 MB of it private dirty. The moment the activity stops, the process
 * becomes a cached app, and a cached app that size is the first thing the low
 * memory killer takes. The player comes back to the launcher with no crash
 * report and no explanation, which is exactly the "either frozen or crashed"
 * report GameData's session marker was added to diagnose.
 *
 * A foreground service with an ongoing notification moves the process out of
 * that bucket, so backgrounding and returning is an ordinary resume rather than
 * a relaunch. It cannot make the app immune - a device genuinely out of memory
 * will still reclaim it - but it stops the app being chosen first purely for
 * being large and cached.
 *
 * Adapted from darchap/Skate3-Port, which solved this before we did.
 */
public class GameKeepAliveService extends Service {
    private static final String TAG = "Skate3KeepAlive";
    private static final String CHANNEL_ID = "skate3_session";
    private static final int NOTIFICATION_ID = 1;

    /** Best effort: a session that cannot start the service still plays. */
    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, GameKeepAliveService.class));
        } catch (Exception e) {
            Log.w(TAG, "could not start the keep-alive service: " + e);
        }
    }

    static void stop(Context context) {
        try {
            context.stopService(new Intent(context, GameKeepAliveService.class));
        } catch (Exception e) {
            Log.w(TAG, "could not stop the keep-alive service: " + e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Game session", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the game session alive while it is in the background");
        // No sound or badge: this notification exists to hold the process, not
        // to tell the player anything they did not already know.
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        PendingIntent tapIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, Skate3Activity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Skate 3 session active")
                .setContentText("Tap to return to the game")
                .setContentIntent(tapIntent)
                .setOngoing(true)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // API 34+ requires the type at the call site as well as in the
                // manifest, and throws if they disagree.
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            // A denied foreground service must not take the game down with it.
            Log.w(TAG, "startForeground refused: " + e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
